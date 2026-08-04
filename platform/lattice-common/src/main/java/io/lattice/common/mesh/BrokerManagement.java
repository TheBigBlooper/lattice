package io.lattice.common.mesh;

import io.vertx.amqp.AmqpConnection;
import io.vertx.amqp.AmqpMessage;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Asks a broker about itself, over the connection a service already holds.
 *
 * <p><b>Why this exists rather than a second transport.</b> Artemis serves its management interface
 * on the same acceptor as ordinary traffic, so a service with a mesh connection can read broker
 * state with no extra port, no HTTP client, and no second credential. That was measured rather than
 * assumed, and {@code BrokerManagementIT} keeps it measured: it is the only thing that would catch
 * the management protocol changing under an upgrade.
 *
 * <p>The reply address is <b>dynamic</b> - the broker generates it - which is what lets this work
 * against a broker whose configuration mentions nothing about management replies.
 *
 * <p><b>What it deliberately cannot answer:</b> anything about the broker's own certificate. Measured
 * across the full management surface, Artemis exposes no certificate, keystore, truststore or TLS
 * attribute at all, which is why {@link BrokerCertificate} reads the handshake instead.
 */
final class BrokerManagement {

    /** Artemis's well-known management address. */
    private static final String MANAGEMENT_ADDRESS = "activemq.management";

    private BrokerManagement() {}

    /**
     * Invokes one management operation and completes with the reply body.
     *
     * @param connection an open connection to the broker.
     * @param resource   the management resource, e.g. {@code broker}.
     * @param operation  the operation on that resource, e.g. {@code listQueues}.
     * @param parameters the operation's parameters, in declaration order.
     * @return the reply body as a JSON array, or a failure when the broker rejected the request.
     */
    static Future<JsonArray> request(
            AmqpConnection connection, String resource, String operation, JsonArray parameters) {
        var replied = Promise.<JsonArray>promise();

        return connection.createDynamicReceiver().compose(receiver -> {
            receiver.handler(reply -> {
                var properties = reply.applicationProperties();
                var succeeded = properties != null && Boolean.TRUE.equals(properties.getBoolean(SUCCEEDED));
                if (succeeded) {
                    replied.tryComplete(new JsonArray(reply.bodyAsString()));
                } else {
                    replied.tryFail("broker rejected %s.%s: %s".formatted(resource, operation, reply.bodyAsString()));
                }
            });

            return connection.createSender(MANAGEMENT_ADDRESS).compose(sender -> {
                sender.send(AmqpMessage.create()
                        .replyTo(receiver.address())
                        .applicationProperties(new JsonObject()
                                .put("_AMQ_ResourceName", resource)
                                .put("_AMQ_OperationName", operation))
                        .withBody(parameters.encode())
                        .build());
                return replied.future();
            });
        });
    }

    /** The application property Artemis sets to say whether it ran the operation. */
    private static final String SUCCEEDED = "_AMQ_OperationSucceeded";
}
