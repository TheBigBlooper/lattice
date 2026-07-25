package io.lattice.common.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * The assert-and-consume handle a test receives from {@link FailOnUnexpectedLogExtension}, used to
 * declare the ERROR and WARN logs it legitimately expects.
 *
 * <p>Product code often logs on a path a test deliberately exercises - an expected error branch, a
 * dependency-unavailable safety valve, a best-effort cleanup. The test-hygiene rule is that such a
 * test <b>asserts</b> the log rather than letting it print, so the log line is pinned as behavior and
 * genuinely unexpected output still fails the run.
 *
 * <p><b>Expectations are evaluated after the test body, not at the call.</b> Services log from Vert.x
 * event-loop and worker threads, so a log can legitimately arrive after the assertion line that
 * expects it - a startup bootstrap failure is reported asynchronously while the test is already
 * making requests. Asserting on a single snapshot mid-test would be flaky by construction (see the
 * timing-dependent rule in core_protocol). Instead each {@code expect...} call <em>records</em> an
 * expectation, and the extension settles them all once the test has finished, briefly waiting for a
 * late arrival before deciding.
 *
 * <p>Matching is per event, not a blanket amnesty: asserting one WARN does not excuse a second,
 * unrelated one.
 */
public final class ExpectedLogs {

    private final CollectingAppender appender;

    private final List<Expectation> expectations = new CopyOnWriteArrayList<>();

    ExpectedLogs(CollectingAppender appender) {
        this.appender = appender;
    }

    /**
     * Declares that this test expects at least one WARN whose message contains the given text. The
     * expectation is settled after the test body runs.
     *
     * @param messageSubstring text the expected WARN message contains.
     */
    public void expectWarn(String messageSubstring) {
        expectations.add(new Expectation(Level.WARN, messageSubstring));
    }

    /**
     * Declares that this test expects at least one ERROR whose message contains the given text. The
     * expectation is settled after the test body runs.
     *
     * @param messageSubstring text the expected ERROR message contains.
     */
    public void expectError(String messageSubstring) {
        expectations.add(new Expectation(Level.ERROR, messageSubstring));
    }

    /**
     * Settles every declared expectation against what was actually logged, waiting up to the given
     * budget for an expectation that has not been satisfied yet (a log still in flight on another
     * thread). Returns immediately once all expectations are met, so a well-behaved test pays nothing.
     */
    Outcome settle(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        List<ILoggingEvent> events;
        List<Expectation> unmet;
        while (true) {
            events = appender.snapshot();
            unmet = unmet(events);
            if (unmet.isEmpty() || System.nanoTime() >= deadline) {
                break;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new Outcome(unmet, unexpected(events));
    }

    /** Expectations that no captured event satisfies. */
    private List<Expectation> unmet(List<ILoggingEvent> events) {
        return expectations.stream()
                .filter(expectation -> events.stream().noneMatch(expectation::matches))
                .toList();
    }

    /** ERROR and WARN events no expectation accounts for - what fails the test. */
    private List<ILoggingEvent> unexpected(List<ILoggingEvent> events) {
        Set<ILoggingEvent> accounted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var expectation : expectations) {
            events.stream().filter(expectation::matches).forEach(accounted::add);
        }
        return events.stream()
                .filter(event -> event.getLevel() == Level.ERROR || event.getLevel() == Level.WARN)
                .filter(event -> !accounted.contains(event))
                .toList();
    }

    /** Renders every captured ERROR/WARN, so a failure says what actually happened. */
    String captured() {
        var seen = appender.snapshot().stream()
                .filter(event -> event.getLevel() == Level.ERROR || event.getLevel() == Level.WARN)
                .map(ExpectedLogs::describe)
                .collect(Collectors.joining(System.lineSeparator() + "  "));
        return seen.isEmpty() ? "(no ERROR or WARN logs were captured)" : seen;
    }

    /** One line per event: level, logger, message - enough to identify it without dumping stack traces. */
    static String describe(ILoggingEvent event) {
        return event.getLevel() + " [" + event.getLoggerName() + "] " + event.getFormattedMessage();
    }

    /** A declared expectation: a level plus text the message must contain. */
    private record Expectation(Level level, String messageSubstring) {

        boolean matches(ILoggingEvent event) {
            return event.getLevel() == level && event.getFormattedMessage().contains(messageSubstring);
        }

        @Override
        public String toString() {
            return level + " containing \"" + messageSubstring + "\"";
        }
    }

    /** What settling found: expectations never satisfied, and logs nothing accounted for. */
    record Outcome(List<Expectation> unmet, List<ILoggingEvent> unexpected) {

        boolean clean() {
            return unmet.isEmpty() && unexpected.isEmpty();
        }

        String describeUnmet() {
            return unmet.stream().map(Expectation::toString).collect(Collectors.joining(", "));
        }

        String describeUnexpected() {
            return unexpected.stream()
                    .map(ExpectedLogs::describe)
                    .collect(Collectors.joining(System.lineSeparator() + "  "));
        }
    }
}
