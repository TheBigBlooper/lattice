package io.lattice.meshgateway;

import io.lattice.common.config.LatticeConfig;
import io.lattice.contract.mesh.ComponentKind;
import io.vertx.amqp.AmqpClientOptions;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

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
 * @param infrastructure  the components this cluster reports on beside its services; never part of
 *                        the announced verdict, and empty when none is configured.
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
        List<InfrastructureTarget> infrastructure,
        Duration heartbeat,
        Duration peerTimeToLive,
        int brokerTlsPort) {

    /**
     * The same configuration with the broker's TLS port left at its default.
     *
     * <p>Every caller that does not care about federation reading keeps its existing shape, which is
     * most of them: the port matters only to the certificate read.
     *
     * @param clusterId       this cluster's stable id.
     * @param region          this cluster's region label.
     * @param baselineVersion the baseline this cluster runs.
     * @param consoleUrl      this cluster's own console root.
     * @param apiBaseUrl      this cluster's REST API base.
     * @param brokerUrl       this cluster's own broker address.
     * @param brokerUser      the broker user.
     * @param brokerPassword  the broker password.
     * @param services        service name to base URL.
     * @param infrastructure  the infrastructure components to report on.
     * @param heartbeat       the announce interval.
     * @param peerTimeToLive  how long silence takes to become unreachable.
     */
    public MeshGatewayConfig(
            String clusterId,
            String region,
            String baselineVersion,
            String consoleUrl,
            String apiBaseUrl,
            String brokerUrl,
            String brokerUser,
            String brokerPassword,
            Map<String, String> services,
            List<InfrastructureTarget> infrastructure,
            Duration heartbeat,
            Duration peerTimeToLive) {
        this(
                clusterId,
                region,
                baselineVersion,
                consoleUrl,
                apiBaseUrl,
                brokerUrl,
                brokerUser,
                brokerPassword,
                services,
                infrastructure,
                heartbeat,
                peerTimeToLive,
                DEFAULT_BROKER_TLS_PORT);
    }

    /**
     * The host-published broker address, used only when nothing configures one - a service run
     * outside Docker against the compose stack. Compose publishes 41616 rather than 61616 because
     * Windows reserves blocks inside the ephemeral range (49152-65535); the CONTAINER port is still
     * 61616, which is what every deployed service addresses.
     */
    private static final String DEFAULT_BROKER_URL = "tcp://localhost:41616";

    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_PEER_TTL = Duration.ofSeconds(30);
    private static final int DEFAULT_BROKER_PORT = 61616;

    /**
     * The broker's federation acceptor, which is where its certificate is presented.
     *
     * <p>Matches the chart's {@code federationPort} default. Overridable with
     * {@code ARTEMIS_TLS_PORT} so a deployment that moves that acceptor does not have to move this
     * too by editing code - the same rule that keeps every variable declared in the chart beside the
     * component that reads it (locked #77).
     */
    private static final int DEFAULT_BROKER_TLS_PORT = 61617;

    /**
     * Defensive copy that PRESERVES ORDER: Map.copyOf is unordered and randomizes its iteration seed
     * per JVM start, which would scramble the configured service order between runs.
     */
    public MeshGatewayConfig {
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        infrastructure = List.copyOf(infrastructure);
    }

    /**
     * Reads the mesh-gateway configuration from the shared loader, applying local defaults.
     *
     * @param config the loaded shared configuration.
     * @return this service's configuration.
     */
    public static MeshGatewayConfig from(LatticeConfig config) {
        return new MeshGatewayConfig(
                config.getString("CLUSTER_ID").orElse("hub-central"),
                config.getString("REGION").orElse("us-central"),
                config.getString("BASELINE_VERSION").orElse("0.1.0-SNAPSHOT"),
                config.getString("CONSOLE_URL").orElse("http://localhost:3000"),
                config.getString("API_BASE_URL").orElse("http://localhost:8080/api/v1"),
                config.getString("ARTEMIS_URL").orElse(DEFAULT_BROKER_URL),
                config.getString("ARTEMIS_USER").orElse("artemis"),
                config.getString("ARTEMIS_PASSWORD").orElse("artemis"),
                parseServices(config.getString("CLUSTER_SERVICES").orElse("")),
                parseInfrastructure(config.getString("CLUSTER_INFRASTRUCTURE").orElse("")),
                parseDuration(config.getString("HEARTBEAT_INTERVAL").orElse(""), DEFAULT_HEARTBEAT),
                parseDuration(config.getString("PEER_TTL").orElse(""), DEFAULT_PEER_TTL),
                parsePort(config.getString("ARTEMIS_TLS_PORT").orElse(""), DEFAULT_BROKER_TLS_PORT));
    }

    /** Parses a port, falling back rather than failing startup over one malformed value. */
    private static int parsePort(String raw, int fallback) {
        try {
            return raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    /**
     * Where this baseline's own broker presents its certificate.
     *
     * <p>The same host as the AMQP address, on the federation acceptor rather than the messaging one:
     * the certificate a peer refuses is the one served there. Read by completing a handshake, because
     * Artemis exposes nothing about its own certificate through management (locked #80).
     *
     * @return the broker host, for a TLS read on {@link #brokerTlsPort()}.
     */
    public String brokerTlsHost() {
        var uri = URI.create(brokerUrl);
        return uri.getHost() == null ? "localhost" : uri.getHost();
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
        forEachEntry(raw, true, parsed::put);
        return parsed;
    }

    /**
     * Parses the {@code CLUSTER_INFRASTRUCTURE} list, of the form
     * {@code elasticsearch:elasticsearch=http://es:9200,artemis:artemis=}. Each entry is
     * {@code name:kind=url}: the name is the label the console renders, so a deployment may call a
     * component whatever it calls it, while the kind is what tells the gateway which probe to run.
     *
     * <p>A separate variable rather than a category added to {@code CLUSTER_SERVICES}, which keeps
     * exactly the meaning it has today: redefining a deployed variable's syntax would misparse on
     * upgrade unless every compose file, the chart and the documented example changed together.
     *
     * <p>Read by the same entry splitting as the service list, with the value made optional - the
     * Artemis entry carries no URL, because its state comes from the gateway's own broker connection
     * rather than from a probe. A malformed entry is skipped rather than failing startup, so one typo
     * cannot stop the cluster announcing itself at all.
     *
     * @param raw the configured value (possibly empty).
     * @return the configured infrastructure targets, in declaration order; empty when unset.
     */
    static List<InfrastructureTarget> parseInfrastructure(String raw) {
        List<InfrastructureTarget> parsed = new ArrayList<>();
        forEachEntry(raw, false, (label, url) -> {
            var separator = label.indexOf(':');
            if (separator <= 0 || separator == label.length() - 1) {
                return;
            }
            var name = label.substring(0, separator).trim();
            var kind = kindOf(label.substring(separator + 1).trim());
            if (name.isEmpty() || kind == null) {
                return;
            }
            // A probed kind with no target is nothing the gateway could poll, so it is a malformed
            // entry rather than a component to report as permanently down.
            if (kind != ComponentKind.ARTEMIS && url.isEmpty()) {
                return;
            }
            parsed.add(new InfrastructureTarget(name, kind, url));
        });
        return List.copyOf(parsed);
    }

    /** The component kind written in a configuration entry, or {@code null} when it names none. */
    private static ComponentKind kindOf(String written) {
        for (var kind : ComponentKind.values()) {
            if (kind.wire().equals(written.toLowerCase(Locale.ROOT))) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Splits a comma-separated {@code name=value} list and hands each well-formed entry to the sink,
     * skipping the rest. Shared by both configured lists so they cannot drift apart in what they
     * tolerate; {@code requireValue} is the single difference between them, since an infrastructure
     * entry may legitimately carry no URL.
     */
    private static void forEachEntry(String raw, boolean requireValue, BiConsumer<String, String> sink) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (var entry : raw.split(",")) {
            var separator = entry.indexOf('=');
            if (separator == 0 || (separator < 0 && requireValue)) {
                continue;
            }
            var name = (separator < 0 ? entry : entry.substring(0, separator)).trim();
            var value = separator < 0 ? "" : entry.substring(separator + 1).trim();
            if (name.isEmpty() || (requireValue && value.isEmpty())) {
                continue;
            }
            sink.accept(name, value);
        }
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
