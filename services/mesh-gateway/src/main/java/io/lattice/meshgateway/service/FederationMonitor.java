package io.lattice.meshgateway.service;

import io.lattice.common.mesh.BrokerCertificate;
import io.lattice.common.metrics.LatticeMetrics;
import io.lattice.contract.mesh.FederationState;
import io.vertx.core.Future;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Holds this baseline's current view of its federation links, refreshed on the health poll.
 *
 * <p><b>Two readings, one conclusion.</b> The links come from the broker and say <em>whether</em>
 * each is carrying; the baseline's own certificate says whether the fault could be local. Neither
 * alone is enough: a dead link is symmetric and tells you nothing about whose problem it is, and an
 * expired certificate with everything still carrying is a warning rather than an outage.
 *
 * <p><b>It rides the existing poll</b> rather than keeping its own cadence, so there is one clock and
 * one place a reading can be stale. Federation changes far less often than readiness does, which
 * makes a second timer cost more in moving parts than it buys in freshness.
 *
 * <p>Detail: {@code docs/design/features/federation_link_visibility.md}, locked #80.
 */
public final class FederationMonitor {

    private final Supplier<Future<Map<String, Boolean>>> readLinks;
    private final Supplier<Future<BrokerCertificate>> readCertificate;

    private final AtomicReference<Map<String, Boolean>> links = new AtomicReference<>(Map.of());
    private final AtomicReference<BrokerCertificate> certificate = new AtomicReference<>();

    /**
     * Creates the monitor over the two reads it refreshes from.
     *
     * @param readLinks       reads each peer's link state from this baseline's own broker.
     * @param readCertificate reads the certificate that broker presents.
     */
    public FederationMonitor(
            Supplier<Future<Map<String, Boolean>>> readLinks, Supplier<Future<BrokerCertificate>> readCertificate) {
        this.readLinks = readLinks;
        this.readCertificate = readCertificate;
    }

    /**
     * Refreshes both readings, and never fails.
     *
     * <p>A failed read leaves the previous value rather than blanking it, for the same reason the
     * registry retains an unreachable peer: the last thing known is more useful than nothing, and
     * this runs on the poll that must never be delayed or failed by a slow dependency.
     *
     * @return a future that completes once both reads have settled, successfully or not.
     */
    public Future<Void> refresh() {
        var linksRead = readLinks.get().onSuccess(links::set).otherwiseEmpty();
        var certificateRead = readCertificate.get().onSuccess(certificate::set).otherwiseEmpty();

        return Future.join(linksRead, certificateRead).map(joined -> {
            publishMeters();
            return null;
        });
    }

    /** The link states, sharpened by the certificate, keyed by peer cluster id. */
    public Map<String, FederationState> states() {
        return statesFrom(links.get(), certificate.get());
    }

    /** This baseline's own broker certificate as last read, or null when it could not be read. */
    public BrokerCertificate certificate() {
        return certificate.get();
    }

    /** Whether each peer's link is carrying, as last read. */
    public Map<String, Boolean> links() {
        return links.get();
    }

    /**
     * Combines link readings with the baseline's own certificate into a state per peer.
     *
     * <p>A carrying link is {@code UP} whatever the certificate says - an expiring certificate that
     * has not yet been refused is a warning, and the broker row carries it. A dead link is
     * {@code REFUSED} only when the certificate has actually expired, which is the single case a
     * baseline can establish about itself; otherwise it is {@code DOWN} and means exactly that.
     *
     * @param links       whether each peer's link is carrying.
     * @param certificate this baseline's own certificate, or null when it could not be read.
     * @return the state per peer.
     */
    static Map<String, FederationState> statesFrom(Map<String, Boolean> links, BrokerCertificate certificate) {
        var ours = certificate != null && certificate.expired();
        var states = new HashMap<String, FederationState>();
        links.forEach((peer, carrying) -> {
            if (Boolean.TRUE.equals(carrying)) {
                states.put(peer, FederationState.UP);
            } else {
                states.put(peer, ours ? FederationState.REFUSED : FederationState.DOWN);
            }
        });
        return states;
    }

    /** Publishes one gauge per peer, so a link that is quietly down is graphable rather than only visible. */
    private void publishMeters() {
        links.get()
                .forEach((peer, carrying) -> LatticeMetrics.gauge(
                        LatticeMetrics.MESH_FEDERATION_UP,
                        this,
                        monitor -> Boolean.TRUE.equals(monitor.links().get(peer)) ? 1 : 0,
                        "peer",
                        peer));
    }
}
