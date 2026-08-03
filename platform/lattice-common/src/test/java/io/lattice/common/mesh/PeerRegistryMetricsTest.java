package io.lattice.common.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lattice.common.metrics.LatticeMetrics;
import io.lattice.contract.mesh.ClusterAnnouncement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the peer-registry meters: the two gauges that report registry size and how much of it is
 * inside the time-to-live window, and the expiry counter that exists because a gauge alone cannot
 * distinguish a peer flapping across its time-to-live from a peer that has cleanly gone.
 */
class PeerRegistryMetricsTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    /** A clock the test advances by hand, so expiry is deterministic rather than timing-dependent. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-03T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private MovableClock clock;

    /** Gives each test its own registry so meter values cannot leak between them. */
    @BeforeEach
    void enableMetrics() {
        LatticeMetrics.reset();
        LatticeMetrics.enableForTesting();
        clock = new MovableClock();
    }

    /** Drops the registry so a later suite does not read this one's meters. */
    @AfterEach
    void resetRegistry() {
        LatticeMetrics.reset();
    }

    private static ClusterAnnouncement announcement(String clusterId) {
        return new ClusterAnnouncement(
                clusterId,
                "us-east",
                "1.0.0",
                "ready",
                "https://" + clusterId + ".console:3000",
                "https://" + clusterId + ".svc:8080/api/v1");
    }

    /** A recorded announcement makes the peer count one, and counts it as reachable. */
    @Test
    void recordingAPeerRaisesKnownAndReachable() {
        var registry = new PeerRegistry("hub-central", TTL, clock);

        registry.record(announcement("hub-east"));
        registry.peers();

        assertEquals(1.0, gauge(LatticeMetrics.MESH_PEERS_KNOWN));
        assertEquals(1.0, gauge(LatticeMetrics.MESH_PEERS_REACHABLE));
    }

    /**
     * A cluster's own announcement is not a peer, so it moves neither gauge. This mirrors the
     * registry's existing behaviour rather than assuming the meters are wired somewhere else.
     */
    @Test
    void ownAnnouncementIsNotCounted() {
        var registry = new PeerRegistry("hub-central", TTL, clock);

        registry.record(announcement("hub-central"));
        registry.peers();

        assertEquals(0.0, gauge(LatticeMetrics.MESH_PEERS_KNOWN));
    }

    /**
     * Crossing the time-to-live drops the reachable gauge while the peer stays known, and increments
     * the expiry counter once. The peer is retained, so only the reachable gauge falls.
     */
    @Test
    void crossingTimeToLiveExpiresThePeerOnce() {
        var registry = new PeerRegistry("hub-central", TTL, clock);
        registry.record(announcement("hub-east"));
        registry.peers();

        clock.advance(TTL.plusSeconds(1));
        registry.peers();

        assertEquals(1.0, gauge(LatticeMetrics.MESH_PEERS_KNOWN), "an expired peer is retained");
        assertEquals(0.0, gauge(LatticeMetrics.MESH_PEERS_REACHABLE));
        assertEquals(1.0, expiries("hub-east"));
    }

    /**
     * The expiry counter counts transitions, not reads. Reading the registry repeatedly while a peer
     * stays expired must not inflate it, or a flapping peer and a quiet one become indistinguishable
     * again - which is the whole reason the counter exists beside the gauge.
     */
    @Test
    void repeatedReadsWhileExpiredDoNotInflateTheCounter() {
        var registry = new PeerRegistry("hub-central", TTL, clock);
        registry.record(announcement("hub-east"));
        registry.peers();

        clock.advance(TTL.plusSeconds(1));
        registry.peers();
        registry.peers();
        registry.peers();

        assertEquals(1.0, expiries("hub-east"));
    }

    /** A peer that announces again after expiring counts as a second expiry only if it expires again. */
    @Test
    void recoveryThenSilenceCountsASecondExpiry() {
        var registry = new PeerRegistry("hub-central", TTL, clock);
        registry.record(announcement("hub-east"));
        registry.peers();

        clock.advance(TTL.plusSeconds(1));
        registry.peers();

        registry.record(announcement("hub-east"));
        registry.peers();
        assertEquals(1.0, gauge(LatticeMetrics.MESH_PEERS_REACHABLE), "announcing again restores reachability");

        clock.advance(TTL.plusSeconds(1));
        registry.peers();

        assertEquals(2.0, expiries("hub-east"));
    }

    private static double gauge(String name) {
        return LatticeMetrics.registry().get(name).gauge().value();
    }

    private static double expiries(String peer) {
        return LatticeMetrics.registry()
                .get(LatticeMetrics.MESH_PEER_EXPIRIES)
                .tag("peer_cluster", peer)
                .counter()
                .count();
    }
}
