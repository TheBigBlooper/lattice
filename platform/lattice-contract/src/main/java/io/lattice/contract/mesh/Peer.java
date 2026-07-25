package io.lattice.contract.mesh;

import io.vertx.core.json.JsonObject;

/**
 * A baseline this cluster has discovered on the mesh, as the status console reads it.
 *
 * <p>This is the REST view, distinct from both the {@link ClusterAnnouncement} that crosses the wire
 * and the registry's internal type: it adds the liveness the console renders ({@code lastSeen},
 * {@code reachability}), which the announcement itself never carries because each cluster derives
 * liveness from its own receive time rather than trusting a peer's clock.
 *
 * <p>An {@code UNREACHABLE} peer keeps its last-known detail rather than being blanked, so an
 * operator sees that a baseline was present and has gone silent instead of a row disappearing.
 *
 * @param clusterId       the peer's stable cluster id.
 * @param region          the peer's region label.
 * @param baselineVersion the baseline the peer last reported running.
 * @param health          the peer's last reported health rollup ({@code ready} / {@code degraded} /
 *                        {@code down}).
 * @param consoleUrl      the peer's own console root, where a federation redirect lands.
 * @param apiBaseUrl      the peer's REST API base, which the unified view reads live.
 * @param lastSeen        when this cluster last heard the peer announce, as an ISO-8601 UTC string.
 * @param reachability    {@code REACHABLE} or {@code UNREACHABLE}, against the liveness time-to-live.
 */
public record Peer(
        String clusterId,
        String region,
        String baselineVersion,
        String health,
        String consoleUrl,
        String apiBaseUrl,
        String lastSeen,
        String reachability) {

    /**
     * Serializes this peer to JSON for the response envelope.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        return new JsonObject()
                .put("clusterId", clusterId)
                .put("region", region)
                .put("baselineVersion", baselineVersion)
                .put("health", health)
                .put("consoleUrl", consoleUrl)
                .put("apiBaseUrl", apiBaseUrl)
                .put("lastSeen", lastSeen)
                .put("reachability", reachability);
    }

    /**
     * Parses a peer from JSON, ignoring unknown fields a newer baseline may have added.
     *
     * @param json the JSON to read.
     * @return the parsed peer.
     */
    public static Peer fromJson(JsonObject json) {
        return new Peer(
                json.getString("clusterId"),
                json.getString("region"),
                json.getString("baselineVersion"),
                json.getString("health"),
                json.getString("consoleUrl"),
                json.getString("apiBaseUrl"),
                json.getString("lastSeen"),
                json.getString("reachability"));
    }
}
