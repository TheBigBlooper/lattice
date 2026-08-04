package io.lattice.common.mesh;

import io.vertx.amqp.AmqpConnection;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads this broker's federation links to its peers, one state per peer.
 *
 * <p><b>Why the broker rather than an inference.</b> A baseline could guess that its links are down
 * when every peer ages out at once, but that guess is mesh-wide, cannot fire when one peer is
 * refused and another is fine, and is simply wrong when every peer genuinely did go down together.
 * The broker holds the truth per peer, so it is asked. Detail: locked #80.
 *
 * <p><b>What is measured.</b> Artemis creates one queue per federated link, named from the
 * federation and upstream names, and a peer's broker appears as a <em>consumer</em> on it. Measured
 * against a running three-baseline mesh: a healthy link shows one consumer, and stopping a peer's
 * broker drops that to zero while the queue itself remains. So consumer count is the signal, and a
 * missing queue is a different and louder condition than a quiet one.
 *
 * <p><b>The naming is a broker implementation detail, and that is the risk.</b> If Artemis renames
 * these queues on an upgrade, this parse stops matching. It therefore reports <em>nothing</em> rather
 * than a healthy reading, so the failure is a console that shows no federation state rather than one
 * that confidently shows "up" - and the certificate scenarios assert the real state end to end, which
 * is what turns a rename from silent into loud.
 */
public final class BrokerFederation {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerFederation.class);

    /** The prefix Artemis gives a federated queue, followed by the owning federation's name. */
    private static final String FEDERATED_PREFIX = "federated.lattice-mesh-";

    private BrokerFederation() {}

    /**
     * Reads the link state for every peer this broker federates with.
     *
     * @param connection an open connection to this baseline's own broker.
     * @return peer cluster id to whether that link is carrying, empty when nothing could be read.
     */
    public static Future<Map<String, Boolean>> read(AmqpConnection connection) {
        return BrokerManagement.request(
                        connection,
                        "broker",
                        "listQueues",
                        new JsonArray().add("").add(1).add(500))
                .map(BrokerFederation::queuesFrom)
                .map(BrokerFederation::linksFrom)
                .otherwise(failure -> {
                    // Absent rather than assumed healthy: a link nobody could read is not a link
                    // that is up, and the console renders nothing rather than a confident state.
                    LOG.warn("could not read federation links from the broker: {}", String.valueOf(failure));
                    return Map.of();
                });
    }

    /** Pulls the queue array out of the listQueues reply, tolerating a shape it does not recognise. */
    private static JsonArray queuesFrom(JsonArray reply) {
        if (reply == null || reply.isEmpty()) {
            return new JsonArray();
        }
        var page = new io.vertx.core.json.JsonObject(reply.getString(0));
        var data = page.getJsonArray("data");
        return data == null ? new JsonArray() : data;
    }

    /**
     * Turns a broker's queue listing into per-peer link state.
     *
     * <p>A peer counts as carrying when <em>any</em> of its federated queues has a consumer: a join
     * creates a pair of links that can settle at different moments, and calling a peer down because
     * one of two has not finished connecting would flap on every restart.
     *
     * @param queues the queue objects from a {@code listQueues} reply.
     * @return peer cluster id to whether that link is carrying.
     */
    static Map<String, Boolean> linksFrom(JsonArray queues) {
        var links = new HashMap<String, Boolean>();
        for (var entry : queues) {
            if (!(entry instanceof io.vertx.core.json.JsonObject queue)) {
                continue;
            }
            var peer = peerOf(queue.getString("name"));
            if (peer == null) {
                continue;
            }
            var carrying = queue.getInteger("consumerCount", 0) > 0;
            links.merge(peer, carrying, (existing, added) -> existing || added);
        }
        return links;
    }

    /** The peer a federated queue belongs to, or null when the name is not one this parse knows. */
    private static String peerOf(String queueName) {
        if (queueName == null || !queueName.toLowerCase(Locale.ROOT).startsWith(FEDERATED_PREFIX)) {
            return null;
        }
        var rest = queueName.substring(FEDERATED_PREFIX.length());
        var end = rest.indexOf('.');
        if (end <= 0) {
            return null;
        }
        return rest.substring(0, end);
    }
}
