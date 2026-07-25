package io.lattice.common.rest;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.UUID;

/**
 * Builds the standard {@code {data|error, meta}} response envelope (see
 * {@code docs/design/architecture/api_structure.md}). Every response carries a {@code meta} block
 * with a generated {@code requestId} and the API version, plus exactly one of {@code data} (success)
 * or {@code error} (failure).
 *
 * <p>This lives in the shared runtime rather than in each service because the envelope is the one
 * shape every service and the status console agree on: a per-service copy is the same code three
 * times over, and three places for it to drift from the contract.
 */
public final class Envelopes {

    /** The API major version these envelopes are stamped with. */
    public static final String API_VERSION = "v1";

    private Envelopes() {
        // Static helper - not instantiable.
    }

    /**
     * Wraps a payload in the success envelope with a fresh {@code meta} block.
     *
     * @param data the response payload (serialized to JSON via the shared Jackson mapper).
     * @return the success envelope.
     */
    public static JsonObject success(Object data) {
        return new JsonObject().put("data", JsonObject.mapFrom(data)).put("meta", meta());
    }

    /**
     * Wraps an error in the failure envelope with a fresh {@code meta} block.
     *
     * @param code    the machine-branchable error code from the taxonomy.
     * @param message the human-readable summary.
     * @param details the field-level problems, or {@code null} to omit them.
     * @return the failure envelope.
     */
    public static JsonObject error(String code, String message, JsonArray details) {
        var error = new JsonObject().put("code", code).put("message", message);
        if (details != null) {
            error.put("details", details);
        }
        return new JsonObject().put("error", error).put("meta", meta());
    }

    private static JsonObject meta() {
        return new JsonObject().put("requestId", UUID.randomUUID().toString()).put("apiVersion", API_VERSION);
    }
}
