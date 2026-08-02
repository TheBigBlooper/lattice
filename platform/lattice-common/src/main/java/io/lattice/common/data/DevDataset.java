package io.lattice.common.data;

import io.lattice.common.es.InventoryMapping;
import io.lattice.contract.orders.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dev dataset the seed job loads: one baseline's worth of orders and inventory, sized so a console
 * screen has something to page through rather than a handful of rows.
 *
 * <p><b>Every baseline gets its own data, and one gets its own model.</b> The platform's headline claim
 * is that each cluster owns a possibly-divergent Elasticsearch model and stays interoperable anyway
 * (locked #14, #37). A seed that loaded identical documents into all three left that claim demonstrated
 * nowhere: three consoles showed three copies of one system. Now the identifier ranges, the volumes and
 * the stock profile differ per baseline, and the baseline {@link InventoryMapping#hasBinLocation(String)}
 * names carries an extra field its peers' mappings do not declare.
 *
 * <p><b>It is obviously fake, on purpose.</b> Customers are {@code CUST-*}, stock-keeping units are
 * {@code SKU-*}, and nothing resembles a real name or address. Seed data that looks plausible is seed
 * data someone eventually mistakes for real - and the guard that keeps this out of production is one
 * variable, so the data itself should not be the thing that makes the mistake expensive.
 *
 * <p><b>Derived rather than listed.</b> A few hundred documents written out by hand would be unreadable
 * and unmaintainable, so each baseline's rows are generated from a small profile. Generation is fully
 * deterministic - no randomness, no clock - so two runs seed byte-identical documents and a screenshot
 * taken today matches one taken next week.
 *
 * <p>Held as plain maps rather than the contract records because this module sits below
 * {@code lattice-contract}'s service types, and because a seed that had to compile against every
 * service's model would need updating for reasons that have nothing to do with the data.
 */
final class DevDataset {

    /**
     * One baseline's shape: how much it holds, and the identifier band it holds it in.
     *
     * <p>The bands are disjoint so no stock-keeping unit or customer appears on two baselines. Shared
     * identifiers would read as one federated catalogue, which is the opposite of each baseline owning
     * its own data.
     *
     * @param band the leading digit of every identifier this baseline issues.
     * @param items how many stock-keeping units it stocks.
     * @param orders how many orders it holds.
     * @param customers how many distinct customers those orders come from.
     */
    private record Profile(int band, int items, int orders, int customers) {}

    /**
     * The three baselines the local stack runs, plus the fallback any other cluster id gets.
     *
     * <p>Deliberately uneven: equal volumes would look generated, and the point of the numbers is that
     * three consoles side by side are visibly three systems.
     */
    private static final Map<String, Profile> PROFILES = Map.of(
            "hub-central", new Profile(1, 70, 160, 24),
            "hub-east", new Profile(2, 55, 120, 18),
            "hub-west", new Profile(3, 85, 190, 31));

    /**
     * What an unnamed or customer-named baseline gets. A customer names their own clusters, so the three
     * names above cannot be the only ones that seed.
     */
    private static final Profile DEFAULT_PROFILE = new Profile(9, 60, 130, 20);

    /** The instant the newest seeded order was placed; everything else is spaced backwards from it. */
    private static final Instant NEWEST_ORDER = Instant.parse("2026-01-15T09:30:00Z");

    /** The gap between consecutive orders, which is what gives newest-first ordering something to sort. */
    private static final Duration ORDER_SPACING = Duration.ofMinutes(37);

    /** The statuses seeded orders cycle through, so a console renders every status pill it supports. */
    private static final List<OrderStatus> STATUS_CYCLE = List.of(
            OrderStatus.RECEIVED,
            OrderStatus.ALLOCATED,
            OrderStatus.SHIPPED,
            OrderStatus.DELIVERED,
            OrderStatus.RECEIVED,
            OrderStatus.ALLOCATED);

    private DevDataset() {}

    /**
     * This baseline's orders, newest first.
     *
     * <p>Every line references a stock-keeping unit this baseline actually stocks. An order pointing at
     * a peer's catalogue would render as a broken screen rather than as a divergent model.
     *
     * @param clusterId this baseline's cluster id.
     * @return the seed orders.
     */
    static List<Map<String, Object>> orders(String clusterId) {
        var profile = profileFor(clusterId);
        var seeded = new ArrayList<Map<String, Object>>(profile.orders());
        for (int i = 0; i < profile.orders(); i++) {
            // Statuses come from OrderStatus, never invented: the mapping types the field as a keyword,
            // so Elasticsearch stores an unknown one happily and the service then cannot decode its own
            // document. Seed data has to satisfy the contract, not just the mapping.
            var status = STATUS_CYCLE.get(i % STATUS_CYCLE.size());
            var customer = "CUST-%d%03d".formatted(profile.band(), 100 + (i % profile.customers()));
            // Two lines on every third order, so the console renders a multi-line order as well as the
            // single-line case that would otherwise be the only one anyone sees.
            var lines = new ArrayList<Map<String, Object>>(2);
            lines.add(line(profile, i, 1 + (i % 4)));
            if (i % 3 == 0) {
                lines.add(line(profile, i + 7, 1 + (i % 2)));
            }
            seeded.add(order(
                    orderId(profile, i), customer, status, lines, NEWEST_ORDER.minus(ORDER_SPACING.multipliedBy(i))));
        }
        return List.copyOf(seeded);
    }

    /**
     * This baseline's inventory, covering plenty, scarce and none, so a screen has something to say
     * beyond a row of healthy numbers.
     *
     * <p>On the diverging baseline every item also carries {@code binLocation}. That field is written
     * <b>only</b> where the mapping declares it: inventory is {@code dynamic: strict}, so sending it to
     * a peer would be rejected on write rather than quietly stored, which is exactly what makes the
     * divergence real.
     *
     * @param clusterId this baseline's cluster id.
     * @return the seed inventory.
     */
    static List<Map<String, Object>> inventory(String clusterId) {
        var profile = profileFor(clusterId);
        var divergent = InventoryMapping.hasBinLocation(clusterId);
        var seeded = new ArrayList<Map<String, Object>>(profile.items());
        for (int i = 0; i < profile.items(); i++) {
            // Field names come from InventoryMapping, which is "dynamic": "strict" - an invented field
            // is rejected outright rather than quietly stored, which is how the first version of this
            // dataset was caught guessing "quantity".
            var item = new LinkedHashMap<String, Object>();
            item.put("sku", sku(profile, i));
            item.put("onHand", onHand(i));
            item.put("reserved", reserved(i));
            if (divergent) {
                item.put("binLocation", "%s-%02d-%02d".formatted(aisle(i), 1 + (i % 12), 1 + (i % 5)));
            }
            seeded.add(Map.copyOf(item));
        }
        return List.copyOf(seeded);
    }

    private static Profile profileFor(String clusterId) {
        return PROFILES.getOrDefault(clusterId == null ? "" : clusterId.strip(), DEFAULT_PROFILE);
    }

    /**
     * Stock levels on a repeating profile that always includes zero and always includes fully reserved,
     * so the out-of-stock empty state and the nothing-available state are both on screen somewhere.
     */
    private static int onHand(int index) {
        return switch (index % 8) {
            case 0 -> 0;
            case 1 -> 12;
            case 2 -> 250;
            case 3 -> 74;
            case 4 -> 3;
            case 5 -> 1_240;
            case 6 -> 96;
            default -> 18;
        };
    }

    private static int reserved(int index) {
        var onHand = onHand(index);
        // Never more than is held: available is computed as onHand - reserved, and a negative one would
        // be a seeded impossibility rather than a scenario.
        return switch (index % 8) {
            case 0 -> 0;
            case 1 -> 12;
            case 4 -> 3;
            default -> Math.min(onHand, (index % 5) * 4);
        };
    }

    private static String aisle(int index) {
        return String.valueOf((char) ('A' + (index % 6)));
    }

    private static String sku(Profile profile, int index) {
        return "SKU-%d%03d".formatted(profile.band(), 100 + index);
    }

    private static Map<String, Object> line(Profile profile, int index, int quantity) {
        return Map.of("sku", sku(profile, index % profile.items()), "quantity", quantity);
    }

    /**
     * A stable, obviously-synthetic identifier: a valid version-4 shaped string whose digits spell out
     * the baseline band and the row, so an id seen on a screen can be traced back to this file.
     */
    private static String orderId(Profile profile, int index) {
        var tail = "%d%011d".formatted(profile.band(), index);
        return "%s-%s-4%s-8%s-%s"
                .formatted(
                        tail.substring(0, 8),
                        tail.substring(0, 4),
                        tail.substring(0, 3),
                        tail.substring(0, 3),
                        tail.substring(0, 12));
    }

    private static Map<String, Object> order(
            String id, String customer, OrderStatus status, List<Map<String, Object>> lines, Instant createdAt) {
        return Map.of(
                "orderId", id,
                "customerId", customer,
                "status", status.name(),
                "lines", List.copyOf(lines),
                // A derived instant rather than "now": a seeded baseline should look the same on every
                // run, so a screenshot taken today matches one taken next week. Spaced rather than
                // shared, because the list operation sorts newest-first and one instant makes that
                // ordering arbitrary.
                "createdAt", createdAt.toString());
    }
}
