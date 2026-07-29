package io.lattice.inventory.repository;

import io.lattice.contract.inventory.Reservation;

/**
 * The persisted shape of a reservation in Elasticsearch: the contract {@link Reservation}'s fields
 * plus the internal {@link ReservationStatus} lifecycle the {@code reservations} mapping stores. This
 * is a persistence projection, not a REST DTO - the wire shape ({@link Reservation}) never exposes
 * {@code status}, so the two are kept as separate types rather than one leaking the other's field
 * (mirroring the {@code StoredItem} / {@code InventoryItem} split).
 *
 * @param reservationId the server-minted reservation id (UUID).
 * @param orderId       the order the reservation is for.
 * @param sku           the stock-keeping unit reserved.
 * @param quantity      the reserved quantity.
 * @param createdAt     the server-set creation timestamp, an ISO-8601 UTC string.
 * @param status        the reservation lifecycle state (gate {@code PENDING}, committed {@code CONFIRMED}).
 */
public record StoredReservation(
        String reservationId, String orderId, String sku, int quantity, String createdAt, ReservationStatus status) {

    /**
     * Projects this stored reservation to its contract {@link Reservation} response shape, dropping the
     * internal {@code status} the wire never carries.
     *
     * @return the contract reservation for the REST response.
     */
    public Reservation toReservation() {
        return new Reservation(reservationId, orderId, sku, quantity, createdAt);
    }

    /**
     * Returns a copy of this reservation in the {@link ReservationStatus#CONFIRMED} state, the promotion
     * written once the stock hold completes.
     *
     * @return the confirmed reservation.
     */
    public StoredReservation confirmed() {
        return new StoredReservation(reservationId, orderId, sku, quantity, createdAt, ReservationStatus.CONFIRMED);
    }
}
