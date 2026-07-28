package io.lattice.contract.mesh;

import java.util.Locale;

/**
 * Whether a baseline can currently reach the mesh at all (locked #46).
 *
 * <p><b>This is a local-only signal, and that is the whole point.</b> A cluster whose broker link is
 * down hears nothing, so its registry ages <em>every</em> peer to unreachable at once. Rendered
 * naively that reads as "the entire mesh died", when the truth is "we are the ones cut off" - two
 * different incidents wanting two different responses. The gateway already knows its own connection
 * state, so it says so rather than leaving an operator to infer it from the fact that everything
 * went quiet at the same moment.
 *
 * <p><b>It never rides the mesh.</b> Served on this baseline's own API only, for the same reason
 * locked #43 keeps the broker out of the health rollup: a report about a broken link cannot travel
 * over that link. From a peer's side the correct and only possible signal is this baseline crossing
 * its liveness deadline and going unreachable, which already happens.
 *
 * <p>The wire form is lowercase, matching {@code health} rather than the per-service {@code UP} /
 * {@code DOWN}: both are baseline-level labels on the same object, while service status keeps the
 * vocabulary of the readiness probes it comes from.
 */
public enum MeshLinkState {
    /** The gateway holds a live broker connection, so peer data is current. */
    UP,

    /** The gateway has no broker connection, so every peer's data is last-known rather than absent. */
    DOWN;

    /**
     * This state as it appears on the wire.
     *
     * @return the lowercase contract value.
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
