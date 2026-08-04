package io.lattice.contract.mesh;

import java.util.Locale;

/**
 * The state of this broker's federation link to one peer broker.
 *
 * <p><b>Distinct from the mesh link beside it.</b> {@link MeshLinkState} is a baseline's connection
 * to its <em>own</em> broker; this is that broker's connection to a <em>peer's</em>, authenticated
 * by certificate. One being healthy says nothing about the other, which is exactly the confusion
 * this type exists to remove: a revoked certificate leaves the mesh link perfectly up while nothing
 * crosses.
 *
 * <p><b>{@link #REFUSED} is a conclusion, and it is drawn here rather than in the console.</b> It
 * means the link is down <em>and</em> this baseline's own certificate has expired, so the fault is
 * local and the fix is a re-issue. The console renders what it is told rather than re-deriving it,
 * for the same reason the cluster verdict is read rather than recomputed - a judgement made in two
 * languages is a judgement that can disagree with itself.
 *
 * <p><b>What it cannot say.</b> Revocation is not knowable from a baseline: it lives in the
 * authority's revocation list, which this baseline does not hold. A revoked but unexpired
 * certificate therefore reads {@link #DOWN}, which is the neutral report doing its job rather than a
 * gap. Detail: {@code docs/design/features/federation_link_visibility.md}, locked #80.
 */
public enum FederationState {
    /** The broker holds a live federation link to that peer, so announcements are crossing. */
    UP,

    /** The link is not carrying traffic, and nothing local explains why. */
    DOWN,

    /** The link is down and this baseline's own certificate has expired, so the fault is here. */
    REFUSED;

    /**
     * The lowercase form used on the wire.
     *
     * <p>Lowercase matches the mesh-link state it sits beside rather than the uppercase
     * reachability it shares a row with, because the two link states are the pair a reader
     * compares.
     *
     * @return the wire value.
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a wire value back into a state.
     *
     * @param wire the lowercase wire value, or null when the field was absent.
     * @return the state, or null when there was none to read.
     */
    public static FederationState fromWire(String wire) {
        return wire == null ? null : valueOf(wire.toUpperCase(Locale.ROOT));
    }
}
