package io.lattice.contract.mesh;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * This cluster's own identity and health, served by the mesh-gateway.
 *
 * <p>The counterpart to {@link Peer}: that is how a cluster sees the others, this is how it reports
 * itself. It carries the same rolled-up {@code health} that rides the mesh, plus the per-service
 * breakdown behind it - detail deliberately kept off the announcement so the wire format does not
 * grow with every service, and because under Shape A federation an operator goes to the owning
 * baseline for detail anyway.
 *
 * @param clusterId       this cluster's stable id.
 * @param region          this cluster's region label.
 * @param baselineVersion the versioned baseline this cluster runs.
 * @param apiVersions     the API major versions this cluster serves.
 * @param health          the rolled-up health ({@code ready} / {@code degraded} / {@code down}).
 * @param services        the per-service readiness behind the rollup; empty when nothing is watched.
 * @param meshLink        whether this cluster can currently reach the mesh at all (locked #46).
 */
public record Baseline(
        String clusterId,
        String region,
        String baselineVersion,
        List<String> apiVersions,
        String health,
        List<ServiceHealth> services,
        MeshLinkState meshLink) {

    /** Defensive copies: both lists are exposed on record accessors. */
    public Baseline {
        apiVersions = List.copyOf(apiVersions);
        services = List.copyOf(services);
    }

    /**
     * Serializes this baseline to JSON for the response envelope.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        var serviceArray = new JsonArray();
        services.forEach(service -> serviceArray.add(service.toJson()));
        return new JsonObject()
                .put("clusterId", clusterId)
                .put("region", region)
                .put("baselineVersion", baselineVersion)
                .put("apiVersions", new JsonArray(List.copyOf(apiVersions)))
                .put("health", health)
                .put("services", serviceArray)
                .put("meshLink", meshLink.wire());
    }
}
