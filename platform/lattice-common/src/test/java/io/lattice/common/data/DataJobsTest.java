package io.lattice.common.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

/**
 * The parts of the data jobs that can be pinned without a cluster: the index counter a reindex walks,
 * and the shape of the dev dataset.
 *
 * <p>The reindex and reset themselves are exercised against a real Elasticsearch rather than mocked -
 * a mock would only assert that this code calls the methods this code calls.
 */
class DataJobsTest {

    /**
     * The counter is what makes a reindex repeatable rather than a one-off, and zero-padding is what
     * keeps the indices sorting in creation order once there are ten of them.
     */
    @Test
    void walksTheConcreteIndexCounter() {
        assertEquals("orders-000002", DataJobs.nextConcreteIndex("orders-000001"));
        assertEquals("orders-000003", DataJobs.nextConcreteIndex("orders-000002"));
        assertEquals("orders-000010", DataJobs.nextConcreteIndex("orders-000009"));
        assertEquals("orders-000100", DataJobs.nextConcreteIndex("orders-000099"));
    }

    /** A logical name containing a hyphen must not confuse the counter. */
    @Test
    void walksTheCounterForAHyphenatedIndexName() {
        assertEquals("order-lines-000002", DataJobs.nextConcreteIndex("order-lines-000001"));
    }

    /** Seeded ids are stable, so re-running the seed overwrites rather than accumulating duplicates. */
    @Test
    void seedsStableIdentifiers() {
        var ids = new HashSet<String>();
        for (var order : DevDataset.orders()) {
            assertTrue(ids.add(String.valueOf(order.get("orderId"))), "seed order ids are unique");
        }
        assertEquals(DevDataset.orders().size(), ids.size());
        assertEquals(
                DevDataset.orders(), DevDataset.orders(), "the dataset is fixed, so two runs seed the same documents");
    }

    /**
     * The dataset must be obviously fake. Seed data that looks plausible is seed data someone
     * eventually mistakes for real, and the guard keeping it out of production is one variable.
     */
    @Test
    void seedsDataThatCouldNotBeMistakenForReal() {
        for (var order : DevDataset.orders()) {
            assertTrue(
                    String.valueOf(order.get("customerId")).startsWith("CUST-"),
                    "a seeded customer is obviously a placeholder");
        }
        for (var item : DevDataset.inventory()) {
            assertTrue(String.valueOf(item.get("sku")).startsWith("SKU-"), "a seeded sku is obviously a placeholder");
        }
    }

    /** A console screen needs more than one status and more than one stock level to be worth looking at. */
    @Test
    void seedsEnoughVarietyToRenderAConsole() {
        var statuses = new HashSet<String>();
        DevDataset.orders().forEach(order -> statuses.add(String.valueOf(order.get("status"))));
        assertTrue(statuses.size() >= 3, "several order statuses, not a wall of one: " + statuses);

        var onHand = new HashSet<Object>();
        DevDataset.inventory().forEach(item -> onHand.add(item.get("onHand")));
        assertTrue(onHand.contains(0), "including something out of stock, so an empty state is visible");
        assertFalse(onHand.size() < 2, "stock levels vary");

        // One item is fully reserved (onHand == reserved), so a console has a "nothing available"
        // case to render as well as a "none left" one - they are different states worth showing.
        assertTrue(
                DevDataset.inventory().stream()
                        .anyMatch(item -> item.get("onHand").equals(item.get("reserved"))
                                && !item.get("onHand").equals(0)),
                "something is stocked but entirely reserved");
    }
}
