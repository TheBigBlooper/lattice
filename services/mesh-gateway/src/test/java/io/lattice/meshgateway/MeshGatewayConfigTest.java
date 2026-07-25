package io.lattice.meshgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.config.LatticeConfig;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MeshGatewayConfig}, the mesh-gateway's own configuration parsing.
 *
 * <p>The parsing here is deliberately forgiving: this service is a cluster's only voice on the mesh,
 * so a single malformed entry in a list must never stop it announcing itself at all. These tests pin
 * that tolerance, and the defaults that apply when nothing is configured.
 */
class MeshGatewayConfigTest {

    private static MeshGatewayConfig configFrom(JsonObject values) {
        return MeshGatewayConfig.from(new LatticeConfig(values));
    }

    /** With nothing configured, every value falls back to a usable local default. */
    @Test
    void appliesLocalDefaultsWhenNothingIsConfigured() {
        var config = configFrom(new JsonObject());

        assertEquals("hub-local", config.clusterId());
        assertEquals("local", config.region());
        assertEquals("tcp://localhost:61616", config.brokerUrl());
        assertEquals(Duration.ofSeconds(10), config.heartbeat());
        assertEquals(Duration.ofSeconds(30), config.peerTimeToLive());
        assertTrue(config.services().isEmpty());
    }

    /** Configured values override the defaults, including this cluster's advertised endpoints. */
    @Test
    void readsConfiguredValues() {
        var config = configFrom(new JsonObject()
                .put("CLUSTER_ID", "hub-west")
                .put("REGION", "us-west")
                .put("BASELINE_VERSION", "1.2.3")
                .put("CONSOLE_URL", "https://west.console:3000")
                .put("API_BASE_URL", "https://west.svc:8080/api/v1")
                .put("ARTEMIS_URL", "tcp://broker.internal:61617"));

        assertEquals("hub-west", config.clusterId());
        assertEquals("us-west", config.region());
        assertEquals("1.2.3", config.baselineVersion());
        assertEquals("https://west.console:3000", config.consoleUrl());
        assertEquals("https://west.svc:8080/api/v1", config.apiBaseUrl());
        assertEquals("tcp://broker.internal:61617", config.brokerUrl());
    }

    /** The service list parses into ordered name-to-url pairs, which is what the health poll fans out over. */
    @Test
    void parsesTheServiceList() {
        var services = MeshGatewayConfig.parseServices("orders=http://orders:8080,inventory=http://inventory:8080");

        assertEquals(List.of("orders", "inventory"), List.copyOf(services.keySet()));
        assertEquals("http://orders:8080", services.get("orders"));
    }

    /**
     * A malformed entry is skipped rather than failing the whole list, so one typo cannot silence a
     * cluster's announcements entirely - the well-formed services are still watched.
     */
    @Test
    void skipsMalformedServiceEntriesRatherThanFailing() {
        var services = MeshGatewayConfig.parseServices("orders=http://orders:8080,garbage,=http://nameless,trailing=");

        assertEquals(List.of("orders"), List.copyOf(services.keySet()));
    }

    /** An unset or blank service list is empty rather than an error - nothing is watched, loudly. */
    @Test
    void treatsAnAbsentServiceListAsEmpty() {
        assertTrue(MeshGatewayConfig.parseServices("").isEmpty());
        assertTrue(MeshGatewayConfig.parseServices("   ").isEmpty());
        assertTrue(MeshGatewayConfig.parseServices(null).isEmpty());
    }

    /** Durations accept the documented `10s` form and a bare seconds count. */
    @Test
    void parsesDurations() {
        assertEquals(Duration.ofSeconds(15), MeshGatewayConfig.parseDuration("15s", Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(45), MeshGatewayConfig.parseDuration("45", Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(15), MeshGatewayConfig.parseDuration(" 15s ", Duration.ofSeconds(1)));
    }

    /** An unreadable or absent duration falls back rather than stopping the service from starting. */
    @Test
    void fallsBackOnAnUnreadableDuration() {
        var fallback = Duration.ofSeconds(10);

        assertEquals(fallback, MeshGatewayConfig.parseDuration("soon", fallback));
        assertEquals(fallback, MeshGatewayConfig.parseDuration("", fallback));
        assertEquals(fallback, MeshGatewayConfig.parseDuration(null, fallback));
    }

    /**
     * The broker URL carries a {@code tcp://} scheme this client does not speak, because one value
     * addresses the broker for every protocol it serves; only host and port are taken from it.
     */
    @Test
    void derivesBrokerOptionsFromTheUrl() {
        var options = configFrom(new JsonObject()
                        .put("ARTEMIS_URL", "tcp://broker.internal:61617")
                        .put("ARTEMIS_USER", "mesh")
                        .put("ARTEMIS_PASSWORD", "secret"))
                .brokerOptions();

        assertEquals("broker.internal", options.getHost());
        assertEquals(61617, options.getPort());
        assertEquals("mesh", options.getUsername());
    }

    /**
     * A broker URL written without a scheme still yields a usable host. {@code URI} parses
     * {@code localhost:61616} as scheme {@code localhost} with no host at all, so without a fallback
     * the client would be pointed at nothing - and this is an easy way to write the value by hand.
     */
    @Test
    void fallsBackToLocalhostWhenTheUrlHasNoParseableHost() {
        var options = configFrom(new JsonObject().put("ARTEMIS_URL", "localhost:61616"))
                .brokerOptions();

        assertEquals("localhost", options.getHost());
    }

    /** A URL without an explicit port falls back to the Artemis default rather than failing. */
    @Test
    void defaultsTheBrokerPortWhenAbsent() {
        var options = configFrom(new JsonObject().put("ARTEMIS_URL", "tcp://broker.internal"))
                .brokerOptions();

        assertEquals("broker.internal", options.getHost());
        assertEquals(61616, options.getPort());
    }
}
