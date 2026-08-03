package io.lattice.meshgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.metrics.LatticeMetrics;
import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.contract.mesh.ComponentHealth;
import io.lattice.contract.mesh.ComponentKind;
import io.lattice.contract.mesh.ComponentStatus;
import io.lattice.contract.mesh.MeshLinkState;
import io.lattice.meshgateway.InfrastructureTarget;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests for {@link ClusterHealthService}, which computes this cluster's health by polling the
 * readiness of the services it is configured to watch, and - in the same fan-out - the state of the
 * infrastructure those services depend on.
 *
 * <p>This rollup is the only thing that makes the {@code health} field on the mesh meaningful: the
 * gateway's own readiness can never fail (it has no datastore, and a broker outage deliberately does
 * not fail it), so announcing that instead would put a constant {@code ready} on the wire while a
 * visibly broken baseline rendered as healthy in every peer's unified view.
 *
 * <p><b>Two surfaces, one poll.</b> The services rollup is what rides the mesh; the infrastructure
 * breakdown and the gateway's own row are served locally only. Several tests below exist purely to
 * pin that separation, because both additions are exactly the kind that leak into the announced
 * verdict by accident (locked #42, #43, #66).
 *
 * <p>Driven against real HTTP servers on ephemeral ports rather than a mocked client, so the
 * distinction between "answered 503", "answered garbage" and "refused the connection" is genuinely
 * exercised - all three must count as not-UP.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class ClusterHealthServiceTest {

    /** A port nothing listens on: the connection is refused rather than answered. */
    private static final String DEAD_ADDRESS = "http://127.0.0.1:1";

    private Vertx vertx;
    private final List<HttpServer> servers = new java.util.ArrayList<>();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

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
        return stubComponent(readinessStatus, "{\"status\":\"x\"}");
    }

    /**
     * Starts a stub component answering every path with the given status code and body, recording the
     * path it was asked for so a test can assert the probe went to the endpoint it should have.
     */
    private String stubComponent(int statusCode, String body) throws Exception {
        var server = vertx.createHttpServer()
                .requestHandler(request -> {
                    requestedPaths.add(request.path());
                    request.response()
                            .setStatusCode(statusCode)
                            .putHeader("content-type", "application/json")
                            .end(body);
                })
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

    /**
     * Builds the service under test, declaring the startup warnings it logs by design so the
     * fail-on-unexpected-log harness sees them asserted rather than printed. An empty watch list and
     * an empty infrastructure list are both supported deployments that warn loudly.
     */
    private ClusterHealthService healthService(
            ExpectedLogs logs, Map<String, String> watched, List<InfrastructureTarget> infrastructure) {
        return healthService(logs, watched, infrastructure, () -> MeshLinkState.UP);
    }

    /** As above, with the mesh-link state the Artemis row renders supplied by the test. */
    private ClusterHealthService healthService(
            ExpectedLogs logs,
            Map<String, String> watched,
            List<InfrastructureTarget> infrastructure,
            Supplier<MeshLinkState> meshLink) {
        if (watched.isEmpty()) {
            logs.expectWarn("no services configured to watch");
        }
        if (infrastructure.isEmpty()) {
            logs.expectWarn("no infrastructure configured");
        }
        return new ClusterHealthService(vertx, watched, infrastructure, meshLink);
    }

    /**
     * The rollup is reported as a state set: exactly one of ready, degraded and down reads 1, and the
     * others read 0. A gauge carrying a number that encodes a state would need a decoder ring; a state
     * set is directly readable and directly graphable.
     */
    @Test
    void rollupIsReportedAsAStateSet(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) throws Exception {
        vertx = testVertx;
        LatticeMetrics.reset();
        LatticeMetrics.enableForTesting();
        var health = healthService(
                logs,
                services(Map.entry("orders", stubService(200)), Map.entry("inventory", stubService(503))),
                List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("degraded", rollup.health());
                    assertEquals(1.0, healthState("degraded"));
                    assertEquals(0.0, healthState("ready"));
                    assertEquals(0.0, healthState("down"));
                    LatticeMetrics.reset();
                    ctx.completeNow();
                })));
    }

    /**
     * Each watched service's readiness poll is counted under its own name and outcome, so "which
     * service dragged the baseline to degraded, and how often" is answerable without reading logs.
     * A non-200 counts as not-up, matching the rollup's own definition rather than a second one.
     */
    @Test
    void readinessPollsAreCountedPerServiceAndOutcome(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs)
            throws Exception {
        vertx = testVertx;
        LatticeMetrics.reset();
        LatticeMetrics.enableForTesting();
        var health = healthService(
                logs,
                services(Map.entry("orders", stubService(200)), Map.entry("inventory", stubService(503))),
                List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals(1.0, polls("orders", "up"));
                    assertEquals(1.0, polls("inventory", "not_up"));
                    LatticeMetrics.reset();
                    ctx.completeNow();
                })));
    }

    private static double healthState(String state) {
        return LatticeMetrics.registry()
                .get(LatticeMetrics.BASELINE_HEALTH)
                .tag("state", state)
                .gauge()
                .value();
    }

    private static double polls(String service, String outcome) {
        return LatticeMetrics.registry()
                .get(LatticeMetrics.SERVICE_READINESS_POLLS)
                .tag("service", service)
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    /** Every watched service answering 200 rolls up to ready. */
    @Test
    void everyServiceUpIsReady(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) throws Exception {
        vertx = testVertx;
        var health = healthService(
                logs,
                services(Map.entry("orders", stubService(200)), Map.entry("inventory", stubService(200))),
                List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("ready", rollup.health());
                    assertEquals("UP", rollup.services().get(0).status());
                    assertEquals("UP", rollup.services().get(1).status());
                    ctx.completeNow();
                })));
    }

    /** One service down rolls up to degraded, and the breakdown names which one. */
    @Test
    void oneServiceDownIsDegraded(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) throws Exception {
        vertx = testVertx;
        var health = healthService(
                logs,
                services(Map.entry("orders", stubService(200)), Map.entry("inventory", stubService(503))),
                List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("degraded", rollup.health());
                    assertEquals("UP", rollup.services().get(0).status());
                    assertEquals("DOWN", rollup.services().get(1).status());
                    ctx.completeNow();
                })));
    }

    /**
     * No watched service reachable rolls up to down, not degraded. A baseline whose every service is
     * gone is a materially different operator situation from one with a single flaky service, and they
     * would otherwise render identically in a peer's unified view.
     */
    @Test
    void noServiceReachableIsDown(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) {
        vertx = testVertx;
        var health = healthService(
                logs, services(Map.entry("orders", DEAD_ADDRESS), Map.entry("inventory", DEAD_ADDRESS)), List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("down", rollup.health());
                    assertEquals("DOWN", rollup.services().get(0).status());
                    assertEquals("DOWN", rollup.services().get(1).status());
                    ctx.completeNow();
                })));
    }

    /**
     * A refused connection counts as DOWN exactly like a 503 answer: "reachable but broken" and
     * "not reachable at all" are both not-UP as far as the rollup is concerned.
     */
    @Test
    void aRefusedConnectionCountsAsDown(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) throws Exception {
        vertx = testVertx;
        var health = healthService(
                logs, services(Map.entry("orders", stubService(200)), Map.entry("inventory", DEAD_ADDRESS)), List.of());

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
     * cluster watching no services would otherwise look permanently healthy. An unconfigured
     * infrastructure list behaves the same way: an empty list plus a warning, never a failure.
     */
    @Test
    void anEmptyServiceListIsReadyButWarns(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) {
        vertx = testVertx;
        var health = healthService(logs, Map.of(), List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("ready", rollup.health());
                    assertTrue(rollup.infrastructure().isEmpty(), "no infrastructure card when none is configured");
                    ctx.completeNow();
                })));
    }

    /**
     * The gateway appears in its own per-service breakdown, so an operator sees all three Vert.x
     * services rather than two - and it is still not counted in the rollup that rides the mesh.
     *
     * <p>This is the sharp case: every watched service is unreachable, so the announced verdict must
     * stay {@code down}. Counting the gateway's own always-up row would lift it to {@code degraded}
     * and put a floor under the verdict that no outage could get past, which is exactly what locked
     * #42 keeps it off the wire to prevent.
     */
    @Test
    void listsTheGatewayItselfWithoutCountingItInTheRollup(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) {
        vertx = testVertx;
        var health = healthService(
                logs, services(Map.entry("orders", DEAD_ADDRESS), Map.entry("inventory", DEAD_ADDRESS)), List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("down", rollup.health(), "the gateway's own row must not lift the verdict off down");
                    assertEquals(3, rollup.services().size(), "three services are listed, not two");
                    var self = rollup.services().get(2);
                    assertEquals("mesh-gateway", self.name());
                    assertEquals("UP", self.status(), "it answered this poll, so it is up by definition");
                    ctx.completeNow();
                })));
    }

    /**
     * The gateway lists itself even when nothing else is configured, because it is running whether or
     * not a watch list names anything.
     */
    @Test
    void listsTheGatewayItselfWithNothingElseConfigured(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) {
        vertx = testVertx;
        var health = healthService(logs, Map.of(), List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals(1, rollup.services().size());
                    assertEquals("mesh-gateway", rollup.services().get(0).name());
                    ctx.completeNow();
                })));
    }

    /**
     * A watch list that names the gateway does not produce a second row, nor let it into the rollup:
     * the gateway reports itself, so a configured entry for it would be a duplicate that also skewed
     * the announced verdict.
     */
    @Test
    void doesNotDuplicateTheGatewayWhenItIsAlsoConfigured(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) {
        vertx = testVertx;
        var health = healthService(
                logs, services(Map.entry("orders", DEAD_ADDRESS), Map.entry("mesh-gateway", DEAD_ADDRESS)), List.of());

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals("down", rollup.health(), "only orders is counted, and it is down");
                    assertEquals(2, rollup.services().size(), "orders plus one gateway row, not two");
                    assertEquals("mesh-gateway", rollup.services().get(1).name());
                    assertEquals("UP", rollup.services().get(1).status());
                    ctx.completeNow();
                })));
    }

    /** A green Elasticsearch cluster is UP, with nothing to add in detail. */
    @Test
    void elasticsearchGreenIsUp(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) throws Exception {
        vertx = testVertx;
        var url = stubComponent(200, "{\"status\":\"green\"}");
        var health = healthService(
                logs, Map.of(), List.of(new InfrastructureTarget("datastore", ComponentKind.ELASTICSEARCH, url)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    var component = rollup.infrastructure().get(0);
                    assertEquals("datastore", component.name());
                    assertEquals(ComponentKind.ELASTICSEARCH, component.kind());
                    assertEquals(ComponentStatus.UP, component.status());
                    assertNull(component.detail(), "a healthy component has nothing to explain");
                    assertTrue(requestedPaths.contains("/_cluster/health"), "cluster health, not a client ping");
                    ctx.completeNow();
                })));
    }

    /**
     * A yellow cluster is DEGRADED, not DOWN and not UP. Yellow means every primary is allocated and
     * the cluster serves, with replicas missing - a real loss of redundancy on a multi-node baseline
     * that an operator should hear about without being told the datastore is unavailable.
     */
    @Test
    void elasticsearchYellowIsDegraded(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) throws Exception {
        vertx = testVertx;
        var url = stubComponent(200, "{\"status\":\"yellow\"}");
        var health = healthService(
                logs, Map.of(), List.of(new InfrastructureTarget("datastore", ComponentKind.ELASTICSEARCH, url)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    var component = rollup.infrastructure().get(0);
                    assertEquals(ComponentStatus.DEGRADED, component.status());
                    assertTrue(component.detail().contains("yellow"), "the native reading is carried through");
                    assertEquals("ready", rollup.health(), "infrastructure never enters the services rollup");
                    ctx.completeNow();
                })));
    }

    /**
     * A red cluster is DOWN. This is the state the existing per-service readiness ping cannot see at
     * all: primaries unallocated, data genuinely unavailable, and a ping that answers perfectly well.
     */
    @Test
    void elasticsearchRedIsDown(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) throws Exception {
        vertx = testVertx;
        var url = stubComponent(200, "{\"status\":\"red\"}");
        var health = healthService(
                logs, Map.of(), List.of(new InfrastructureTarget("datastore", ComponentKind.ELASTICSEARCH, url)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    var component = rollup.infrastructure().get(0);
                    assertEquals(ComponentStatus.DOWN, component.status());
                    assertTrue(component.detail().contains("red"));
                    ctx.completeNow();
                })));
    }

    /** Keycloak answering its documented body on the management port is UP, with no translation. */
    @Test
    void keycloakReportingUpIsUp(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) throws Exception {
        vertx = testVertx;
        var url = stubComponent(200, "{\"status\":\"UP\",\"checks\":[]}");
        var health = healthService(
                logs, Map.of(), List.of(new InfrastructureTarget("identity", ComponentKind.KEYCLOAK, url)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    var component = rollup.infrastructure().get(0);
                    assertEquals(ComponentKind.KEYCLOAK, component.kind());
                    assertEquals(ComponentStatus.UP, component.status());
                    assertTrue(requestedPaths.contains("/health/ready"), "the management readiness path");
                    ctx.completeNow();
                })));
    }

    /**
     * Keycloak answering 200 with a status other than UP is DEGRADED, carrying that status line: it
     * answered, so it is reachable, but it is telling us a sub-check is unhappy.
     */
    @Test
    void keycloakReportingSomethingElseIsDegraded(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs)
            throws Exception {
        vertx = testVertx;
        var url = stubComponent(200, "{\"status\":\"DOWN\",\"checks\":[]}");
        var health = healthService(
                logs, Map.of(), List.of(new InfrastructureTarget("identity", ComponentKind.KEYCLOAK, url)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    var component = rollup.infrastructure().get(0);
                    assertEquals(ComponentStatus.DEGRADED, component.status());
                    assertTrue(component.detail().contains("DOWN"), "the reported status line is carried");
                    ctx.completeNow();
                })));
    }

    /**
     * A failing sub-check is named in the detail line rather than reduced to a status code.
     *
     * <p>This is the identity database's only route to the console: it is deliberately not probed
     * separately, so the one place its state can be reported is the check Keycloak already publishes
     * about it. The body here is the one a Keycloak 26 with metrics enabled actually returns when its
     * database has gone - a 503 whose checks array names the failing check - so a detail line saying
     * only "returned 503" would leave an operator with a broken login and no cause.
     */
    @Test
    void keycloakNamesTheFailingCheckInDetail(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs)
            throws Exception {
        vertx = testVertx;
        var url = stubComponent(503, """
                {"status":"DOWN","checks":[\
                {"name":"Keycloak cluster health check","status":"UP"},\
                {"name":"Keycloak database connections async health check","status":"DOWN",\
                "data":{"Failing since":"2026-07-31 00:59:19,000"}}]}\
                """);
        var health = healthService(
                logs, Map.of(), List.of(new InfrastructureTarget("identity", ComponentKind.KEYCLOAK, url)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    var component = rollup.infrastructure().get(0);
                    assertTrue(
                            component.detail().contains("database connections"),
                            "the failing check is named, not just the status code - was: " + component.detail());
                    assertFalse(
                            component.detail().contains("cluster health check"),
                            "a check that is UP is not reported as a problem - was: " + component.detail());
                    ctx.completeNow();
                })));
    }

    /** A non-200 from the management endpoint is DEGRADED with the code in detail, not DOWN. */
    @Test
    void keycloakAnsweringNonTwoHundredIsDegraded(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs)
            throws Exception {
        vertx = testVertx;
        var url = stubComponent(503, "{}");
        var health = healthService(
                logs, Map.of(), List.of(new InfrastructureTarget("identity", ComponentKind.KEYCLOAK, url)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    var component = rollup.infrastructure().get(0);
                    assertEquals(ComponentStatus.DEGRADED, component.status());
                    assertTrue(component.detail().contains("503"));
                    ctx.completeNow();
                })));
    }

    /**
     * An unreachable component is reported DOWN with the failure in detail. It must never fail the
     * poll: a dead datastore would otherwise take the whole rollup with it and silence the announce.
     */
    @Test
    void anUnreachableComponentIsDownRatherThanAnError(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs)
            throws Exception {
        vertx = testVertx;
        var health = healthService(
                logs,
                services(Map.entry("orders", stubService(200))),
                List.of(
                        new InfrastructureTarget("datastore", ComponentKind.ELASTICSEARCH, DEAD_ADDRESS),
                        new InfrastructureTarget("identity", ComponentKind.KEYCLOAK, DEAD_ADDRESS)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals(
                            ComponentStatus.DOWN, rollup.infrastructure().get(0).status());
                    assertEquals(
                            ComponentStatus.DOWN, rollup.infrastructure().get(1).status());
                    assertNotNull(
                            rollup.infrastructure().get(0).detail(), "the failure is named rather than left blank");
                    assertEquals("ready", rollup.health(), "the services rollup is untouched by dead infrastructure");
                    ctx.completeNow();
                })));
    }

    /**
     * The Artemis row renders the mesh-link state the gateway already holds, rather than probing the
     * broker a second time. A held link is UP.
     */
    @Test
    void artemisRendersAHeldMeshLinkAsUp(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) {
        vertx = testVertx;
        var health = healthService(
                logs,
                Map.of(),
                List.of(new InfrastructureTarget("broker", ComponentKind.ARTEMIS, "")),
                () -> MeshLinkState.UP);

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    var component = rollup.infrastructure().get(0);
                    assertEquals(ComponentKind.ARTEMIS, component.kind());
                    assertEquals(ComponentStatus.UP, component.status());
                    ctx.completeNow();
                })));
    }

    /**
     * A dropped mesh link renders the Artemis row DOWN. There is no DEGRADED here: a connection is
     * held or it is not.
     */
    @Test
    void artemisRendersADroppedMeshLinkAsDown(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs) {
        vertx = testVertx;
        var health = healthService(
                logs,
                Map.of(),
                List.of(new InfrastructureTarget("broker", ComponentKind.ARTEMIS, "")),
                () -> MeshLinkState.DOWN);

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals(
                            ComponentStatus.DOWN, rollup.infrastructure().get(0).status());
                    assertEquals("ready", rollup.health(), "a dead broker is not a service outage");
                    ctx.completeNow();
                })));
    }

    /** Components are reported in configured order, so the console renders a stable list. */
    @Test
    void reportsInfrastructureInConfiguredOrder(Vertx testVertx, VertxTestContext ctx, ExpectedLogs logs)
            throws Exception {
        vertx = testVertx;
        var elasticsearch = stubComponent(200, "{\"status\":\"green\"}");
        var health = healthService(
                logs,
                Map.of(),
                List.of(
                        new InfrastructureTarget("datastore", ComponentKind.ELASTICSEARCH, elasticsearch),
                        new InfrastructureTarget("broker", ComponentKind.ARTEMIS, ""),
                        new InfrastructureTarget("identity", ComponentKind.KEYCLOAK, DEAD_ADDRESS)));

        health.poll()
                .onComplete(ctx.succeeding(rollup -> ctx.verify(() -> {
                    assertEquals(
                            List.of("datastore", "broker", "identity"),
                            rollup.infrastructure().stream()
                                    .map(ComponentHealth::name)
                                    .toList());
                    ctx.completeNow();
                })));
    }
}
