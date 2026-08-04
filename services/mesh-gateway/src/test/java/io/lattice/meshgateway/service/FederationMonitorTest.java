package io.lattice.meshgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.mesh.BrokerCertificate;
import io.lattice.contract.mesh.FederationState;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests how per-peer link readings and this baseline's own certificate combine into the state a peer
 * row reports.
 *
 * <p>This is where the neutral report is sharpened, and the rules are the whole of locked #80's
 * Decision 7: the signal is symmetric and its meaning is not, so a link being down says nothing about
 * whose fault it is until a purely local fact - the baseline's own certificate - says otherwise.
 */
class FederationMonitorTest {

    private static BrokerCertificate validFor(long days) {
        return new BrokerCertificate(
                Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(days)), "CN=broker");
    }

    private static BrokerCertificate expired() {
        return new BrokerCertificate(
                Instant.now().minus(Duration.ofDays(900)), Instant.now().minus(Duration.ofDays(1)), "CN=broker");
    }

    /** A carrying link reads up regardless of what the certificate says. */
    @Test
    void aCarryingLinkIsUpEvenWithAnExpiredCertificate() {
        var states = FederationMonitor.statesFrom(Map.of("hub-east", true), expired());

        assertEquals(FederationState.UP, states.get("hub-east"));
    }

    /**
     * A dead link with a healthy certificate reads down, not refused: nothing local establishes that
     * the fault is here, and claiming it would be a guess.
     */
    @Test
    void aDeadLinkWithAHealthyCertificateIsMerelyDown() {
        var states = FederationMonitor.statesFrom(Map.of("hub-east", false), validFor(400));

        assertEquals(FederationState.DOWN, states.get("hub-east"));
    }

    /**
     * A dead link plus an expired certificate reads refused, which is the one case the baseline can
     * name and fix on its own.
     */
    @Test
    void aDeadLinkWithAnExpiredCertificateIsRefused() {
        var states = FederationMonitor.statesFrom(Map.of("hub-east", false), expired());

        assertEquals(FederationState.REFUSED, states.get("hub-east"));
    }

    /**
     * An expired certificate refuses <em>every</em> link at once, which is the operator's strongest
     * clue that the fault is local rather than a peer's.
     */
    @Test
    void anExpiredCertificateRefusesEveryDeadLinkTogether() {
        var states = FederationMonitor.statesFrom(Map.of("hub-east", false, "hub-west", false), expired());

        assertEquals(FederationState.REFUSED, states.get("hub-east"));
        assertEquals(FederationState.REFUSED, states.get("hub-west"));
    }

    /**
     * With no certificate reading the neutral state still reports, because a link that is provably
     * not carrying is worth saying even when the cause cannot be narrowed.
     */
    @Test
    void reportsNeutrallyWithNoCertificateReading() {
        var states = FederationMonitor.statesFrom(Map.of("hub-east", false), null);

        assertEquals(FederationState.DOWN, states.get("hub-east"));
    }

    /** No link readings means no states at all, rather than a set of confident ups. */
    @Test
    void reportsNothingWhenNoLinksCouldBeRead() {
        assertTrue(FederationMonitor.statesFrom(Map.of(), validFor(400)).isEmpty());
    }
}
