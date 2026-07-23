package io.lattice.contract.mesh;

import io.vertx.core.json.JsonObject;

/**
 * The payload one hub sends to hand a fulfilment it cannot serve to a peer hub, carried
 * in a {@link MeshEnvelope} of {@link #TYPE} to the target hub's inbox address.
 *
 * <p>The {@code subjectId} is the cluster-qualified cross-cluster reference
 * ({@code <clusterId>:<localId>}, e.g. {@code hub-west:order-123}); the receiving hub mints
 * its own local id and stores this as an origin reference (see the cluster_interop design).
 * Item detail is the canonical envelope form, not either hub's local order model.
 *
 * @param subjectId            the cluster-qualified subject reference ({@code <clusterId>:<localId>}).
 * @param requestingClusterId  the hub asking the peer to fulfil (where the ack returns).
 * @param itemSku              the item to fulfil.
 * @param quantity             the quantity requested.
 */
public record FulfillmentHandoff(String subjectId, String requestingClusterId, String itemSku, int quantity) {

    /** The {@link MeshEnvelope#type()} value for this payload. */
    public static final String TYPE = "FulfillmentHandoff";

    /** The current payload schema version (see the envelope versioning rule). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Serializes this handoff to a JSON object for an envelope payload.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        return new JsonObject()
                .put("subjectId", subjectId)
                .put("requestingClusterId", requestingClusterId)
                .put("itemSku", itemSku)
                .put("quantity", quantity);
    }

    /**
     * Parses a handoff from an envelope payload, ignoring any unknown fields a newer baseline
     * may have added (forward-compatibility).
     *
     * @param json the payload JSON.
     * @return the parsed handoff.
     */
    public static FulfillmentHandoff fromJson(JsonObject json) {
        return new FulfillmentHandoff(
                json.getString("subjectId"),
                json.getString("requestingClusterId"),
                json.getString("itemSku"),
                json.getInteger("quantity"));
    }
}
