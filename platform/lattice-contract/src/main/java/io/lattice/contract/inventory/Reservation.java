package io.lattice.contract.inventory;

/**
 * A persisted reservation of stock against an order line: the response shape returned by {@code
 * createReservation} and the document shape stored in Elasticsearch. Mirrors the {@code Reservation}
 * schema in the v1 OpenAPI contract.
 *
 * <p>The reservation is keyed by the order line {@code (orderId, sku)} - that pair is the
 * Elasticsearch document id ({@code "<orderId>:<sku>"}), which makes the reserve operation
 * idempotent. The {@code reservationId} and {@code createdAt} are server-set (a UUID and a UTC
 * ISO-8601 instant); {@code createdAt} is carried as an ISO-8601 string (always UTC, ending in
 * {@code Z}) so it binds cleanly through the Elasticsearch client's JSON mapper into the {@code date}
 * field without a date-time module.
 *
 * @param reservationId the server-minted reservation id (UUID).
 * @param orderId       the order the reservation is for.
 * @param sku           the stock-keeping unit reserved.
 * @param quantity      the reserved quantity.
 * @param createdAt     the server-set creation timestamp, an ISO-8601 UTC string.
 */
public record Reservation(String reservationId, String orderId, String sku, int quantity, String createdAt) {}
