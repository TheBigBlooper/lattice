package io.lattice.common.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.contract.mesh.ClusterAnnouncement;
import io.vertx.amqp.AmqpClientOptions;
import io.vertx.core.Vertx;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Discovery over a real Artemis broker (Testcontainers, the same image the local stack runs): two
 * clusters announce themselves and each ends up holding the other in its peer registry, with the
 * endpoints Shape A federation needs.
 *
 * <p>This is the test the whole mesh ticket exists to satisfy, and it is the only place the wiring is
 * genuinely proven - announcements must <b>fan out</b> to every peer rather than being load-balanced
 * between them, which no unit test can establish because it is a property of how the broker routes
 * the address.
 *
 * <p>Assertions poll to the settled state rather than snapshotting a single instant, since delivery is
 * asynchronous.
 */
class MeshDiscoveryIT {

    private static final DockerImageName IMAGE = DockerImageName.parse("apache/activemq-artemis:2.44.0-alpine");

    private static final int AMQP_PORT = 61616;
    private static final Duration TTL = Duration.ofSeconds(30);

    // Singleton container: started once for the suite. The suppression matches the sibling suites -
    // the Testcontainers stop() lifecycle is handled deterministically in @AfterAll.
    @SuppressWarnings("resource")
    private static final GenericContainer<?> ARTEMIS = new GenericContainer<>(IMAGE)
            .withEnv("ARTEMIS_USER", "artemis")
            .withEnv("ARTEMIS_PASSWORD", "artemis")
            .withExposedPorts(AMQP_PORT)
            .waitingFor(Wait.forListeningPort());

    private Vertx vertx;
    private AmqpMeshClient west;
    private AmqpMeshClient east;

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
        if (west != null) {
            await(west.close());
        }
        if (east != null) {
            await(east.close());
        }
        if (vertx != null) {
            await(vertx.close());
        }
    }

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    private static AmqpClientOptions brokerOptions() {
        return new AmqpClientOptions()
                .setHost(ARTEMIS.getHost())
                .setPort(ARTEMIS.getMappedPort(AMQP_PORT))
                .setUsername("artemis")
                .setPassword("artemis");
    }

    private static ClusterAnnouncement announcementFor(String cluster, String region) {
        return new ClusterAnnouncement(
                cluster,
                region,
                "1.0.0",
                "ready",
                "https://" + cluster + ".console:3000",
                "https://" + cluster + ".svc:8080/api/v1");
    }

    /** Polls until the registry holds the wanted peer, so the async delivery is awaited, not guessed. */
    private static PeerRegistry.Peer awaitPeer(PeerRegistry registry, String clusterId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            var found = registry.peers().stream()
                    .filter(peer -> peer.clusterId().equals(clusterId))
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssertionError("peer " + clusterId + " was never discovered");
    }

    /**
     * Two clusters announcing on the mesh each discover the other, and the discovered peer carries the
     * console and API endpoints that make Shape A federation work (redirect an operator to the peer's
     * own console; live-pull its status from its API). Crucially both sides see each other, which only
     * holds if the announce address fans out to every subscriber rather than sharing messages between
     * them.
     */
    @Test
    void twoClustersDiscoverEachOther() throws Exception {
        vertx = Vertx.vertx();
        var clock = Clock.systemUTC();

        var westRegistry = new PeerRegistry("hub-west", TTL, clock);
        var eastRegistry = new PeerRegistry("hub-east", TTL, clock);

        west = AmqpMeshClient.create(vertx, "hub-west", brokerOptions(), clock);
        east = AmqpMeshClient.create(vertx, "hub-east", brokerOptions(), clock);

        await(west.subscribe(
                MeshClient.ANNOUNCE_ADDRESS,
                envelope -> westRegistry.record(ClusterAnnouncement.fromJson(envelope.payload()))));
        await(east.subscribe(
                MeshClient.ANNOUNCE_ADDRESS,
                envelope -> eastRegistry.record(ClusterAnnouncement.fromJson(envelope.payload()))));

        // Announce repeatedly: a subscription established moments ago may miss the very first message,
        // and re-announcing is exactly what the real heartbeat does anyway.
        for (int beat = 0; beat < 5; beat++) {
            await(west.announce(announcementFor("hub-west", "us-west")));
            await(east.announce(announcementFor("hub-east", "us-east")));
            TimeUnit.MILLISECONDS.sleep(300);
        }

        var eastAsSeenByWest = awaitPeer(westRegistry, "hub-east");
        assertEquals("us-east", eastAsSeenByWest.region());
        assertEquals("1.0.0", eastAsSeenByWest.baselineVersion());
        assertEquals("ready", eastAsSeenByWest.health());
        assertEquals("https://hub-east.console:3000", eastAsSeenByWest.consoleUrl());
        assertEquals("https://hub-east.svc:8080/api/v1", eastAsSeenByWest.apiBaseUrl());
        assertEquals(Reachability.REACHABLE, eastAsSeenByWest.reachability());

        var westAsSeenByEast = awaitPeer(eastRegistry, "hub-west");
        assertEquals("us-west", westAsSeenByEast.region());
        assertEquals("https://hub-west.console:3000", westAsSeenByEast.consoleUrl());
        assertEquals(Reachability.REACHABLE, westAsSeenByEast.reachability());

        // Neither cluster lists itself, however many of its own announcements it hears back.
        assertTrue(
                westRegistry.peers().stream().noneMatch(peer -> peer.clusterId().equals("hub-west")),
                "a cluster must not appear in its own peer registry");
        assertEquals(
                List.of("hub-east"),
                westRegistry.peers().stream().map(PeerRegistry.Peer::clusterId).toList());
    }
}
