package io.lattice.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LatticeConfig}, the shared configuration facade. Pins the documented
 * defaults (applied when a key is absent), explicit-override precedence, and the optional
 * accessor's present/absent behavior.
 */
class LatticeConfigTest {

    /** With no values supplied, the Elasticsearch URL falls back to the documented local default. */
    @Test
    void elasticsearchUrlDefaultsToLocalNodeWhenUnset() {
        var config = new LatticeConfig(new JsonObject());
        assertEquals("http://localhost:9200", config.elasticsearchUrl());
    }

    /** An explicit ELASTICSEARCH_URL value overrides the default. */
    @Test
    void elasticsearchUrlUsesExplicitValueWhenSet() {
        var config = new LatticeConfig(new JsonObject().put(LatticeConfig.ELASTICSEARCH_URL, "http://es:9200"));
        assertEquals("http://es:9200", config.elasticsearchUrl());
    }

    /** With no values supplied, the HTTP port falls back to the documented default of 8080. */
    @Test
    void httpPortDefaultsTo8080WhenUnset() {
        var config = new LatticeConfig(new JsonObject());
        assertEquals(8080, config.httpPort());
    }

    /** An explicit HTTP_PORT value overrides the default. */
    @Test
    void httpPortUsesExplicitValueWhenSet() {
        var config = new LatticeConfig(new JsonObject().put(LatticeConfig.HTTP_PORT, 9999));
        assertEquals(9999, config.httpPort());
    }

    /** A null backing object is tolerated and every key reads as absent (defaults apply). */
    @Test
    void nullValuesAreTreatedAsEmpty() {
        var config = new LatticeConfig(null);
        assertEquals("http://localhost:9200", config.elasticsearchUrl());
        assertFalse(config.getString("ANYTHING").isPresent());
    }

    /** The optional accessor returns the value when present and empty when absent. */
    @Test
    void getStringReflectsPresenceOfKey() {
        var config = new LatticeConfig(new JsonObject().put("PRESENT", "yes"));
        assertEquals(Optional.of("yes"), config.getString("PRESENT"));
        assertTrue(config.getString("MISSING").isEmpty());
    }
}
