package io.lattice.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Readiness gating when Elasticsearch is unreachable. The service still starts (its HTTP server
 * binds and liveness stays UP) but {@code /readiness} reports DOWN (503) so an orchestrator pulls the
 * not-ready pod out of rotation until its dependency recovers. Deployed against a closed port, so no
 * container is needed - the readiness check's DOWN branch is what is under test.
 */
@ExtendWith(VertxExtension.class)
class InventoryReadinessDownIT {

    // A port nothing listens on: the Elasticsearch ping fails fast (connection refused).
    private static final String UNREACHABLE_ES = "http://127.0.0.1:1";

    /** Liveness stays UP (200) even while the Elasticsearch dependency is unreachable. */
    @Test
    void livenessStaysUpWhileElasticsearchDown(Vertx vertx, VertxTestContext ctx) {
        var verticle = new InventoryVerticle(UNREACHABLE_ES, 0);
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
     * A business request while Elasticsearch is unreachable is classified as a down dependency: it
     * returns a 503 UNAVAILABLE error envelope (the dependency failure is not leaked), rather than
     * hanging or exposing internals. That path logs at WARN, so per the test-hygiene rule this asserts
     * the log fired (a scoped Logback ListAppender on the InventoryVerticle logger).
     */
    @Test
    void requestWhileElasticsearchDownReturnsUnavailableEnvelope(Vertx vertx, VertxTestContext ctx) {
        var verticleLogger = (Logger) LoggerFactory.getLogger(InventoryVerticle.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        verticleLogger.addAppender(appender);

        var verticle = new InventoryVerticle(UNREACHABLE_ES, 0);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/api/v1/inventory/any-sku")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(503, resp.statusCode());
                        assertEquals(
                                "UNAVAILABLE",
                                resp.bodyAsJsonObject().getJsonObject("error").getString("code"));
                        boolean loggedWarn = appender.list.stream()
                                .anyMatch(event -> event.getLevel() == Level.WARN
                                        && event.getFormattedMessage().contains("dependency unavailable"));
                        assertTrue(loggedWarn, "the dependency-unavailable path must log at WARN");
                        verticleLogger.detachAppender(appender);
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** Readiness reports DOWN (503) with the elasticsearch check DOWN while Elasticsearch is unreachable. */
    @Test
    void readinessIsDownWhileElasticsearchDown(Vertx vertx, VertxTestContext ctx) {
        var verticle = new InventoryVerticle(UNREACHABLE_ES, 0);
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/readiness")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(503, resp.statusCode());
                        var body = resp.bodyAsJsonObject();
                        assertEquals("DOWN", body.getString("status"));
                        assertTrue(
                                body.getJsonArray("checks").stream()
                                        .map(JsonObject.class::cast)
                                        .anyMatch(c -> "elasticsearch".equals(c.getString("name"))
                                                && "DOWN".equals(c.getString("status"))),
                                "the elasticsearch readiness check must be DOWN");
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }
}
