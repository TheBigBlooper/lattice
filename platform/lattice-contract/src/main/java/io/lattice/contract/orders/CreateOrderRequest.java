package io.lattice.contract.orders;

import java.util.List;

/**
 * The create-order request body: the customer and the lines to order. Mirrors the strict {@code
 * CreateOrderRequest} schema in the v1 OpenAPI contract; it carries no id, status, or timestamp
 * (those are server-set on the resulting {@link Order}).
 *
 * @param customerId the customer the order is for.
 * @param lines      the requested order lines (at least one, at most 100).
 */
public record CreateOrderRequest(String customerId, List<CreateOrderLine> lines) {

    /**
     * Canonical constructor that stores an immutable copy of {@code lines} so the record cannot
     * expose or absorb an externally mutable list (it stays effectively immutable).
     */
    public CreateOrderRequest {
        lines = List.copyOf(lines);
    }
}
