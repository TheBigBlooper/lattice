package io.lattice.common;

import io.vertx.core.Future;
import java.util.function.Supplier;

/**
 * A retrying gate around a one-time startup action a service's requests depend on. Work sequences
 * behind {@link #ready()}, so the action is guaranteed to have succeeded before the request proceeds,
 * without blocking startup on the dependency being reachable.
 *
 * <p><b>Why this exists.</b> Holding the action's {@link Future} directly memoizes whatever it settles
 * to, including a <em>failure</em>. A service that starts before its dependency is reachable (the
 * ordinary Kubernetes startup race) would then compose every later request onto that permanently
 * failed future and keep failing after the dependency recovered, until someone restarted the pod. This
 * class memoizes a success but retries a failure, so the service heals on its own.
 *
 * <p><b>Attempt sharing.</b> A pending attempt is shared by every concurrent caller rather than
 * duplicated, so a burst of requests against a not-yet-ready dependency issues one call, not one per
 * request. A new attempt starts only once the previous one has actually failed.
 *
 * <p>The gated action must be <b>idempotent</b>, since it is re-run after any failure. Two things use
 * it: a service's create-if-absent Elasticsearch index provisioning, and the fetch of the realm signing
 * keys the {@code /api/v1} guard validates bearer tokens against.
 */
public final class RetryingGate {

    private final Supplier<Future<Void>> action;

    /** The current attempt: null before the first, otherwise the latest pending or settled one. */
    private Future<Void> attempt;

    /**
     * Creates the gate over an idempotent action, typically a repository's {@code bootstrap()} method
     * reference or a signing-key fetch.
     *
     * @param action the action to run, and to re-run after a failure.
     */
    public RetryingGate(Supplier<Future<Void>> action) {
        this.action = action;
    }

    /**
     * Returns a future that completes when the action has succeeded, starting a fresh attempt if none
     * has run yet or if the previous one failed, and otherwise reusing the pending or successful
     * attempt already in hand.
     *
     * @return a future completing when the action has succeeded (this attempt or an earlier one).
     */
    public synchronized Future<Void> ready() {
        if (attempt == null || attempt.failed()) {
            attempt = action.get();
        }
        return attempt;
    }
}
