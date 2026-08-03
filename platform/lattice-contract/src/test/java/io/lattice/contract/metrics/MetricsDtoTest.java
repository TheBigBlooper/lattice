package io.lattice.contract.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the metrics wire shapes: one sample and the snapshot that carries a service's selected
 * meters to the console.
 *
 * <p>The shape is a flat list of samples rather than a nested per-meter structure, because the
 * console renders both the cards and the full series table from the same payload and a nested shape
 * would have to be flattened on arrival for the table anyway.
 */
class MetricsDtoTest {

    /** A sample round-trips through JSON with its labels intact. */
    @Test
    void sampleRoundTripsThroughJson() {
        var sample = new MetricSample(
                "lattice.mesh.announcements.received", MetricKind.COUNTER, Map.of("source_cluster", "hub-east"), 46.0);

        var parsed = MetricSample.fromJson(sample.toJson());

        assertEquals(sample, parsed);
    }

    /** A sample carrying no labels round-trips as an empty map rather than a null. */
    @Test
    void sampleWithoutLabelsRoundTripsAsEmpty() {
        var sample = new MetricSample("lattice.mesh.link.up", MetricKind.GAUGE, Map.of(), 1.0);

        var parsed = MetricSample.fromJson(sample.toJson());

        assertEquals(Map.of(), parsed.labels());
        assertEquals(sample, parsed);
    }

    /**
     * Labels default to empty rather than null when absent from the JSON entirely, so a reader never
     * has to null-check a collection - the null-safety rule the whole contract follows.
     */
    @Test
    void missingLabelsBecomeAnEmptyMap() {
        var json = new JsonObject()
                .put("name", "lattice.mesh.peers.known")
                .put("kind", "GAUGE")
                .put("value", 2.0);

        assertEquals(Map.of(), MetricSample.fromJson(json).labels());
    }

    /** A snapshot round-trips with its samples, naming the service that produced it. */
    @Test
    void snapshotRoundTripsThroughJson() {
        var snapshot = new MetricsSnapshot(
                "mesh-gateway",
                List.of(
                        new MetricSample("lattice.mesh.link.up", MetricKind.GAUGE, Map.of(), 1.0),
                        new MetricSample(
                                "lattice.mesh.peer.expiries",
                                MetricKind.COUNTER,
                                Map.of("peer_cluster", "hub-west"),
                                3.0)));

        var parsed = MetricsSnapshot.fromJson(snapshot.toJson());

        assertEquals(snapshot, parsed);
        assertEquals(2, parsed.samples().size());
    }

    /**
     * A snapshot with no samples parses to an empty list rather than a null. A service whose registry
     * holds nothing yet is a real state - it has just started - and it must not read as a failure.
     */
    @Test
    void snapshotWithoutSamplesParsesToAnEmptyList() {
        var json = new JsonObject().put("service", "orders");

        assertTrue(MetricsSnapshot.fromJson(json).samples().isEmpty());
    }

    /** The three kinds a sample may take are the ones the console branches on. */
    @Test
    void kindCoversCounterGaugeAndTimer() {
        assertEquals(3, MetricKind.values().length);
        assertEquals(MetricKind.COUNTER, MetricKind.valueOf("COUNTER"));
        assertEquals(MetricKind.GAUGE, MetricKind.valueOf("GAUGE"));
        assertEquals(MetricKind.TIMER, MetricKind.valueOf("TIMER"));
    }
}
