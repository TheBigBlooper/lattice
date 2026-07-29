package io.lattice.common.es;

/**
 * The single-writer definition of the {@code inventory} Elasticsearch index mapping. Mappings are
 * owned by {@code lattice-common} (never defined or mutated from a service module), so the inventory
 * service reads this constant and passes it to {@link EsRepository#ensureIndex(String, String)}
 * rather than declaring its own.
 *
 * <p>The mapping is {@code dynamic: strict} (an unexpected field is rejected on write rather than
 * silently mistyped). The document id is the {@code sku}. Only {@code sku}, {@code onHand}, and
 * {@code reserved} are stored: {@code available} is computed ({@code onHand - reserved}) at read and
 * never persisted, so it is deliberately absent from the mapping (a strict mapping would reject it).
 * {@code sku} is {@code keyword} (exact-match filters, aggregations); {@code onHand} and
 * {@code reserved} are {@code integer}.
 */
public final class InventoryMapping {

    /** The logical index name (also the read alias): {@code inventory}. */
    public static final String INDEX = "inventory";

    /**
     * The explicit {@code inventory} index settings body (the {@code settings} content), applied when
     * the index is created.
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

    /** The explicit {@code inventory} mapping body (the {@code mappings} content). */
    public static final String MAPPING_JSON = """
            {
              "dynamic": "strict",
              "properties": {
                "sku":      { "type": "keyword" },
                "onHand":   { "type": "integer" },
                "reserved": { "type": "integer" }
              }
            }
            """;

    private InventoryMapping() {
        // Constants holder - not instantiable.
    }
}
