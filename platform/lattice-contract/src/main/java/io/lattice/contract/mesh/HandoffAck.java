package io.lattice.contract.mesh;

import io.vertx.core.json.JsonObject;

/**
 * The payload a hub returns to acknowledge a {@link FulfillmentHandoff}, carried in a
 * {@link MeshEnvelope} of {@link #TYPE} back to the requesting hub's inbox.
 *
 * <p>The envelope's {@code correlationId} links the ack to the handoff's {@code messageId}
 * (see {@link MeshEnvelope#ack}); a rejection carries a human-readable reason.
 *
 * @param outcome whether the peer accepted or rejected the handoff.
 * @param reason  a short reason when {@code outcome} is {@link HandoffOutcome#REJECTED}; null otherwise.
 */
public record HandoffAck(HandoffOutcome outcome, String reason) {

  /** The {@link MeshEnvelope#type()} value for this payload. */
  public static final String TYPE = "HandoffAck";

  /** The current payload schema version (see the envelope versioning rule). */
  public static final int SCHEMA_VERSION = 1;

  /**
   * Serializes this ack to a JSON object for an envelope payload.
   *
   * @return the JSON representation.
   */
  public JsonObject toJson() {
    return new JsonObject()
        .put("outcome", outcome.name())
        .put("reason", reason);
  }

  /**
   * Parses an ack from an envelope payload, ignoring any unknown fields a newer baseline may
   * have added (forward-compatibility).
   *
   * @param json the payload JSON.
   * @return the parsed ack.
   */
  public static HandoffAck fromJson(JsonObject json) {
    return new HandoffAck(HandoffOutcome.valueOf(json.getString("outcome")), json.getString("reason"));
  }
}
