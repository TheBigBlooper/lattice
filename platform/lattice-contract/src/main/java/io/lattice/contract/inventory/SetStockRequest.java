package io.lattice.contract.inventory;

/**
 * The set-stock request body: the absolute on-hand quantity to set for an item. Mirrors the strict
 * {@code SetStockRequest} schema in the v1 OpenAPI contract; it carries no reserved or available
 * (reserved is server-owned and available is computed).
 *
 * @param onHand the absolute on-hand quantity to set (zero or more).
 */
public record SetStockRequest(int onHand) {}
