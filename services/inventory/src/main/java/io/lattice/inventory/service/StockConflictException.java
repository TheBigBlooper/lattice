package io.lattice.inventory.service;

/**
 * Signals a reserve or set-stock request that conflicts with current stock state - the business
 * outcome the route maps to 409 {@code CONFLICT}. It covers the three conflict cases the inventory
 * invariants reject: insufficient available stock to reserve, a repeat reservation for the same order
 * line with a different quantity, and a set-stock that would drop on-hand below what is reserved.
 */
public final class StockConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a human-readable summary of the conflict.
     *
     * @param message the conflict summary.
     */
    public StockConflictException(String message) {
        super(message);
    }
}
