package io.lattice.common.rest;

import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.Set;

/**
 * Narrows the shared baseline contract to the operations one service actually serves.
 *
 * <p><b>The problem it removes.</b> Every service builds its router from the whole contract and
 * claims its operations by name, so which operations a host owns is implicit - whatever
 * {@code getRoute} happened to be called for. Two things follow, and they are one defect seen twice:
 * the docs page on the orders service advertises {@code setStock} and {@code getPeers}, which orders
 * cannot serve and which answer 404 to anyone who tries them from the page; and the router logs a
 * warning for every operation the service did not claim, on every start.
 *
 * <p>Filtering before the router is built means no unmounted operation ever exists, so the warnings
 * stop happening rather than being silenced, and the published document describes the host serving
 * it. What a service owns becomes a declaration in one place instead of a runtime accident.
 *
 * <p><b>This is not a per-service spec.</b> One versioned contract for the baseline is deliberate:
 * this narrows a shared document as it is loaded and never fragments the source of truth. Editing
 * the API is still one edit in one file.
 *
 * <p><b>{@code components} is left whole.</b> Filtering paths alone leaves every {@code $ref} in the
 * surviving operations resolvable. Pruning to reachable schemas would need a reference walk and buys
 * only a smaller document - and a mistake in that walk produces a document that fails to resolve in
 * a browser rather than in a build.
 */
public final class OwnedOperations {

    /** The key the contract loader stamps its own resolved base URI under. */
    private static final String LOADER_MARKER = "__absolute_uri__";

    private OwnedOperations() {
        // Static helper - not instantiable.
    }

    /**
     * Returns the contract carrying only the operations this service owns.
     *
     * <p><b>Built by re-assembly rather than by copying and deleting.</b> The loader hands back a
     * lazily-resolved view of the document, and deep-copying it materialises internal bookkeeping -
     * including an absolute base URI nothing outside this process can resolve - into values that
     * would then be published. Everything except {@code paths} is therefore carried over by
     * reference and left exactly as the loader produced it; only {@code paths} is rebuilt. Nothing
     * is mutated, so the caller's contract stays whole for anyone else reading it.
     *
     * <p>A path left with no operations is dropped rather than published as a heading with nothing
     * under it.
     *
     * @param spec         the raw baseline contract.
     * @param operationIds the {@code operationId}s this service serves.
     * @return the contract narrowed to those operations.
     */
    public static JsonObject filteredTo(JsonObject spec, Set<String> operationIds) {
        var paths = spec.getJsonObject("paths");
        if (paths == null) {
            return spec;
        }

        var keptPaths = new JsonObject();
        for (var path : paths.fieldNames()) {
            // Not every member of `paths` is a path item - the loader stores its own base URI beside
            // them as a plain string - so anything that is not an object is skipped rather than
            // interpreted as a path.
            var pathItem = asObject(paths.getValue(path));
            if (pathItem == null) {
                continue;
            }
            var kept = ownedPartOf(pathItem, operationIds);
            if (kept != null) {
                keptPaths.put(path, kept);
            }
        }

        var filtered = new JsonObject();
        spec.fieldNames().forEach(name -> filtered.put(name, spec.getValue(name)));
        filtered.put("paths", keptPaths);
        return filtered;
    }

    /**
     * A path item reduced to the operations this service owns, or {@code null} when none survive.
     *
     * <p>Members that are not operations - a shared {@code parameters} list, a path-level summary -
     * are carried over as they are. Only something carrying an {@code operationId} is judged.
     */
    private static JsonObject ownedPartOf(JsonObject pathItem, Set<String> operationIds) {
        var kept = new JsonObject();
        var keptAnOperation = false;
        for (var member : pathItem.fieldNames()) {
            // The loader stamps each path item with the absolute URI it resolved the document from.
            // It is bookkeeping, not part of the API, and it carries an internal `app:///` address
            // that nothing outside this process can resolve - so it must not reach a browser. It was
            // never visible before because the loader's lazy view does not encode it; rebuilding the
            // path item is what would have published it.
            if (LOADER_MARKER.equals(member)) {
                continue;
            }
            var value = pathItem.getValue(member);
            var operation = asObject(value);
            if (operation == null || !operation.containsKey("operationId")) {
                kept.put(member, value);
                continue;
            }
            if (operationIds.contains(operation.getString("operationId"))) {
                kept.put(member, value);
                keptAnOperation = true;
            }
        }
        return keptAnOperation ? kept : null;
    }

    /**
     * Reads a nested value as a JSON object, whichever form it is held in.
     *
     * <p>The two forms are not interchangeable in practice. A document parsed from YAML holds nested
     * values as raw {@link Map}s, while one built in code holds real {@link JsonObject}s - so a type
     * check against either alone matches half the cases and silently skips the rest. That is not
     * hypothetical: checking only for {@code JsonObject} turned this whole filter into a no-op
     * against the real contract while every fixture test went on passing.
     *
     * @param value the nested value.
     * @return the value as an object, or {@code null} when it is not one.
     */
    @SuppressWarnings("unchecked")
    private static JsonObject asObject(Object value) {
        if (value instanceof JsonObject json) {
            return json;
        }
        return value instanceof Map<?, ?> map ? new JsonObject((Map<String, Object>) map) : null;
    }
}
