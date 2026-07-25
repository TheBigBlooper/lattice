package io.lattice.common.es;

import io.vertx.core.Future;
import java.util.function.Supplier;

/**
 * A retrying gate around a service's one-time index provisioning. Every read and write sequences
 * behind {@link #ready()}, so the service's indices are guaranteed to exist before the first document
 * is written, without blocking startup on Elasticsearch (readiness gates traffic instead).
 *
 * <p><b>Why this exists.</b> Holding the provisioning {@link Future} directly memoizes whatever it
 * settles to, including a <em>failure</em>. A service that starts before Elasticsearch is reachable
 * (the ordinary Kubernetes startup race) would then compose every later request onto that permanently
 * failed future and keep failing after the dependency recovered, until someone restarted the pod.
 * This class memoizes a success but retries a failure, so the service heals on its own.
 *
 * <p><b>Attempt sharing.</b> A pending attempt is shared by every concurrent caller rather than
 * duplicated, so a burst of requests against a not-yet-provisioned index issues one provisioning call,
 * not one per request. A new attempt starts only once the previous one has actually failed.
 *
 * <p>The provisioning action must be <b>idempotent</b> - it is create-if-absent
 * ({@link EsRepository#ensureIndex(String, String)}), so re-running it against an already-provisioned
 * index is a no-op that additively re-applies the mapping.
 */
public final class IndexBootstrap {

    private final Supplier<Future<Void>> action;

    /** The current attempt: null before the first, otherwise the latest pending or settled one. */
    private Future<Void> attempt;

    /**
     * Creates the gate over an idempotent provisioning action, typically a repository's
     * {@code bootstrap()} method reference.
     *
     * @param action the create-if-absent provisioning action to run, and to re-run after a failure.
     */
    public IndexBootstrap(Supplier<Future<Void>> action) {
        this.action = action;
    }

    /**
     * Returns a future that completes when the indices are provisioned, starting a fresh attempt if
     * none has run yet or if the previous one failed, and otherwise reusing the pending or successful
     * attempt already in hand.
     *
     * @return a future completing when provisioning has succeeded (this attempt or an earlier one).
     */
    public synchronized Future<Void> ready() {
        if (attempt == null || attempt.failed()) {
            attempt = action.get();
        }
        return attempt;
    }
}
