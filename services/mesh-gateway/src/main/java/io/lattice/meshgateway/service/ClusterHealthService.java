package io.lattice.meshgateway.service;

import io.lattice.contract.mesh.ServiceHealth;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes this cluster's health by polling the readiness of the services it is configured to watch.
 *
 * <p><b>Why this exists at all.</b> The gateway's own readiness can never fail - it has no datastore,
 * and a broker outage deliberately does not fail it - so announcing that would put a constant,
 * meaningless {@code ready} on the mesh while a visibly broken baseline rendered as healthy in every
 * peer's unified view. Health is only worth announcing if it describes the cluster's services.
 *
 * <p><b>What counts as up.</b> A service is UP only if its readiness probe answers HTTP 200. A
 * non-200, a timeout, and a refused connection are all DOWN: "reachable but broken" and "not there
 * at all" are equally not-up as far as a peer deciding whether to send an operator here is concerned.
 *
 * <p><b>Never blocks the announce.</b> Services are polled in parallel with a timeout well inside the
 * heartbeat interval, so one hanging service cannot delay or suppress this cluster's announcement -
 * going silent would make peers believe the whole baseline had vanished.
 */
public final class ClusterHealthService {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterHealthService.class);

    /** Comfortably inside the default 10s heartbeat, so a hung service never delays an announce. */
    private static final int POLL_TIMEOUT_MILLIS = 2000;

    private static final String READINESS_PATH = "/readiness";

    private final Map<String, String> services;
    private final WebClient client;

    /**
     * Creates the health service over the watched services.
     *
     * @param vertx    the Vert.x instance owning the HTTP client.
     * @param services service name to base URL, as configured; may be empty.
     */
    public ClusterHealthService(Vertx vertx, Map<String, String> services) {
        // LinkedHashMap, not Map.copyOf: the latter is unordered AND randomizes its iteration seed per
        // JVM start, which would scramble the per-service breakdown between runs. Configured order is
        // part of the contract the console renders.
        this.services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        this.client = WebClient.create(
                vertx,
                new WebClientOptions().setConnectTimeout(POLL_TIMEOUT_MILLIS).setIdleTimeout(POLL_TIMEOUT_MILLIS));
        if (this.services.isEmpty()) {
            LOG.warn("no services configured to watch (CLUSTER_SERVICES is empty) - this cluster will always"
                    + " announce itself as ready, which cannot reflect its services");
        }
    }

    /**
     * Polls every watched service's readiness and rolls the results up.
     *
     * @return a future of this cluster's health and the per-service breakdown behind it. Never fails:
     *     an unreachable service is a DOWN result, not an error.
     */
    public Future<ClusterHealth> poll() {
        if (services.isEmpty()) {
            // Nothing is watched, so nothing can contradict readiness. The warning above is where the
            // misconfiguration surfaces; failing here would stop the cluster announcing at all.
            return Future.succeededFuture(new ClusterHealth("ready", List.of()));
        }
        var probes = services.entrySet().stream()
                .map(entry -> probe(entry.getKey(), entry.getValue()))
                .toList();
        return Future.all(probes).map(composite -> {
            List<ServiceHealth> results = composite.list();
            var up = results.stream()
                    .filter(service -> "UP".equals(service.status()))
                    .count();
            String rollup;
            if (up == results.size()) {
                rollup = "ready";
            } else if (up > 0) {
                rollup = "degraded";
            } else {
                rollup = "down";
            }
            return new ClusterHealth(rollup, results);
        });
    }

    /** Probes one service, mapping any failure to DOWN rather than propagating it. */
    private Future<ServiceHealth> probe(String name, String baseUrl) {
        return client.getAbs(baseUrl + READINESS_PATH)
                .timeout(POLL_TIMEOUT_MILLIS)
                .send()
                .map(response -> new ServiceHealth(name, response.statusCode() == 200 ? "UP" : "DOWN"))
                .recover(err -> {
                    // Unreachable is an ordinary, expected state for this poll, not an error worth
                    // logging at WARN on every heartbeat - it is reported through the rollup instead.
                    LOG.debug("service {} unreachable during health poll: {}", name, String.valueOf(err));
                    return Future.succeededFuture(new ServiceHealth(name, "DOWN"));
                });
    }

    /** Releases the polling client. */
    public void close() {
        client.close();
    }

    /**
     * This cluster's rolled-up health and the per-service readiness behind it.
     *
     * @param health   {@code ready} (all up), {@code degraded} (some up), or {@code down} (none up).
     * @param services the per-service breakdown, in configured order; empty when nothing is watched.
     */
    public record ClusterHealth(String health, List<ServiceHealth> services) {

        /** Defensive copy: the list is exposed on a record accessor. */
        public ClusterHealth {
            services = List.copyOf(services);
        }
    }
}
