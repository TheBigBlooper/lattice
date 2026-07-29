package io.lattice.contract.mesh;

/**
 * One infrastructure component's coarse state, in a vocabulary all three components can speak (locked
 * #66).
 *
 * <p>Elasticsearch speaks green/yellow/red, Artemis is connected or it is not, and Keycloak is up or
 * it is not. Typing each native vocabulary into the contract would be more faithful but would make the
 * console render per kind, so adding a component would mean changing the console. Reusing the plain
 * {@code UP} / {@code DOWN} of the service probes cannot express {@link #DEGRADED}, which is the state
 * this reporting exists to preserve. This is the middle: three words the console colours and counts,
 * with the component's own reading carried alongside on {@link ComponentHealth#detail()}.
 *
 * <p>The wire form is uppercase, keeping the vocabulary of the per-service {@code UP} / {@code DOWN}
 * it sits beside, rather than the lowercase of the baseline-level labels ({@code health},
 * {@link MeshLinkState}, {@link ComponentKind}).
 */
public enum ComponentStatus {
    /** The component is fully healthy: every shard allocated, the connection held, the probe green. */
    UP,

    /** The component serves, with something genuinely lost - missing replicas, a failing sub-check. */
    DEGRADED,

    /** The component cannot serve, is unreachable, or its connection is not held. */
    DOWN;

    /**
     * This status as it appears on the wire.
     *
     * @return the uppercase contract value.
     */
    public String wire() {
        return name();
    }
}
