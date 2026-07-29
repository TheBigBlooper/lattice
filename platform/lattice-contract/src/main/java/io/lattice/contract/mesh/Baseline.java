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
 * <p>It also carries the infrastructure this cluster's services depend on, as a sibling of the service
 * breakdown (locked #66). That stays local for the same reason the breakdown does, and for a stronger
 * one: locked #43 keeps the announced verdict a rollup of this cluster's own services, so a datastore
 * that has merely lost a replica cannot make a peer believe this cluster is unable to serve.
 *
 * @param clusterId       this cluster's stable id.
 * @param region          this cluster's region label.
 * @param baselineVersion the versioned baseline this cluster runs.
 * @param apiVersions     the API major versions this cluster serves.
 * @param health          the rolled-up health ({@code ready} / {@code degraded} / {@code down}).
 * @param services        the per-service readiness behind the rollup; empty when nothing is watched.
 * @param infrastructure  the components those services depend on; empty when none is configured,
 *                        which is a supported deployment rather than a fault.
 * @param meshLink        whether this cluster can currently reach the mesh at all (locked #46).
 */
public record Baseline(
        String clusterId,
        String region,
        String baselineVersion,
        List<String> apiVersions,
        String health,
        List<ServiceHealth> services,
        List<ComponentHealth> infrastructure,
        MeshLinkState meshLink) {

    /** Defensive copies: every list is exposed on a record accessor. */
    public Baseline {
        apiVersions = List.copyOf(apiVersions);
        services = List.copyOf(services);
        infrastructure = List.copyOf(infrastructure);
    }

    /**
     * Serializes this baseline to JSON for the response envelope.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        var serviceArray = new JsonArray();
        services.forEach(service -> serviceArray.add(service.toJson()));
        var infrastructureArray = new JsonArray();
        infrastructure.forEach(component -> infrastructureArray.add(component.toJson()));
        return new JsonObject()
                .put("clusterId", clusterId)
                .put("region", region)
                .put("baselineVersion", baselineVersion)
                .put("apiVersions", new JsonArray(List.copyOf(apiVersions)))
                .put("health", health)
                .put("services", serviceArray)
                .put("infrastructure", infrastructureArray)
                .put("meshLink", meshLink.wire());
    }
}
