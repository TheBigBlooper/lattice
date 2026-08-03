package io.lattice.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.BaseVerticle;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Contract tests for the metrics surface {@link BaseVerticle} mounts.
 *
 * <p>Pins the two properties the observability design turns on: the scrape endpoint is served on a
 * <b>separate management port</b> and is deliberately absent from the API port, and switching
 * metrics off binds no management port at all rather than serving an empty one.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class MetricsSurfaceTest {

    /** A minimal service whose metrics state is fixed at construction, on ephemeral ports. */
    static final class MeteredVerticle extends BaseVerticle {
        private final boolean metricsOn;

        MeteredVerticle(boolean metricsOn) {
            this.metricsOn = metricsOn;
        }

        @Override
        protected String keycloakRealmUrl() {
            return realm.realmUrl();
        }

        @Override
        protected int httpPort() {
            return 0;
        }

        @Override
        protected boolean metricsEnabled() {
            return metricsOn;
        }

        @Override
        protected int metricsPort() {
            return 0;
        }

        @Override
        protected void configureRoutes(Router router) {
            router.get("/ping").handler(ctx -> ctx.response().end("pong"));
        }
    }

    private static TestRealm realm;

    /** The base refuses to start without a realm, so even a metrics test needs one present. */
    @BeforeAll
    static void startRealm() {
        realm = TestRealm.start("lattice");
    }

    /** Releases the realm's HTTP server. */
    @AfterAll
    static void stopRealm() {
        realm.close();
    }

    /** Drops the registry between tests so one test's meters cannot be read by the next. */
    @AfterEach
    void resetRegistry() {
        LatticeMetrics.reset();
    }

    /**
     * With metrics enabled the management port serves Prometheus text exposition at {@code /metrics},
     * and that port is not the API port - the separation the design chose over sharing one port.
     */
    @Test
    void metricsServedOnItsOwnManagementPort(Vertx vertx, VertxTestContext ctx) {
        var verticle = new MeteredVerticle(true);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            ctx.verify(() -> assertNotEquals(
                    verticle.actualPort(), verticle.actualMetricsPort(), "metrics must not share the API port"));
            client.get(verticle.actualMetricsPort(), "localhost", "/metrics")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        assertTrue(
                                resp.getHeader("content-type").startsWith("text/plain"),
                                "Prometheus exposition is text/plain, was " + resp.getHeader("content-type"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * The scrape path is absent from the API port. Serving it there would put an unauthenticated
     * surface on the port the console's browser reaches, which is the thing the separate port avoids.
     */
    @Test
    void metricsPathIsNotServedOnTheApiPort(Vertx vertx, VertxTestContext ctx) {
        var verticle = new MeteredVerticle(true);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/metrics")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(404, resp.statusCode());
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * With metrics disabled no management port is bound at all. A disabled state that still opened a
     * port would leave the surface reachable while reporting itself off.
     */
    @Test
    void metricsDisabledBindsNoManagementPort(Vertx vertx, VertxTestContext ctx) {
        var verticle = new MeteredVerticle(false);
        vertx.deployVerticle(verticle)
                .onComplete(ctx.succeeding(id -> ctx.verify(() -> {
                    assertEquals(-1, verticle.actualMetricsPort(), "no management port should be bound");
                    ctx.completeNow();
                })));
    }

    /**
     * The service still serves normally with metrics off - switching instrumentation off is not
     * allowed to change what the service does.
     */
    @Test
    void serviceServesNormallyWithMetricsDisabled(Vertx vertx, VertxTestContext ctx) {
        var verticle = new MeteredVerticle(false);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/ping")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        assertEquals("pong", resp.bodyAsString());
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }
}
