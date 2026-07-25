package io.lattice.meshgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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

    // A port nothing listens on: the broker connection fails fast (connection refused).
    private static final String UNREACHABLE_BROKER = "amqp://127.0.0.1:1";

    /** Liveness is UP once the process is up, independent of the mesh. */
    @Test
    void livenessIsUpWhileTheBrokerIsUnreachable(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        // All three WARNs are inherent to this scenario and asserted rather than tolerated: the broker
        // port is closed, no services are configured to watch, and the heartbeat therefore cannot
        // publish. Each is the service correctly reporting a degraded-but-serving state.
        logs.expectWarn("mesh connection deferred");
        logs.expectWarn("no services configured to watch");
        logs.expectWarn("announce failed");

        var verticle = new MeshGatewayVerticle(UNREACHABLE_BROKER, 0);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
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
        logs.expectWarn("announce failed");

        var verticle = new MeshGatewayVerticle(UNREACHABLE_BROKER, 0);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
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
}
