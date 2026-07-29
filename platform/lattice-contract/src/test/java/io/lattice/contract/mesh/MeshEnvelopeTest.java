package io.lattice.contract.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the shared mesh envelope header (the wrapper every mesh
 * message carries). Pins serialization round-trip, UTC instant handling, the
 * nullable correlationId, and the forward-compatibility rule (unknown fields
 * are ignored on read) per the mesh_envelopes.md design + locked decision 31.
 */
class MeshEnvelopeTest {

    private static final Instant WHEN = Instant.parse("2026-07-22T20:00:00Z");

    /** Every header field survives a JSON encode/decode round-trip unchanged. */
    @Test
    void roundTripsThroughJsonPreservingEveryHeaderField() {
        var payload = new JsonObject().put("k", "v");
        var env = new MeshEnvelope("m-1", "ClusterAnnouncement", 1, "hub-west", WHEN, "c-1", payload);

        // to JSON text and back, the way an Artemis message body would travel.
        var restored = MeshEnvelope.fromJson(new JsonObject(env.toJson().encode()));

        assertEquals("m-1", restored.messageId());
        assertEquals("ClusterAnnouncement", restored.type());
        assertEquals(1, restored.schemaVersion());
        assertEquals("hub-west", restored.sourceClusterId());
        assertEquals(WHEN, restored.occurredAt());
        assertEquals("c-1", restored.correlationId());
        assertEquals("v", restored.payload().getString("k"));
    }

    /** {@code occurredAt} serializes as an ISO-8601 UTC string on the wire. */
    @Test
    void occurredAtIsEncodedAsIso8601Utc() {
        var env = new MeshEnvelope("m-1", "ClusterAnnouncement", 1, "hub-west", WHEN, null, new JsonObject());
        assertEquals("2026-07-22T20:00:00Z", env.toJson().getString("occurredAt"));
    }

    /** A null correlationId (an unsolicited message) round-trips as null. */
    @Test
    void correlationIdMayBeNull() {
        var env = new MeshEnvelope("m-1", "ClusterAnnouncement", 1, "hub-west", WHEN, null, new JsonObject());
        var restored = MeshEnvelope.fromJson(new JsonObject(env.toJson().encode()));
        assertNull(restored.correlationId());
    }

    /** Unknown header and payload fields from a newer baseline are ignored, not rejected (forward-compat). */
    @Test
    void ignoresUnknownFieldsOnRead_forwardCompatibility() {
        // An envelope from a newer baseline may carry header + payload fields this
        // version does not know. The versioning rule: ignore them, do not fail.
        var json = new JsonObject()
                .put("messageId", "m-1")
                .put("type", "ClusterAnnouncement")
                .put("schemaVersion", 1)
                .put("sourceClusterId", "hub-east")
                .put("occurredAt", "2026-07-22T20:00:00Z")
                .put("correlationId", (String) null)
                .put("payload", new JsonObject().put("known", "yes").put("newFutureField", 42))
                .put("someFutureHeaderField", "ignore me");

        var env = MeshEnvelope.fromJson(json);

        assertEquals("m-1", env.messageId());
        assertEquals("yes", env.payload().getString("known"));
        assertTrue(env.payload().containsKey("newFutureField"));
    }
}
