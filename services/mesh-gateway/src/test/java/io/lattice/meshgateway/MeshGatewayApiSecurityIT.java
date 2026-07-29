package io.lattice.meshgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves the shared {@code /api/v1} guard is in force on the mesh-gateway's own routes. This service
 * is the one where the protection is least obvious and most load-bearing: a cluster's peer list,
 * endpoints, and health rollup are operational detail about the whole mesh, not public information,
 * so {@code getPeers} and {@code getBaseline} are protected exactly like a business endpoint.
 *
 * <p>The gateway serves both from memory and never fails on a broker outage, so a closed broker port
 * is enough - no container is needed.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class MeshGatewayApiSecurityIT {

    // A port nothing listens on: the broker connection fails fast (connection refused).
    private static final String UNREACHABLE_BROKER = "amqp://127.0.0.1:1";

    private static TestRealm realm;

    /** Starts the realm this service validates tokens against. */
    @BeforeAll
    static void startRealm() {
        realm = TestRealm.start("lattice");
    }

    /** Releases the realm. */
    @AfterAll
    static void stopRealm() {
        realm.close();
    }

    /** The peer list is not public: without a token it is rejected as UNAUTHORIZED. */
    @Test
    void getPeersWithoutATokenIsUnauthorized(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        expectDegradedMeshWarnings(logs);
        var verticle = new MeshGatewayVerticle(UNREACHABLE_BROKER, 0, realm.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/api/v1/peers")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(401, resp.statusCode());
                        assertEquals(
                                "UNAUTHORIZED",
                                resp.bodyAsJsonObject().getJsonObject("error").getString("code"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** This cluster's baseline identity is protected too, for the same reason as the peer list. */
    @Test
    void getBaselineWithoutATokenIsUnauthorized(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        expectDegradedMeshWarnings(logs);
        var verticle = new MeshGatewayVerticle(UNREACHABLE_BROKER, 0, realm.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/api/v1/baseline")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(401, resp.statusCode());
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * A viewer reads the peer list. Both gateway operations are reads, so the viewer role is the whole
     * grant this service needs - there is no write here for an operator to hold.
     */
    @Test
    void getPeersAsViewerIsAllowed(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        expectDegradedMeshWarnings(logs);
        var verticle = new MeshGatewayVerticle(UNREACHABLE_BROKER, 0, realm.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = realm.viewerClient(vertx);
            client.get(verticle.actualPort(), "localhost", "/api/v1/peers")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * With no realm configured the gateway refuses to start rather than serving {@code /api/v1}
     * unprotected. It tolerates a missing broker by design, but identity is not the same kind of
     * dependency: without it there is no safe way to answer at all.
     */
    @Test
    void refusesToStartWithNoRealmConfigured(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectError("keycloak is not configured");
        // Startup gets as far as the mesh and health wiring before the realm check fails, so those two
        // degraded-state warnings are reached; the announce heartbeat never starts, so there is no
        // "announce failed" here.
        logs.expectWarn("mesh connection deferred");
        logs.expectWarn("no services configured to watch");
        logs.expectWarn("no infrastructure configured");
        vertx.deployVerticle(new MeshGatewayVerticle(UNREACHABLE_BROKER, 0, null))
                .onComplete(ctx.failing(err -> ctx.verify(ctx::completeNow)));
    }

    /**
     * Declares the warnings inherent to deploying against a closed broker with nothing to watch and
     * nothing to probe: each is the service correctly reporting a degraded-but-serving state, so they
     * are asserted rather than tolerated.
     */
    private static void expectDegradedMeshWarnings(ExpectedLogs logs) {
        logs.expectWarn("mesh connection deferred");
        logs.expectWarn("no services configured to watch");
        logs.expectWarn("no infrastructure configured");
        logs.expectWarn("announce failed");
    }
}
