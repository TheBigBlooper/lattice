package io.lattice.common.es;

/**
 * The single-writer definition of the {@code orders} Elasticsearch index mapping. Mappings are
 * owned by {@code lattice-common} (never defined or mutated from a service module), so the orders
 * service reads this constant and passes it to {@link EsRepository#ensureIndex(String, String)}
 * rather than declaring its own.
 *
 * <p>The mapping is {@code dynamic: strict} (no unbounded dynamic fields - an unexpected field is
 * rejected on write rather than silently mistyped). All identifier and status and sku fields are
 * {@code keyword} (exact-match filters, aggregations, sorting); {@code quantity} is {@code integer};
 * {@code createdAt} is {@code date}. {@code lines} is {@code nested} so each line's sku and quantity
 * stay associated per-line (a later "orders containing sku X with quantity &gt; N" query matches
 * within one line, not across lines).
 */
public final class OrdersMapping {

    /** The logical index name (also the read alias): {@code orders}. */
    public static final String INDEX = "orders";

    /** The explicit {@code orders} mapping body (the {@code mappings} content). */
    public static final String MAPPING_JSON = """
            {
              "dynamic": "strict",
              "properties": {
                "orderId":    { "type": "keyword" },
                "customerId": { "type": "keyword" },
                "status":     { "type": "keyword" },
                "createdAt":  { "type": "date" },
                "lines": {
                  "type": "nested",
                  "properties": {
                    "sku":      { "type": "keyword" },
                    "quantity": { "type": "integer" }
                  }
                }
              }
            }
            """;

    private OrdersMapping() {
        // Constants holder - not instantiable.
    }
}
