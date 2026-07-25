package io.lattice.meshgateway.routes;

import io.vertx.core.json.JsonObject;
import java.util.UUID;

/**
 * Builds the success half of the standard {@code {data|error, meta}} response envelope (see
 * api_structure.md): a {@code data} block plus a {@code meta} block carrying a generated
 * {@code requestId} and the API version.
 *
 * <p>Only the success shape lives here. Both mesh-gateway operations are reads served from state
 * already in memory - the peer registry and the last health rollup - so neither can fail a request;
 * the error half belongs to the services that can. Kept as a small local helper so handlers stay thin
 * and every response is shaped one way.
 */
public final class Envelopes {

    /** The API major version this service serves. */
    public static final String API_VERSION = "v1";

    private Envelopes() {
        // Static helper - not instantiable.
    }

    /**
     * Wraps a payload in the success envelope.
     *
     * @param data the response payload.
     * @return the enveloped response.
     */
    public static JsonObject success(Object data) {
        return new JsonObject().put("data", JsonObject.mapFrom(data)).put("meta", meta());
    }

    private static JsonObject meta() {
        return new JsonObject().put("requestId", UUID.randomUUID().toString()).put("apiVersion", API_VERSION);
    }
}
