package io.lattice.contract.mesh;

import java.util.Locale;

/**
 * Which piece of infrastructure a reported component is, and therefore which probe produced its state
 * (locked #66).
 *
 * <p>Carried explicitly rather than inferred from the component's name, so a deployment may label its
 * datastore whatever it calls it while the gateway still knows what it is talking to, and the console
 * still knows how to describe it.
 *
 * <p>The wire form is lowercase, matching {@code health} and {@link MeshLinkState} rather than the
 * {@code UP} / {@code DOWN} of a probe result: this names a thing, not a state, and every other
 * baseline-level label on this object is lowercase.
 */
public enum ComponentKind {
    /** This baseline's Elasticsearch cluster, read from its own cluster health. */
    ELASTICSEARCH,

    /** This baseline's Artemis broker, read from the gateway's existing mesh-link state (locked #46). */
    ARTEMIS,

    /** This baseline's Keycloak, read from its management-port readiness probe. */
    KEYCLOAK;

    /**
     * This kind as it appears on the wire.
     *
     * @return the lowercase contract value.
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
