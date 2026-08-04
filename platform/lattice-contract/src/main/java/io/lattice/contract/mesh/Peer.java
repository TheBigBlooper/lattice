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
 * @param apiBaseUrl      the peer's REST API base. Recorded and advertised, but never read from a
 *                        browser: every operation on it is bearer-protected against that peer's own
 *                        realm and membership is deliberately unsynchronized, so a fan-out would be
 *                        refused by every peer (locked #61).
 * @param lastSeen        when this cluster last heard the peer announce, as an ISO-8601 UTC string.
 * @param reachability    {@code REACHABLE} or {@code UNREACHABLE}, against the liveness time-to-live.
 * @param federation      this broker's federation link to that peer, or null when it could not be
 *                        read. A different fact from reachability: a link can be dead while the peer
 *                        is still inside its liveness window, and it goes first (locked #80).
 */
public record Peer(
        String clusterId,
        String region,
        String baselineVersion,
        String health,
        String consoleUrl,
        String apiBaseUrl,
        String lastSeen,
        String reachability,
        FederationState federation) {

    /**
     * A peer with no federation reading, for callers that have not measured one.
     *
     * <p>Absent rather than defaulted to {@code UP}: a baseline that cannot read the link must say
     * nothing about it rather than claim it is healthy.
     *
     * @param clusterId       the peer's stable cluster id.
     * @param region          the peer's region label.
     * @param baselineVersion the baseline the peer last reported running.
     * @param health          the peer's last reported health rollup.
     * @param consoleUrl      the peer's own console root.
     * @param apiBaseUrl      the peer's REST API base.
     * @param lastSeen        when this cluster last heard the peer announce.
     * @param reachability    {@code REACHABLE} or {@code UNREACHABLE}.
     */
    public Peer(
            String clusterId,
            String region,
            String baselineVersion,
            String health,
            String consoleUrl,
            String apiBaseUrl,
            String lastSeen,
            String reachability) {
        this(clusterId, region, baselineVersion, health, consoleUrl, apiBaseUrl, lastSeen, reachability, null);
    }

    /**
     * The same peer carrying a federation reading.
     *
     * @param state the measured link state.
     * @return a copy with that state attached.
     */
    public Peer withFederation(FederationState state) {
        return new Peer(
                clusterId, region, baselineVersion, health, consoleUrl, apiBaseUrl, lastSeen, reachability, state);
    }

    /**
     * Serializes this peer to JSON for the response envelope.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        var json = new JsonObject()
                .put("clusterId", clusterId)
                .put("region", region)
                .put("baselineVersion", baselineVersion)
                .put("health", health)
                .put("consoleUrl", consoleUrl)
                .put("apiBaseUrl", apiBaseUrl)
                .put("lastSeen", lastSeen)
                .put("reachability", reachability);
        // Omitted rather than sent null, so a client cannot read "we did not measure this" as a
        // state. Optional in the spec for the same reason.
        if (federation != null) {
            json.put("federation", federation.wire());
        }
        return json;
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
                json.getString("reachability"),
                FederationState.fromWire(json.getString("federation")));
    }
}
