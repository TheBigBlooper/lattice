package io.lattice.meshgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.mesh.MeshClient;
import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.contract.mesh.MeshEnvelope;
import io.lattice.contract.mesh.ServiceHealth;
import io.lattice.meshgateway.MeshGatewayConfig;
import io.lattice.meshgateway.service.ClusterHealthService.ClusterHealth;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests for {@link AnnouncerService}, which publishes this cluster's presence to the mesh.
 *
 * <p>Exercised through {@code announceOnce}, the unit the heartbeat timer calls, rather than by waiting
 * on real timer ticks - a test that sleeps for a cadence is slow and flaky by construction, and the
 * cadence itself is Vert.x's periodic timer rather than logic worth re-verifying here.
 */
@ExtendWith(VertxExtension.class)
class AnnouncerServiceTest {

    private static MeshGatewayConfig config() {
        return new MeshGatewayConfig(
                "hub-west",
                "us-west",
                "1.0.0",
                "https://west.console:3000",
                "https://west.svc:8080/api/v1",
                "tcp://localhost:61616",
                "artemis",
                "artemis",
                Map.of(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }

    /** An announcement carries this cluster's identity and both Shape A endpoints from config. */
    @Test
    void announcesThisClustersIdentityAndEndpoints(VertxTestContext ctx) {
        var mesh = new RecordingMeshClient();
        var announcer = new AnnouncerService(config(), mesh, () -> Future.succeededFuture(readyRollup()));

        announcer
                .announceOnce()
                .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    assertEquals(1, mesh.announcements.size());
                    var announced = mesh.announcements.get(0);
                    assertEquals("hub-west", announced.clusterId());
                    assertEquals("us-west", announced.region());
                    assertEquals("1.0.0", announced.baselineVersion());
                    assertEquals("https://west.console:3000", announced.consoleUrl());
                    assertEquals("https://west.svc:8080/api/v1", announced.apiBaseUrl());
                    ctx.completeNow();
                })));
    }

    /** The announced health is the freshly polled rollup, not a stale or hardcoded value. */
    @Test
    void announcesTheFreshlyPolledRollup(VertxTestContext ctx) {
        var mesh = new RecordingMeshClient();
        var degraded = new ClusterHealth("degraded", List.of(new ServiceHealth("orders", "DOWN")));
        var announcer = new AnnouncerService(config(), mesh, () -> Future.succeededFuture(degraded));

        announcer
                .announceOnce()
                .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    assertEquals("degraded", mesh.announcements.get(0).health());
                    ctx.completeNow();
                })));
    }

    /**
     * The last polled rollup is retained so {@code getBaseline} can serve it without triggering its own
     * poll - the endpoint stays cheap and never blocks on a slow service.
     */
    @Test
    void retainsTheLastRollupForTheBaselineEndpoint(VertxTestContext ctx) {
        var mesh = new RecordingMeshClient();
        var degraded = new ClusterHealth("degraded", List.of(new ServiceHealth("orders", "DOWN")));
        var announcer = new AnnouncerService(config(), mesh, () -> Future.succeededFuture(degraded));

        assertEquals("ready", announcer.lastRollup().health(), "before any poll it reports the optimistic default");

        announcer
                .announceOnce()
                .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    assertEquals("degraded", announcer.lastRollup().health());
                    assertEquals(1, announcer.lastRollup().services().size());
                    ctx.completeNow();
                })));
    }

    /**
     * A failed health poll still announces. Going silent would make every peer believe this baseline
     * had vanished entirely, which is far worse than announcing a stale or pessimistic health label.
     */
    @Test
    void stillAnnouncesWhenTheHealthPollFails(VertxTestContext ctx) {
        var mesh = new RecordingMeshClient();
        var announcer = new AnnouncerService(
                config(), mesh, () -> Future.failedFuture(new IllegalStateException("poll exploded")));

        announcer
                .announceOnce()
                .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    assertEquals(1, mesh.announcements.size(), "a broken health poll must not silence the announce");
                    assertTrue(
                            mesh.announcements.get(0).health() != null,
                            "the announcement still carries some health label");
                    ctx.completeNow();
                })));
    }

    /**
     * A health change is carried on the very next announcement, so a peer's unified view reflects it
     * within one heartbeat rather than needing its own trigger. Announcing repeatedly at an unchanged
     * health is equally fine - the heartbeat is the liveness signal, so it must keep going regardless.
     */
    @Test
    void carriesAHealthChangeOnTheNextAnnouncement(VertxTestContext ctx) {
        var mesh = new RecordingMeshClient();
        var health = new java.util.concurrent.atomic.AtomicReference<>(readyRollup());
        var announcer = new AnnouncerService(config(), mesh, () -> Future.succeededFuture(health.get()));

        announcer
                .announceOnce()
                .compose(first -> {
                    health.set(new ClusterHealth("down", List.of(new ServiceHealth("orders", "DOWN"))));
                    return announcer.announceOnce();
                })
                .compose(second -> announcer.announceOnce())
                .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    assertEquals(3, mesh.announcements.size(), "the heartbeat keeps announcing either way");
                    assertEquals("ready", mesh.announcements.get(0).health());
                    assertEquals("down", mesh.announcements.get(1).health(), "the change is carried immediately");
                    assertEquals("down", mesh.announcements.get(2).health(), "and stays until it changes again");
                    ctx.completeNow();
                })));
    }

    /** A mesh publish failure is absorbed, so one bad tick cannot break the heartbeat loop. */
    @Test
    void absorbsAMeshPublishFailure(VertxTestContext ctx) {
        var mesh = new RecordingMeshClient();
        mesh.failNextAnnounce = true;
        var announcer = new AnnouncerService(config(), mesh, () -> Future.succeededFuture(readyRollup()));

        announcer.announceOnce().onComplete(ctx.succeeding(done -> ctx.verify(ctx::completeNow)));
    }

    private static ClusterHealth readyRollup() {
        return new ClusterHealth("ready", List.of(new ServiceHealth("orders", "UP")));
    }

    /** A mesh client that records what was announced instead of touching a broker. */
    private static final class RecordingMeshClient implements MeshClient {

        private final List<ClusterAnnouncement> announcements = new ArrayList<>();
        private boolean failNextAnnounce;

        @Override
        public Future<Void> announce(ClusterAnnouncement announcement) {
            if (failNextAnnounce) {
                failNextAnnounce = false;
                return Future.failedFuture(new IllegalStateException("broker gone"));
            }
            announcements.add(announcement);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> subscribe(String address, Handler<MeshEnvelope> handler) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> close() {
            return Future.succeededFuture();
        }
    }
}
