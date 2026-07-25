package io.lattice.common.mesh;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.contract.mesh.ClusterAnnouncement;
import io.vertx.amqp.AmqpClientOptions;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Survival across a broker restart - the behavior mesh_discovery.md promises when the broker is
 * "briefly unavailable": announcements pause, peers may age out, and discovery self-heals on
 * reconnect with no manual intervention.
 *
 * <p>This exists because it did not. A two-cluster QA pass found that the client held its original
 * connection forever: an AMQP sender bound to a dead connection accepts writes without complaint, so
 * a restarted broker left the mesh permanently dead while every publish still reported success. The
 * failure was invisible - peers simply went unreachable and stayed there, with nothing in the log.
 *
 * <p>The broker is bound to a port reserved before it starts, so the same address can be taken away
 * and given back - a container restart alone would land on a new random port and prove nothing.
 */
@ExtendWith(VertxExtension.class)
class MeshReconnectIT {

    private static final DockerImageName IMAGE = DockerImageName.parse("apache/activemq-artemis:2.44.0-alpine");

    private static final int AMQP_PORT = 61616;

    private Vertx vertx;
    private AmqpMeshClient client;
    private GenericContainer<?> broker;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            await(client.close());
        }
        if (vertx != null) {
            await(vertx.close());
        }
        if (broker != null && broker.isRunning()) {
            broker.stop();
        }
    }

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(90, TimeUnit.SECONDS);
    }

    /** Reserves a port by binding and releasing it, so the broker can claim the same one twice. */
    private static int reservePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @SuppressWarnings("resource")
    private GenericContainer<?> startBrokerOn(int hostPort) {
        var container = new GenericContainer<>(IMAGE)
                .withEnv("ARTEMIS_USER", "artemis")
                .withEnv("ARTEMIS_PASSWORD", "artemis")
                .withExposedPorts(AMQP_PORT)
                .waitingFor(Wait.forListeningPort());
        container.setPortBindings(List.of(hostPort + ":" + AMQP_PORT));
        container.start();
        return container;
    }

    private static AmqpClientOptions optionsFor(int hostPort) {
        return new AmqpClientOptions()
                .setHost("localhost")
                .setPort(hostPort)
                .setUsername("artemis")
                .setPassword("artemis");
    }

    private static ClusterAnnouncement announcement() {
        return new ClusterAnnouncement(
                "hub-west", "us-west", "1.0.0", "ready", "https://west.console:3000", "https://west.svc:8080/api/v1");
    }

    /**
     * The mesh survives a broker restart: publishing fails loudly while the broker is gone (rather
     * than silently succeeding into a dead connection), and once it returns the client reconnects,
     * restores its subscription, and announcements flow again - all without restarting the service.
     */
    @Test
    void reconnectsAndResubscribesAfterABrokerRestart(Vertx testVertx) throws Exception {
        vertx = testVertx;
        int port = reservePort();
        broker = startBrokerOn(port);

        var received = new AtomicInteger();
        client = AmqpMeshClient.create(vertx, "hub-west", optionsFor(port), Clock.systemUTC());
        await(client.subscribe(MeshClient.ANNOUNCE_ADDRESS, envelope -> received.incrementAndGet()));

        // Before: announcements flow.
        for (int beat = 0; beat < 5 && received.get() == 0; beat++) {
            await(client.announce(announcement()));
            TimeUnit.MILLISECONDS.sleep(300);
        }
        assertTrue(received.get() > 0, "announcements must flow before the broker is taken away");

        broker.stop();
        var deliveredBeforeOutage = received.get();

        // During: publishing must FAIL rather than quietly succeed into a dead connection. That silence
        // was the original defect - the mesh looked healthy while nothing was being delivered.
        var duringOutage = assertThrows(
                ExecutionException.class,
                () -> client.announce(announcement())
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(30, TimeUnit.SECONDS),
                "a publish with no broker must fail, not report success");
        assertTrue(duringOutage.getCause() != null, "the failure carries a cause the caller can report");

        // After: the same address comes back, and the client heals itself with no restart.
        broker = startBrokerOn(port);
        boolean flowing = false;
        for (int attempt = 0; attempt < 40 && !flowing; attempt++) {
            try {
                await(client.announce(announcement()));
                flowing = received.get() > deliveredBeforeOutage;
            } catch (Exception stillDown) {
                flowing = false;
            }
            if (!flowing) {
                TimeUnit.MILLISECONDS.sleep(500);
            }
        }
        assertTrue(
                flowing, "the mesh must self-heal on reconnect: announcements delivered again, subscription restored");
    }
}
