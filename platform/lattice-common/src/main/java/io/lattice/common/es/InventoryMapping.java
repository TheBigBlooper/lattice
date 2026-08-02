package io.lattice.common.es;

/**
 * The single-writer definition of the {@code inventory} Elasticsearch index mapping. Mappings are
 * owned by {@code lattice-common} (never defined or mutated from a service module), so the inventory
 * service reads this constant and passes it to {@link EsRepository#ensureIndex(String, IndexDefinition)}
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

    /**
     * The {@code inventory} mapping as the diverging baseline holds it: the base fields plus
     * {@code binLocation}, a warehouse position this baseline records and its peers do not.
     *
     * <p>Locked #14 gives every cluster its own, possibly-divergent model, and this is that decision
     * made visible rather than merely permitted. The divergence is additive, so a peer reading this
     * baseline's documents over the contract is unaffected - nothing crosses the mesh anyway
     * (locked #37), and the operator is redirected to the owning baseline to see it.
     */
    public static final String MAPPING_JSON_WITH_BIN_LOCATION = """
            {
              "dynamic": "strict",
              "properties": {
                "sku":         { "type": "keyword" },
                "onHand":      { "type": "integer" },
                "reserved":    { "type": "integer" },
                "binLocation": { "type": "keyword" }
              }
            }
            """;

    /**
     * The baseline whose inventory model carries {@link #MAPPING_JSON_WITH_BIN_LOCATION}.
     *
     * <p>One rather than several, so the contrast is legible: two baselines agree and one does not.
     */
    private static final String DIVERGING_CLUSTER_ID = "hub-west";

    /**
     * The two bodies together, which is how the index is always created. Pairing them here means no
     * caller assembles the pair itself, so none can create the index from half its definition.
     *
     * <p>This is the base model, which every baseline but {@value #DIVERGING_CLUSTER_ID} holds. Callers
     * that know their cluster id should use {@link #definitionFor(String)} instead.
     */
    public static final IndexDefinition DEFINITION = new IndexDefinition(MAPPING_JSON, SETTINGS_JSON);

    /** The diverging baseline's definition, paired with the same settings every baseline uses. */
    private static final IndexDefinition DEFINITION_WITH_BIN_LOCATION =
            new IndexDefinition(MAPPING_JSON_WITH_BIN_LOCATION, SETTINGS_JSON);

    /**
     * The inventory index definition this baseline owns.
     *
     * <p>Taking the cluster id rather than reading configuration keeps the decision in one testable
     * place, and keeps this class free of any dependency on how a service is configured.
     *
     * @param clusterId this baseline's cluster id; an unknown or blank id gets the base model, because
     *     a customer names their own clusters and the divergence is a demonstration rather than a
     *     requirement.
     * @return the definition to create or update the index from.
     */
    public static IndexDefinition definitionFor(String clusterId) {
        return hasBinLocation(clusterId) ? DEFINITION_WITH_BIN_LOCATION : DEFINITION;
    }

    /**
     * Whether this baseline's inventory model declares {@code binLocation}.
     *
     * <p>The single source of that fact: the mapping and the seed data must agree, because the mapping
     * is {@code dynamic: strict} and a document carrying a field the index does not declare is rejected
     * outright rather than stored.
     *
     * @param clusterId this baseline's cluster id.
     * @return {@code true} only for the diverging baseline.
     */
    public static boolean hasBinLocation(String clusterId) {
        return DIVERGING_CLUSTER_ID.equals(clusterId);
    }

    private InventoryMapping() {
        // Constants holder - not instantiable.
    }
}
