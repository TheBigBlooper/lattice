package io.lattice.contract.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * A peer carries the state of this broker's federation link to it, which is a different fact
     * from whether the peer has been heard from: a link can be dead while the peer is still within
     * its liveness window, which is the whole reason the state is reported separately.
     */
    @Test
    void peerCarriesItsFederationLinkState() {
        var refused = peer().withFederation(FederationState.REFUSED);

        var restored = Peer.fromJson(new JsonObject(refused.toJson().encode()));

        assertEquals(FederationState.REFUSED, restored.federation());
        assertEquals("REACHABLE", restored.reachability(), "the two states are independent");
    }

    /**
     * An absent federation state is omitted rather than sent as a null or a default, so a baseline
     * that cannot read it says nothing instead of claiming the link is healthy.
     */
    @Test
    void peerOmitsAnAbsentFederationState() {
        var json = peer().toJson();

        assertNull(peer().federation());
        assertFalse(json.containsKey("federation"), "absent means absent, never a defaulted up");
    }

    /**
     * Federation state is lowercase on the wire, matching the mesh-link state beside it rather than
     * the uppercase reachability it sits next to.
     */
    @Test
    void federationStateIsLowercaseOnTheWire() {
        assertEquals("up", FederationState.UP.wire());
        assertEquals("down", FederationState.DOWN.wire());
        assertEquals("refused", FederationState.REFUSED.wire());
        assertEquals(
                "refused",
                peer().withFederation(FederationState.REFUSED).toJson().getString("federation"));
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
                List.of(new ServiceHealth("orders", "UP"), new ServiceHealth("inventory", "DOWN")),
                List.of(),
                MeshLinkState.UP);

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
        var baseline = new Baseline(
                "hub-west", "us-west", "1.0.0", List.of("v1"), "ready", List.of(), List.of(), MeshLinkState.UP);

        assertTrue(baseline.toJson().getJsonArray("services").isEmpty());
    }

    /**
     * The baseline reports whether its own mesh link is up (locked #46).
     *
     * <p>Without it a broker outage is indistinguishable from a total peer outage: the registry ages
     * <em>every</em> peer to unreachable at once, because a cluster that hears nothing cannot tell
     * silence from absence. "We are cut off" and "they are gone" are different incidents with
     * different responses, and an operator must not have to infer which one they are in from the
     * fact that everything went quiet simultaneously.
     */
    @Test
    void baselineReportsItsOwnMeshLinkState() {
        var cutOff = new Baseline(
                "hub-west", "us-west", "1.0.0", List.of("v1"), "ready", List.of(), List.of(), MeshLinkState.DOWN);

        assertEquals("down", cutOff.toJson().getString("meshLink"));
    }

    /**
     * The link state is lowercase on the wire, matching {@code health} rather than {@code services}.
     * Both are baseline-level labels on this same object; the per-service {@code UP}/{@code DOWN}
     * comes from the readiness probes and keeps their vocabulary.
     */
    @Test
    void meshLinkStateIsLowercaseOnTheWire() {
        assertEquals("up", MeshLinkState.UP.wire());
        assertEquals("down", MeshLinkState.DOWN.wire());
    }

    /**
     * An infrastructure component serializes its label, its kind and its coarse state, and carries the
     * component's own reading in {@code detail} when there is one to give. The detail line is what an
     * operator reads to learn why a component is unhappy, in the vocabulary of the system it came from
     * rather than translated into this contract's three words.
     */
    @Test
    void componentHealthCarriesItsKindStatusAndDetail() {
        var keycloak = new ComponentHealth(
                "keycloak", ComponentKind.KEYCLOAK, ComponentStatus.DEGRADED, "management endpoint returned 503");

        var json = keycloak.toJson();
        assertEquals("keycloak", json.getString("name"));
        assertEquals("keycloak", json.getString("kind"));
        assertEquals("DEGRADED", json.getString("status"));
        assertEquals("management endpoint returned 503", json.getString("detail"));
    }

    /**
     * A healthy component has nothing to add, so {@code detail} is omitted from the JSON entirely
     * rather than serialized as an explicit null. An optional field that is absent and one that is
     * present-but-null are different things to a reader, and only the first is what "no detail" means.
     */
    @Test
    void componentHealthOmitsAnAbsentDetail() {
        var json = new ComponentHealth("elasticsearch", ComponentKind.ELASTICSEARCH, ComponentStatus.UP, null).toJson();

        assertEquals("UP", json.getString("status"));
        assertFalse(json.containsKey("detail"), "an absent detail is omitted, not serialized as null");
    }

    /**
     * A component's kind is lowercase on the wire while its status stays uppercase. The kind is a
     * baseline-level label like {@code health} and {@code meshLink}; the status keeps the vocabulary of
     * the per-service {@code UP} / {@code DOWN} it sits beside, widened only by {@code DEGRADED}.
     */
    @Test
    void componentKindIsLowercaseAndStatusUppercaseOnTheWire() {
        assertEquals("elasticsearch", ComponentKind.ELASTICSEARCH.wire());
        assertEquals("artemis", ComponentKind.ARTEMIS.wire());
        assertEquals("keycloak", ComponentKind.KEYCLOAK.wire());

        assertEquals("UP", ComponentStatus.UP.wire());
        assertEquals("DEGRADED", ComponentStatus.DEGRADED.wire());
        assertEquals("DOWN", ComponentStatus.DOWN.wire());
    }

    /**
     * The baseline carries an infrastructure breakdown as a sibling of the services one (locked #66).
     * The two groups answer the same question about different things and speak different vocabularies,
     * so they stay separate arrays rather than one tagged list, and the services array is untouched.
     */
    @Test
    void baselineCarriesAnInfrastructureBreakdownBesideItsServices() {
        var baseline = new Baseline(
                "hub-west",
                "us-west",
                "1.0.0",
                List.of("v1"),
                "ready",
                List.of(new ServiceHealth("orders", "UP")),
                List.of(
                        new ComponentHealth("elasticsearch", ComponentKind.ELASTICSEARCH, ComponentStatus.UP, null),
                        new ComponentHealth("artemis", ComponentKind.ARTEMIS, ComponentStatus.DOWN, null),
                        new ComponentHealth(
                                "identity", ComponentKind.KEYCLOAK, ComponentStatus.DEGRADED, "status was STARTING")),
                MeshLinkState.UP);

        var json = baseline.toJson();
        assertEquals(1, json.getJsonArray("services").size(), "the services array is unchanged");

        var infrastructure = json.getJsonArray("infrastructure");
        assertEquals(3, infrastructure.size());
        assertEquals("elasticsearch", infrastructure.getJsonObject(0).getString("kind"));
        assertEquals("DOWN", infrastructure.getJsonObject(1).getString("status"));
        assertEquals(
                "identity",
                infrastructure.getJsonObject(2).getString("name"),
                "a deployment may label a component whatever it calls it, since kind is explicit");
        assertEquals("status was STARTING", infrastructure.getJsonObject(2).getString("detail"));
    }

    /**
     * A baseline with no infrastructure configured still serves a valid shape, with an empty array. An
     * unconfigured baseline is a supported deployment rather than a fault, so the shape must tolerate
     * it the same way it already tolerates an empty service breakdown.
     */
    @Test
    void baselineToleratesAnEmptyInfrastructureBreakdown() {
        var baseline = new Baseline(
                "hub-west", "us-west", "1.0.0", List.of("v1"), "ready", List.of(), List.of(), MeshLinkState.UP);

        assertTrue(baseline.toJson().getJsonArray("infrastructure").isEmpty());
    }
}
