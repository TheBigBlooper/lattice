package io.lattice.inventory.repository;

/**
 * The lifecycle state of a reservation record, persisted as a {@code keyword} on the {@code
 * reservations} index so a concurrent reader can tell a committed reservation from one still in
 * flight. The record is the atomic idempotency gate: it is created {@link #PENDING} the instant a
 * caller wins the gate, then promoted to {@link #CONFIRMED} only after the stock hold succeeds. A
 * hold that cannot complete deletes the record rather than leaving a {@code PENDING} one behind.
 *
 * <p>This closes the post-gate rollback edge: a duplicate that observes a record trusts it as a
 * committed reservation only when it is {@code CONFIRMED} (a state never rolled back). A {@code
 * PENDING} record is still settling, so the duplicate waits for it to become {@code CONFIRMED} or
 * disappear rather than returning a reservation that may be about to be removed.
 */
public enum ReservationStatus {

    /** The gate winner created the record but has not yet completed (and confirmed) the stock hold. */
    PENDING,

    /** The stock hold completed; the reservation is committed and will not be rolled back. */
    CONFIRMED
}
