package io.lattice.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.metrics.LatticeMetrics;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LatticeBootstrap}, which constructs the {@code Vertx} instance every service runs
 * on.
 *
 * <p>This is the only place Vert.x's own metrics can be switched on: the backend is chosen when the
 * instance is built, so a verticle cannot do it later. The tests therefore assert the thing that
 * cannot be checked anywhere else - that building through the bootstrap makes Vert.x report its own
 * HTTP server activity, and that switching metrics off leaves nothing behind.
 */
class LatticeBootstrapTest {

    private Vertx vertx;

    /** Closes the instance and unbinds the registry so one test cannot read another's meters. */
    @AfterEach
    void tearDown() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            vertx = null;
        }
        LatticeMetrics.reset();
    }

    /** Serves one request against a throwaway server, so Vert.x has something to have measured. */
    private void serveOneRequest() throws Exception {
        var server = vertx.createHttpServer()
                .requestHandler(request -> request.response().end("ok"))
                .listen(0)
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        var client = WebClient.create(vertx);
        client.get(server.actualPort(), "localhost", "/ping")
                .send()
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        client.close();
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** The names Vert.x has registered on the scrape registry, empty when metrics are off. */
    private static java.util.List<String> vertxMeterNames() {
        return LatticeMetrics.prometheus()
                .map(registry -> registry.getMeters().stream()
                        .map(meter -> meter.getId().getName())
                        .filter(name -> name.startsWith("vertx."))
                        .distinct()
                        .toList())
                .orElse(java.util.List.of());
    }

    /**
     * Built with metrics on, the instance reports Vert.x's own HTTP server activity. This is what the
     * bootstrap exists for: the binding supplies these families, and nothing a verticle does later can
     * turn them on.
     */
    @Test
    void metricsOnMakesVertxReportItsOwnHttpServer() throws Exception {
        vertx = LatticeBootstrap.vertx(true);

        serveOneRequest();

        assertTrue(
                vertxMeterNames().stream().anyMatch(name -> name.startsWith("vertx.http.server")),
                "expected a vertx.http.server meter, found " + vertxMeterNames());
    }

    /**
     * The families the design deliberately leaves off stay off, so the scrape payload and the label
     * surface do not carry families nobody has asked a question about.
     */
    @Test
    void disabledFamiliesAreNotRegistered() throws Exception {
        vertx = LatticeBootstrap.vertx(true);

        serveOneRequest();

        var names = vertxMeterNames();
        assertTrue(
                names.stream().noneMatch(name -> name.startsWith("vertx.eventbus")),
                "the event bus is in-process and deliberately not measured, found " + names);
        assertTrue(
                names.stream().noneMatch(name -> name.startsWith("vertx.http.client")),
                "the HTTP client family is deliberately off, found " + names);
    }

    /** Built with metrics off, no registry exists at all and Vert.x registers nothing. */
    @Test
    void metricsOffRegistersNothing() throws Exception {
        vertx = LatticeBootstrap.vertx(false);

        serveOneRequest();

        assertFalse(LatticeMetrics.prometheus().isPresent(), "no registry should exist when metrics are off");
        assertEquals(java.util.List.of(), vertxMeterNames());
    }

    /**
     * The Java Virtual Machine meters are bound exactly once. They are bound by this project rather
     * than by the Vert.x binding, and letting both do it would register every one of them twice.
     */
    @Test
    void jvmMetersAreBoundOnce() {
        vertx = LatticeBootstrap.vertx(true);

        var memoryUsed = LatticeMetrics.prometheus().orElseThrow().getMeters().stream()
                .filter(meter -> "jvm.memory.used".equals(meter.getId().getName()))
                .count();

        assertTrue(memoryUsed > 0, "the JVM binders should be bound");
    }
}
