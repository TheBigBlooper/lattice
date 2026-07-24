package io.lattice.contract.orders;

import java.util.List;

/**
 * A persisted order and its lines: the response shape returned by {@code createOrder} and {@code
 * getOrder}, and the document shape stored in Elasticsearch. Mirrors the {@code Order} schema in the
 * v1 OpenAPI contract.
 *
 * <p>The {@code orderId} and {@code createdAt} are server-set (a UUID and a UTC ISO-8601 instant);
 * a client-supplied id is never trusted. {@code createdAt} is carried as an ISO-8601 string (always
 * UTC, ending in {@code Z}) so it binds cleanly through the Elasticsearch client's JSON mapper into
 * the {@code date} field without a date-time module.
 *
 * @param orderId    the server-minted order id (UUID).
 * @param customerId the customer the order is for (echoed from the request).
 * @param status     the order status (always {@link OrderStatus#RECEIVED} on create).
 * @param lines      the order's lines, as submitted.
 * @param createdAt  the server-set creation timestamp, an ISO-8601 UTC string.
 */
public record Order(String orderId, String customerId, OrderStatus status, List<OrderLine> lines, String createdAt) {

    /**
     * Canonical constructor that stores an immutable copy of {@code lines} so the record cannot
     * expose or absorb an externally mutable list (it stays effectively immutable).
     */
    public Order {
        lines = List.copyOf(lines);
    }
}
