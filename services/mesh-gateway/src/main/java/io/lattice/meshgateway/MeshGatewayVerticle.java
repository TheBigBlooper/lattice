package io.lattice.meshgateway;

import io.lattice.common.BaseVerticle;
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
 * serving, so a broker that is slow to come up cannot stop the cluster from answering for itself.
 */
public final class MeshGatewayVerticle extends BaseVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(MeshGatewayVerticle.class);

    private static final String SPEC = "openapi/v1.yaml";

    private final String brokerUrlOverride;
    private final MeshGatewayConfig configOverride;
    private final int portOverride;

    private MeshGatewayConfig config;
    private PeerRegistry peerRegistry;
    private MeshClient meshClient;
    private ClusterHealthService clusterHealth;
    private AnnouncerService announcer;
    private MeshGatewayRoutes routes;
    private OpenAPIContract contract;

    /** Creates the verticle using the shared config for the broker URL and HTTP port. */
    public MeshGatewayVerticle() {
        this((String) null, -1);
    }

    /**
     * Creates the verticle with test overrides for the broker URL and HTTP port.
     *
     * @param brokerUrlOverride the Artemis URL to use, or {@code null} to read it from config.
     * @param portOverride      the HTTP port to bind, or a negative value to read it from config
     *                          (0 binds an ephemeral port).
     */
    MeshGatewayVerticle(String brokerUrlOverride, int portOverride) {
        this.brokerUrlOverride = brokerUrlOverride;
        this.configOverride = null;
        this.portOverride = portOverride;
    }

    /**
     * Creates the verticle with its whole configuration supplied, bypassing the environment. This is
     * what lets more than one cluster identity run in a single test process, which the environment
     * alone cannot express - two gateways in one JVM would otherwise be forced to share a
     * {@code CLUSTER_ID} and could never discover each other.
     *
     * @param configOverride the complete configuration to use.
     * @param portOverride   the HTTP port to bind (0 binds an ephemeral port).
     */
    MeshGatewayVerticle(MeshGatewayConfig configOverride, int portOverride) {
        this.brokerUrlOverride = null;
        this.configOverride = configOverride;
        this.portOverride = portOverride;
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
                    // The announcer publishes through whatever mesh client is available; when the broker
                    // is unreachable its publishes fail and are absorbed, so the heartbeat keeps ticking
                    // and starts landing on its own once the broker appears.
                    this.announcer = new AnnouncerService(config, new DeferredMeshClient(), clusterHealth::poll);
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
     * Connects to the broker and subscribes to the announce address, without blocking startup. A
     * failure is an expected condition (the broker may simply not be up yet), so it is reported at
     * WARN and the service continues serving; peers stay absent until the connection succeeds.
     */
    private void connectToMesh() {
        AmqpMeshClient.connect(vertx, config.clusterId(), config.brokerOptions(), Clock.systemUTC())
                .compose(client -> {
                    this.meshClient = client;
                    return client.subscribe(
                            MeshClient.ANNOUNCE_ADDRESS,
                            envelope -> peerRegistry.record(ClusterAnnouncement.fromJson(envelope.payload())));
                })
                .onFailure(err -> LOG.warn(
                        "mesh connection deferred - broker not reachable at {}: {}",
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
        router.route("/*").subRouter(builder.createRouter());
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

    /**
     * A mesh client that defers to whichever connection currently exists.
     *
     * <p>The broker connection is established asynchronously and may not exist yet (or may have been
     * lost), while the announce heartbeat starts on a fixed schedule regardless. This delegate lets the
     * announcer be constructed once and keep ticking through that: a publish with no connection fails,
     * which the announcer absorbs, and later ticks start landing the moment the broker appears. The
     * alternative - deferring the whole heartbeat until connected - would mean a cluster that came up
     * before its broker never announced at all.
     */
    private final class DeferredMeshClient implements MeshClient {

        @Override
        public Future<Void> announce(ClusterAnnouncement announcement) {
            return meshClient == null
                    ? Future.failedFuture("mesh not connected yet")
                    : meshClient.announce(announcement);
        }

        @Override
        public Future<Void> subscribe(
                String address, io.vertx.core.Handler<io.lattice.contract.mesh.MeshEnvelope> handler) {
            return meshClient == null
                    ? Future.failedFuture("mesh not connected yet")
                    : meshClient.subscribe(address, handler);
        }

        @Override
        public Future<Void> close() {
            return meshClient == null ? Future.succeededFuture() : meshClient.close();
        }
    }
}
