package io.lattice.common.es;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IndexBootstrap}, the retry wrapper around a service's index provisioning.
 * The behavior under test is the distinction between memoizing a <em>success</em> (provisioning
 * happens once) and memoizing a <em>failure</em> (which would wedge the service until restart): a
 * failed attempt must be retried on the next call, so a service that started before Elasticsearch was
 * reachable recovers on its own once the dependency comes back.
 *
 * <p>Driven with hand-completed promises rather than a live Elasticsearch, so every branch (failed,
 * pending, succeeded) is exercised deterministically.
 */
class IndexBootstrapTest {

    /**
     * A failed attempt is not memoized: the next call re-runs the provisioning action and succeeds,
     * so a startup-time Elasticsearch outage recovers without a restart. This is the regression guard
     * for the memoized-failed-future defect.
     */
    @Test
    void retriesAfterAFailedAttempt() {
        var attempts = new AtomicInteger();
        var bootstrap = new IndexBootstrap(() -> attempts.incrementAndGet() == 1
                ? Future.failedFuture(new IllegalStateException("elasticsearch unreachable"))
                : Future.succeededFuture());

        var first = bootstrap.ready();
        assertTrue(first.failed(), "the first attempt fails while the dependency is down");

        var second = bootstrap.ready();
        assertTrue(second.succeeded(), "a later call must retry rather than replay the failure");
        assertEquals(2, attempts.get(), "the failed attempt must be retried exactly once more");
    }

    /**
     * A successful attempt is memoized: provisioning runs once and every later call reuses it, so the
     * create-if-absent bootstrap is not re-issued on every request.
     */
    @Test
    void memoizesASuccessfulAttempt() {
        var attempts = new AtomicInteger();
        var bootstrap = new IndexBootstrap(() -> {
            attempts.incrementAndGet();
            return Future.succeededFuture();
        });

        var first = bootstrap.ready();
        var second = bootstrap.ready();

        assertTrue(first.succeeded());
        assertSame(first, second, "a succeeded attempt is reused, not re-run");
        assertEquals(1, attempts.get(), "provisioning must happen exactly once on success");
    }

    /**
     * Callers arriving while an attempt is still in flight share that attempt rather than each
     * starting their own, so a burst of requests against a not-yet-ready index cannot stampede
     * Elasticsearch with duplicate provisioning calls.
     */
    @Test
    void sharesAnInFlightAttempt() {
        var attempts = new AtomicInteger();
        Promise<Void> pending = Promise.promise();
        var bootstrap = new IndexBootstrap(() -> {
            attempts.incrementAndGet();
            return pending.future();
        });

        var first = bootstrap.ready();
        var second = bootstrap.ready();

        assertFalse(first.isComplete(), "the attempt is still in flight");
        assertSame(first, second, "a pending attempt is shared, not duplicated");
        assertEquals(1, attempts.get(), "an in-flight attempt must not be re-issued");

        pending.complete();
        assertTrue(second.succeeded(), "both callers settle on the shared attempt");
    }

    /**
     * Recovery is durable: once a retry succeeds, that success is memoized like any other, so the
     * service stops re-attempting provisioning after it has recovered.
     */
    @Test
    void memoizesTheSuccessfulRetry() {
        var attempts = new AtomicInteger();
        var bootstrap = new IndexBootstrap(() -> attempts.incrementAndGet() == 1
                ? Future.failedFuture(new IllegalStateException("elasticsearch unreachable"))
                : Future.succeededFuture());

        bootstrap.ready();
        bootstrap.ready();
        bootstrap.ready();

        assertEquals(2, attempts.get(), "no further attempts once one has succeeded");
    }
}
