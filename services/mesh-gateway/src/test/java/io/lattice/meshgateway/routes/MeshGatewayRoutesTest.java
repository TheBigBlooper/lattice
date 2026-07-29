package io.lattice.meshgateway.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.mesh.PeerRegistry;
import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.contract.mesh.ComponentHealth;
import io.lattice.contract.mesh.ComponentKind;
import io.lattice.contract.mesh.ComponentStatus;
import io.lattice.contract.mesh.MeshLinkState;
import io.lattice.contract.mesh.ServiceHealth;
import io.lattice.meshgateway.MeshGatewayConfig;
import io.lattice.meshgateway.service.ClusterHealthService.ClusterHealth;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the exact JSON the mesh-gateway puts on the wire.
 *
 * <p>The response bodies are built by pure functions of the registry and the last health rollup, so
 * the wire shape is pinned here without standing up a router; the routing itself is exercised end to
 * end against the real OpenAPI-validated router by {@code MeshGatewayServiceIT}.
 */
class MeshGatewayRoutesTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    private static MeshGatewayConfig config() {
        return new MeshGatewayConfig(
                "hub-west",
                "us-west",
                "1.0.0",
                "https://west.console:3000",
                "https://west.svc:8080/api/v1",
                "tcp://localhost:61616",
                "artemis",
                "artemis",
                Map.of(),
                List.of(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }

    private static ClusterAnnouncement eastAnnouncement() {
        return new ClusterAnnouncement(
                "hub-east",
                "us-east",
                "1.0.0",
                "degraded",
                "https://east.console:3000",
                "https://east.svc:8080/api/v1");
    }

    /** A discovered peer is serialized with both Shape A endpoints, its health, and its liveness. */
    @Test
    void serializesADiscoveredPeerWithItsEndpoints() {
        var registry = new PeerRegistry("hub-west", Duration.ofSeconds(30), Clock.fixed(NOW, ZoneOffset.UTC));
        registry.record(eastAnnouncement());

        var payload = MeshGatewayRoutes.peersPayload(registry);

        var peers = payload.getJsonArray("data");
        assertEquals(1, peers.size());
        var peer = peers.getJsonObject(0);
        assertEquals("hub-east", peer.getString("clusterId"));
        assertEquals("us-east", peer.getString("region"));
        assertEquals("degraded", peer.getString("health"));
        assertEquals("https://east.console:3000", peer.getString("consoleUrl"));
        assertEquals("https://east.svc:8080/api/v1", peer.getString("apiBaseUrl"));
        assertEquals("REACHABLE", peer.getString("reachability"));
        assertEquals(NOW.toString(), peer.getString("lastSeen"));
        assertEquals("v1", payload.getJsonObject("meta").getString("apiVersion"));
    }

    /**
     * A peer past its liveness window is still served, marked UNREACHABLE with its last-known detail
     * intact - the console must be able to show that a baseline was here and has gone silent, rather
     * than a row quietly disappearing.
     */
    @Test
    void retainsASilentPeerAsUnreachable() {
        var clock = new AdvanceableClock(NOW);
        var registry = new PeerRegistry("hub-west", Duration.ofSeconds(30), clock);
        registry.record(eastAnnouncement());
        clock.advance(Duration.ofSeconds(120));

        var peers = MeshGatewayRoutes.peersPayload(registry).getJsonArray("data");

        assertEquals(1, peers.size(), "an unreachable peer is retained, not dropped");
        var peer = peers.getJsonObject(0);
        assertEquals("UNREACHABLE", peer.getString("reachability"));
        assertEquals("https://east.console:3000", peer.getString("consoleUrl"), "last-known detail is kept");
        assertEquals(NOW.toString(), peer.getString("lastSeen"), "still reports when it was last heard");
    }

    /** With nothing discovered the peer list is an empty array, never null or an error. */
    @Test
    void servesAnEmptyListBeforeAnyPeerAnnounces() {
        var registry = new PeerRegistry("hub-west", Duration.ofSeconds(30), Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(MeshGatewayRoutes.peersPayload(registry).getJsonArray("data").isEmpty());
    }

    /**
     * The infrastructure breakdown is served beside the per-service one, each component carrying its
     * kind, its coarse state, and - when there is something to say - its own reading in its own
     * vocabulary. A healthy component carries no detail at all rather than a null one.
     */
    @Test
    void serializesTheInfrastructureBreakdownBesideTheServices() {
        var rollup = new ClusterHealth(
                "ready",
                List.of(new ServiceHealth("orders", "UP")),
                List.of(
                        new ComponentHealth(
                                "datastore",
                                ComponentKind.ELASTICSEARCH,
                                ComponentStatus.DEGRADED,
                                "cluster status yellow"),
                        new ComponentHealth("broker", ComponentKind.ARTEMIS, ComponentStatus.UP, null),
                        new ComponentHealth(
                                "identity",
                                ComponentKind.KEYCLOAK,
                                ComponentStatus.DOWN,
                                "management endpoint unreachable")));

        var data = MeshGatewayRoutes.baselinePayload(config(), rollup, MeshLinkState.UP)
                .getJsonObject("data");

        var infrastructure = data.getJsonArray("infrastructure");
        assertEquals(3, infrastructure.size());
        assertEquals("datastore", infrastructure.getJsonObject(0).getString("name"));
        assertEquals("elasticsearch", infrastructure.getJsonObject(0).getString("kind"));
        assertEquals("DEGRADED", infrastructure.getJsonObject(0).getString("status"));
        assertEquals("cluster status yellow", infrastructure.getJsonObject(0).getString("detail"));
        assertEquals("UP", infrastructure.getJsonObject(1).getString("status"));
        assertTrue(!infrastructure.getJsonObject(1).containsKey("detail"), "nothing to add is omitted, not null");
        assertEquals("DOWN", infrastructure.getJsonObject(2).getString("status"));
        // The services rollup is what rides the mesh, and infrastructure never enters it (#43, #66).
        assertEquals("ready", data.getString("health"));
    }

    /** With nothing configured the infrastructure array is empty rather than absent or an error. */
    @Test
    void servesAnEmptyInfrastructureArrayWhenNoneIsConfigured() {
        var rollup = new ClusterHealth("ready", List.of());

        var data = MeshGatewayRoutes.baselinePayload(config(), rollup, MeshLinkState.UP)
                .getJsonObject("data");

        assertTrue(data.getJsonArray("infrastructure").isEmpty());
    }

    /** The baseline carries this cluster's identity plus the rollup and its per-service breakdown. */
    @Test
    void serializesTheBaselineWithHealthAndBreakdown() {
        var rollup = new ClusterHealth(
                "degraded", List.of(new ServiceHealth("orders", "UP"), new ServiceHealth("inventory", "DOWN")));

        var data = MeshGatewayRoutes.baselinePayload(config(), rollup, MeshLinkState.UP)
                .getJsonObject("data");

        assertEquals("hub-west", data.getString("clusterId"));
        assertEquals("us-west", data.getString("region"));
        assertEquals("1.0.0", data.getString("baselineVersion"));
        assertEquals("degraded", data.getString("health"));
        assertEquals(List.of("v1"), data.getJsonArray("apiVersions").getList());
        assertEquals("orders", data.getJsonArray("services").getJsonObject(0).getString("name"));
        assertEquals("DOWN", data.getJsonArray("services").getJsonObject(1).getString("status"));
    }

    /**
     * A cut-off baseline says so, rather than letting its registry speak for the mesh.
     *
     * <p>When the broker link drops this cluster hears nothing, so every peer ages to unreachable at
     * once - which renders as a total mesh outage when the truth is that this one baseline is
     * isolated. Locked #46: the gateway knows its own connection state, so it reports it here, on
     * this baseline own API, and never on the mesh.
     */
    @Test
    void serializesTheBaselineMeshLinkStateWhenCutOff() {
        var rollup = new ClusterHealth("ready", List.of());

        var data = MeshGatewayRoutes.baselinePayload(config(), rollup, MeshLinkState.DOWN)
                .getJsonObject("data");

        assertEquals("down", data.getString("meshLink"));
        // The rollup is this cluster own services, which a broken mesh link does not touch (#43).
        assertEquals("ready", data.getString("health"));
    }
    /** A clock the test advances by hand, so liveness expiry is exercised without sleeping. */
    private static final class AdvanceableClock extends Clock {

        private Instant now;

        AdvanceableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
