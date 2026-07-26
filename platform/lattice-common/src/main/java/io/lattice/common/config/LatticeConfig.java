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

    /**
     * Environment variable naming this baseline's own Keycloak base URL. Single-valued by design: a
     * service never addresses a peer's Keycloak, for the same reason it never addresses a peer's broker.
     */
    public static final String KEYCLOAK_URL = "KEYCLOAK_URL";

    /** Environment variable naming this baseline's Keycloak realm. */
    public static final String KEYCLOAK_REALM = "KEYCLOAK_REALM";

    /**
     * Environment variable naming where a service reaches Keycloak from inside the cluster, when that
     * differs from the browser-facing {@link #KEYCLOAK_URL} a token's issuer claim carries. Optional -
     * unset means the two are the same address.
     */
    public static final String KEYCLOAK_INTERNAL_URL = "KEYCLOAK_INTERNAL_URL";

    /**
     * Environment variable gating the OpenAPI document at {@code /docs/json}. Optional - unset means
     * ON, so a developer never loses the contract to a value nobody set; a prod environment sets it
     * to {@code false} explicitly, which a deploy can be checked for.
     */
    public static final String API_DOCS_ENABLED = "API_DOCS_ENABLED";

    private static final String DEFAULT_ELASTICSEARCH_URL = "http://localhost:9200";
    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final boolean DEFAULT_API_DOCS_ENABLED = true;

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
     * Returns this baseline's own realm URL as a token's issuer claims it, composed from
     * {@link #KEYCLOAK_URL} and {@link #KEYCLOAK_REALM}.
     *
     * @return the realm URL, or an empty string when either setting is unset.
     */
    public String keycloakRealmUrl() {
        return realmUrl(values.getString(KEYCLOAK_URL, ""));
    }

    /**
     * Returns the realm URL a service <em>reaches</em> Keycloak at, which is not always the one a token
     * claims: in a cluster the console's browser reaches Keycloak through a published address while a
     * service reaches it by an internal one. Falls back to {@link #keycloakRealmUrl()} when
     * {@link #KEYCLOAK_INTERNAL_URL} is unset, so a deployment where the two are the same sets one key.
     *
     * @return the realm URL to fetch signing keys from.
     */
    public String keycloakInternalRealmUrl() {
        var internal = realmUrl(values.getString(KEYCLOAK_INTERNAL_URL, ""));
        return internal.isBlank() ? keycloakRealmUrl() : internal;
    }

    /** Composes a base URL and the configured realm into a realm URL, or empty when either is unset. */
    private String realmUrl(String baseUrl) {
        var url = baseUrl == null ? "" : baseUrl.strip();
        var realm = values.getString(KEYCLOAK_REALM, "").strip();
        if (url.isBlank() || realm.isBlank()) {
            return "";
        }
        var base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return base + "/realms/" + realm;
    }

    /**
     * Whether the OpenAPI document should be served at {@code /docs/json}.
     *
     * <p>Only the exact string {@code false} turns it off, and that strictness is the point: a
     * mistyped value must not be read as "off" and quietly withdraw the contract in dev, nor be read
     * as "on" and publish it in prod. Anything unrecognised keeps the documented default, and the
     * prod deploy is checked for the explicit {@code false} rather than trusted to be tidy.
     *
     * @return {@code true} unless the setting is exactly {@code false} (case-insensitive).
     */
    public boolean apiDocsEnabled() {
        var configured = values.getString(API_DOCS_ENABLED, "").strip();
        return configured.isEmpty() ? DEFAULT_API_DOCS_ENABLED : !"false".equalsIgnoreCase(configured);
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
