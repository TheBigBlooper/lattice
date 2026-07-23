package io.lattice.common.config;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/**
 * The shared, read-only configuration facade every Lattice service reads through, so config
 * access is centralized here rather than scattering {@code System.getenv} calls across the code.
 *
 * <p>Values are loaded once from the process environment via vertx-config (an environment store)
 * and then read through typed accessors that apply the documented defaults. The instance is an
 * immutable snapshot: the backing {@link JsonObject} is defensively copied on construction.
 *
 * @see #load(Vertx)
 */
public final class LatticeConfig {

    /** Environment variable naming the Elasticsearch HTTP endpoint. */
    public static final String ELASTICSEARCH_URL = "ELASTICSEARCH_URL";

    /** Environment variable naming the port the service HTTP server binds. */
    public static final String HTTP_PORT = "HTTP_PORT";

    private static final String DEFAULT_ELASTICSEARCH_URL = "http://localhost:9200";
    private static final int DEFAULT_HTTP_PORT = 8080;

    private final JsonObject values;

    /**
     * Wraps an already-loaded set of configuration values.
     *
     * @param values the raw configuration values (e.g. the environment snapshot); defensively copied.
     */
    public LatticeConfig(JsonObject values) {
        this.values = values == null ? new JsonObject() : values.copy();
    }

    /**
     * Loads configuration from the process environment through vertx-config, off the caller's
     * control flow (the returned future completes when the environment snapshot is read).
     *
     * @param vertx the Vert.x instance whose event loop runs the retrieval.
     * @return a future of the loaded configuration.
     */
    public static Future<LatticeConfig> load(Vertx vertx) {
        var envStore = new ConfigStoreOptions().setType("env");
        var options = new ConfigRetrieverOptions().addStore(envStore);
        return ConfigRetriever.create(vertx, options).getConfig().map(LatticeConfig::new);
    }

    /**
     * Returns the configured Elasticsearch endpoint, defaulting to a local single node.
     *
     * @return the Elasticsearch HTTP endpoint URL.
     */
    public String elasticsearchUrl() {
        return values.getString(ELASTICSEARCH_URL, DEFAULT_ELASTICSEARCH_URL);
    }

    /**
     * Returns the configured HTTP server port, defaulting to 8080.
     *
     * @return the HTTP server port.
     */
    public int httpPort() {
        return values.getInteger(HTTP_PORT, DEFAULT_HTTP_PORT);
    }

    /**
     * Returns an optional string configuration value by key (empty when unset).
     *
     * @param key the configuration key.
     * @return the value if present, otherwise empty.
     */
    public Optional<String> getString(String key) {
        return Optional.ofNullable(values.getString(key));
    }
}
