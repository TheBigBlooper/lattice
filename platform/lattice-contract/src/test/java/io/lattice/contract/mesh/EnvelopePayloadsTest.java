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

    /** A ClusterAnnouncement round-trips through JSON and exposes its type name + schema version. */
    @Test
    void clusterAnnouncementRoundTrips() {
        var a = new ClusterAnnouncement("hub-west", "us-west", "1.0.0", "ready", "https://west.svc:8080");
        var restored = ClusterAnnouncement.fromJson(new JsonObject(a.toJson().encode()));
        assertEquals(a, restored);
        assertEquals("ClusterAnnouncement", ClusterAnnouncement.TYPE);
        assertEquals(1, ClusterAnnouncement.SCHEMA_VERSION);
    }

    /** The announce factory stamps the header with the announcement's type + version and no correlationId. */
    @Test
    void announcementEnvelopeFactoryStampsHeader() {
        var a = new ClusterAnnouncement("hub-west", "us-west", "1.0.0", "ready", "https://west.svc:8080");
        var env = MeshEnvelope.announce("m-1", "hub-west", WHEN, a);

        assertEquals("ClusterAnnouncement", env.type());
        assertEquals(1, env.schemaVersion());
        assertEquals("hub-west", env.sourceClusterId());
        assertNull(env.correlationId());
        assertEquals("hub-west", ClusterAnnouncement.fromJson(env.payload()).clusterId());
    }
}
