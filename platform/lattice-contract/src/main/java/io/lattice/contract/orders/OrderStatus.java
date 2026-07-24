package io.lattice.contract.orders;

/**
 * The declared order lifecycle status. Mirrors the {@code OrderStatus} enum in the v1 OpenAPI
 * contract so the REST shape, the Elasticsearch mapping, and this type stay in step.
 *
 * <p>The full lifecycle is declared now ({@code RECEIVED -> ALLOCATED -> PACKED -> SHIPPED ->
 * DELIVERED}) so adding transition endpoints later does not reshape the {@code status} field or the
 * mapping. In the MVP an order is created as {@link #RECEIVED}; there are no transitions yet.
 */
public enum OrderStatus {

    /** The order has been received (the create-time status). */
    RECEIVED,

    /** Stock has been allocated to the order. */
    ALLOCATED,

    /** The order has been packed. */
    PACKED,

    /** The order has shipped. */
    SHIPPED,

    /** The order has been delivered. */
    DELIVERED
}
