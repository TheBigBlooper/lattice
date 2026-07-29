package io.lattice.common.es;

/**
 * The single-writer definition of the {@code reservations} Elasticsearch index mapping. Mappings are
 * owned by {@code lattice-common} (never defined or mutated from a service module), so the inventory
 * service reads this constant and passes it to {@link EsRepository#ensureIndex(String, IndexDefinition)}
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

    /**
     * The explicit {@code reservations} index settings body (the {@code settings} content), applied
     * when the index is created.
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

    /**
     * The two bodies together, which is how the index is always created. Pairing them here means no
     * caller assembles the pair itself, so none can create the index from half its definition.
     */
    public static final IndexDefinition DEFINITION = new IndexDefinition(MAPPING_JSON, SETTINGS_JSON);

    private ReservationMapping() {
        // Constants holder - not instantiable.
    }
}
