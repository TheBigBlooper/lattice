package io.lattice.contract.metrics;

/**
 * What kind of meter a {@link MetricSample} came from, so a reader knows what its value means.
 *
 * <p>The distinction is load-bearing rather than descriptive. A counter's instantaneous value is
 * close to meaningless - the interesting quantity is how fast it moves - while a gauge's value is
 * the answer on its own. A console that could not tell them apart would either render a rate for
 * something that has none, or a raw total for something whose total nobody wants.
 */
public enum MetricKind {

    /** Monotonically increasing. Read as a rate, not as a level. */
    COUNTER,

    /** A level that moves both ways. Read as it stands. */
    GAUGE,

    /** A timing distribution, emitted as its count, total, and any published percentile. */
    TIMER
}
