package io.lattice.common.mesh;

import io.lattice.contract.mesh.ClusterAnnouncement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One cluster's own view of the peers it has heard announce themselves on the mesh.
 *
 * <p>Discovery is <b>decentralized</b>: there is no central registry, so every cluster builds this
 * view independently from the announcements it hears, and liveness is derived purely from how
 * recently each peer was last heard. The announcement heartbeat is itself the liveness signal - there
 * is no separate ping. This registry is what the status console reads to render the unified view and
 * to redirect an operator to a peer's own console (Shape A, locked #37).
 *
 * <p><b>Liveness uses this cluster's own receive time</b>, never the announcement's {@code occurredAt}.
 * Peers keep independent clocks, so trusting a peer's timestamp would let its clock drift mask (or
 * fake) its liveness.
 *
 * <p><b>Reachability is computed at read, never stored.</b> A peer does not need a timer to expire it;
 * whether it is past the time-to-live is a function of the clock at the moment someone asks. That
 * keeps the registry a plain data structure with no background task to leak.
 *
 * <p>Safe for concurrent use: announcements arrive on mesh subscription threads while the console
 * reads.
 */
public final class PeerRegistry {

    private final String ownClusterId;
    private final Duration peerTimeToLive;
    private final Clock clock;

    private final Map<String, Peer> peersByClusterId = new ConcurrentHashMap<>();

    /**
     * Creates a registry for one cluster.
     *
     * @param ownClusterId   this cluster's id, so its own announcements are not recorded as a peer.
     * @param peerTimeToLive how long a peer may stay silent before it counts as unreachable.
     * @param clock          the clock used for receive times, injected so expiry is testable.
     */
    public PeerRegistry(String ownClusterId, Duration peerTimeToLive, Clock clock) {
        this.ownClusterId = ownClusterId;
        this.peerTimeToLive = peerTimeToLive;
        this.clock = clock;
    }

    /**
     * Records an announcement heard on the mesh, adding the peer or refreshing it in place. An
     * announcement from this cluster itself is ignored - a cluster hears its own multicast, but it is
     * not its own peer. Repeat announcements are the normal case (every heartbeat), so this is
     * naturally idempotent: the latest one simply wins.
     *
     * @param announcement the announcement just received.
     */
    public void record(ClusterAnnouncement announcement) {
        if (ownClusterId.equals(announcement.clusterId())) {
            return;
        }
        peersByClusterId.put(
                announcement.clusterId(),
                new Peer(
                        announcement.clusterId(),
                        announcement.region(),
                        announcement.baselineVersion(),
                        announcement.health(),
                        announcement.consoleUrl(),
                        announcement.apiBaseUrl(),
                        clock.instant()));
    }

    /**
     * The peers known to this cluster, each with its reachability resolved against the clock right
     * now. Unreachable peers are included, carrying their last-known detail.
     *
     * @return every known peer; empty if none has announced yet.
     */
    public List<Peer> peers() {
        var now = clock.instant();
        return peersByClusterId.values().stream()
                .map(peer -> peer.withReachabilityAt(now, peerTimeToLive))
                .toList();
    }

    /**
     * A discovered peer: its identity and health as last announced, where to reach it, and when it was
     * last heard from.
     *
     * @param clusterId       the peer's stable cluster id.
     * @param region          the peer's region label.
     * @param baselineVersion the baseline the peer last reported running.
     * @param health          the peer's last reported health label.
     * @param consoleUrl      the peer's own status-console root, the target of a federation redirect.
     * @param apiBaseUrl      the peer's REST API base, which the unified view reads live.
     * @param lastSeen        when this cluster last heard the peer announce, by this cluster's clock.
     * @param reachability    whether the peer is still announcing, resolved when the registry is read.
     */
    public record Peer(
            String clusterId,
            String region,
            String baselineVersion,
            String health,
            String consoleUrl,
            String apiBaseUrl,
            Instant lastSeen,
            Reachability reachability) {

        /** Creates a freshly heard peer; its reachability is resolved on read, so it starts reachable. */
        Peer(
                String clusterId,
                String region,
                String baselineVersion,
                String health,
                String consoleUrl,
                String apiBaseUrl,
                Instant lastSeen) {
            this(clusterId, region, baselineVersion, health, consoleUrl, apiBaseUrl, lastSeen, Reachability.REACHABLE);
        }

        /**
         * Returns this peer with its reachability resolved against the given moment: silent for longer
         * than the time-to-live means unreachable. The boundary itself still counts as reachable.
         */
        Peer withReachabilityAt(Instant now, Duration timeToLive) {
            var silentFor = Duration.between(lastSeen, now);
            var resolved = silentFor.compareTo(timeToLive) > 0 ? Reachability.UNREACHABLE : Reachability.REACHABLE;
            return new Peer(clusterId, region, baselineVersion, health, consoleUrl, apiBaseUrl, lastSeen, resolved);
        }
    }
}
