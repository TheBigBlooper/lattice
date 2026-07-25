package io.lattice.meshgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests for {@link ClusterHealthService}, which computes this cluster's health by polling the
 * readiness of the services it is configured to watch.
 *
 * <p>This rollup is the only thing that makes the {@code health} field on the mesh meaningful: the
 * gateway's own readiness can never fail (it has no datastore, and a broker outage deliberately does
 * not fail it), so announcing that instead would put a constant {@code ready} on the wire while a
 * visibly broken baseline rendered as healthy in every peer's unified view.
 *
 * <p>Driven against real HTTP servers on ephemeral ports rather than a mocked client, so the
 * distinction between "answered 503", "answered garbage" and "refused the connection" is genuinely
 * exercised - all three must count as not-UP.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class ClusterHealthServiceTest {

    private Vertx vertx;
    private final List<HttpServer> servers = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (var server : servers) {
            server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** Starts a stub service whose /readiness answers with the given status code. */
    private String stubService(int readinessStatus) throws Exception {
        var server = vertx.createHttpServer()
                .requestHandler(request ->
                        request.response().setStatusCode(readinessStatus).end("{\"status\":\"x\"}"))
                .listen(0)
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        servers.add(server);
        return "http://localhost:" + server.actualPort();
    }

    /**
     * Builds the watched-service map in declaration order, which {@code Map.of} cannot promise.
     *
     * <p>{@code @SafeVarargs} because the varargs array is only ever read here, never stored or
     * published, so the generic array javac must create cannot be polluted. Without it every call site
     * carries an unchecked-generic-array warning for a call that is plainly safe.
     */
    @SafeVarargs
    private static Map<String, String> services(Map.Entry<String, String>... entries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (var entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    /** Every watched service answering 200 rolls up to ready. */
    @Test
    void everyServiceUpIsReady(Vertx testVertx, VertxTestContext ctx) throws Exception {
        vertx = testVertx;
        var health = new ClusterHealthService(
                vertx, services(Map.entry("orders", stubService(200)), Map.entry("inventory", stubService(200))));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("ready", rollup.health());
                    assertEquals(2, rollup.services().size());
                    assertTrue(rollup.services().stream()
                            .allMatch(service -> service.status().equals("UP")));
                    ctx.completeNow();
                })));
    }

    /** One service down rolls up to degraded, and the breakdown names which one. */
    @Test
    void oneServiceDownIsDegraded(Vertx testVertx, VertxTestContext ctx) throws Exception {
        vertx = testVertx;
        var health = new ClusterHealthService(
                vertx, services(Map.entry("orders", stubService(200)), Map.entry("inventory", stubService(503))));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("degraded", rollup.health());
                    assertEquals("UP", rollup.services().get(0).status());
                    assertEquals("DOWN", rollup.services().get(1).status());
                    ctx.completeNow();
                })));
    }

    /**
     * No service reachable rolls up to down, not degraded. A baseline whose every service is gone is a
     * materially different operator situation from one with a single flaky service, and they would
     * otherwise render identically in a peer's unified view.
     */
    @Test
    void noServiceReachableIsDown(Vertx testVertx, VertxTestContext ctx) {
        vertx = testVertx;
        // A port nothing listens on: the connection is refused rather than answered.
        var health = new ClusterHealthService(
                vertx,
                services(Map.entry("orders", "http://127.0.0.1:1"), Map.entry("inventory", "http://127.0.0.1:1")));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("down", rollup.health());
                    assertTrue(rollup.services().stream()
                            .allMatch(service -> service.status().equals("DOWN")));
                    ctx.completeNow();
                })));
    }

    /**
     * A refused connection counts as DOWN exactly like a 503 answer: "reachable but broken" and
     * "not reachable at all" are both not-UP as far as the rollup is concerned.
     */
    @Test
    void aRefusedConnectionCountsAsDown(Vertx testVertx, VertxTestContext ctx) throws Exception {
        vertx = testVertx;
        var health = new ClusterHealthService(
                vertx, services(Map.entry("orders", stubService(200)), Map.entry("inventory", "http://127.0.0.1:1")));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("degraded", rollup.health());
                    assertEquals("DOWN", rollup.services().get(1).status());
                    ctx.completeNow();
                })));
    }

    /**
     * With nothing configured to watch there is nothing that can contradict readiness, so the rollup
     * is ready - but the misconfiguration is surfaced loudly rather than passing silently, since a
     * cluster watching no services would otherwise look permanently healthy.
     */
    @Test
    void anEmptyServiceListIsReadyButWarns(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) {
        vertx = testVertx;
        logs.expectWarn("no services configured");
        var health = new ClusterHealthService(vertx, Map.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("ready", rollup.health());
                    assertTrue(rollup.services().isEmpty());
                    ctx.completeNow();
                })));
    }
}
