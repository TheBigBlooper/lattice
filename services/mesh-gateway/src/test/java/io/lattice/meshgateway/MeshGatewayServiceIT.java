package io.lattice.meshgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.testing.TestRealm;
import io.lattice.contract.mesh.ComponentKind;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
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
 * The mesh-gateway's deliverable, end to end: two clusters on one real Artemis broker (Testcontainers,
 * the image the local stack runs) each discover the other, and each serves the peer over its own
 * {@code /api/v1/peers} with the endpoints Shape A federation needs.
 *
 * <p>This exercises the whole vertical slice the service exists for - announce heartbeat, mesh
 * subscription, peer registry, health rollup, and both REST operations - which no unit test can,
 * because discovery depends on how the broker actually routes the announce address.
 *
 * <p>Both gateways run in one process with their configuration supplied directly rather than read from
 * the environment, since a single JVM has only one environment and they must have distinct cluster ids
 * to see each other as peers at all.
 */
@ExtendWith(VertxExtension.class)
class MeshGatewayServiceIT {

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

    // Singleton container: started once for the suite, stopped after. The suppression matches the
    // sibling suites - the Testcontainers stop() lifecycle is handled deterministically in @AfterAll.
    @SuppressWarnings("resource")
    private static final GenericContainer<?> ARTEMIS = new GenericContainer<>(IMAGE)
            .withEnv("ARTEMIS_USER", "artemis")
            .withEnv("ARTEMIS_PASSWORD", "artemis")
            .withExposedPorts(AMQP_PORT)
            .waitingFor(Wait.forListeningPort());

    private Vertx vertx;
    private WebClient client;

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
            client.close();
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    /** A gateway configuration for one cluster, pointed at the shared test broker. */
    private static MeshGatewayConfig configFor(String cluster, String region) {
        return configFor(cluster, region, List.of());
    }

    /**
     * As above, naming the infrastructure this baseline reports on.
     *
     * @param cluster    this cluster's id.
     * @param region     this cluster's region label.
     * @param components the infrastructure targets to probe.
     * @return the gateway configuration.
     */
    private static MeshGatewayConfig configFor(String cluster, String region, List<InfrastructureTarget> components) {
        return new MeshGatewayConfig(
                cluster,
                region,
                "1.0.0",
                "https://" + cluster + ".console:3000",
                "https://" + cluster + ".svc:8080/api/v1",
                "tcp://" + ARTEMIS.getHost() + ":" + ARTEMIS.getMappedPort(AMQP_PORT),
                "artemis",
                "artemis",
                // Nothing to watch: the rollup is exercised directly by ClusterHealthServiceTest, and a
                // real service here would only add moving parts to a discovery test.
                Map.of(),
                components,
                // A brisk heartbeat so discovery settles quickly; the cadence itself is not under test.
                Duration.ofMillis(500),
                Duration.ofSeconds(30));
    }

    /** Polls a gateway's /api/v1/peers until the wanted peer appears, so async delivery is awaited. */
    private JsonObject awaitPeer(int port, String clusterId) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
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
     * Two gateways announcing on one broker each end up serving the other from /api/v1/peers, carrying
     * the console and API endpoints that make Shape A federation work, and neither lists itself despite
     * hearing its own multicast announcements.
     */
    @Test
    void twoGatewaysDiscoverEachOtherOverTheirRestApi(Vertx testVertx) throws Exception {
        vertx = testVertx;
        client = REALM.operatorClient(vertx);

        var west = new MeshGatewayVerticle(configFor("hub-west", "us-west"), 0, REALM.realmUrl());
        var east = new MeshGatewayVerticle(configFor("hub-east", "us-east"), 0, REALM.realmUrl());
        await(vertx.deployVerticle(west));
        await(vertx.deployVerticle(east));

        var eastAsSeenByWest = awaitPeer(west.actualPort(), "hub-east");
        assertEquals("us-east", eastAsSeenByWest.getString("region"));
        assertEquals("1.0.0", eastAsSeenByWest.getString("baselineVersion"));
        assertEquals("https://hub-east.console:3000", eastAsSeenByWest.getString("consoleUrl"));
        assertEquals("https://hub-east.svc:8080/api/v1", eastAsSeenByWest.getString("apiBaseUrl"));
        assertEquals("REACHABLE", eastAsSeenByWest.getString("reachability"));
        assertTrue(eastAsSeenByWest.getString("lastSeen") != null, "liveness is recorded on receipt");

        var westAsSeenByEast = awaitPeer(east.actualPort(), "hub-west");
        assertEquals("us-west", westAsSeenByEast.getString("region"));
        assertEquals("https://hub-west.console:3000", westAsSeenByEast.getString("consoleUrl"));

        // Neither cluster is its own peer, however many of its own announcements it hears back.
        var westPeers = await(client.get(west.actualPort(), "localhost", "/api/v1/peers")
                        .send())
                .bodyAsJsonObject()
                .getJsonArray("data");
        assertEquals(1, westPeers.size(), "only the other cluster, never itself");
        assertEquals("hub-east", westPeers.getJsonObject(0).getString("clusterId"));
    }

