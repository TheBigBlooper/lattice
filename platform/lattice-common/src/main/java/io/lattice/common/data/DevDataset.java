package io.lattice.common.data;

import io.lattice.contract.orders.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The dev dataset the seed job loads: a handful of orders and inventory items, enough to make a
 * console screen look like a working baseline rather than an empty one.
 *
 * <p><b>It is obviously fake, on purpose.</b> Customers are {@code CUST-*}, stock-keeping units are
 * {@code SKU-*}, and nothing resembles a real name or address. Seed data that looks plausible is
 * seed data someone eventually mistakes for real - and the guard that keeps this out of production
 * is one variable, so the data itself should not be the thing that makes the mistake expensive.
 *
 * <p>Held as plain maps rather than the contract records because this module sits below
 * {@code lattice-contract}'s service types, and because a seed that had to compile against every
 * service's model would need updating for reasons that have nothing to do with the data.
 */
final class DevDataset {

    private DevDataset() {}

    /**
     * Orders spread across the statuses a console renders, so the seeded baseline exercises more
     * than one path through a status pill.
     *
     * @return the seed orders.
     */
    static List<Map<String, Object>> orders() {
        // Statuses come from OrderStatus, never invented: the mapping types the field as a keyword,
        // so Elasticsearch stores an unknown one happily and the service then cannot decode its own
        // document. Seed data has to satisfy the contract, not just the mapping.
        return List.of(
                order("11111111-1111-4111-8111-111111111111", "CUST-1001", OrderStatus.RECEIVED, "SKU-100", 2),
                order("22222222-2222-4222-8222-222222222222", "CUST-1002", OrderStatus.ALLOCATED, "SKU-200", 1),
                order("33333333-3333-4333-8333-333333333333", "CUST-1003", OrderStatus.SHIPPED, "SKU-300", 5),
                order("44444444-4444-4444-8444-444444444444", "CUST-1001", OrderStatus.DELIVERED, "SKU-100", 1));
    }

    /**
     * Inventory covering plenty, scarce, and none, so a screen has something to say beyond a row of
     * healthy numbers.
     *
     * @return the seed inventory.
     */
    static List<Map<String, Object>> inventory() {
        // Field names come from InventoryMapping, which is "dynamic": "strict" - an invented field
        // is rejected outright rather than quietly stored, which is how the first version of this
        // dataset was caught guessing "quantity".
        return List.of(
                Map.of("sku", "SKU-100", "onHand", 250, "reserved", 4),
                Map.of("sku", "SKU-200", "onHand", 12, "reserved", 12),
                Map.of("sku", "SKU-300", "onHand", 0, "reserved", 0));
    }

    private static Map<String, Object> order(String id, String customer, OrderStatus status, String sku, int quantity) {
        return Map.of(
                "orderId", id,
                "customerId", customer,
                "status", status.name(),
                "lines", List.of(Map.of("sku", sku, "quantity", quantity)),
                // A fixed instant rather than "now": a seeded baseline should look the same on every
                // run, so a screenshot taken today matches one taken next week.
                "createdAt", Instant.parse("2026-01-15T09:30:00Z").toString());
    }
}
