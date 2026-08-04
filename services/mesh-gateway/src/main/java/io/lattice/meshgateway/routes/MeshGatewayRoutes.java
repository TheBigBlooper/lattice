package io.lattice.meshgateway.routes;

import io.lattice.common.mesh.PeerRegistry;
import io.lattice.common.rest.Envelopes;
import io.lattice.contract.mesh.Baseline;
import io.lattice.contract.mesh.FederationState;
import io.lattice.contract.mesh.MeshLinkState;
import io.lattice.contract.mesh.Peer;
import io.lattice.meshgateway.MeshGatewayConfig;
import io.lattice.meshgateway.service.AnnouncerService;
import io.lattice.meshgateway.service.ClusterHealthService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The thin mesh-gateway route handlers: they read already-computed state and shape the
 * {@code {data|error, meta}} envelope. Neither endpoint does any work of its own - the registry is
 * populated by mesh subscriptions and the health rollup by the announce heartbeat - so both stay cheap
 * and neither can block on the broker or on a slow service.
 */
public final class MeshGatewayRoutes {

    private static final String JSON = "application/json";

    private final MeshGatewayConfig config;
    private final PeerRegistry peerRegistry;
    private final AnnouncerService announcer;
    private final Supplier<Map<String, FederationState>> federation;

    /**
     * Creates the handlers over the state they serve. All three are shared, injected collaborators
     * (the standard dependency-injection pattern), held by reference not copied.
     *
     * @param config       this cluster's identity.
     * @param peerRegistry the registry populated from peers' announcements.
     * @param announcer    the announcer holding the last polled health rollup.
     */
    public MeshGatewayRoutes(MeshGatewayConfig config, PeerRegistry peerRegistry, AnnouncerService announcer) {
        this(config, peerRegistry, announcer, Map::of);
    }

    /**
     * Creates the handlers with the federation link states each peer row carries.
     *
     * @param config       this cluster's identity.
     * @param peerRegistry the registry populated from peers' announcements.
     * @param announcer    the announcer holding the last polled health rollup.
     * @param federation   supplies each peer's link state, empty when none has been read.
     */
    public MeshGatewayRoutes(
            MeshGatewayConfig config,
            PeerRegistry peerRegistry,
            AnnouncerService announcer,
            Supplier<Map<String, FederationState>> federation) {
        this.config = config;
        this.peerRegistry = peerRegistry;
        this.announcer = announcer;
        this.federation = federation;
    }

    /**
     * Handles {@code GET /api/v1/peers}: every baseline this cluster has heard announce itself, with
     * the endpoints Shape A federation needs. An unreachable peer is included with its last-known
     * detail, so an operator sees a baseline went quiet rather than a row disappearing.
     *
     * @param ctx the routing context.
     */
    public void getPeers(RoutingContext ctx) {
        ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", JSON)
                .end(peersPayload(peerRegistry, federation.get()).encode());
    }

    /**
     * Builds the peer-list response body. Kept as a pure function of the registry so the wire shape is
     * unit-testable without standing up a router.
     *
     * @param registry the peer registry to render.
     * @return the success envelope carrying every known peer.
     */
    static JsonObject peersPayload(PeerRegistry registry) {
        return peersPayload(registry, Map.of());
    }

    /**
     * As above, attaching each peer's federation link state where one has been read.
     *
     * <p>A peer with no reading carries none: absent means "not measured" rather than "healthy",
     * so a console shows nothing instead of an all-clear the baseline has not earned (locked #80).
     *
     * @param registry the peer registry to render.
     * @param federation the link state per peer cluster id, possibly empty.
     * @return the success envelope carrying every known peer.
     */
    static JsonObject peersPayload(PeerRegistry registry, Map<String, FederationState> federation) {
        var peers = new JsonArray();
        registry.peers().stream()
                .map(MeshGatewayRoutes::toContract)
                .map(peer -> {
                    var state = federation.get(peer.clusterId());
                    return state == null ? peer : peer.withFederation(state);
                })
                .forEach(peer -> peers.add(peer.toJson()));
        return new JsonObject()
                .put("data", peers)
                .put("meta", Envelopes.success(new JsonObject()).getJsonObject("meta"));
    }

    /**
     * Handles {@code GET /api/v1/baseline}: this cluster's identity plus its health rollup, the
     * per-service breakdown behind it, and the infrastructure those services depend on. Serves the
     * rollup last computed on the announce heartbeat, so the endpoint never triggers its own poll -
     * which is what keeps it answerable during the outage an operator is trying to look at.
     *
     * @param ctx the routing context.
     */
    public void getBaseline(RoutingContext ctx) {
        ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", JSON)
                .end(baselinePayload(config, announcer.lastRollup(), announcer.meshLinkState())
                        .encode());
    }

    /**
     * Builds the baseline response body from this cluster's identity and its last polled health. Kept
     * as a pure function so the wire shape is unit-testable without standing up a router, and so the
     * handler cannot accidentally trigger a fresh poll.
     *
     * @param config   this cluster's identity.
     * @param rollup   the health rollup last computed on the announce heartbeat.
     * @param meshLink whether this cluster can currently reach the mesh (locked #46).
     * @return the success envelope carrying this cluster's baseline.
     */
    static JsonObject baselinePayload(
            MeshGatewayConfig config, ClusterHealthService.ClusterHealth rollup, MeshLinkState meshLink) {
        var baseline = new Baseline(
                config.clusterId(),
                config.region(),
                config.baselineVersion(),
                List.of(Envelopes.API_VERSION),
                rollup.health(),
                rollup.services(),
                // Served here and never announced: locked #43 keeps the announced verdict a rollup of
                // this baseline's own services. Empty when nothing is configured, which is a supported
                // deployment rather than a fault.
                rollup.infrastructure(),
                meshLink);
        return new JsonObject()
                .put("data", baseline.toJson())
                .put("meta", Envelopes.success(new JsonObject()).getJsonObject("meta"));
    }

    /** Maps the registry's internal peer to its REST shape (the wire adds nothing the registry lacks). */
    private static Peer toContract(PeerRegistry.Peer peer) {
        return new Peer(
                peer.clusterId(),
                peer.region(),
                peer.baselineVersion(),
                peer.health(),
                peer.consoleUrl(),
                peer.apiBaseUrl(),
                peer.lastSeen().toString(),
                peer.reachability().name());
    }
}