    /**
     * The baseline endpoint reports this cluster's own identity and health, with the standard envelope.
     * With nothing configured to watch, the rollup is ready and the only service listed is the gateway
     * itself - which is running, whatever a watch list does or does not name.
     */
    @Test
    void baselineReportsThisClustersIdentityAndHealth(Vertx testVertx) throws Exception {
        vertx = testVertx;
        client = REALM.operatorClient(vertx);

        var west = new MeshGatewayVerticle(configFor("hub-west", "us-west"), 0, REALM.realmUrl());
        await(vertx.deployVerticle(west));

        HttpResponse<Buffer> response = await(
                client.get(west.actualPort(), "localhost", "/api/v1/baseline").send());

        assertEquals(200, response.statusCode());
        var body = response.bodyAsJsonObject();
        var data = body.getJsonObject("data");
        assertEquals("hub-west", data.getString("clusterId"));
        assertEquals("us-west", data.getString("region"));
        assertEquals("1.0.0", data.getString("baselineVersion"));
        assertEquals("ready", data.getString("health"));
        var services = data.getJsonArray("services");
        assertEquals(1, services.size(), "the gateway lists itself even with nothing else configured");
        assertEquals("mesh-gateway", services.getJsonObject(0).getString("name"));
        assertTrue(data.getJsonArray("infrastructure").isEmpty(), "nothing configured to probe");
        assertEquals("v1", body.getJsonObject("meta").getString("apiVersion"));
    }

    /**
     * A baseline whose infrastructure cannot be reached reports every component DOWN and keeps
     * serving. This is the incident the reporting exists for: the gateway must stay answerable during
     * exactly the outage an operator is trying to look at, and the failure must arrive as a state on
     * the row rather than as an error that breaks the poll.
     *
     * <p>Read through the served endpoint rather than the poll, so the whole path is exercised: the
     * heartbeat's fan-out, the cached rollup, and the response envelope.
     */
    @Test
    void baselineReportsUnreachableInfrastructureWithoutFailing(Vertx testVertx) throws Exception {
        vertx = testVertx;
        client = REALM.operatorClient(vertx);

        // A port nothing listens on, so both probed components refuse the connection.
        var dead = "http://127.0.0.1:1";
        var config = configFor(
                "hub-west",
                "us-west",
                List.of(
                        new InfrastructureTarget("datastore", ComponentKind.ELASTICSEARCH, dead),
                        new InfrastructureTarget("broker", ComponentKind.ARTEMIS, ""),
                        new InfrastructureTarget("identity", ComponentKind.KEYCLOAK, dead)));
        var west = new MeshGatewayVerticle(config, 0, REALM.realmUrl());
        await(vertx.deployVerticle(west));

        var infrastructure = awaitInfrastructure(west.actualPort());

        assertEquals(3, infrastructure.size());
        assertEquals("datastore", infrastructure.getJsonObject(0).getString("name"));
        assertEquals("elasticsearch", infrastructure.getJsonObject(0).getString("kind"));
        assertEquals("DOWN", infrastructure.getJsonObject(0).getString("status"));
        assertTrue(infrastructure.getJsonObject(0).getString("detail") != null, "the failure is named");
        // The broker is real and reachable here, so the Artemis row renders the held mesh link as UP.
        assertEquals("UP", infrastructure.getJsonObject(1).getString("status"));
        assertEquals("DOWN", infrastructure.getJsonObject(2).getString("status"));
    }

    /** Polls a gateway's baseline until the first infrastructure poll has landed in the cached rollup. */
    private JsonArray awaitInfrastructure(int port) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            HttpResponse<Buffer> response =
                    await(client.get(port, "localhost", "/api/v1/baseline").send());
            assertEquals(200, response.statusCode(), "the gateway keeps serving while its infrastructure is down");
            var infrastructure =
                    response.bodyAsJsonObject().getJsonObject("data").getJsonArray("infrastructure");
            if (!infrastructure.isEmpty()) {
                return infrastructure;
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new AssertionError("gateway on port " + port + " never reported its infrastructure");
    }

    /** Before any peer announces, the peer list is an empty array rather than an error or null. */
    @Test
    void peersIsEmptyBeforeAnyPeerAnnounces(Vertx testVertx) throws Exception {
        vertx = testVertx;
        client = REALM.operatorClient(vertx);

        var lone = new MeshGatewayVerticle(configFor("hub-lonely", "us-west"), 0, REALM.realmUrl());
        await(vertx.deployVerticle(lone));

        HttpResponse<Buffer> response = await(
                client.get(lone.actualPort(), "localhost", "/api/v1/peers").send());

        assertEquals(200, response.statusCode());
        assertTrue(response.bodyAsJsonObject().getJsonArray("data").isEmpty());
    }
}
