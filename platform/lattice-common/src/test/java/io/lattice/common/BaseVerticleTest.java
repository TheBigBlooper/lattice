package io.lattice.common;

import io.vertx.core.Vertx;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Contract tests for {@link BaseVerticle}, the shared service base. Deploys a tiny concrete
 * subclass on a real Vert.x instance and drives its mounted endpoints with a {@link WebClient},
 * pinning the health/readiness surface: liveness is always up once the process is running,
 * readiness reflects the registered dependency checks (up when all pass, 503 when one is down).
 */
@ExtendWith(VertxExtension.class)
class BaseVerticleTest {

    /**
     * A minimal concrete {@link BaseVerticle} whose readiness state is fixed at construction, so a
     * test can deploy a healthy or a not-ready instance. Binds an ephemeral port for isolation.
     */
    static final class ProbeVerticle extends BaseVerticle {
        private final boolean ready;

        ProbeVerticle(boolean ready) {
            this.ready = ready;
        }

        @Override
        protected int httpPort() {
            return 0;
        }

        @Override
        protected void configureRoutes(Router router) {
            router.get("/ping").handler(ctx -> ctx.response().end("pong"));
        }

        @Override
        protected void registerReadinessChecks(HealthChecks readiness) {
            readiness.register("dependency", promise -> promise.complete(ready ? Status.OK() : Status.KO()));
        }
    }

    /** Liveness (/health) and readiness (/readiness) both report UP with 200 when dependencies are healthy. */
    @Test
    void healthyInstanceReportsLivenessAndReadinessUp(Vertx vertx, VertxTestContext ctx) {
        var verticle = new ProbeVerticle(true);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            var port = verticle.actualPort();
            client.get(port, "localhost", "/health")
                    .send()
                    .onComplete(ctx.succeeding(health -> ctx.verify(() -> {
                        assertUp(ctx, health.statusCode(), health.bodyAsString());
                        client.get(port, "localhost", "/readiness")
                                .send()
                                .onComplete(ctx.succeeding(ready -> ctx.verify(() -> {
                                    assertUp(ctx, ready.statusCode(), ready.bodyAsString());
                                    client.close();
                                    ctx.completeNow();
                                })));
                    })));
        }));
    }

    /** A readiness check reporting DOWN drives /readiness to HTTP 503 while liveness stays UP. */
    @Test
    void notReadyInstanceReportsReadinessDownButLivenessUp(Vertx vertx, VertxTestContext ctx) {
        var verticle = new ProbeVerticle(false);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            var port = verticle.actualPort();
            client.get(port, "localhost", "/health")
                    .send()
                    .onComplete(ctx.succeeding(health -> ctx.verify(() -> {
                        assertUp(ctx, health.statusCode(), health.bodyAsString());
                        client.get(port, "localhost", "/readiness")
                                .send()
                                .onComplete(ctx.succeeding(ready -> ctx.verify(() -> {
                                    if (ready.statusCode() != 503) {
                                        ctx.failNow("expected readiness 503, got " + ready.statusCode());
                                    }
                                    client.close();
                                    ctx.completeNow();
                                })));
                    })));
        }));
    }

    private static void assertUp(VertxTestContext ctx, int statusCode, String body) {
        if (statusCode != 200) {
            ctx.failNow("expected 200, got " + statusCode + " body=" + body);
        }
    }
}
