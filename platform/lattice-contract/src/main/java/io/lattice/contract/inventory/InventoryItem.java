package io.lattice.contract.inventory;

/**
 * An item's stock as returned by {@code setStock} and {@code getInventory}: the response shape in
 * the success envelope. Mirrors the {@code InventoryItem} schema in the v1 OpenAPI contract.
 *
 * <p>{@code available} is computed ({@code onHand - reserved}) at read and is never stored in
 * Elasticsearch (the {@code inventory} mapping is strict over only {@code sku}, {@code onHand}, and
 * {@code reserved}); this record is the read/response projection, not the persisted document.
 *
 * @param sku       the stock-keeping unit this item is for.
 * @param onHand    the absolute on-hand quantity.
 * @param reserved  the quantity currently reserved against orders.
 * @param available the computed available quantity ({@code onHand - reserved}).
 */
public record InventoryItem(String sku, int onHand, int reserved, int available) {}
