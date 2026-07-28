package io.lattice.common.rest;

import io.lattice.common.es.Page;
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
     * Wraps a page in the success envelope, with its counts in {@code meta.pagination}.
     *
     * <p><b>The counts describe the collection, not the slice.</b> An operator paging through needs
     * to know there is a page four, and the page in their hand cannot tell them that - a full page
     * might be the last one.
     *
     * <p><b>An empty collection is a page, not an absence.</b> A baseline with no orders yet answers
     * with an empty array rather than a 404: nothing is wrong, there is simply nothing there, and a
     * 404 would send an operator looking for a fault that does not exist.
     *
     * @param page the slice to serve, carrying the total across the whole collection.
     * @param index the zero-based page index this slice was requested at.
     * @param size the page size this slice was requested at.
     * @return the success envelope carrying the page.
     */
    public static JsonObject successPage(Page<?> page, int index, int size) {
        var items = new JsonArray();
        page.items().forEach(item -> items.add(JsonObject.mapFrom(item)));
        // Ceiling division rather than a floor plus a correction: the remainder is a page too, and
        // writing it as a rounding fix is where an off-by-one hides on the last page.
        var totalPages = size <= 0 ? 0 : Math.ceilDiv(page.total(), size);
        var pagination = new JsonObject()
                .put("page", index)
                .put("size", size)
                .put("total", page.total())
                .put("totalPages", totalPages);
        return new JsonObject().put("data", items).put("meta", meta().put("pagination", pagination));
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
