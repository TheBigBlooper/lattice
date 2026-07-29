package io.lattice.contract.orders;

/**
 * One requested line in a create-order request: a sku and a quantity. Mirrors the {@code
 * CreateOrderLine} schema in the v1 OpenAPI contract (strict: no client-supplied line id).
 *
 * @param sku      the stock-keeping unit for this line.
 * @param quantity the requested quantity for this line (at least 1).
 */
public record CreateOrderLine(String sku, int quantity) {}
