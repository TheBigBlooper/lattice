package io.lattice.common.testing;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Logback appender that captures events for assertion by {@link FailOnUnexpectedLogExtension}.
 *
 * <p>Deliberately not Logback's stock {@code ListAppender}: that one collects into a plain
 * {@link java.util.ArrayList}, and Vert.x services log from event-loop and worker threads while the
 * test thread reads. Concurrent add-while-iterating there is a
 * {@link java.util.ConcurrentModificationException} waiting to happen, which would make the very
 * harness meant to stabilise the suites a source of flakes. A {@link CopyOnWriteArrayList} makes
 * append and read safe without locking the logging path.
 */
final class CollectingAppender extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
        // Materialise the formatted message and thread/context data now: the event is read later, on a
        // different thread, after the logging call has returned.
        event.prepareForDeferredProcessing();
        events.add(event);
    }

    /**
     * Returns the events captured so far, as an immutable snapshot that is safe to iterate while more
     * events are still arriving.
     *
     * @return the captured logging events, oldest first.
     */
    List<ILoggingEvent> snapshot() {
        return List.copyOf(events);
    }
}
