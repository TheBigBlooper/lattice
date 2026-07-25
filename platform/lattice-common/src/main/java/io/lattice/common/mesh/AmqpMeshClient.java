package io.lattice.common.mesh;

import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.contract.mesh.MeshEnvelope;
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
import java.util.UUID;
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
 * <p>Publishing is fire-and-forget by design: an announcement is a heartbeat, so a lost one is
 * corrected by the next tick rather than being worth retrying or blocking on.
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
    private final AmqpConnection connection;
    private final AmqpSender announceSender;

    private AmqpMeshClient(
            String clusterId, Clock clock, AmqpClient client, AmqpConnection connection, AmqpSender announceSender) {
        this.clusterId = clusterId;
        this.clock = clock;
        this.client = client;
        this.connection = connection;
        this.announceSender = announceSender;
    }

    /**
     * Connects to the Artemis broker and opens the announce sender.
     *
     * @param vertx     the Vert.x instance owning the connection.
     * @param clusterId this cluster's id, stamped as the source of every announcement it publishes.
     * @param options   the broker connection options (host, port, credentials).
     * @param clock     the clock stamping {@code occurredAt}, injected so tests can pin it.
     * @return a future of the connected client.
     */
    public static Future<AmqpMeshClient> connect(
            Vertx vertx, String clusterId, AmqpClientOptions options, Clock clock) {
        var client = AmqpClient.create(vertx, options);
        return client.connect()
                .compose(connection -> connection
                        .createSender(TOPIC_PREFIX + ANNOUNCE_ADDRESS)
                        .map(sender -> {
                            LOG.info("mesh connected cluster={} address={}", clusterId, ANNOUNCE_ADDRESS);
                            return new AmqpMeshClient(clusterId, clock, client, connection, sender);
                        }));
    }

    @Override
    public Future<Void> announce(ClusterAnnouncement announcement) {
        var envelope = MeshEnvelope.announce(UUID.randomUUID().toString(), clusterId, clock.instant(), announcement);
        announceSender.send(
                AmqpMessage.create().withBody(envelope.toJson().encode()).build());
        LOG.debug("announced cluster={} health={}", announcement.clusterId(), announcement.health());
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> subscribe(String address, Handler<MeshEnvelope> handler) {
        return connection.createReceiver(TOPIC_PREFIX + address).compose(receiver -> {
            receiver.handler(message -> deliver(message, handler));
            LOG.info("mesh subscribed cluster={} address={}", clusterId, address);
            return Future.<Void>succeededFuture();
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
        return connection.close().eventually(() -> client.close()).recover(err -> {
            // Closing is best effort: the process is shutting down, and a broker already gone is the
            // ordinary case rather than a failure worth propagating.
            LOG.debug("mesh close completed with {}", String.valueOf(err));
            return Future.succeededFuture();
        });
    }
}
