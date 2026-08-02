package io.lattice.inventory.repository;

/**
 * The persisted shape of an inventory item in Elasticsearch: exactly the fields the strict {@code
 * inventory} mapping stores ({@code sku}, {@code onHand}, {@code reserved}). This is a persistence
 * projection, not a REST DTO - the wire shape adds a computed {@code available}
 * ({@code io.lattice.contract.inventory.InventoryItem}), which the {@code inventory} mapping does not
 * store, so the two are kept as separate types rather than one leaking the other's field.
 *
 * <p>{@code binLocation} is present only on a baseline whose own mapping declares it (locked #14). It
 * deserializes to {@code null} everywhere else, which is correct rather than merely tolerated: the
 * field does not exist in those baselines' models, and the strict mapping refuses to store it.
 *
 * @param sku         the stock-keeping unit (also the document id).
 * @param onHand      the absolute on-hand quantity.
 * @param reserved    the quantity currently reserved against orders.
 * @param binLocation where this baseline holds the item, or {@code null} if its model has no such
 *     field.
 */
public record StoredItem(String sku, int onHand, int reserved, String binLocation) {

    /**
     * An item on a baseline whose model records no location.
     *
     * @param sku      the stock-keeping unit (also the document id).
     * @param onHand   the absolute on-hand quantity.
     * @param reserved the quantity currently reserved against orders.
     */
    public StoredItem(String sku, int onHand, int reserved) {
        this(sku, onHand, reserved, null);
    }
}
