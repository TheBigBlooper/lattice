package io.lattice.meshgateway.service;

import io.lattice.common.mesh.MeshClient;
import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.meshgateway.MeshGatewayConfig;
import io.lattice.meshgateway.service.ClusterHealthService.ClusterHealth;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes this cluster's presence to the mesh: once at startup, then on every heartbeat.
 *
 * <p>The heartbeat <b>is</b> the liveness signal - there is no separate ping - so the single most
 * important property here is that it never stops. A failed health poll and a failed publish are both
 * absorbed rather than propagated: going silent would make every peer conclude this baseline had
 * vanished, which is far worse than announcing a stale or pessimistic health label.
 *
 * <p><b>Propagation of a health change.</b> Each tick re-polls health and announces, so a change is on
 * the mesh within one heartbeat interval rather than needing its own trigger. A transition is logged at
 * INFO while the routine ticks stay at DEBUG, so the log shows the moments that mattered instead of a
 * line every ten seconds.
 */
public final class AnnouncerService {

    private static final Logger LOG = LoggerFactory.getLogger(AnnouncerService.class);

    /** Assumed before the first poll completes: optimistic, and corrected within one tick. */
    private static final ClusterHealth UNKNOWN_YET = new ClusterHealth("ready", List.of());

    private final MeshGatewayConfig config;
    private final MeshClient mesh;
    private final Supplier<Future<ClusterHealth>> healthPoll;

    private volatile ClusterHealth lastRollup = UNKNOWN_YET;
    // volatile: a 64-bit write is not otherwise guaranteed atomic, and start/stop can run on
    // different threads (deployment vs shutdown).
    private volatile long timerId = -1;

    /**
     * Creates the announcer.
     *
     * @param config     this cluster's identity and cadence.
     * @param mesh       the mesh client announcements are published through.
     * @param healthPoll polls this cluster's health; supplied rather than injected as the service so
     *                   the announce logic can be tested without an HTTP fan-out.
     */
    public AnnouncerService(MeshGatewayConfig config, MeshClient mesh, Supplier<Future<ClusterHealth>> healthPoll) {
        this.config = config;
        this.mesh = mesh;
        this.healthPoll = healthPoll;
    }

    /**
     * Announces once: polls health, publishes this cluster's announcement, and remembers the rollup.
     *
     * @return a future that always succeeds - a poll or publish failure is reported and absorbed, since
     *     a broken tick must not stop the heartbeat.
     */
    public Future<Void> announceOnce() {
        return healthPoll
                .get()
                .recover(err -> {
                    // Announce anyway with whatever was last known: silence reads as "baseline gone".
                    LOG.warn("health poll failed, announcing last known health: {}", String.valueOf(err));
                    return Future.succeededFuture(lastRollup);
                })
                .compose(rollup -> {
                    if (!rollup.health().equals(lastRollup.health())) {
                        LOG.info("cluster health {} -> {}", lastRollup.health(), rollup.health());
                    }
                    lastRollup = rollup;
                    return mesh.announce(announcementOf(rollup));
                })
                .recover(err -> {
                    LOG.warn("announce failed, will retry on the next heartbeat: {}", String.valueOf(err));
                    return Future.succeededFuture();
                })
                .mapEmpty();
    }

    /**
     * Announces immediately, then on the configured heartbeat interval.
     *
     * @param vertx the Vert.x instance owning the periodic timer.
     */
    public void start(Vertx vertx) {
        announceOnce();
        timerId = vertx.setPeriodic(config.heartbeat().toMillis(), tick -> announceOnce());
        LOG.info(
                "announcing cluster={} every {}s on {}",
                config.clusterId(),
                config.heartbeat().toSeconds(),
                MeshClient.ANNOUNCE_ADDRESS);
    }

    /**
     * Stops the heartbeat.
     *
     * @param vertx the Vert.x instance owning the timer.
     */
    public void stop(Vertx vertx) {
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    /**
     * The health rollup last polled, so the baseline endpoint can serve it without a fresh poll of its
     * own - keeping that endpoint cheap and never blocking it on a slow service.
     *
     * @return the last polled rollup, or an optimistic default before the first poll completes.
     */
    public ClusterHealth lastRollup() {
        return lastRollup;
    }

    private ClusterAnnouncement announcementOf(ClusterHealth rollup) {
        return new ClusterAnnouncement(
                config.clusterId(),
                config.region(),
                config.baselineVersion(),
                rollup.health(),
                config.consoleUrl(),
                config.apiBaseUrl());
    }
}
