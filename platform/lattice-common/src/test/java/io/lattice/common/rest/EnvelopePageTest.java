package io.lattice.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.es.Page;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The list envelope: the same {@code {data, meta}} shape as a single item, with the page counts in
 * the {@code meta.pagination} block the contract already declares.
 */
class EnvelopePageTest {

    /** A page is an array in {@code data}, never an object wrapping one. */
    @Test
    void aPageIsAnArrayInData() {
        var page = new Page<>(List.of(new JsonObject().put("sku", "A")), 1);

        var envelope = Envelopes.successPage(page, 0, 20);

        assertEquals(1, envelope.getJsonArray("data").size());
        assertEquals("v1", envelope.getJsonObject("meta").getString("apiVersion"));
    }

    /**
     * The counts describe the whole collection, not the slice returned. An operator paging through
     * needs to know there is a page four, and the size of the page in their hand cannot tell them.
     */
    @Test
    void theCountsDescribeTheWholeCollection() {
        var page = new Page<>(List.of(new JsonObject(), new JsonObject()), 47);

        var pagination =
                Envelopes.successPage(page, 1, 20).getJsonObject("meta").getJsonObject("pagination");

        assertEquals(1, pagination.getInteger("page"));
        assertEquals(20, pagination.getInteger("size"));
        assertEquals(47, pagination.getInteger("total"));
        assertEquals(3, pagination.getInteger("totalPages"), "47 items at 20 a page is three pages");
    }

    /**
     * An empty collection is a page, not an absence. A baseline with no orders yet answers with an
     * empty array rather than a 404: nothing is wrong, there is simply nothing there, and a 404
     * would send an operator looking for a fault that does not exist.
     */
    @Test
    void anEmptyCollectionIsAnEmptyPage() {
        var envelope = Envelopes.successPage(new Page<>(List.of(), 0), 0, 20);

        assertTrue(envelope.getJsonArray("data").isEmpty());
        var pagination = envelope.getJsonObject("meta").getJsonObject("pagination");
        assertEquals(0, pagination.getInteger("total"));
        assertEquals(0, pagination.getInteger("totalPages"));
    }
}
