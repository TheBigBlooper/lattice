package io.lattice.common.es;

/**
 * The single-writer definition of the {@code reservations} Elasticsearch index mapping. Mappings are
 * owned by {@code lattice-common} (never defined or mutated from a service module), so the inventory
 * service reads this constant and passes it to {@link EsRepository#ensureIndex(String, String)}
 * rather than declaring its own.
 *
 * <p>The mapping is {@code dynamic: strict} (an unexpected field is rejected on write rather than
 * silently mistyped). The document id is the order line {@code "<orderId>:<sku>"}, which makes the
 * reserve operation idempotent. All identifier and sku fields are {@code keyword} (exact-match
 * filters, aggregations); {@code quantity} is {@code integer}; {@code createdAt} is {@code date};
 * {@code status} is a {@code keyword} lifecycle state ({@code PENDING} while the gate winner is still
 * holding stock, {@code CONFIRMED} once the hold completes) so a concurrent reader can tell a
 * committed reservation from one still in flight, closing the post-gate rollback edge.
 */
public final class ReservationMapping {

    /** The logical index name (also the read alias): {@code reservations}. */
    public static final String INDEX = "reservations";

    /** The explicit {@code reservations} mapping body (the {@code mappings} content). */
    public static final String MAPPING_JSON = """
            {
              "dynamic": "strict",
              "properties": {
                "reservationId": { "type": "keyword" },
                "orderId":       { "type": "keyword" },
                "sku":           { "type": "keyword" },
                "quantity":      { "type": "integer" },
                "createdAt":     { "type": "date" },
                "status":        { "type": "keyword" }
              }
            }
            """;

    private ReservationMapping() {
        // Constants holder - not instantiable.
    }
}
