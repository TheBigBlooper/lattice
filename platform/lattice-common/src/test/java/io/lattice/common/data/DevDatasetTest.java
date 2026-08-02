package io.lattice.common.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.es.InventoryMapping;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The dev dataset's per-baseline behaviour: that three baselines seeded from one build hold visibly
 * different data, at a volume worth paging through, and that the one baseline carrying a divergent
 * inventory mapping is the only one whose documents use it.
 *
 * <p>These are the properties the demonstration rests on. The platform's headline claim is that every
 * cluster owns its own, possibly-divergent Elasticsearch model and stays interoperable anyway; a seed
 * that loaded identical documents everywhere left that claim shown nowhere.
 */
class DevDatasetTest {

    private static final List<String> BASELINES = List.of("hub-central", "hub-east", "hub-west");

    /**
     * Each baseline holds a different number of orders and inventory items, so three consoles side by
     * side read as three systems rather than three copies of one.
     */
    @Test
    void seedsADifferentVolumePerBaseline() {
        var orderCounts = BASELINES.stream()
                .map(baseline -> DevDataset.orders(baseline).size())
                .collect(Collectors.toSet());
        assertEquals(BASELINES.size(), orderCounts.size(), "every baseline has its own order count: " + orderCounts);

        var itemCounts = BASELINES.stream()
                .map(baseline -> DevDataset.inventory(baseline).size())
                .collect(Collectors.toSet());
        assertEquals(BASELINES.size(), itemCounts.size(), "every baseline has its own item count: " + itemCounts);
    }

    /**
     * Enough documents that the paged list operations have something to page. A handful of rows makes
     * newest-first ordering and a page boundary invisible, which is most of what the operational views
     * exist to show.
     */
    @Test
    void seedsEnoughToPageThrough() {
        for (var baseline : BASELINES) {
            assertTrue(
                    DevDataset.orders(baseline).size() >= 100,
                    baseline + " carries enough orders to page: "
                            + DevDataset.orders(baseline).size());
            assertTrue(
                    DevDataset.inventory(baseline).size() >= 50,
                    baseline + " carries enough inventory to page: "
                            + DevDataset.inventory(baseline).size());
        }
    }

    /**
     * No stock-keeping unit or customer appears on two baselines. Overlapping identifiers would read as
     * one shared catalogue, which is the opposite of what each baseline owning its own data means.
     */
    @Test
    void seedsIdentifiersThatDoNotOverlapBetweenBaselines() {
        assertDisjointAcrossBaselines(
                "sku",
                baseline -> DevDataset.inventory(baseline).stream()
                        .map(item -> String.valueOf(item.get("sku")))
                        .collect(Collectors.toSet()));

        assertDisjointAcrossBaselines(
                "customerId",
                baseline -> DevDataset.orders(baseline).stream()
                        .map(order -> String.valueOf(order.get("customerId")))
                        .collect(Collectors.toSet()));

        assertDisjointAcrossBaselines(
                "orderId",
                baseline -> DevDataset.orders(baseline).stream()
                        .map(order -> String.valueOf(order.get("orderId")))
                        .collect(Collectors.toSet()));
    }

    /**
     * Every order line references a stock-keeping unit this baseline actually stocks. A line pointing at
     * a peer's catalogue would be a broken screen rather than a divergent model.
     */
    @Test
    void seedsOrderLinesAgainstThisBaselinesOwnCatalogue() {
        for (var baseline : BASELINES) {
            var stocked = DevDataset.inventory(baseline).stream()
                    .map(item -> String.valueOf(item.get("sku")))
                    .collect(Collectors.toSet());
            for (var order : DevDataset.orders(baseline)) {
                @SuppressWarnings("unchecked") // The dataset builds its own lines; the shape is this file's.
                var lines = (List<java.util.Map<String, Object>>) order.get("lines");
                for (var line : lines) {
                    assertTrue(
                            stocked.contains(String.valueOf(line.get("sku"))),
                            baseline + " orders only what it stocks: " + line.get("sku"));
                }
            }
        }
    }

