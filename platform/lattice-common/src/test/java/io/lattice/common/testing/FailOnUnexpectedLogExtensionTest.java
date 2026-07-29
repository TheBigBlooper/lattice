package io.lattice.common.testing;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link FailOnUnexpectedLogExtension}, the shared harness that makes the test-hygiene rule
 * mechanical: an unexpected ERROR or WARN emitted during a test fails that test, while a log the test
 * legitimately expects is asserted and consumed.
 *
 * <p>The extension's whole job is to change whether a test passes, so it cannot be proven by calling
 * its methods - it is exercised by running a real JUnit engine over the {@link Cases} class below and
 * asserting the outcomes. {@code Cases} is a plain static nested class, so it is never picked up by
 * the normal surefire/failsafe scan; it only runs when a test here selects one of its methods.
 */
class FailOnUnexpectedLogExtensionTest {

    /** Runs a no-argument method of {@link Cases} through a real JUnit engine. */
    private static EngineTestKit.Builder engineFor(String method) {
        return EngineTestKit.engine("junit-jupiter").selectors(selectMethod(Cases.class, method));
    }

    /**
     * Runs a {@link Cases} method that takes the injected {@link ExpectedLogs}. The parameter types
     * must be named explicitly: the two-argument {@code selectMethod} only matches a no-argument
     * method, and silently selects nothing otherwise.
     */
    private static EngineTestKit.Builder engineForLogsCase(String method) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectMethod(Cases.class, method, ExpectedLogs.class.getName()));
    }

    /** A test that emits a WARN it never asserts fails, which is the rule the harness exists to enforce. */
    @Test
    void failsATestThatLeaksAnUnassertedWarn() {
        engineFor("emitsUnassertedWarn").execute().testEvents().assertStatistics(stats -> stats.failed(1));
    }

    /** A test that emits an ERROR it never asserts fails for the same reason. */
    @Test
    void failsATestThatLeaksAnUnassertedError() {
        engineFor("emitsUnassertedError").execute().testEvents().assertStatistics(stats -> stats.failed(1));
    }

    /**
     * A test that legitimately logs on an expected path passes when it asserts that log, so product
     * code that logs a handled condition stays testable rather than being forced to go silent.
     */
    @Test
    void passesWhenTheExpectedLogIsAsserted() {
        engineForLogsCase("assertsItsOwnWarn").execute().testEvents().assertStatistics(stats -> stats.succeeded(1));
    }

    /** A test that logs nothing above INFO is unaffected by the harness. */
    @Test
    void passesAQuietTest() {
        engineFor("logsNothingNoteworthy").execute().testEvents().assertStatistics(stats -> stats.succeeded(1));
    }

    /**
     * Asserting a log that was never emitted fails the test, so the assert-and-consume API cannot be
     * used to wave through a log line that product code stopped emitting.
     */
    @Test
    void failsWhenAnAssertedLogWasNeverEmitted() {
        engineForLogsCase("assertsALogItNeverEmitted")
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.failed(1));
    }

    /**
     * Asserting one WARN does not excuse a second, unrelated one: consuming is per matching event, not
     * a blanket amnesty for the test.
     */
    @Test
    void failsWhenOnlyOneOfTwoWarnsIsAsserted() {
        engineForLogsCase("assertsOnlyOneOfTwoWarns").execute().testEvents().assertStatistics(stats -> stats.failed(1));
    }

    /**
     * A tolerated log is excused when it happens, which is the whole point of it existing alongside
     * {@code expect}: some third-party teardown noise is real but not guaranteed.
     */
    @Test
    void passesWhenAToleratedLogIsEmitted() {
        engineForLogsCase("toleratesNoiseThatHappens")
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1));
    }

    /**
     * And - the case that makes {@code tolerate} worth having at all - it does NOT fail when the log
     * never appears. Using {@code expectError} for non-deterministic noise would fail here, which is
     * exactly how a flaky test gets written while trying to fix one.
     */
    @Test
    void passesWhenAToleratedLogNeverArrives() {
        engineForLogsCase("toleratesNoiseThatDoesNotHappen")
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1));
    }

    /**
     * Tolerating one log is still not a blanket amnesty: an unrelated ERROR fails the test exactly as
     * it would otherwise. Without this, {@code tolerate} would be a way to switch the rule off.
     */
    @Test
    void stillFailsOnUnrelatedNoiseWhenSomethingElseIsTolerated() {
        engineForLogsCase("toleratesOneThingAndLeaksAnother")
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.failed(1));
    }

    /**
     * The cases the engine runs. Not a test class by naming convention, so the normal build never
     * executes it directly - {@link FailOnUnexpectedLogExtensionTest} selects its methods explicitly.
     */
    @ExtendWith(FailOnUnexpectedLogExtension.class)
    static class Cases {

        private static final Logger LOG = LoggerFactory.getLogger(Cases.class);

        @Test
        void emitsUnassertedWarn() {
            LOG.warn("an unexpected warning nobody asserted");
        }

        @Test
        void emitsUnassertedError() {
            LOG.error("an unexpected error nobody asserted");
        }

        @Test
        void assertsItsOwnWarn(ExpectedLogs logs) {
            LOG.warn("dependency unavailable handling /api/v1/orders");
            logs.expectWarn("dependency unavailable");
        }

        @Test
        void logsNothingNoteworthy() {
            LOG.info("routine lifecycle message");
        }

        @Test
        void assertsALogItNeverEmitted(ExpectedLogs logs) {
            logs.expectWarn("a warning that was never logged");
        }

        @Test
        void assertsOnlyOneOfTwoWarns(ExpectedLogs logs) {
            LOG.warn("the expected one");
            LOG.warn("a second, unrelated warning");
            logs.expectWarn("the expected one");
        }

        @Test
        void toleratesNoiseThatHappens(ExpectedLogs logs) {
            logs.tolerateError("Connection reset");
            LOG.error("Connection reset");
        }

        @Test
        void toleratesNoiseThatDoesNotHappen(ExpectedLogs logs) {
            logs.tolerateError("Connection reset");
        }

        @Test
        void toleratesOneThingAndLeaksAnother(ExpectedLogs logs) {
            logs.tolerateError("Connection reset");
            LOG.error("Connection reset");
            LOG.error("a genuine failure nobody asserted");
        }
    }
}
