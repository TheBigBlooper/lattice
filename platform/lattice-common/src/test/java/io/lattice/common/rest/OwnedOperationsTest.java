package io.lattice.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Narrowing the shared baseline contract to the operations one host actually serves. */
class OwnedOperationsTest {

    private static JsonObject spec() {
        return new JsonObject()
                .put(
                        "paths",
                        new JsonObject()
                                .put(
                                        "/api/v1/orders",
                                        new JsonObject()
                                                .put("get", new JsonObject().put("operationId", "listOrders"))
                                                .put("post", new JsonObject().put("operationId", "createOrder")))
                                .put(
                                        "/api/v1/inventory/{sku}",
                                        new JsonObject()
                                                .put("get", new JsonObject().put("operationId", "getInventory"))))
                .put("components", new JsonObject().put("schemas", new JsonObject().put("Order", new JsonObject())));
    }

    /**
     * An operation this host does not serve is removed, so the published document cannot advertise
     * something that answers 404 to anyone who tries it.
     */
    @Test
    void anUnownedOperationIsRemoved() {
        var filtered = OwnedOperations.filteredTo(spec(), Set.of("listOrders", "createOrder"));

        var orders = filtered.getJsonObject("paths").getJsonObject("/api/v1/orders");
        assertNotNull(orders.getJsonObject("get"));
        assertNotNull(orders.getJsonObject("post"));
        assertFalse(
                filtered.getJsonObject("paths").containsKey("/api/v1/inventory/{sku}"),
                "a path with no surviving operation goes too");
    }

    /**
     * A path keeps the operations it does own when only some are removed - the two live on the same
     * path here, which is exactly the case a per-path filter would get wrong.
     */
    @Test
    void aPathKeepsTheOperationsItOwns() {
        var filtered = OwnedOperations.filteredTo(spec(), Set.of("listOrders"));

        var orders = filtered.getJsonObject("paths").getJsonObject("/api/v1/orders");
        assertNotNull(orders.getJsonObject("get"), "the owned operation stays");
        assertFalse(orders.containsKey("post"), "the unowned one on the same path goes");
    }

    /**
     * {@code components} is left whole rather than pruned to reachable schemas. Filtering paths alone
     * leaves every surviving {@code $ref} resolvable, and a reference walk would buy only a smaller
     * document at the cost of a failure mode that shows up in a browser rather than a build.
     */
    @Test
    void componentsAreLeftIntact() {
        var filtered = OwnedOperations.filteredTo(spec(), Set.of("listOrders"));

        assertTrue(filtered.getJsonObject("components").getJsonObject("schemas").containsKey("Order"));
    }

    /** The source document is not modified, so the caller's copy stays whole for anyone else. */
    @Test
    void theSourceDocumentIsUntouched() {
        var original = spec();

        OwnedOperations.filteredTo(original, Set.of("listOrders"));

        assertEquals(2, original.getJsonObject("paths").size(), "the original still carries both paths");
    }
}
