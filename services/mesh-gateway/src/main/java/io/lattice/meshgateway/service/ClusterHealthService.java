package io.lattice.meshgateway.service;

import io.lattice.common.mesh.BrokerCertificate;
import io.lattice.common.metrics.LatticeMetrics;
import io.lattice.contract.mesh.ComponentHealth;
import io.lattice.contract.mesh.ComponentStatus;
import io.lattice.contract.mesh.MeshLinkState;
import io.lattice.contract.mesh.ServiceHealth;
import io.lattice.meshgateway.InfrastructureTarget;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes this cluster's health by polling, in one parallel fan-out, the readiness of the services
 * it is configured to watch and the state of the infrastructure those services depend on.
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
 * <p><b>Two surfaces, one poll.</b> Only the services rollup rides the mesh. Two things computed here
 * are served on this baseline's own endpoint and never announced, and both are deliberately kept out
 * of the verdict:
 *
 * <ul>
 *   <li><b>The gateway's own row.</b> It lists itself so an operator sees every Vert.x service running
 *       here, but it is never counted: its readiness cannot fail, so counting it would put a floor
 *       under the verdict that no outage could get past - a baseline with every service dead would
 *       announce {@code degraded} rather than {@code down}. That floor is what locked #42 keeps the
 *       gateway off the wire to avoid.
 *   <li><b>Infrastructure.</b> Locked #43 stands unamended, so a datastore that has merely lost a
 *       replica cannot make a peer believe this baseline is unable to serve (locked #66).
 * </ul>
 *
 * <p><b>Never blocks the announce.</b> Everything is polled in parallel with a timeout well inside the
 * heartbeat interval, so one hanging target cannot delay or suppress this cluster's announcement -
 * going silent would make peers believe the whole baseline had vanished. Every probe maps its own
 * failure to a state, so no dead component can fail the poll.
 */
public final class ClusterHealthService {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterHealthService.class);

    /** Comfortably inside the default 10s heartbeat, so a hung service never delays an announce. */
    private static final int POLL_TIMEOUT_MILLIS = 2000;

    private static final String READINESS_PATH = "/readiness";

    /** Keycloak serves its health probes on the management port, not the port it issues tokens on. */
    private static final String KEYCLOAK_READY_PATH = "/health/ready";

    /**
     * The only source that distinguishes green, yellow and red. The per-service readiness check is a
     * client ping, so it answers even on a red cluster and reports data loss as healthy.
     */
    private static final String CLUSTER_HEALTH_PATH = "/_cluster/health";

    /** The name the gateway lists itself under in its own per-service breakdown. */
    private static final String SELF_NAME = "mesh-gateway";

    /** Matches the contract's medium-string cap, so a long failure message cannot break the shape. */
    private static final int MAX_DETAIL_LENGTH = 512;

    /**
     * How long before expiry the broker row starts warning.
     *
     * <p>Thirty days against an 825-day leaf: long enough that a re-issue is scheduled work rather
     * than an incident, short enough that the warning still means something when it appears. Wrong
     * first guesses here are a values change, not a rebuild.
     */
    private static final Duration EXPIRY_WARNING = Duration.ofDays(30);

    private final Map<String, String> services;
    private final List<InfrastructureTarget> infrastructure;
    private final Supplier<MeshLinkState> meshLink;
    private final Supplier<Map<String, Boolean>> federation;
    private final Supplier<BrokerCertificate> certificate;
    private final WebClient client;

    /**
     * Creates the health service over the watched services and the infrastructure behind them.
     *
     * @param vertx          the Vert.x instance owning the HTTP client.
     * @param services       service name to base URL, as configured; may be empty.
     * @param infrastructure the infrastructure components to report on, in configured order; may be
     *                       empty, which is a supported deployment rather than a fault.
     * @param meshLink       supplies the current mesh-link state, which the Artemis row renders rather
     *                       than probing the broker a second time (locked #46).
     */
    public ClusterHealthService(
            Vertx vertx,
            Map<String, String> services,
            List<InfrastructureTarget> infrastructure,
            Supplier<MeshLinkState> meshLink) {
        this(vertx, services, infrastructure, meshLink, Map::of, () -> null);
    }

    /**
     * Creates the health service with the federation readings the Artemis row derives from.
     *
     * @param vertx          the Vert.x instance owning the HTTP client.
     * @param services       service name to base URL, as configured; may be empty.
     * @param infrastructure the infrastructure components to report on, in configured order.
     * @param meshLink       supplies the current mesh-link state (locked #46).
     * @param federation     supplies each peer's federation link state, empty when unreadable.
     * @param certificate    supplies this baseline's own broker certificate, null when unreadable.
     */
    public ClusterHealthService(
            Vertx vertx,
            Map<String, String> services,
            List<InfrastructureTarget> infrastructure,
            Supplier<MeshLinkState> meshLink,
            Supplier<Map<String, Boolean>> federation,
            Supplier<BrokerCertificate> certificate) {
        this.infrastructure = List.copyOf(infrastructure);
        this.meshLink = meshLink;
        this.federation = federation;
        this.certificate = certificate;
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
        if (this.infrastructure.isEmpty()) {
            // Loud, but never fatal: a deliberately minimal deployment still has to run. The console
            // renders no infrastructure card rather than a card full of unknowns.
            LOG.warn("no infrastructure configured (CLUSTER_INFRASTRUCTURE is empty) - this cluster will"
                    + " report nothing about its datastore, broker or identity provider");
        }
    }

    /**
     * Polls every watched service's readiness and every configured component's state, in one parallel
     * fan-out, and rolls the service results up.
     *
     * @return a future of this cluster's verdict, the per-service breakdown, and the infrastructure
     *     breakdown. Never fails: an unreachable target is a DOWN result, not an error.
     */
    public Future<ClusterHealth> poll() {
        var watched = pollServices();
        var components = pollInfrastructure();
        return Future.all(watched, components).map(composite -> {
            var rollup = rollupOf(watched.result());
            report(rollup, watched.result());
            return new ClusterHealth(rollup, breakdownOf(watched.result()), components.result());
        });
    }

    /**
     * Records the rollup as a state set and counts each service's poll outcome.
     *
     * <p>A state set rather than a number encoding a state, so a reader does not need to know that 2
     * means degraded. The poll outcome reuses the rollup's own definition of up, rather than deciding
     * again here and risking two answers to one question.
     */
    private void report(String rollup, List<ServiceHealth> watched) {
        LatticeMetrics.baselineHealth(rollup);
        watched.forEach(service -> LatticeMetrics.count(
                LatticeMetrics.SERVICE_READINESS_POLLS,
                "service",
                service.name(),
                "outcome",
                "UP".equals(service.status()) ? "up" : "not_up"));
    }

    /**
     * Polls the configured services, skipping any entry naming the gateway itself: it reports its own
     * row below, so a configured entry for it would be both a duplicate row and an extra vote in the
     * verdict.
     */
    private Future<List<ServiceHealth>> pollServices() {
        var probes = services.entrySet().stream()
                .filter(entry -> !SELF_NAME.equals(entry.getKey()))
                .map(entry -> probe(entry.getKey(), entry.getValue()))
                .toList();
        return allOf(probes);
    }

    /**
     * The verdict that rides the mesh, computed from the watched services alone. With nothing watched
     * there is nothing that can contradict readiness; the constructor warning is where that
     * misconfiguration surfaces, since failing here would stop the cluster announcing at all.
     */
    private static String rollupOf(List<ServiceHealth> watched) {
        if (watched.isEmpty()) {
            return "ready";
        }
        var up = watched.stream()
                .filter(service -> "UP".equals(service.status()))
                .count();
        if (up == watched.size()) {
            return "ready";
        }
        return up > 0 ? "degraded" : "down";
    }

    /**
     * The breakdown served locally: the watched services, then the gateway itself. It is UP with no
     * probe because it answered this poll - anything else could not have produced this list.
     */
    private static List<ServiceHealth> breakdownOf(List<ServiceHealth> watched) {
        var breakdown = new ArrayList<>(watched);
        breakdown.add(new ServiceHealth(SELF_NAME, "UP"));
        return List.copyOf(breakdown);
    }

    /** Reads every configured component's state, each mapping its own failure rather than propagating it. */
    private Future<List<ComponentHealth>> pollInfrastructure() {
        return allOf(infrastructure.stream().map(this::readComponent).toList());
    }

    /**
     * Combines a fan-out into one future of its results, short-circuiting an empty one rather than
     * relying on how a composite of nothing behaves.
     */
    private static <T> Future<List<T>> allOf(List<Future<T>> probes) {
        if (probes.isEmpty()) {
            return Future.succeededFuture(List.of());
        }
        return Future.all(probes).map(composite -> composite.list());
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

    /** Reads one component's state, choosing the probe by its configured kind. */
    private Future<ComponentHealth> readComponent(InfrastructureTarget target) {
        return switch (target.kind()) {
            case ARTEMIS -> Future.succeededFuture(artemisState(target));
            case KEYCLOAK -> probeKeycloak(target);
            case ELASTICSEARCH -> probeElasticsearch(target);
        };
    }

    /**
     * Renders the broker row from the mesh-link state the gateway already holds (locked #46), then
     * from what its federation links and its own certificate say (locked #80).
     *
     * <p><b>A down broker outranks everything below it.</b> Two conditions compete for one status and
     * the more severe wins: reporting DEGRADED because the links are readable-as-broken would
     * understate an outright outage, and naming the links would be noise, since of course nothing
     * federates when the broker is gone.
     *
     * <p>Below that, the row is DEGRADED when the baseline is serving perfectly well and still cannot
     * reach somebody - an expired certificate, one approaching expiry, or a link that is not carrying.
     * The certificate cases come first because they are the ones the operator can act on alone.
     */
    private ComponentHealth artemisState(InfrastructureTarget target) {
        if (meshLink.get() != MeshLinkState.UP) {
            return component(target, ComponentStatus.DOWN, null);
        }

        var own = certificate.get();
        if (own != null && own.expired()) {
            return component(target, ComponentStatus.DEGRADED, "this baseline's certificate has expired");
        }
        if (own != null && own.remaining().compareTo(EXPIRY_WARNING) < 0) {
            return component(
                    target,
                    ComponentStatus.DEGRADED,
                    "this baseline's certificate expires in %d days"
                            .formatted(own.remaining().toDays()));
        }

        var silent = federation.get().entrySet().stream()
                .filter(link -> !link.getValue())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!silent.isEmpty()) {
            return component(
                    target, ComponentStatus.DEGRADED, "federation not carrying to " + String.join(", ", silent));
        }
        return component(target, ComponentStatus.UP, null);
    }

    /**
     * Probes Keycloak's management port, whose body is already the operational probe shape this
     * project defines, so no vocabulary translation is needed. An answer that is not a clean UP is
     * DEGRADED rather than DOWN: it answered, so it is reachable, and it is naming its own complaint.
     */
    private Future<ComponentHealth> probeKeycloak(InfrastructureTarget target) {
        return client.getAbs(target.url() + KEYCLOAK_READY_PATH)
                .timeout(POLL_TIMEOUT_MILLIS)
                .send()
                .map(response -> {
                    // A named failing check is always the better detail line, whichever way Keycloak
                    // reports the failure - the identity database has no other route to the console.
                    var failing = failingChecks(response);
                    if (response.statusCode() != 200) {
                        return component(
                                target,
                                ComponentStatus.DEGRADED,
                                failing != null ? failing : "management endpoint returned " + response.statusCode());
                    }
                    var reported = reportedStatus(response);
                    if ("UP".equals(reported)) {
                        return component(target, ComponentStatus.UP, null);
                    }
                    return component(
                            target,
                            ComponentStatus.DEGRADED,
                            failing != null ? failing : "management endpoint reported " + reported);
                })
                .recover(err -> Future.succeededFuture(unreachable(target, err)));
    }

    /**
     * Probes Elasticsearch's own cluster health, which is the only source that separates green,
     * yellow and red. Yellow is DEGRADED rather than DOWN because a yellow cluster serves reads and
     * writes normally; what it has lost is redundancy, which is worth telling an operator about
     * without claiming the datastore is unavailable.
     */
    private Future<ComponentHealth> probeElasticsearch(InfrastructureTarget target) {
        return client.getAbs(target.url() + CLUSTER_HEALTH_PATH)
                .timeout(POLL_TIMEOUT_MILLIS)
                .send()
                .map(response -> {
                    if (response.statusCode() != 200) {
                        return component(
                                target, ComponentStatus.DOWN, "cluster health returned " + response.statusCode());
                    }
                    var reported = reportedStatus(response);
                    return switch (String.valueOf(reported)) {
                        case "green" -> component(target, ComponentStatus.UP, null);
                        case "yellow" -> component(target, ComponentStatus.DEGRADED, "cluster status yellow");
                        case "red" -> component(target, ComponentStatus.DOWN, "cluster status red");
                        default -> component(target, ComponentStatus.DOWN, "cluster health reported " + reported);
                    };
                })
                .recover(err -> Future.succeededFuture(unreachable(target, err)));
    }

    /**
     * Reads the {@code status} field from a probe response, tolerating a body that is not the JSON
     * object it should be - a component answering 200 with something unparseable is a state to report,
     * not an exception to throw out of the poll.
     */
    /**
     * Names every sub-check a health body reports as not UP, or {@code null} when it names none.
     *
     * <p>This is how the identity database reaches the console. It is deliberately not probed as its
     * own component - a second check would duplicate a signal Keycloak already publishes, the same
     * reasoning that leaves Artemis without a URL - so the check Keycloak reports about it is the one
     * place its state exists.
     *
     * <p>Whatever reports not-UP is surfaced under the name its own system gives it, rather than
     * matching a known check name. Matching a literal would couple this to a third-party label, and a
     * rename on an upgrade would silently stop matching and report nothing - a regression that reads
     * as health, which is the failure mode this method exists to remove.
     *
     * @param response a health response whose body may carry a {@code checks} array.
     * @return a comma-separated list of the failing checks' names, or {@code null} if none are named.
     */
    private static String failingChecks(HttpResponse<Buffer> response) {
        try {
            var body = response.bodyAsJsonObject();
            if (body == null) {
                return null;
            }
            var checks = body.getJsonArray("checks");
            if (checks == null) {
                return null;
            }
            var failing = new ArrayList<String>();
            for (var entry : checks) {
                if (entry instanceof JsonObject check && !"UP".equals(check.getString("status"))) {
                    var name = check.getString("name");
                    if (name != null && !name.isBlank()) {
                        failing.add(name);
                    }
                }
            }
            return failing.isEmpty() ? null : String.join(", ", failing);
        } catch (DecodeException unreadable) {
            return null;
        }
    }

    private static String reportedStatus(HttpResponse<Buffer> response) {
        try {
            var body = response.bodyAsJsonObject();
            return body == null ? null : body.getString("status");
        } catch (DecodeException unreadable) {
            return null;
        }
    }

    /**
     * A component that could not be reached at all. Unreachable is an ordinary, expected state for
     * this poll rather than an error worth a WARN on every heartbeat, so the failure is logged at
     * DEBUG and reported on the row instead.
     */
    private ComponentHealth unreachable(InfrastructureTarget target, Throwable err) {
        LOG.debug("component {} unreachable during health poll: {}", target.name(), String.valueOf(err));
        return component(target, ComponentStatus.DOWN, "unreachable: " + err);
    }

    /** Builds one component result, capping the detail line at the length the contract allows. */
    private static ComponentHealth component(InfrastructureTarget target, ComponentStatus status, String detail) {
        String capped = null;
        if (detail != null) {
            capped = detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH);
        }
        return new ComponentHealth(target.name(), target.kind(), status, capped);
    }

    /** Releases the polling client. */
    public void close() {
        client.close();
    }

    /**
     * This cluster's rolled-up health and the per-service readiness behind it.
     *
     * @param health         {@code ready} (all up), {@code degraded} (some up), or {@code down}.
     * @param services       the per-service breakdown, in configured order.
     * @param infrastructure the per-component breakdown, in configured order; served locally only.
     */
    public record ClusterHealth(String health, List<ServiceHealth> services, List<ComponentHealth> infrastructure) {

        /** Defensive copies: both lists are exposed on record accessors. */
        public ClusterHealth {
            services = List.copyOf(services);
            infrastructure = List.copyOf(infrastructure);
        }

        /**
         * A rollup with no infrastructure, for the callers that never read it - the announcer, which
         * publishes the verdict alone, and the tests that exercise it.
         *
         * @param health   the rolled-up verdict.
         * @param services the per-service breakdown.
         */
        public ClusterHealth(String health, List<ServiceHealth> services) {
            this(health, services, List.of());
        }
    }
}
