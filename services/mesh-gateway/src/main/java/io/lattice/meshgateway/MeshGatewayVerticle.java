package io.lattice.meshgateway;

import io.lattice.common.BaseVerticle;
import io.lattice.common.auth.ApiSecurity;
import io.lattice.common.config.LatticeConfig;
import io.lattice.common.mesh.AmqpMeshClient;
import io.lattice.common.mesh.MeshClient;
import io.lattice.common.mesh.PeerRegistry;
import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.meshgateway.routes.MeshGatewayRoutes;
import io.lattice.meshgateway.service.AnnouncerService;
import io.lattice.meshgateway.service.ClusterHealthService;
import io.vertx.core.Future;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.contract.OpenAPIContract;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mesh-gateway verticle: this cluster's sole participant in the Artemis mesh. It announces the
 * cluster, consumes peers' announcements into the {@link PeerRegistry}, and serves both the registry
 * and this cluster's identity over {@code /api/v1}.
 *
 * <p><b>Readiness deliberately ignores the broker.</b> Unlike orders and inventory - where a dead
 * Elasticsearch genuinely means the service cannot answer, so readiness goes DOWN - a mesh outage
 * still leaves this gateway able to serve its registry from last-known state, and peers age out to
 * {@code UNREACHABLE} on their own. Reporting DOWN would pull the pod from rotation and leave the
 * status console with nothing during exactly the incident an operator needs to watch.
 *
 * <p>Startup never blocks on the broker: a connection failure is reported and the service keeps
 * serving, so a broker that is slow to come up cannot stop the cluster from answering for itself. Nor
 * is that first failure terminal - the announce heartbeat re-attempts the connection on every tick, so
 * a gateway started before its broker joins the mesh on its own once the broker appears.
 */
