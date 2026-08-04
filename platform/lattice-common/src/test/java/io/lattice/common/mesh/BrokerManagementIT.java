package io.lattice.common.mesh;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.amqp.AmqpClient;
import io.vertx.amqp.AmqpClientOptions;
import io.vertx.amqp.AmqpMessage;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the one mechanism the federation-link design rests on: that Artemis answers a management
 * request over the <b>same AMQP connection</b> a service already holds, rather than requiring a
 * second transport, port, or credential.
 *
 * <p>This suite exists because that assumption was recorded as unverified rather than assumed true.
 * If it fails, reading per-peer federation state needs another transport and the design's cost
 * changes, so nothing else in that work should be built until this is green.
 *
 * <p>The request shape is Artemis's own management protocol: a message to {@code
 * activemq.management} carrying the resource and operation as application properties, with a
 * dynamic reply address the broker sends the answer to.
 */
class BrokerManagementIT {

    private static final DockerImageName IMAGE = DockerImageName.parse("apache/activemq-artemis:2.44.0-alpine");

    private static final int AMQP_PORT = 61616;
    private static final String MANAGEMENT_ADDRESS = "activemq.management";
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(30);

    // Singleton container: started once for the suite, matching the sibling mesh suites. The
    // suppression is theirs too - stop() is handled deterministically in @AfterAll.
    @SuppressWarnings("resource")
    private static final GenericContainer<?> ARTEMIS = new GenericContainer<>(IMAGE)
            .withEnv("ARTEMIS_USER", "artemis")
            .withEnv("ARTEMIS_PASSWORD", "artemis")
            .withExposedPorts(AMQP_PORT)
            .waitingFor(Wait.forListeningPort());

    private Vertx vertx;
    private AmqpClient client;

    @BeforeAll
    static void startBroker() {
        ARTEMIS.start();
    }

    @AfterAll
    static void stopBroker() {
        ARTEMIS.stop();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            await(client.close());
        }
        if (vertx != null) {
            await(vertx.close());
        }
    }

    /**
     * Verifies that the broker answers a {@code getQueueNames} management request sent over an
     * ordinary AMQP connection, and that the reply carries the broker's queue names.
     *
     * <p>This is the whole point of the suite: a successful reply means a service holding a mesh
     * connection can read broker state with no second transport, port, or credential.
     */
    @Test
    void answersAManagementRequestOverTheSameAmqpConnection() throws Exception {
        vertx = Vertx.vertx();
        client = AmqpClient.create(
                vertx,
                new AmqpClientOptions()
                        .setHost(ARTEMIS.getHost())
                        .setPort(ARTEMIS.getMappedPort(AMQP_PORT))
                        .setUsername("artemis")
                        .setPassword("artemis"));

        var names = await(queueNames());

        // The broker always has its own internal queues, so a non-empty reply is the signal that
        // the request was understood and executed rather than merely accepted.
        assertThat(names).isNotEmpty();
    }

    /** Sends getQueueNames and completes with the names the broker replied with. */
    private Future<JsonArray> queueNames() {
        var replied = Promise.<JsonArray>promise();

        return client.connect()
                .compose(connection -> connection
                        // A dynamic receiver: the broker generates the reply address, so nothing has to be
                        // declared in the broker's configuration for this to work.
                        .createDynamicReceiver()
                        .compose(receiver -> {
                            receiver.handler(reply -> {
                                var succeeded = reply.applicationProperties() != null
                                        && Boolean.TRUE.equals(
                                                reply.applicationProperties().getBoolean("_AMQ_OperationSucceeded"));
                                if (!succeeded) {
                                    replied.tryFail("management request was rejected: " + reply.bodyAsString());
                                    return;
                                }
                                // Artemis replies with a JSON array whose single element is the array of
                                // names, so the outer wrapper is unwrapped here rather than by the caller.
                                var body = new JsonArray(reply.bodyAsString());
                                replied.tryComplete(body.getJsonArray(0));
                            });

                            return connection.createSender(MANAGEMENT_ADDRESS).compose(sender -> {
                                sender.send(AmqpMessage.create()
                                        .replyTo(receiver.address())
                                        .applicationProperties(new io.vertx.core.json.JsonObject()
                                                .put("_AMQ_ResourceName", "broker")
                                                .put("_AMQ_OperationName", "getQueueNames"))
                                        .withBody(new JsonArray().encode())
                                        .build());
                                return replied.future();
                            });
                        }));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(REPLY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
}
