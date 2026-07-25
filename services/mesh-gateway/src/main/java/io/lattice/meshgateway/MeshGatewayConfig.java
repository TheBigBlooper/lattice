package io.lattice.meshgateway;

import io.lattice.common.config.LatticeConfig;
import io.vertx.amqp.AmqpClientOptions;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mesh-gateway's own configuration, read through the shared {@link LatticeConfig} loader rather
 * than by reaching for the environment directly.
 *
 * <p>It is a service-local record instead of accessors on {@code LatticeConfig} because every value
 * here is meaningful only to this service - no other service joins the mesh - so putting them on the
 * shared loader would grow a common surface for one caller.
 *
 * @param clusterId       this cluster's stable id, announced to peers.
 * @param region          this cluster's region label.
 * @param baselineVersion the baseline version this cluster reports running.
 * @param consoleUrl      this cluster's own console root, the target of a federation redirect.
 * @param apiBaseUrl      this cluster's REST API base, which a peer's unified view reads live.
 * @param brokerUrl       the Artemis broker URL.
 * @param brokerUser      the broker username.
 * @param brokerPassword  the broker password.
 * @param services        the services whose readiness forms this cluster's health rollup, by name.
 * @param heartbeat       how often this cluster re-announces (the liveness signal).
 * @param peerTimeToLive  how long a peer may stay silent before it counts as unreachable.
 */
public record MeshGatewayConfig(
        String clusterId,
        String region,
        String baselineVersion,
        String consoleUrl,
        String apiBaseUrl,
        String brokerUrl,
        String brokerUser,
        String brokerPassword,
        Map<String, String> services,
        Duration heartbeat,
        Duration peerTimeToLive) {

    private static final String DEFAULT_BROKER_URL = "tcp://localhost:61616";
    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_PEER_TTL = Duration.ofSeconds(30);
    private static final int DEFAULT_BROKER_PORT = 61616;

    /** Defensive copy: the services map is exposed on a record accessor. */
    public MeshGatewayConfig {
        services = Map.copyOf(services);
    }

    /**
     * Reads the mesh-gateway configuration from the shared loader, applying local defaults.
     *
     * @param config the loaded shared configuration.
     * @return this service's configuration.
     */
    public static MeshGatewayConfig from(LatticeConfig config) {
        return new MeshGatewayConfig(
                config.getString("CLUSTER_ID").orElse("hub-local"),
                config.getString("REGION").orElse("local"),
                config.getString("BASELINE_VERSION").orElse("0.1.0-SNAPSHOT"),
                config.getString("CONSOLE_URL").orElse("http://localhost:3000"),
                config.getString("API_BASE_URL").orElse("http://localhost:8080/api/v1"),
                config.getString("ARTEMIS_URL").orElse(DEFAULT_BROKER_URL),
                config.getString("ARTEMIS_USER").orElse("artemis"),
                config.getString("ARTEMIS_PASSWORD").orElse("artemis"),
                parseServices(config.getString("CLUSTER_SERVICES").orElse("")),
                parseDuration(config.getString("HEARTBEAT_INTERVAL").orElse(""), DEFAULT_HEARTBEAT),
                parseDuration(config.getString("PEER_TTL").orElse(""), DEFAULT_PEER_TTL));
    }

    /**
     * Parses the {@code CLUSTER_SERVICES} list, of the form
     * {@code orders=http://orders:8080,inventory=http://inventory:8080}. A malformed entry is skipped
     * rather than failing startup, so one typo cannot stop the cluster announcing itself at all.
     *
     * @param raw the configured value (possibly empty).
     * @return service name to base URL, in declaration order; empty when nothing is configured.
     */
    static Map<String, String> parseServices(String raw) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        for (var entry : raw.split(",")) {
            var separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            var name = entry.substring(0, separator).trim();
            var url = entry.substring(separator + 1).trim();
            if (!name.isEmpty() && !url.isEmpty()) {
                parsed.put(name, url);
            }
        }
        return parsed;
    }

    /**
     * Parses a duration written as {@code 10s} / {@code 30s} (or plain seconds), falling back to the
     * default when unset or unreadable - a bad value must not stop the service starting.
     */
    static Duration parseDuration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        var trimmed = raw.trim();
        var digits = trimmed.endsWith("s") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        try {
            return Duration.ofSeconds(Long.parseLong(digits.trim()));
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    /**
     * Builds the broker connection options from the configured URL, which carries a scheme this
     * client does not use ({@code tcp://}) because the same value addresses the broker for every
     * protocol it serves.
     *
     * @return the AMQP client options for this cluster's broker.
     */
    public AmqpClientOptions brokerOptions() {
        var uri = URI.create(brokerUrl);
        return new AmqpClientOptions()
                .setHost(uri.getHost() == null ? "localhost" : uri.getHost())
                .setPort(uri.getPort() == -1 ? DEFAULT_BROKER_PORT : uri.getPort())
                .setUsername(brokerUser)
                .setPassword(brokerPassword);
    }
}