public final class MeshGatewayVerticle extends BaseVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(MeshGatewayVerticle.class);

    private static final String SPEC = "openapi/v1.yaml";

    private final String brokerUrlOverride;
    private final MeshGatewayConfig configOverride;
    private final int portOverride;
    private final String realmUrlOverride;

    private MeshGatewayConfig config;
    private PeerRegistry peerRegistry;
    private MeshClient meshClient;
    private ClusterHealthService clusterHealth;
    private AnnouncerService announcer;
    private MeshGatewayRoutes routes;
    private OpenAPIContract contract;

    /** Creates the verticle using the shared config for the broker URL, HTTP port, and realm. */
    public MeshGatewayVerticle() {
        this((String) null, -1, null);
    }

    /**
     * Creates the verticle with test overrides for the broker URL, HTTP port, and identity realm.
     *
     * @param brokerUrlOverride the Artemis URL to use, or {@code null} to read it from config.
     * @param portOverride      the HTTP port to bind, or a negative value to read it from config
     *                          (0 binds an ephemeral port).
     * @param realmUrlOverride  the realm URL the API guard validates tokens against, or {@code null}
     *                          to read it from config.
     */
    MeshGatewayVerticle(String brokerUrlOverride, int portOverride, String realmUrlOverride) {
        this.brokerUrlOverride = brokerUrlOverride;
        this.configOverride = null;
        this.portOverride = portOverride;
        this.realmUrlOverride = realmUrlOverride;
    }

    /**
     * Creates the verticle with its whole configuration supplied, bypassing the environment. This is
     * what lets more than one cluster identity run in a single test process, which the environment
     * alone cannot express - two gateways in one JVM would otherwise be forced to share a
     * {@code CLUSTER_ID} and could never discover each other.
     *
     * @param configOverride   the complete configuration to use.
     * @param portOverride     the HTTP port to bind (0 binds an ephemeral port).
     * @param realmUrlOverride the realm URL the API guard validates tokens against.
     */
    MeshGatewayVerticle(MeshGatewayConfig configOverride, int portOverride, String realmUrlOverride) {
        this.brokerUrlOverride = null;
        this.configOverride = configOverride;
        this.portOverride = portOverride;
        this.realmUrlOverride = realmUrlOverride;
    }

    @Override
    protected String keycloakRealmUrl() {
        return realmUrlOverride != null ? realmUrlOverride : super.keycloakRealmUrl();
    }

    @Override
    public Future<?> start() {
        return LatticeConfig.load(vertx)
                .compose(loaded -> {
                    this.config = resolve(loaded);
                    this.peerRegistry =
                            new PeerRegistry(config.clusterId(), config.peerTimeToLive(), Clock.systemUTC());
                    this.clusterHealth = new ClusterHealthService(vertx, config.services());
                    connectToMesh();
                    // The announcer publishes through the mesh client from the first tick. While the
                    // broker is unreachable those publishes fail and are absorbed, so the heartbeat keeps
                    // ticking - and because each one re-attempts the connection, it is also what carries
                    // the gateway onto the mesh once the broker appears.
                    this.announcer = new AnnouncerService(config, meshClient, clusterHealth::poll);
                    this.routes = new MeshGatewayRoutes(config, peerRegistry, announcer);
                    return OpenAPIContract.from(vertx, SPEC);
                })
                .compose(loadedContract -> {
                    this.contract = loadedContract;
                    return super.start();
                })
                .onSuccess(started -> {
                    announcer.start(vertx);
                    LOG.info("mesh-gateway started cluster={} port={}", config.clusterId(), actualPort());
                });
    }

    /** Applies the test overrides on top of the loaded configuration. */
    private MeshGatewayConfig resolve(LatticeConfig loaded) {
        if (configOverride != null) {
            return configOverride;
        }
        var base = MeshGatewayConfig.from(loaded);
        return brokerUrlOverride == null
                ? base
                : new MeshGatewayConfig(
                        base.clusterId(),
                        base.region(),
                        base.baselineVersion(),
                        base.consoleUrl(),
                        base.apiBaseUrl(),
                        brokerUrlOverride,
                        base.brokerUser(),
                        base.brokerPassword(),
                        base.services(),
                        base.heartbeat(),
                        base.peerTimeToLive());
    }

    /**
     * Subscribes to the announce address, without blocking startup. A failure is an expected condition
     * (the broker may simply not be up yet), so it is reported at WARN and the service continues
     * serving; peers stay absent until the connection succeeds.
     *
     * <p>The client is created before it can connect and is held whatever happens, so a broker that is
     * not up yet costs only this one subscription attempt. The subscription is remembered by the client
     * regardless, and is restored by the reconnect the announce heartbeat drives - which is what lets a
     * gateway that started before its broker join the mesh with no restart.
     */
    private void connectToMesh() {
        this.meshClient = AmqpMeshClient.create(vertx, config.clusterId(), config.brokerOptions(), Clock.systemUTC());
        meshClient
                .subscribe(
                        MeshClient.ANNOUNCE_ADDRESS,
                        envelope -> peerRegistry.record(ClusterAnnouncement.fromJson(envelope.payload())))
                .onFailure(err -> LOG.warn(
                        "mesh connection deferred - broker not reachable at {}, retrying on the heartbeat: {}",
                        config.brokerUrl(),
                        String.valueOf(err)));
    }

    @Override
    public Future<?> stop() {
        return (meshClient == null ? Future.succeededFuture() : meshClient.close()).compose(closed -> super.stop());
    }

    @Override
    protected int httpPort() {
        return portOverride >= 0 ? portOverride : super.httpPort();
    }

    @Override
    protected void configureRoutes(Router router) {
        var builder = RouterBuilder.create(vertx, contract);
        builder.getRoute("getPeers").addHandler(routes::getPeers);
        builder.getRoute("getBaseline").addHandler(routes::getBaseline);
        router.route("/*").subRouter(ApiSecurity.enforcedByBaseVerticle(builder).createRouter());
    }

    @Override
    protected void registerReadinessChecks(HealthChecks readiness) {
        // Intentionally none: a mesh outage degrades discovery, not this service's ability to answer.
        // Registering a broker check here would take the gateway out of rotation and leave the console
        // with nothing, instead of the honest degraded view peers aging out to UNREACHABLE provides.
    }

    /** This cluster's peer registry, for the routes that serve it. */
    PeerRegistry peerRegistry() {
        return peerRegistry;
    }
}
