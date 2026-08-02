package io.lattice.contract.inventory;

/**
 * An item's stock as returned by {@code setStock} and {@code getInventory}: the response shape in
 * the success envelope. Mirrors the {@code InventoryItem} schema in the v1 OpenAPI contract.
 *
 * <p>{@code available} is computed ({@code onHand - reserved}) at read and is never stored in
 * Elasticsearch (the {@code inventory} mapping is strict over only {@code sku}, {@code onHand}, and
 * {@code reserved}); this record is the read/response projection, not the persisted document.
 *
 * <p>{@code binLocation} is optional and is the one component a baseline may not have. Every cluster
 * owns its own, possibly-divergent Elasticsearch model (locked #14), and a warehouse position is a
 * field some baselines record and others do not; a baseline whose model has no such field reports
 * {@code null} rather than an empty string, so a client can tell "not recorded here" from "recorded
 * as blank".
 *
 * @param sku         the stock-keeping unit this item is for.
 * @param onHand      the absolute on-hand quantity.
 * @param reserved    the quantity currently reserved against orders.
 * @param available   the computed available quantity ({@code onHand - reserved}).
 * @param binLocation where this baseline holds the item, or {@code null} if its model does not
 *     record one.
 */
public record InventoryItem(String sku, int onHand, int reserved, int available, String binLocation) {

    /**
     * An item from a baseline whose model records no location.
     *
     * <p>Kept so the shape this record had before {@code binLocation} existed still compiles and still
     * means the same thing: the field is additive, and a caller that never knew about it is unchanged.
     *
     * @param sku       the stock-keeping unit this item is for.
     * @param onHand    the absolute on-hand quantity.
     * @param reserved  the quantity currently reserved against orders.
     * @param available the computed available quantity ({@code onHand - reserved}).
     */
    public InventoryItem(String sku, int onHand, int reserved, int available) {
        this(sku, onHand, reserved, available, null);
    }
}
