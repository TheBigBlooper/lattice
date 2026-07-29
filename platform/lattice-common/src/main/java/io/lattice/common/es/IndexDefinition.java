package io.lattice.common.es;

/**
 * The two bodies a logical index is created from, kept together so they cannot drift apart.
 *
 * <p>An index is defined by more than its mapping. Its settings decide the shape of the index itself
 * (how many replicas it expands to, per locked decision #67), and anything that creates an index
 * needs both or it creates a different index from the one that was committed. Passing the mapping
 * alone is exactly how a reindex silently reverted a baseline to the cluster default replica count.
 *
 * @param mappingJson  the explicit Elasticsearch mapping body (the {@code mappings} content, e.g.
 *                     {@code {"dynamic":"strict","properties":{...}}}).
 * @param settingsJson the explicit Elasticsearch index settings body (the {@code settings} content,
 *                     e.g. {@code {"auto_expand_replicas":"0-1"}}), or {@code null} to create the
 *                     index with the cluster defaults.
 */
public record IndexDefinition(String mappingJson, String settingsJson) {}