    /**
     * Orders carry distinct timestamps. The list operation sorts newest-first, so one shared instant
     * would make the ordering arbitrary and the page boundary unstable between reads.
     */
    @Test
    void seedsDistinctOrderTimestamps() {
        for (var baseline : BASELINES) {
            var timestamps = DevDataset.orders(baseline).stream()
                    .map(order -> String.valueOf(order.get("createdAt")))
                    .collect(Collectors.toSet());
            assertEquals(
                    DevDataset.orders(baseline).size(),
                    timestamps.size(),
                    baseline + " gives every order its own instant");
        }
    }

    /**
     * The dataset is fixed rather than generated afresh, so a screenshot taken today matches one taken
     * next week and a test can assert against it.
     */
    @Test
    void seedsTheSameDocumentsOnEveryRun() {
        for (var baseline : BASELINES) {
            assertEquals(DevDataset.orders(baseline), DevDataset.orders(baseline), baseline + " orders are fixed");
            assertEquals(
                    DevDataset.inventory(baseline), DevDataset.inventory(baseline), baseline + " inventory is fixed");
        }
    }

    /**
     * The divergent baseline is the only one whose inventory documents carry the extra field, because it
     * is the only one whose mapping declares it. Inventory is {@code dynamic: strict}, so writing that
     * field to either peer is rejected outright - which is what makes the divergence real rather than
     * decorative.
     */
    @Test
    void seedsTheDivergentFieldOnlyWhereTheMappingDeclaresIt() {
        var diverging =
                BASELINES.stream().filter(InventoryMapping::hasBinLocation).collect(Collectors.toSet());
        assertEquals(1, diverging.size(), "exactly one baseline diverges: " + diverging);

        for (var baseline : BASELINES) {
            var carrying = DevDataset.inventory(baseline).stream()
                    .filter(item -> item.containsKey("binLocation"))
                    .count();
            if (InventoryMapping.hasBinLocation(baseline)) {
                assertEquals(
                        DevDataset.inventory(baseline).size(),
                        carrying,
                        baseline + " declares binLocation, so every item carries it");
            } else {
                assertEquals(0, carrying, baseline + " does not declare binLocation, so no item may carry it");
            }
        }
    }

    /** The divergent baseline's mapping declares the field; the others' mappings do not mention it. */
    @Test
    void divergesTheMappingItselfRatherThanOnlyTheData() {
        for (var baseline : BASELINES) {
            var mapping = InventoryMapping.definitionFor(baseline).mappingJson();
            if (InventoryMapping.hasBinLocation(baseline)) {
                assertTrue(mapping.contains("binLocation"), baseline + " declares the field");
            } else {
                assertFalse(mapping.contains("binLocation"), baseline + " does not declare the field");
            }
        }

        assertNotEquals(
                InventoryMapping.definitionFor("hub-central").mappingJson(),
                InventoryMapping.definitionFor("hub-west").mappingJson(),
                "the two baselines genuinely hold different mappings");
    }

    /**
     * An unknown cluster id still seeds a usable baseline. A customer names their own clusters, so the
     * three local names cannot be the only ones that work.
     */
    @Test
    void seedsAnUnknownBaselineRatherThanFailing() {
        assertFalse(DevDataset.orders("some-customer-hub").isEmpty(), "an unknown baseline still gets orders");
        assertFalse(DevDataset.inventory("some-customer-hub").isEmpty(), "an unknown baseline still gets inventory");
        assertFalse(
                InventoryMapping.hasBinLocation("some-customer-hub"),
                "an unknown baseline gets the base mapping, never the divergent one");
    }

    /** Ids stay unique within a baseline, so re-running the seed overwrites rather than accumulating. */
    @Test
    void seedsStableIdentifiersWithinABaseline() {
        for (var baseline : BASELINES) {
            var ids = new HashSet<String>();
            for (var order : DevDataset.orders(baseline)) {
                assertTrue(ids.add(String.valueOf(order.get("orderId"))), baseline + " order ids are unique");
            }
            assertEquals(DevDataset.orders(baseline).size(), ids.size());
        }
    }

    private static void assertDisjointAcrossBaselines(
            String field, java.util.function.Function<String, Set<String>> extract) {
        var seen = new HashSet<String>();
        for (var baseline : BASELINES) {
            for (var value : extract.apply(baseline)) {
                assertTrue(seen.add(value), field + " '" + value + "' appears on more than one baseline");
            }
        }
    }
}
