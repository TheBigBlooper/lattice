package io.lattice.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

/**
 * The one place a Lattice meter is named, created, or read.
 *
 * <p>Instrumentation everywhere else registers against Micrometer's <b>global registry</b> rather
 * than against a registry it was handed. That is what lets metrics be switched off without a single
 * branch in instrumented code: with nothing bound to the global registry a meter is a no-op, so
 * {@code counter(...).increment()} costs almost nothing and reads nowhere.
 *
 * <p>The scrape endpoint needs the Prometheus registry specifically (it is the thing that can render
 * exposition text), so that one is kept here as well and handed to {@code BaseVerticle}.
 *
 * @see io.lattice.common.BaseVerticle
 */
public final class LatticeMetrics {

    /** Announcements this baseline has published to the mesh. */
    public static final String MESH_ANNOUNCEMENTS_PUBLISHED = "lattice.mesh.announcements.published";

    /** Announcements heard from peers, tagged {@code source_cluster}. */
    public static final String MESH_ANNOUNCEMENTS_RECEIVED = "lattice.mesh.announcements.received";

    /** Peers in the registry, reachable or not. */
    public static final String MESH_PEERS_KNOWN = "lattice.mesh.peers.known";

    /** Peers inside the time-to-live window. */
    public static final String MESH_PEERS_REACHABLE = "lattice.mesh.peers.reachable";

    /** Times a peer has crossed its time-to-live, tagged {@code peer_cluster}. */
    public static final String MESH_PEER_EXPIRIES = "lattice.mesh.peer.expiries";

    /** This baseline's link to its own broker: 1 up, 0 down. */
    public static final String MESH_LINK_UP = "lattice.mesh.link.up";

    /** Times the broker link has been re-established after loss. */
    public static final String MESH_LINK_RECONNECTS = "lattice.mesh.link.reconnects";

    /** The health rollup as a state set, tagged {@code state}: 1 on the current state, 0 elsewhere. */
    public static final String BASELINE_HEALTH = "lattice.baseline.health";

    /** Readiness poll outcomes, tagged {@code service} and {@code outcome}. */
    public static final String SERVICE_READINESS_POLLS = "lattice.service.readiness.polls";

    /** Elasticsearch call latency, tagged {@code operation} and {@code index}. */
    public static final String ES_OPERATION = "lattice.elasticsearch.operation";

    /** Failed Elasticsearch calls, tagged {@code operation} and {@code index}. */
    public static final String ES_OPERATION_ERRORS = "lattice.elasticsearch.operation.errors";

    private static final AtomicReference<PrometheusMeterRegistry> PROMETHEUS = new AtomicReference<>();

    /** The three states the rollup can take, reported as one meter carrying a {@code state} tag. */
    private static final java.util.List<String> ROLLUP_STATES = java.util.List.of("ready", "degraded", "down");

    /** The process's current rollup. One baseline per process, so this is a property of the process. */
    private static final AtomicReference<String> ROLLUP = new AtomicReference<>("");

    /** Guards one-time gauge registration; cleared by {@link #reset()} so a test can rebind. */
    private static final java.util.concurrent.atomic.AtomicBoolean ROLLUP_BOUND =
            new java.util.concurrent.atomic.AtomicBoolean();

    private LatticeMetrics() {}

    /**
     * Reports this baseline's health rollup as a state set: the current state reads 1 and the others
     * read 0.
     *
     * <p>The state lives here rather than on the service that computes it because a gauge binds to the
     * object it was registered with. Registered per instance, a second one in the same process is
     * refused by Micrometer and its rollup then never reports at all - so the meter would go quietly
     * stale rather than fail. A process has one baseline, so the state is held per process.
     *
     * @param state the rollup just computed: {@code ready}, {@code degraded} or {@code down}.
     */
    public static void baselineHealth(String state) {
        ROLLUP.set(state);
        if (ROLLUP_BOUND.compareAndSet(false, true)) {
            ROLLUP_STATES.forEach(known ->
                    gauge(BASELINE_HEALTH, ROLLUP, current -> known.equals(current.get()) ? 1 : 0, "state", known));
        }
    }

    /**
     * Creates the Prometheus registry, binds the Java Virtual Machine meters to it, and adds it to
     * the global registry so already-registered instrumentation starts reporting.
     *
     * <p>Called once from the bootstrap, before the {@code Vertx} instance is created, because the
     * Vert.x binding takes the registry as a construction option.
     *
     * @return the registry, for handing to the Vert.x metrics options.
     */
    public static PrometheusMeterRegistry enable() {
        var existing = PROMETHEUS.get();
        if (existing != null) {
            return existing;
        }
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        PROMETHEUS.set(registry);
        Metrics.addRegistry(registry);
        return registry;
    }

    /**
     * Binds a plain registry for a test that wants to read meter values without rendering exposition
     * text or paying for the Java Virtual Machine binders.
     */
    public static void enableForTesting() {
        var registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    /**
     * Removes every bound registry, returning meters to their no-op state.
     *
     * <p>Exists for tests: meter values are cumulative and the global registry is process-wide, so
     * without this one suite would read another's counters.
     */
    public static void reset() {
        ROLLUP_BOUND.set(false);
        ROLLUP.set("");
        var prometheus = PROMETHEUS.getAndSet(null);
        if (prometheus != null) {
            Metrics.removeRegistry(prometheus);
            prometheus.close();
        }
        Metrics.globalRegistry.getRegistries().forEach(Metrics::removeRegistry);
        Metrics.globalRegistry.clear();
    }

    /** The registry instrumentation registers against. */
    public static MeterRegistry registry() {
        return Metrics.globalRegistry;
    }

    /** The Prometheus registry, when metrics are enabled; empty when they are off. */
    public static Optional<PrometheusMeterRegistry> prometheus() {
        return Optional.ofNullable(PROMETHEUS.get());
    }

    /** Increments a counter, creating it on first use. */
    public static void count(String name, String... tags) {
        Counter.builder(name).tags(tags).register(Metrics.globalRegistry).increment();
    }

    /**
     * Registers a gauge that reads through to live state.
     *
     * <p>Micrometer holds the state object weakly and a gauge is registered once per name and tag
     * set, so re-registering the same gauge is safe and returns the original.
     *
     * @param name  the meter name.
     * @param state the object the value is read from.
     * @param value how to read the current value from that object.
     * @param tags  alternating key and value, for a gauge that is one member of a tagged set.
     * @param <T>   the state type.
     */
    public static <T> void gauge(String name, T state, ToDoubleFunction<T> value, String... tags) {
        // Rebind rather than let Micrometer ignore a repeat registration. Ignoring leaves the meter
        // reading the object it first saw, so a replaced owner reports the dead one's value forever -
        // a gauge that goes quietly stale is worse than one that follows the latest owner.
        Metrics.globalRegistry.find(name).tags(Tags.of(tags)).meters().forEach(Metrics.globalRegistry::remove);
        io.micrometer.core.instrument.Gauge.builder(name, state, value)
                .tags(tags)
                .strongReference(true)
                .register(Metrics.globalRegistry);
    }

    /** Records a timing sample against a named timer with the given tags. */
    public static void time(String name, long nanos, String... tags) {
        io.micrometer.core.instrument.Timer.builder(name)
                .tags(Tags.of(tags))
                .register(Metrics.globalRegistry)
                .record(java.time.Duration.ofNanos(nanos));
    }
}
