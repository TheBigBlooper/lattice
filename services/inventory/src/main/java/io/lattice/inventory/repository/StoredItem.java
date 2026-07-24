package io.lattice.inventory.repository;

/**
 * The persisted shape of an inventory item in Elasticsearch: exactly the fields the strict {@code
 * inventory} mapping stores ({@code sku}, {@code onHand}, {@code reserved}). This is a persistence
 * projection, not a REST DTO - the wire shape adds a computed {@code available}
 * ({@code io.lattice.contract.inventory.InventoryItem}), which the {@code inventory} mapping does not
 * store, so the two are kept as separate types rather than one leaking the other's field.
 *
 * @param sku      the stock-keeping unit (also the document id).
 * @param onHand   the absolute on-hand quantity.
 * @param reserved the quantity currently reserved against orders.
 */
public record StoredItem(String sku, int onHand, int reserved) {}
