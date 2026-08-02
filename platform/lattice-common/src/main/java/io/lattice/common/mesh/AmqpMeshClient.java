package io.lattice.common.mesh;

import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.contract.mesh.MeshEnvelope;
import io.lattice.contract.mesh.MeshLinkState;
import io.vertx.amqp.AmqpClient;
import io.vertx.amqp.AmqpClientOptions;
import io.vertx.amqp.AmqpConnection;
import io.vertx.amqp.AmqpMessage;
import io.vertx.amqp.AmqpSender;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Artemis-backed {@link MeshClient}, speaking AMQP 1.0 to the broker.
 *
 * <p>AMQP rather than the Artemis CORE/JMS client because it is Vert.x-native and non-blocking: the
 * mesh never has to be wrapped in {@code executeBlocking} to keep off the event loop, and its version
 * rides the Vert.x bill of materials the rest of the stack already uses (one engine per job).
 *
 * <p><b>Fan-out.</b> Announcements must reach <em>every</em> peer, not be load-balanced between them,
 * so the announce address is addressed as a topic. Each subscriber therefore gets its own
 * subscription and its own copy of every announcement; if the address were treated as a queue, peers
 * would round-robin the announcements and each would see only a fraction of the mesh.
 *
 * <p><b>The connection heals itself.</b> A broker restart drops the connection, and an AMQP sender
 * bound to a dead connection accepts writes without complaint - so a client that simply held its
 * original connection would publish into the void <em>silently and forever</em>, while every peer
 * aged out to unreachable with nothing in the log to explain it. Instead the connection is
 * re-established on demand: every publish and subscribe goes through {@code ensureConnected}, remembered
 * subscriptions are restored on reconnect, and a lost connection is reported. The announce heartbeat
 * therefore doubles as the reconnect driver, with no separate retry timer to leak.
 *
 * <p><b>Connecting is lazy, so the <em>first</em> connection heals too.</b> {@link #create} never
 * touches the network: the client exists whether or not the broker does, and the first publish or
 * subscribe is what dials. A factory that connected eagerly and failed left the caller holding nothing
 * to retry with, so a service that started before its broker stayed permanently mesh-deaf - the
 * self-healing above only ever covered a connection that had once been established.
 *
 * <p><b>A failed publish is visible.</b> {@link #announce} fails its future when there is no usable
 * connection, so the caller reports it rather than believing a silent write succeeded.
 */
public final class AmqpMeshClient implements MeshClient {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpMeshClient.class);

    /**
     * Artemis routes an address as a topic (fan-out to every subscriber) rather than a queue
     * (round-robin between them) when it is addressed with this prefix. Without it, peers would share
     * the announcements instead of each receiving them all.
     */
    private static final String TOPIC_PREFIX = "topic://";

    private final String clusterId;
    private final Clock clock;
    private final AmqpClient client;

    /** Remembered so a reconnect can restore them: a subscription does not survive its connection. */
    private final Map<String, Handler<MeshEnvelope>> subscriptions = new ConcurrentHashMap<>();

    /**
     * Addresses that already have a live receiver on the <em>current</em> connection, so an address is
     * never attached twice. Two paths race to attach the same one: {@link #subscribe} attaches
     * explicitly, and a connect triggered by that same call restores every remembered subscription -
     * which now includes it. A second receiver would deliver every announcement twice over.
     */
    private final Set<String> attached = ConcurrentHashMap.newKeySet();

    private volatile AmqpConnection connection;
    private volatile AmqpSender announceSender;

    /** The in-flight connect attempt, shared by concurrent callers so they do not stampede. */
    private Future<Void> connecting;

    private AmqpMeshClient(String clusterId, Clock clock, AmqpClient client) {
        this.clusterId = clusterId;
        this.clock = clock;
        this.client = client;
    }

    /**
     * Creates the client without contacting the broker. The first {@link #announce} or
     * {@link #subscribe} establishes the connection, and every later one re-establishes it if it has
     * been lost - so a caller may hold this client from before the broker exists and still join the
     * mesh the moment it appears.
     *
     * @param vertx     the Vert.x instance owning the connection.
     * @param clusterId this cluster's id, stamped as the source of every announcement it publishes.
     * @param options   the broker connection options (host, port, credentials).
     * @param clock     the clock stamping {@code occurredAt}, injected so tests can pin it.
     * @return the client, connected lazily on first use.
     */
    public static AmqpMeshClient create(Vertx vertx, String clusterId, AmqpClientOptions options, Clock clock) {
        return new AmqpMeshClient(clusterId, clock, AmqpClient.create(vertx, options));
    }

    /**
     * Completes once a usable connection and announce sender exist, connecting if necessary.
     * Concurrent callers share one attempt rather than each opening a connection.
     */
    private synchronized Future<Void> ensureConnected() {
        if (announceSender != null) {
            return Future.succeededFuture();
        }
        if (connecting != null && !connecting.failed()) {
            return connecting;
        }
        connecting = client.connect()
                .compose(established -> {
                    // A drop has to be noticed, or the next publish goes silently nowhere. Both paths
                    // matter: exceptionHandler covers a transport error, closeFuture covers the broker
                    // closing the connection cleanly (which is what a graceful broker restart does).
                    established.exceptionHandler(err -> onConnectionLost(String.valueOf(err)));
                    established.closeFuture().onComplete(closed -> onConnectionLost("connection closed"));
                    return established
                            .createSender(TOPIC_PREFIX + ANNOUNCE_ADDRESS)
                            .map(sender -> {
                                this.connection = established;
                                this.announceSender = sender;
                                LOG.info("mesh connected cluster={} address={}", clusterId, ANNOUNCE_ADDRESS);
                                return established;
                            });
                })
                .compose(this::restoreSubscriptions)
                .mapEmpty();
        return connecting;
    }

    /** Re-establishes every remembered subscription, which the dropped connection took with it. */
    private Future<Void> restoreSubscriptions(AmqpConnection established) {
        Future<Void> restored = Future.succeededFuture();
        for (var entry : subscriptions.entrySet()) {
            restored = restored.compose(previous -> attach(established, entry.getKey(), entry.getValue()));
        }
        return restored;
    }

    private Future<Void> attach(AmqpConnection established, String address, Handler<MeshEnvelope> handler) {
        if (!attached.add(address)) {
            return Future.succeededFuture();
        }
        return established
                .createReceiver(TOPIC_PREFIX + address)
                .compose(receiver -> {
                    receiver.handler(message -> deliver(message, handler));
                    LOG.info("mesh subscribed cluster={} address={}", clusterId, address);
                    return Future.<Void>succeededFuture();
                })
                // Released on failure so the next reconnect retries it, rather than recording an
                // attachment that does not exist and never listening on the address again.
                .onFailure(err -> attached.remove(address));
    }

    /**
     * Drops the dead connection so the next publish reconnects rather than writing into the void.
     *
     * <p>A restarted or briefly unreachable broker is an expected operational event, so this is a WARN
     * rather than an error - but it is never silent, because a mesh that has quietly stopped working
     * looks exactly like every peer having legitimately gone away.
     */
    private synchronized void onConnectionLost(String reason) {
        if (announceSender == null && connection == null) {
            return;
        }
        announceSender = null;
        connection = null;
        connecting = null;
        // The receivers died with the connection; forgetting them is what lets the reconnect re-attach.
        attached.clear();
        LOG.warn("mesh connection lost cluster={} ({}); reconnecting on the next announce", clusterId, reason);
    }

    @Override
    public MeshLinkState linkState() {
        // The live connection field is already the answer, so it is reported rather than mirrored
        // into a parallel flag that a third code path could forget to update.
        return connection == null ? MeshLinkState.DOWN : MeshLinkState.UP;
    }

    @Override
    public Future<Void> announce(ClusterAnnouncement announcement) {
        var envelope = MeshEnvelope.announce(UUID.randomUUID().toString(), clusterId, clock.instant(), announcement);
        return ensureConnected().compose(ready -> {
            var sender = announceSender;
            if (sender == null) {
                return Future.failedFuture("mesh sender unavailable");
            }
            sender.send(
                    AmqpMessage.create().withBody(envelope.toJson().encode()).build());
            LOG.debug("announced cluster={} health={}", announcement.clusterId(), announcement.health());
            return Future.<Void>succeededFuture();
        });
    }

    @Override
    public Future<Void> subscribe(String address, Handler<MeshEnvelope> handler) {
        // Remembered first, so a later reconnect restores it even if attaching right now fails.
        subscriptions.put(address, handler);
        return ensureConnected().compose(ready -> {
            var established = connection;
            return established == null ? Future.<Void>succeededFuture() : attach(established, address, handler);
        });
    }

    /**
     * Parses one received message and hands it to the subscriber. A message that cannot be parsed is
     * logged and dropped rather than propagated: a peer on a newer or malformed baseline must never be
     * able to break this cluster's discovery loop.
     */
    private void deliver(AmqpMessage message, Handler<MeshEnvelope> handler) {
        try {
            handler.handle(MeshEnvelope.fromJson(new JsonObject(message.bodyAsString())));
        } catch (RuntimeException malformed) {
            LOG.warn("dropped an unreadable mesh message: {}", String.valueOf(malformed));
        }
    }

    @Override
    public Future<Void> close() {
        AmqpConnection established;
        synchronized (this) {
            subscriptions.clear();
            attached.clear();
            established = connection;
            announceSender = null;
            connection = null;
            connecting = null;
        }
        return (established == null ? Future.<Void>succeededFuture() : established.close())
                .eventually(() -> client.close())
                .recover(err -> {
                    // Closing is best effort: the process is shutting down, and a broker already gone
                    // is the ordinary case rather than a failure worth propagating.
                    LOG.debug("mesh close completed with {}", String.valueOf(err));
                    return Future.succeededFuture();
                });
    }
}
