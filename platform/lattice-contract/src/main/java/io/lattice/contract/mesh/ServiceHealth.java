package io.lattice.contract.mesh;

import io.vertx.core.json.JsonObject;

/**
 * One service's readiness as this cluster's mesh-gateway last observed it, part of the per-service
 * breakdown behind the cluster's health rollup.
 *
 * <p>Served only on this cluster's own baseline endpoint, never over the mesh: peers receive the
 * rolled-up label alone, so the announcement does not grow with every service added.
 *
 * @param name   the service name as configured in the watched-services list.
 * @param status {@code UP} when its readiness probe returned 200, {@code DOWN} otherwise (including
 *               a timeout or a refused connection).
 */
public record ServiceHealth(String name, String status) {

    /**
     * Serializes this service health to JSON.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        return new JsonObject().put("name", name).put("status", status);
    }

    /**
     * Parses a service health from JSON, ignoring unknown fields.
     *
     * @param json the JSON to read.
     * @return the parsed service health.
     */
    public static ServiceHealth fromJson(JsonObject json) {
        return new ServiceHealth(json.getString("name"), json.getString("status"));
    }
}
