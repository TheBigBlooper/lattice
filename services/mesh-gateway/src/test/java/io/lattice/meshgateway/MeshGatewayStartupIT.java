package io.lattice.meshgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Startup ordering: a gateway that comes up <b>before</b> its broker still joins the mesh on its own
 * once the broker appears, with no restart.
 *
 * <p>This exists because it did not. The gateway connected to the broker exactly once, from
 * {@code start()}: if that first attempt failed, the mesh client was never assigned and every later
 * announce failed permanently, because nothing re-attempted the connection. The self-healing reconnect
 * in {@code AmqpMeshClient} only recovers a connection that was <em>once</em> established, so it did
 * not cover this case at all - the gateway stayed mesh-deaf until someone restarted it, reporting only
 * a warning. A {@code depends_on} startup gate in the local compose stack is what kept it from ever
 * being observed.
 *
 * <p>It matters under per-baseline federated brokers: a baseline's broker is restarted by that
 * baseline's own operators, so "gateway starts before broker" is routine rather than exotic.
 *
 * <p>The broker is bound to a port reserved before anything starts, so the gateways can be pointed at
 * an address that is dead at deploy time and alive later - starting a container first and connecting
 * afterwards would prove nothing about this ordering.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class MeshGatewayStartupIT {

    /**
     * This baseline's identity realm. Every service now refuses to start without one and rejects an
     * unauthenticated /api/v1 call, so a suite testing what the endpoints do runs as an operator.
     */
    private static TestRealm REALM;

    /** Starts the realm the deployed service validates tokens against. */
    @BeforeAll
    static void startRealm() {
        REALM = TestRealm.start("lattice");
    }

    /** Releases the realm. */
    @AfterAll
    static void stopRealm() {
        REALM.close();
    }

    private static final DockerImageName IMAGE = DockerImageName.parse("apache/activemq-artemis:2.44.0-alpine");

    private static final int AMQP_PORT = 61616;

    private Vertx vertx;
    private WebClient client;
    private GenericContainer<?> broker;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
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

    /** Reserves a port by binding and releasing it, so the broker can claim it after the gateways start. */
    private static int reservePort() throws IOException {
        // Reserved and closed immediately; nothing is ever sent, so there is no traffic to encrypt.
        // The marker below must stay on the line directly above the finding.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
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

    /** A gateway configuration pointed at the reserved port, whether or not anything listens there yet. */
    private static MeshGatewayConfig configFor(String cluster, String region, int brokerPort) {
        return new MeshGatewayConfig(
                cluster,
                region,
                "1.0.0",
                "https://" + cluster + ".console:3000",
                "https://" + cluster + ".svc:8080/api/v1",
                "tcp://localhost:" + brokerPort,
                "artemis",
                "artemis",
                // Nothing to watch and nothing to probe: both are covered by ClusterHealthServiceTest,
                // and a real target here would only add moving parts to a startup-ordering test.
                Map.of(),
                List.of(),
                // A brisk heartbeat, because the heartbeat is what drives the reconnect under test.
                Duration.ofMillis(500),
                Duration.ofSeconds(30));
    }

    /** Polls a gateway's peer list until the wanted peer appears, so async delivery is awaited. */
    private JsonObject awaitPeer(int port, String clusterId) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            HttpResponse<Buffer> response =
                    await(client.get(port, "localhost", "/api/v1/peers").send());
            assertEquals(200, response.statusCode());
            var peers = response.bodyAsJsonObject().getJsonArray("data");
            for (int i = 0; i < peers.size(); i++) {
                var peer = peers.getJsonObject(i);
                if (clusterId.equals(peer.getString("clusterId"))) {
                    return peer;
                }
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new AssertionError("gateway on port " + port + " never discovered " + clusterId);
    }

    /**
     * Two gateways deployed against an address nothing is listening on keep serving their own API, and
     * once a broker claims that address they announce, subscribe, and discover each other - all without
     * being restarted. The announce heartbeat is the only thing driving it; there is no separate retry
     * timer to leak.
     */
    @Test
    void gatewaysStartedBeforeTheirBrokerJoinTheMeshOnceItAppears(Vertx testVertx, ExpectedLogs logs) throws Exception {
        // Every WARN here is the service correctly reporting a broker that is not up yet, and is
        // asserted rather than tolerated. Each must be logged once per gateway, not once per heartbeat.
        logs.expectWarn("mesh connection deferred");
        logs.expectWarn("announce failed");
        logs.expectWarn("no services configured to watch");
        logs.expectWarn("no infrastructure configured");

        // Vert.x reports the AMQP connections being severed when the broker container stops in
        // teardown. Whether it surfaces depends on how fast the machine tears the container down,
        // so it is tolerated rather than expected - expectError would fail wherever it does not.
        logs.tolerateError("Connection reset");

        // The mirror of that at startup: this test deploys both gateways before the broker exists,
        // so an announce already in flight can be rejected while the broker is still settling into
        // the address. Timing decides whether it surfaces, so tolerate rather than expect.
        logs.tolerateWarn("Message rejected by remote peer");

        vertx = testVertx;
        client = REALM.operatorClient(vertx);
        int port = reservePort();

        var west = new MeshGatewayVerticle(configFor("hub-west", "us-west", port), 0, REALM.realmUrl());
        var east = new MeshGatewayVerticle(configFor("hub-east", "us-east", port), 0, REALM.realmUrl());
        await(vertx.deployVerticle(west));
        await(vertx.deployVerticle(east));

        // The broker being absent must not stop a gateway answering for itself (locked #42).
        HttpResponse<Buffer> baseline = await(
                client.get(west.actualPort(), "localhost", "/api/v1/baseline").send());
        assertEquals(200, baseline.statusCode(), "a gateway must serve its own baseline with no broker");

        // ...and the mesh really is down: nothing has been discovered yet.
        var beforeBroker = await(client.get(west.actualPort(), "localhost", "/api/v1/peers")
                        .send())
                .bodyAsJsonObject()
                .getJsonArray("data");
        assertTrue(beforeBroker.isEmpty(), "no peer can be known before the broker exists");

        broker = startBrokerOn(port);

        var eastAsSeenByWest = awaitPeer(west.actualPort(), "hub-east");
        assertEquals("us-east", eastAsSeenByWest.getString("region"));
        assertEquals("https://hub-east.console:3000", eastAsSeenByWest.getString("consoleUrl"));
        assertEquals("REACHABLE", eastAsSeenByWest.getString("reachability"));

        var westAsSeenByEast = awaitPeer(east.actualPort(), "hub-west");
        assertEquals("us-west", westAsSeenByEast.getString("region"));

        // Exactly one copy of the peer, not one per reconnect attempt: a retry that re-subscribed on
        // every tick would stack duplicate receivers and deliver each announcement many times over.
        var westPeers = await(client.get(west.actualPort(), "localhost", "/api/v1/peers")
                        .send())
                .bodyAsJsonObject()
                .getJsonArray("data");
        assertEquals(1, westPeers.size(), "only the other cluster, never itself and never a duplicate");
    }
}
