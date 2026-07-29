package io.lattice.common.es;

/**
 * The single-writer definition of the {@code orders} Elasticsearch index mapping. Mappings are
 * owned by {@code lattice-common} (never defined or mutated from a service module), so the orders
 * service reads this constant and passes it to {@link EsRepository#ensureIndex(String, IndexDefinition)}
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

    /**
     * The explicit {@code orders} index settings body (the {@code settings} content), applied when the
     * index is created.
     *
     * <p>{@code auto_expand_replicas: "0-1"} lets Elasticsearch derive the replica count from the
     * number of nodes actually available rather than pinning it at the default of one (locked decision
     * #67). A single-node cluster cannot allocate a replica of its own primary, so a fixed replica
     * leaves the shard unassigned and the cluster permanently yellow; with this setting the same
     * cluster reports zero replicas and green. A multi-node cluster expands back to one replica, so
     * redundancy is preserved and yellow keeps meaning a genuine loss of it.
     */
    public static final String SETTINGS_JSON = """
            {
              "auto_expand_replicas": "0-1"
            }
            """;

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

    /**
     * The two bodies together, which is how the index is always created. Pairing them here means no
     * caller assembles the pair itself, so none can create the index from half its definition.
     */
    public static final IndexDefinition DEFINITION = new IndexDefinition(MAPPING_JSON, SETTINGS_JSON);

    private OrdersMapping() {
        // Constants holder - not instantiable.
    }
}
