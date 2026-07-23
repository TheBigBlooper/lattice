package io.lattice.contract.mesh;

import io.vertx.core.json.JsonObject;
import java.time.Instant;

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
        .put("payload", payload);
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
        messageId, ClusterAnnouncement.TYPE, ClusterAnnouncement.SCHEMA_VERSION,
        sourceClusterId, occurredAt, null, announcement.toJson());
  }

  /**
   * Wraps a {@link FulfillmentHandoff} in an envelope, stamping its type + schema version.
   *
   * @param messageId       unique message id (also the ack's correlationId).
   * @param sourceClusterId the requesting cluster.
   * @param occurredAt      the event time (UTC).
   * @param handoff         the handoff payload.
   * @return the handoff envelope (no correlationId - it originates a request).
   */
  public static MeshEnvelope handoff(
      String messageId, String sourceClusterId, Instant occurredAt, FulfillmentHandoff handoff) {
    return new MeshEnvelope(
        messageId, FulfillmentHandoff.TYPE, FulfillmentHandoff.SCHEMA_VERSION,
        sourceClusterId, occurredAt, null, handoff.toJson());
  }

  /**
   * Wraps a {@link HandoffAck} in an envelope, stamping its type + schema version and linking
   * it to the handoff it answers.
   *
   * @param messageId        unique message id for this ack.
   * @param sourceClusterId  the acknowledging cluster.
   * @param occurredAt       the event time (UTC).
   * @param correlationId    the answered handoff's messageId.
   * @param ack              the ack payload.
   * @return the ack envelope.
   */
  public static MeshEnvelope ack(
      String messageId, String sourceClusterId, Instant occurredAt, String correlationId, HandoffAck ack) {
    return new MeshEnvelope(
        messageId, HandoffAck.TYPE, HandoffAck.SCHEMA_VERSION,
        sourceClusterId, occurredAt, correlationId, ack.toJson());
  }
}
