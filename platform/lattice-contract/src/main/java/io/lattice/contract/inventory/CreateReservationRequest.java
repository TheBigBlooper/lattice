package io.lattice.contract.inventory;

/**
 * The create-reservation request body: the order line to reserve against ({@code orderId} +
 * {@code sku}) and the quantity to reserve. Mirrors the strict {@code CreateReservationRequest}
 * schema in the v1 OpenAPI contract; it carries no reservation id or timestamp (those are server-set
 * on the resulting {@link Reservation}).
 *
 * @param orderId  the order the reservation is for.
 * @param sku      the stock-keeping unit to reserve.
 * @param quantity the quantity to reserve (at least 1).
 */
public record CreateReservationRequest(String orderId, String sku, int quantity) {}
