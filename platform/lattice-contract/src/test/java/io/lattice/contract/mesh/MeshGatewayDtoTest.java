package io.lattice.contract.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the mesh-gateway's REST shapes: the discovered {@link Peer} the status console
 * renders, and the {@link Baseline} identity plus health a cluster reports about itself.
 *
 * <p>These are the wire shapes, distinct from the internal registry type - a peer's REST view carries
 * its reachability and last-seen for the console, which the mesh envelope never does.
 */
class MeshGatewayDtoTest {

    private static Peer peer() {
        return new Peer(
                "hub-east",
                "us-east",
                "1.0.0",
                "ready",
                "https://east.console:3000",
                "https://east.svc:8080/api/v1",
                "2026-07-25T00:00:00Z",
                "REACHABLE");
    }

    /** A peer round-trips through JSON with both Shape A endpoints and its liveness fields intact. */
    @Test
    void peerRoundTrips() {
        var restored = Peer.fromJson(new JsonObject(peer().toJson().encode()));

        assertEquals(peer(), restored);
        assertEquals("https://east.console:3000", restored.consoleUrl());
        assertEquals("https://east.svc:8080/api/v1", restored.apiBaseUrl());
        assertEquals("REACHABLE", restored.reachability());
    }

    /**
     * An unreachable peer keeps its last-known detail rather than being blanked, which is what lets
     * the console show "this baseline was here and has gone silent" instead of a peer vanishing.
     */
    @Test
    void anUnreachablePeerRetainsItsLastKnownDetail() {
        var silent = new Peer(
                "hub-east",
                "us-east",
                "1.0.0",
                "ready",
                "https://east.console:3000",
                "https://east.svc:8080/api/v1",
                "2026-07-25T00:00:00Z",
                "UNREACHABLE");

        var json = silent.toJson();
        assertEquals("UNREACHABLE", json.getString("reachability"));
        assertEquals("https://east.console:3000", json.getString("consoleUrl"), "last-known endpoint is kept");
        assertEquals("2026-07-25T00:00:00Z", json.getString("lastSeen"));
    }

    /** The baseline carries this cluster's identity plus its rollup and the per-service breakdown. */
    @Test
    void baselineCarriesHealthAndServiceBreakdown() {
        var baseline = new Baseline(
                "hub-west",
                "us-west",
                "1.0.0",
                List.of("v1"),
                "degraded",
                List.of(new ServiceHealth("orders", "UP"), new ServiceHealth("inventory", "DOWN")));

        var json = baseline.toJson();
        assertEquals("degraded", json.getString("health"));
        assertEquals(List.of("v1"), json.getJsonArray("apiVersions").getList());

        var services = json.getJsonArray("services");
        assertEquals(2, services.size());
        assertEquals("orders", services.getJsonObject(0).getString("name"));
        assertEquals("DOWN", services.getJsonObject(1).getString("status"));
    }

    /** A baseline with nothing configured to watch still serves a valid shape, with an empty breakdown. */
    @Test
    void baselineToleratesAnEmptyServiceBreakdown() {
        var baseline = new Baseline("hub-west", "us-west", "1.0.0", List.of("v1"), "ready", List.of());

        assertTrue(baseline.toJson().getJsonArray("services").isEmpty());
    }
}
