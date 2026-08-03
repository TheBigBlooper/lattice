package io.lattice.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.contract.metrics.MetricKind;
import io.lattice.contract.metrics.MetricSample;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the snapshot the metrics operation serves: which meters are selected, and how each kind
 * of meter becomes samples the console can read.
 */
class MetricsSnapshotFactoryTest {

    /** Gives each test a registry of its own; meter values are cumulative and the registry is global. */
    @BeforeEach
    void enableMetrics() {
        LatticeMetrics.reset();
        LatticeMetrics.enable();
    }

    /** Unbinds the registry so a later suite does not read this one's meters. */
    @AfterEach
    void resetRegistry() {
        LatticeMetrics.reset();
    }

    private static List<MetricSample> samplesNamed(String name) {
        return LatticeMetrics.snapshot("orders").samples().stream()
                .filter(sample -> sample.name().equals(name))
                .toList();
    }

    /** The snapshot names the service that produced it, so merged readings stay distinguishable. */
    @Test
    void snapshotNamesItsService() {
        assertEquals("mesh-gateway", LatticeMetrics.snapshot("mesh-gateway").service());
    }

    /** A counter becomes one sample carrying its labels, reported as a counter. */
    @Test
    void counterBecomesOneLabelledSample() {
        Counter.builder("lattice.mesh.announcements.received")
                .tag("source_cluster", "hub-east")
                .register(LatticeMetrics.registry())
                .increment(46);

        var samples = samplesNamed("lattice.mesh.announcements.received");

        assertEquals(1, samples.size());
        assertEquals(MetricKind.COUNTER, samples.get(0).kind());
        assertEquals(46.0, samples.get(0).value());
        assertEquals("hub-east", samples.get(0).labels().get("source_cluster"));
    }

    /** A gauge becomes one sample reported as a gauge, so a reader does not derive a rate from it. */
    @Test
    void gaugeBecomesOneSampleReportedAsAGauge() {
        LatticeMetrics.gauge("lattice.mesh.peers.known", new AtomicInteger(2), AtomicInteger::get);

        var samples = samplesNamed("lattice.mesh.peers.known");

        assertEquals(1, samples.size());
        assertEquals(MetricKind.GAUGE, samples.get(0).kind());
        assertEquals(2.0, samples.get(0).value());
    }

    /**
     * A timer becomes several samples distinguished by a {@code statistic} label rather than by
     * inventing suffixed metric names. The name stays the one the registry knows, which is what lets
     * the console match a card to a meter without a translation table.
     */
    @Test
    void timerBecomesSamplesDistinguishedByStatistic() {
        Timer.builder("lattice.elasticsearch.operation")
                .tag("index", "orders")
                .register(LatticeMetrics.registry())
                .record(Duration.ofMillis(12));

        var samples = samplesNamed("lattice.elasticsearch.operation");
        var statistics = samples.stream()
                .map(sample -> sample.labels().get("statistic"))
                .sorted()
                .toList();

        assertEquals(List.of("count", "max", "total"), statistics);
        assertTrue(samples.stream().allMatch(sample -> sample.kind() == MetricKind.TIMER));
        assertTrue(samples.stream()
                .allMatch(sample -> "orders".equals(sample.labels().get("index"))));
        assertEquals(
                1.0,
                samples.stream()
                        .filter(sample -> "count".equals(sample.labels().get("statistic")))
                        .findFirst()
                        .orElseThrow()
                        .value());
    }

    /**
     * The Java Virtual Machine families are excluded. They are genuinely useful to a collector and
     * close to meaningless on an operator console, and shipping them would push hundreds of series
     * through an authenticated endpoint for the client to discard.
     */
    @Test
    void javaVirtualMachineFamiliesAreNotSelected() {
        // Asserted against the registry, not the snapshot: the snapshot is where they must be absent,
        // so proving the exclusion means proving they were there to exclude.
        var inRegistry = LatticeMetrics.prometheus().orElseThrow().getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith("jvm."))
                .toList();
        assertFalse(inRegistry.isEmpty(), "the JVM binders should be bound, or this test proves nothing");

        var published = LatticeMetrics.snapshot("orders").samples().stream()
                .map(MetricSample::name)
                .toList();

        assertTrue(
                published.stream().noneMatch(name -> name.startsWith("jvm.")),
                "no jvm family may reach the console, found " + published);
    }

    /**
     * With metrics switched off the operation still answers, with nothing in it. A service that is
     * not measuring itself is a supported deployment, not a failure to report.
     */
    @Test
    void metricsDisabledYieldsAnEmptySnapshot() {
        LatticeMetrics.reset();

        var snapshot = LatticeMetrics.snapshot("orders");

        assertEquals("orders", snapshot.service());
        assertTrue(snapshot.samples().isEmpty());
    }
}
