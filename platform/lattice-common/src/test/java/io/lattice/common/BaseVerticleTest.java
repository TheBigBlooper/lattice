package io.lattice.common;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
 * pinning the operational health surface to the shape fixed in {@code api_structure.md}: a
 * non-enveloped {@code {status, checks:[{name,status}]}} body where {@code /health} is always UP
 * (liveness) and {@code /readiness} reflects the registered dependency checks (200 UP, 503 DOWN).
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

    /**
     * Liveness ({@code /health}) returns HTTP 200 with the operational shape: overall {@code status}
     * UP and a {@code checks} entry for the always-passing {@code process} check.
     */
    @Test
    void healthLivenessReportsUpShapeAt200(Vertx vertx, VertxTestContext ctx) {
        var verticle = new ProbeVerticle(true);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/health")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertStatusCode(ctx, 200, resp.statusCode(), resp.bodyAsString());
                        var body = resp.bodyAsJsonObject();
                        assertOverallStatus(ctx, "UP", body);
                        assertCheck(ctx, body, "process", "UP");
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * Readiness ({@code /readiness}) with every registered check passing returns HTTP 200, overall
     * {@code status} UP, and a {@code checks} entry naming the dependency as UP.
     */
    @Test
    void readinessAllChecksPassingReportsUpAt200(Vertx vertx, VertxTestContext ctx) {
        var verticle = new ProbeVerticle(true);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/readiness")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertStatusCode(ctx, 200, resp.statusCode(), resp.bodyAsString());
                        var body = resp.bodyAsJsonObject();
                        assertOverallStatus(ctx, "UP", body);
                        assertCheck(ctx, body, "dependency", "UP");
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * A registered check reporting DOWN drives {@code /readiness} to HTTP 503 with overall
     * {@code status} DOWN and a {@code checks} entry naming that check as DOWN, while liveness stays
     * UP at 200, so an orchestrator pulls the not-ready pod without killing the process.
     */
    @Test
    void readinessWithDownCheckReports503WhileLivenessStaysUp(Vertx vertx, VertxTestContext ctx) {
        var verticle = new ProbeVerticle(false);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            var port = verticle.actualPort();
            client.get(port, "localhost", "/health")
                    .send()
                    .onComplete(ctx.succeeding(health -> ctx.verify(() -> {
                        assertStatusCode(ctx, 200, health.statusCode(), health.bodyAsString());
                        assertOverallStatus(ctx, "UP", health.bodyAsJsonObject());
                        client.get(port, "localhost", "/readiness")
                                .send()
                                .onComplete(ctx.succeeding(ready -> ctx.verify(() -> {
                                    assertStatusCode(ctx, 503, ready.statusCode(), ready.bodyAsString());
                                    var body = ready.bodyAsJsonObject();
                                    assertOverallStatus(ctx, "DOWN", body);
                                    assertCheck(ctx, body, "dependency", "DOWN");
                                    client.close();
                                    ctx.completeNow();
                                })));
                    })));
        }));
    }

    private static void assertStatusCode(VertxTestContext ctx, int expected, int actual, String body) {
        if (actual != expected) {
            ctx.failNow("expected " + expected + ", got " + actual + " body=" + body);
        }
    }

    private static void assertOverallStatus(VertxTestContext ctx, String expected, JsonObject body) {
        if (!expected.equals(body.getString("status"))) {
            ctx.failNow("expected status " + expected + ", got body=" + body.encode());
        }
    }

    private static void assertCheck(VertxTestContext ctx, JsonObject body, String name, String status) {
        JsonArray checks = body.getJsonArray("checks");
        if (checks == null) {
            ctx.failNow("expected a checks array, got body=" + body.encode());
            return;
        }
        for (int i = 0; i < checks.size(); i++) {
            var check = checks.getJsonObject(i);
            if (name.equals(check.getString("name"))) {
                if (!status.equals(check.getString("status"))) {
                    ctx.failNow("check '" + name + "' expected " + status + ", got body=" + body.encode());
                }
                return;
            }
        }
        ctx.failNow("no check named '" + name + "' in body=" + body.encode());
    }
}
