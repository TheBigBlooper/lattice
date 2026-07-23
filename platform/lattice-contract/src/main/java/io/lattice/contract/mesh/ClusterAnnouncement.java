package io.lattice.contract.mesh;

import io.vertx.core.json.JsonObject;

/**
 * The payload a cluster broadcasts to announce its presence on the mesh, carried in a
 * {@link MeshEnvelope} of {@link #TYPE} to the multicast announce address.
 *
 * <p>Every peer that hears it adds/refreshes the cluster in its peer registry (see the
 * mesh_discovery design). Fields are the cross-cluster canonical form - a hub's local
 * detail never crosses the mesh.
 *
 * @param clusterId       the announcing cluster's stable id (e.g. {@code hub-west}).
 * @param region          the cluster's region label (e.g. {@code us-west}).
 * @param baselineVersion the versioned baseline the cluster is running.
 * @param health          the cluster's current health label (e.g. {@code ready}, {@code degraded}).
 * @param endpoint        the cluster's reachable endpoint for peers.
 */
public record ClusterAnnouncement(
        String clusterId, String region, String baselineVersion, String health, String endpoint) {

    /** The {@link MeshEnvelope#type()} value for this payload. */
    public static final String TYPE = "ClusterAnnouncement";

    /** The current payload schema version (see the envelope versioning rule). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Serializes this announcement to a JSON object for an envelope payload.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        return new JsonObject()
                .put("clusterId", clusterId)
                .put("region", region)
                .put("baselineVersion", baselineVersion)
                .put("health", health)
                .put("endpoint", endpoint);
    }

    /**
     * Parses an announcement from an envelope payload, ignoring any unknown fields a newer
     * baseline may have added (forward-compatibility).
     *
     * @param json the payload JSON.
     * @return the parsed announcement.
     */
    public static ClusterAnnouncement fromJson(JsonObject json) {
        return new ClusterAnnouncement(
                json.getString("clusterId"),
                json.getString("region"),
                json.getString("baselineVersion"),
                json.getString("health"),
                json.getString("endpoint"));
    }
}
