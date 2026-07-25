package io.lattice.common.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.contract.mesh.ClusterAnnouncement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PeerRegistry}, a cluster's own view of the peers it has heard announce
 * themselves on the mesh. Discovery is decentralized (there is no central registry), so each cluster
 * derives liveness purely from when it last heard from a peer.
 *
 * <p>Driven with a hand-advanced clock rather than real waiting, so the time-to-live expiry and
 * recovery are exercised deterministically instead of by sleeping.
 */
class PeerRegistryTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Instant START = Instant.parse("2026-07-25T00:00:00Z");

    private MutableClock clock;
    private PeerRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        registry = new PeerRegistry("hub-west", TTL, clock);
    }

    private static ClusterAnnouncement peerAnnouncement(String health) {
        return new ClusterAnnouncement(
                "hub-east", "us-east", "1.0.0", health, "https://east.console:3000", "https://east.svc:8080/api/v1");
    }

    /**
     * A heard announcement becomes a REACHABLE peer carrying the identity and both Shape A endpoints,
     * which is what the console needs to render the peer and to redirect an operator to it.
     */
    @Test
    void recordsAnAnnouncedPeerWithItsEndpoints() {
        registry.record(peerAnnouncement("ready"));

        var peers = registry.peers();
        assertEquals(1, peers.size());
        var peer = peers.get(0);
        assertEquals("hub-east", peer.clusterId());
        assertEquals("us-east", peer.region());
        assertEquals("1.0.0", peer.baselineVersion());
        assertEquals("ready", peer.health());
        assertEquals("https://east.console:3000", peer.consoleUrl());
        assertEquals("https://east.svc:8080/api/v1", peer.apiBaseUrl());
        assertEquals(START, peer.lastSeen());
        assertEquals(Reachability.REACHABLE, peer.reachability());
    }

    /**
     * A cluster never lists itself: the registry is a view of <em>peers</em>, and a cluster hears its
     * own multicast announcements.
     */
    @Test
    void ignoresItsOwnAnnouncement() {
        registry.record(new ClusterAnnouncement(
                "hub-west", "us-west", "1.0.0", "ready", "https://west.console:3000", "https://west.svc:8080/api/v1"));

        assertTrue(registry.peers().isEmpty(), "a cluster is not its own peer");
    }

    /** A repeat announcement refreshes the peer's last-seen and its latest fields, without duplicating it. */
    @Test
    void refreshesAnExistingPeerRatherThanDuplicatingIt() {
        registry.record(peerAnnouncement("ready"));
        clock.advance(Duration.ofSeconds(10));
        registry.record(peerAnnouncement("degraded"));

        var peers = registry.peers();
        assertEquals(1, peers.size(), "the same cluster is one peer, however often it announces");
        assertEquals("degraded", peers.get(0).health(), "the latest announcement wins");
        assertEquals(START.plusSeconds(10), peers.get(0).lastSeen());
    }

    /**
     * A peer unheard for longer than the time-to-live flips to UNREACHABLE but is <b>retained</b>, so an
     * operator sees "this baseline was here and has gone silent" rather than a peer silently vanishing.
     */
    @Test
    void expiresASilentPeerToUnreachableButRetainsIt() {
        registry.record(peerAnnouncement("ready"));
        clock.advance(TTL.plusSeconds(1));

        var peers = registry.peers();
        assertEquals(1, peers.size(), "an unreachable peer is retained, not deleted");
        assertEquals(Reachability.UNREACHABLE, peers.get(0).reachability());
        assertEquals("https://east.console:3000", peers.get(0).consoleUrl(), "last-known detail is kept");
    }

    /** A peer exactly at the time-to-live boundary is still reachable; expiry is strictly beyond it. */
    @Test
    void treatsThePeerAsReachableUpToTheBoundary() {
        registry.record(peerAnnouncement("ready"));
        clock.advance(TTL);

        assertEquals(Reachability.REACHABLE, registry.peers().get(0).reachability());
    }

    /** Hearing from an expired peer again flips it straight back to REACHABLE - discovery self-heals. */
    @Test
    void recoversAnExpiredPeerOnTheNextAnnouncement() {
        registry.record(peerAnnouncement("ready"));
        clock.advance(TTL.plusSeconds(5));
        assertEquals(Reachability.UNREACHABLE, registry.peers().get(0).reachability());

        registry.record(peerAnnouncement("ready"));

        var peer = registry.peers().get(0);
        assertEquals(Reachability.REACHABLE, peer.reachability());
        assertEquals(START.plus(TTL).plusSeconds(5), peer.lastSeen());
    }

    /** Several distinct clusters are each tracked separately. */
    @Test
    void tracksMultiplePeersIndependently() {
        registry.record(peerAnnouncement("ready"));
        registry.record(new ClusterAnnouncement(
                "hub-south",
                "us-south",
                "1.0.0",
                "ready",
                "https://south.console:3000",
                "https://south.svc:8080/api/v1"));

        assertEquals(2, registry.peers().size());
        assertTrue(registry.peers().stream().anyMatch(p -> p.clusterId().equals("hub-south")));
    }

    /** A hand-advanced clock, so time-to-live behavior is tested without sleeping. */
    private static final class MutableClock extends java.time.Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

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
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
