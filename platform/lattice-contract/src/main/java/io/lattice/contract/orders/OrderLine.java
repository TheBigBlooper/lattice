package io.lattice.contract.orders;

/**
 * One line of an order: a stock-keeping unit (sku) and its quantity. Embedded in an {@link Order}
 * (no line id); mirrors the {@code OrderLine} schema in the v1 OpenAPI contract and the {@code
 * lines} nested mapping in Elasticsearch.
 *
 * @param sku      the stock-keeping unit for this line.
 * @param quantity the quantity for this line (at least 1).
 */
public record OrderLine(String sku, int quantity) {}
