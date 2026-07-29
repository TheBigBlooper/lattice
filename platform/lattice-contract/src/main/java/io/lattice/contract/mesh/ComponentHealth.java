package io.lattice.contract.mesh;

import io.vertx.core.json.JsonObject;

/**
 * One infrastructure component's state as this baseline's mesh-gateway last observed it, part of the
 * infrastructure breakdown that sits beside the per-service one (locked #66).
 *
 * <p>The sibling of {@link ServiceHealth}, and deliberately a separate shape rather than a widening of
 * it: a component carries a kind and a detail line that mean nothing on a service, and a service's two
 * states cannot express {@link ComponentStatus#DEGRADED}.
 *
 * <p>Served only on this baseline's own endpoint, never over the mesh. Locked #43 stands unamended, so
 * the announced verdict remains a rollup of this baseline's services alone: a datastore that has lost
 * a replica must not make a peer believe this baseline cannot serve.
 *
 * @param name   the component's label as configured, which a deployment may choose freely.
 * @param kind   which piece of infrastructure this is, and so which probe produced the status.
 * @param status the coarse state the console colours and counts.
 * @param detail the component's own reading in its own vocabulary, or {@code null} when there is
 *               nothing to add; omitted from the JSON entirely rather than serialized as null.
 */
public record ComponentHealth(String name, ComponentKind kind, ComponentStatus status, String detail) {

    /**
     * Serializes this component health to JSON, omitting an absent detail.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        var json = new JsonObject().put("name", name).put("kind", kind.wire()).put("status", status.wire());
        if (detail != null) {
            json.put("detail", detail);
        }
        return json;
    }
}
