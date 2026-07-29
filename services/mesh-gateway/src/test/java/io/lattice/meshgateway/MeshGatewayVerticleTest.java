package io.lattice.meshgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.lattice.contract.mesh.ComponentKind;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The mesh-gateway's operational surface. The service deploys and serves the shared unversioned
 * health probes even when the mesh broker is unreachable, because a broker outage is a mesh
 * degradation rather than a service failure: the gateway can still answer for its own last-known
 * state, and taking it out of rotation would leave the status console with nothing during exactly the
 * incident an operator needs to see.
 *
 * <p>Deployed against a closed broker port, so no container is needed here - the two-cluster
 * discovery path is covered separately by the integration suite.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class MeshGatewayVerticleTest {

    /**
     * This baseline's identity realm. Every service now refuses to start without one and rejects an
     * unauthenticated /api/v1 call, so a suite testing what the endpoints do runs as an operator.
     */
    private static TestRealm REALM;

    /** Starts the realm the deployed service validates tokens against. */
    @BeforeAll
    static void startRealm() {
        REALM = TestRealm.start("lattice");
    }

    /** Releases the realm. */
    @AfterAll
    static void stopRealm() {
        REALM.close();
    }

    // A port nothing listens on: the broker connection fails fast (connection refused).
    private static final String UNREACHABLE_BROKER = "amqp://127.0.0.1:1";

    // The same closed port over HTTP, for an infrastructure probe that cannot reach its target.
    private static final String DEAD_ADDRESS = "http://127.0.0.1:1";

    /** Liveness is UP once the process is up, independent of the mesh. */
    @Test
    void livenessIsUpWhileTheBrokerIsUnreachable(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        // Every WARN is inherent to this scenario and asserted rather than tolerated: the broker
        // port is closed, nothing is configured to watch or to probe, and the heartbeat therefore cannot
        // publish. Each is the service correctly reporting a degraded-but-serving state.
        logs.expectWarn("mesh connection deferred");
        logs.expectWarn("no services configured to watch");
        logs.expectWarn("no infrastructure configured");
        logs.expectWarn("announce failed");

        var verticle = new MeshGatewayVerticle(UNREACHABLE_BROKER, 0, REALM.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = REALM.operatorClient(vertx);
            client.get(verticle.actualPort(), "localhost", "/health")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        assertEquals("UP", resp.bodyAsJsonObject().getString("status"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * Readiness stays UP while the broker is unreachable. This deliberately differs from orders and
     * inventory, where a dead Elasticsearch means DOWN: those services genuinely cannot answer, while
     * this one still serves its registry with last-known state and lets peers age out to UNREACHABLE.
     */
    @Test
    void readinessStaysUpWhileTheBrokerIsUnreachable(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectWarn("mesh connection deferred");
        logs.expectWarn("no services configured to watch");
        logs.expectWarn("no infrastructure configured");
        logs.expectWarn("announce failed");

        var verticle = new MeshGatewayVerticle(UNREACHABLE_BROKER, 0, REALM.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = REALM.operatorClient(vertx);
            client.get(verticle.actualPort(), "localhost", "/readiness")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode(), "a mesh outage must not take the gateway out of rotation");
                        assertEquals("UP", resp.bodyAsJsonObject().getString("status"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * Readiness stays UP with every infrastructure component unreachable, for the same reason it
     * stays UP through a broker outage: the gateway has no datastore of its own and validates tokens
     * against cached signing keys rather than by calling Keycloak per request, so it can still serve
     * the peer registry and this baseline's own document. Reporting DOWN would pull the pod from
     * rotation and leave the console with nothing during exactly the incident an operator most needs
     * to see (the reasoning locked #42 gives for broker loss, extended to the rest).
     */
    @Test
    void readinessStaysUpWhileEveryInfrastructureComponentIsDown(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectWarn("mesh connection deferred");
        logs.expectWarn("no services configured to watch");
        logs.expectWarn("announce failed");

        var verticle = new MeshGatewayVerticle(configWithDeadInfrastructure(), 0, REALM.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = REALM.operatorClient(vertx);
            client.get(verticle.actualPort(), "localhost", "/readiness")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(
                                200,
                                resp.statusCode(),
                                "dead infrastructure must not take the gateway out of rotation");
                        assertEquals("UP", resp.bodyAsJsonObject().getString("status"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** A configuration naming three components that cannot answer, so every probe fails. */
    private static MeshGatewayConfig configWithDeadInfrastructure() {
        return new MeshGatewayConfig(
                "hub-west",
                "us-west",
                "1.0.0",
                "https://west.console:3000",
                "https://west.svc:8080/api/v1",
                UNREACHABLE_BROKER,
                "artemis",
                "artemis",
                Map.of(),
                List.of(
                        new InfrastructureTarget("datastore", ComponentKind.ELASTICSEARCH, DEAD_ADDRESS),
                        new InfrastructureTarget("broker", ComponentKind.ARTEMIS, ""),
                        new InfrastructureTarget("identity", ComponentKind.KEYCLOAK, DEAD_ADDRESS)),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }
}
