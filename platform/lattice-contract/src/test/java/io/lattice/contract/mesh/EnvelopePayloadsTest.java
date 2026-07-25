package io.lattice.contract.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the MVP mesh envelope payload type (ClusterAnnouncement)
 * per mesh_envelopes.md + locked decision 31. The payload round-trips through
 * JSON and declares its type name + schema version; the announce factory stamps
 * the header. Under Shape A federation the mesh carries discovery only, so
 * ClusterAnnouncement is the single envelope type.
 */
class EnvelopePayloadsTest {

    private static final Instant WHEN = Instant.parse("2026-07-22T20:00:00Z");

    private static ClusterAnnouncement announcement() {
        return new ClusterAnnouncement(
                "hub-west", "us-west", "1.0.0", "ready", "https://west.console:3000", "https://west.svc:8080/api/v1");
    }

    /** A ClusterAnnouncement round-trips through JSON and exposes its type name + schema version. */
    @Test
    void clusterAnnouncementRoundTrips() {
        var a = announcement();
        var restored = ClusterAnnouncement.fromJson(new JsonObject(a.toJson().encode()));
        assertEquals(a, restored);
        assertEquals("ClusterAnnouncement", ClusterAnnouncement.TYPE);
        assertEquals(1, ClusterAnnouncement.SCHEMA_VERSION);
    }

    /**
     * The announcement advertises both Shape A endpoints separately: {@code consoleUrl} is where an
     * operator is redirected to act on that baseline, {@code apiBaseUrl} is where the unified view
     * live-pulls its status. They are distinct addresses, so a single combined endpoint cannot serve
     * both roles.
     */
    @Test
    void clusterAnnouncementCarriesBothShapeAEndpoints() {
        var a = announcement();
        assertEquals("https://west.console:3000", a.consoleUrl());
        assertEquals("https://west.svc:8080/api/v1", a.apiBaseUrl());

        var json = a.toJson();
        assertEquals("https://west.console:3000", json.getString("consoleUrl"));
        assertEquals("https://west.svc:8080/api/v1", json.getString("apiBaseUrl"));
    }

    /**
     * An announcement from a newer baseline that added an optional field is still parsed: unknown
     * fields are ignored rather than rejected, which is the additive-compatibility rule that lets
     * peers on different baselines keep discovering each other.
     */
    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        var fromNewerPeer = announcement().toJson().put("someFieldAddedLater", "value");
        var parsed = ClusterAnnouncement.fromJson(fromNewerPeer);
        assertEquals(announcement(), parsed);
    }

    /** The announce factory stamps the header with the announcement's type + version and no correlationId. */
    @Test
    void announcementEnvelopeFactoryStampsHeader() {
        var env = MeshEnvelope.announce("m-1", "hub-west", WHEN, announcement());

        assertEquals("ClusterAnnouncement", env.type());
        assertEquals(1, env.schemaVersion());
        assertEquals("hub-west", env.sourceClusterId());
        assertNull(env.correlationId());
        assertEquals("hub-west", ClusterAnnouncement.fromJson(env.payload()).clusterId());
    }
}
