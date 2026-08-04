package io.lattice.common.mesh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.amqp.AmqpClient;
import io.vertx.amqp.AmqpClientOptions;
import io.vertx.core.Future;
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
        connect();

        var reply = await(client.connect()
                .compose(connection ->
                        BrokerManagement.request(connection, "broker", "getQueueNames", new JsonArray())));

        // The broker always has its own internal queues, so a non-empty reply is the signal that
        // the request was understood and executed rather than merely accepted.
        assertThat(reply.getJsonArray(0)).isNotEmpty();
    }

    /**
     * Verifies a rejected operation fails rather than resolving empty, so a caller cannot read
     * "the broker refused this" as "there was nothing to report".
     */
    @Test
    void failsWhenTheBrokerRejectsTheOperation() throws Exception {
        connect();

        var rejected = client.connect()
                .compose(connection ->
                        BrokerManagement.request(connection, "broker", "noSuchOperation", new JsonArray()));

        assertThatThrownBy(() -> await(rejected)).hasMessageContaining("noSuchOperation");
    }

    /**
     * Verifies the federation read works against a real broker, and reports <b>no links</b> on one
     * that federates with nobody.
     *
     * <p>Empty is the correct answer here and it is worth pinning: this broker has queues, so an
     * implementation that mistook any queue for a federated one would report peers that do not
     * exist.
     */
    @Test
    void readsNoFederationLinksFromABrokerWithNoPeers() throws Exception {
        connect();

        var links = await(client.connect().compose(BrokerFederation::read));

        assertThat(links).isEmpty();
    }

    /** Opens the client against the container. */
    private void connect() {
        vertx = Vertx.vertx();
        client = AmqpClient.create(
                vertx,
                new AmqpClientOptions()
                        .setHost(ARTEMIS.getHost())
                        .setPort(ARTEMIS.getMappedPort(AMQP_PORT))
                        .setUsername("artemis")
                        .setPassword("artemis"));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(REPLY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
}
