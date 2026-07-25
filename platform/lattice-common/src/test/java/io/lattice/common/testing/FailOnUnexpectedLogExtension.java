package io.lattice.common.testing;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.LoggerFactory;

/**
 * Makes the test-hygiene rule mechanical: a test that emits an unexpected ERROR or WARN fails.
 *
 * <p>Noisy logs accumulate quietly - a post-teardown leak, an unmocked call, a swallowed exception -
 * and eventually mask a real failure. Enforcing it per test keeps a regression failing locally instead
 * of surfacing weeks later on {@code dev}.
 *
 * <p><b>Usage.</b> Add {@code @ExtendWith(FailOnUnexpectedLogExtension.class)} to a test class. A test
 * that legitimately logs on a path it exercises declares an {@link ExpectedLogs} parameter and asserts
 * the log, which both pins the log line as behavior and consumes it:
 *
 * <pre>{@code
 * @Test
 * void requestWhileElasticsearchDownReturns503(ExpectedLogs logs) {
 *     // ... exercise the dependency-down path ...
 *     logs.expectWarn("dependency unavailable");
 * }
 * }</pre>
 *
 * <p><b>Framework noise is quieted, not asserted.</b> Third-party chatter (an OpenAPI router warning
 * about operations this service does not implement) is silenced by raising that logger's level in
 * {@code logback-test.xml}, so the events are never emitted. Do not lower product log levels to get a
 * suite green.
 *
 * <p><b>A failing test is left alone.</b> If the test already failed, its logs are not checked - a
 * failure usually logs errors, and reporting those on top would bury the real cause.
 */
public final class FailOnUnexpectedLogExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(FailOnUnexpectedLogExtension.class);

    private static final String KEY = "expectedLogs";

    /** How long to wait after the test body for a log still in flight on another thread. */
    private static final java.time.Duration SETTLE_BUDGET = java.time.Duration.ofSeconds(2);

    @Override
    public void beforeEach(ExtensionContext context) {
        var appender = new CollectingAppender();
        // A Logback appender must be given its LoggerContext before start(), or it never receives events.
        appender.setContext(rootLogger().getLoggerContext());
        appender.start();
        rootLogger().addAppender(appender);
        context.getStore(NAMESPACE).put(KEY, new Captured(appender, new ExpectedLogs(appender)));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        var captured = context.getStore(NAMESPACE).remove(KEY, Captured.class);
        if (captured == null) {
            return;
        }
        rootLogger().detachAppender(captured.appender());
        captured.appender().stop();

        // The test already failed: its own failure is the useful signal, so do not pile log noise on
        // top of it (a failing test very often logs errors on the way down).
        if (context.getExecutionException().isPresent()) {
            return;
        }
        var outcome = captured.logs().settle(SETTLE_BUDGET);
        if (outcome.clean()) {
            return;
        }
        var message = new StringBuilder("log expectations were not met by this test.");
        if (!outcome.unmet().isEmpty()) {
            message.append(System.lineSeparator())
                    .append("  Expected but never logged: ")
                    .append(outcome.describeUnmet());
        }
        if (!outcome.unexpected().isEmpty()) {
            message.append(System.lineSeparator())
                    .append("  Unexpected ERROR/WARN (fix the cause, or assert it with the ExpectedLogs")
                    .append(" parameter if it is expected):")
                    .append(System.lineSeparator())
                    .append("  ")
                    .append(outcome.describeUnexpected());
        }
        message.append(System.lineSeparator())
                .append("  All captured ERROR/WARN: ")
                .append(captured.logs().captured());
        throw new AssertionError(message.toString());
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == ExpectedLogs.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE).get(KEY, Captured.class).logs();
    }

    /** The root logger, which every other logger propagates to, so one appender sees the whole run. */
    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    /** Pairs the appender (needed for teardown) with the handle the test asserts through. */
    private record Captured(CollectingAppender appender, ExpectedLogs logs) {}
}
