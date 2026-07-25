package io.lattice.meshgateway;

import io.lattice.common.BaseVerticle;
import io.lattice.common.config.LatticeConfig;
import io.lattice.common.mesh.AmqpMeshClient;
import io.lattice.common.mesh.MeshClient;
import io.lattice.common.mesh.PeerRegistry;
import io.lattice.contract.mesh.ClusterAnnouncement;
import io.vertx.core.Future;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.web.Router;
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

    private final String brokerUrlOverride;
    private final int portOverride;

    private MeshGatewayConfig config;
    private PeerRegistry peerRegistry;
    private MeshClient meshClient;

    /** Creates the verticle using the shared config for the broker URL and HTTP port. */
    public MeshGatewayVerticle() {
        this(null, -1);
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
        this.portOverride = portOverride;
    }

    @Override
    public Future<?> start() {
        return LatticeConfig.load(vertx)
                .compose(loaded -> {
                    this.config = resolve(loaded);
                    this.peerRegistry =
                            new PeerRegistry(config.clusterId(), config.peerTimeToLive(), Clock.systemUTC());
                    connectToMesh();
                    return super.start();
                })
                .onSuccess(started ->
                        LOG.info("mesh-gateway started cluster={} port={}", config.clusterId(), actualPort()));
    }

    /** Applies the test overrides on top of the loaded configuration. */
    private MeshGatewayConfig resolve(LatticeConfig loaded) {
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
        // The /api/v1 peers + baseline routes mount here; they arrive with the contract operations.
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
