package io.lattice.common.mesh;

/**
 * Whether a discovered peer is still announcing itself on the mesh.
 *
 * <p>Derived purely from how recently this cluster last heard from the peer, against the configured
 * time-to-live. There is no separate ping: the announcement heartbeat is itself the liveness signal.
 */
public enum Reachability {

    /** Heard from within the time-to-live - the peer is announcing normally. */
    REACHABLE,

    /**
     * Silent for longer than the time-to-live. The peer is <b>retained</b> with its last-known detail
     * rather than removed, so an operator sees that a baseline was present and has gone quiet instead
     * of it simply disappearing.
     */
    UNREACHABLE
}
