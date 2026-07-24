package io.lattice.contract.mesh;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.Objects;

/**
 * The self-describing wrapper every mesh message carries: a common header plus a typed
 * {@code payload}. It is the single-writer wire contract every cluster agrees on for
 * cross-cluster interop over Artemis (see the mesh_envelopes design + locked decision 31).
 *
 * <p>Serialized as JSON on the wire via Vert.x's built-in JSON. Versioning is
 * additive-compatible: a consumer ignores unknown fields; a breaking change bumps the
 * payload's {@code schemaVersion} rather than rewriting a version in place.
 *
 * @param messageId       unique per message; the idempotency / dedup key.
 * @param type            the payload type name (e.g. {@link ClusterAnnouncement#TYPE}).
 * @param schemaVersion   the payload's schema version.
 * @param sourceClusterId the originating cluster's id (also how a reply is routed back).
 * @param occurredAt      the event time, always UTC.
 * @param correlationId   ties a reply to its request (an ack sets it to the request's messageId); null otherwise.
 * @param payload         the typed body, its shape selected by {@code type} + {@code schemaVersion}.
 */
public record MeshEnvelope(
        String messageId,
        String type,
        int schemaVersion,
        String sourceClusterId,
        Instant occurredAt,
        String correlationId,
        JsonObject payload) {

    /**
     * Canonical constructor that defensively copies the mutable {@code payload} so the envelope
     * is effectively immutable (no caller can mutate the stored payload after construction).
     */
    public MeshEnvelope {
        payload = Objects.requireNonNull(payload, "payload must not be null").copy();
    }

    /**
     * Returns a defensive copy of the payload, keeping the envelope immutable.
     *
     * @return a copy of the payload.
     */
    @Override
    public JsonObject payload() {
        return payload.copy();
    }

    /**
     * Serializes this envelope to a JSON object (the Artemis message body). {@code occurredAt}
     * is encoded as an ISO-8601 UTC string by Vert.x JSON.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        return new JsonObject()
                .put("messageId", messageId)
                .put("type", type)
                .put("schemaVersion", schemaVersion)
                .put("sourceClusterId", sourceClusterId)
                .put("occurredAt", occurredAt)
                .put("correlationId", correlationId)
                .put("payload", payload());
    }

    /**
     * Parses an envelope from JSON, reading only known header fields so that unknown fields a
     * newer baseline may have added are ignored (forward-compatibility).
     *
     * @param json the envelope JSON.
     * @return the parsed envelope.
     */
    public static MeshEnvelope fromJson(JsonObject json) {
        return new MeshEnvelope(
                json.getString("messageId"),
                json.getString("type"),
                json.getInteger("schemaVersion"),
                json.getString("sourceClusterId"),
                json.getInstant("occurredAt"),
                json.getString("correlationId"),
                json.getJsonObject("payload"));
    }

    /**
     * Wraps a {@link ClusterAnnouncement} in an envelope, stamping its type + schema version.
     *
     * @param messageId       unique message id.
     * @param sourceClusterId the announcing cluster.
     * @param occurredAt      the event time (UTC).
     * @param announcement    the announcement payload.
     * @return the announce envelope (no correlationId - it is unsolicited).
     */
    public static MeshEnvelope announce(
            String messageId, String sourceClusterId, Instant occurredAt, ClusterAnnouncement announcement) {
        return new MeshEnvelope(
                messageId,
                ClusterAnnouncement.TYPE,
                ClusterAnnouncement.SCHEMA_VERSION,
                sourceClusterId,
                occurredAt,
                null,
                announcement.toJson());
    }
}
