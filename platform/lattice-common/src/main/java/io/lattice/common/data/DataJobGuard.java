package io.lattice.common.data;

import java.util.Locale;

/**
 * Decides whether a {@link DataJob} may run against the cluster it is pointed at.
 *
 * <p>This exists because the runbook in {@code deploy_protocol.md} describes resetting and reseeding
 * a cluster, and the difference between doing that to a dev cluster and doing it to a production one
 * is a single environment variable nobody notices until afterwards. Making the job refuse is the only
 * version of that safeguard that survives being tired.
 *
 * <p><b>Silence means refuse.</b> Every other setting in this codebase defaults to the useful thing
 * when unset. A destructive job inverts that deliberately: an unnamed environment is an
 * <em>unknown</em> one, and the accident worth preventing is precisely running against a cluster you
 * had not thought about. The cost is one more variable to type.
 *
 * <p><b>The three jobs are not equally dangerous</b>, and one blanket rule would get one of them
 * wrong. A reindex keeps every document, so production may do it once someone says so out loud;
 * refusing it there would only push the work into a hand-typed sequence with no log. A seed writes
 * invented records and a reset deletes real ones, and production is "real data only, no seed" - so
 * those are refused there whatever is set.
 */
public final class DataJobGuard {

    /** Names the environment this job is pointed at: {@code local}, {@code dev}, or {@code prod}. */
    public static final String ENV = "LATTICE_ENV";

    /** The explicit opt-in. Must be exactly {@code true}; absent means refuse. */
    public static final String ALLOW = "LATTICE_ALLOW_DATA_JOBS";

    private static final String PROD = "prod";

    private DataJobGuard() {}

    /**
     * The outcome of the check, carrying the reason either way so a job log says what happened
     * rather than just stopping.
     *
     * @param allowed whether the job may run.
     * @param because why, in one line, phrased for whoever is reading the job's output.
     */
    public record Decision(boolean allowed, String because) {}

    /**
     * Decides whether the given job may run.
     *
     * @param job the job being attempted.
     * @param environment the value of {@link #ENV}; blank when unset.
     * @param optIn the value of {@link #ALLOW}; anything but {@code true} is treated as absent.
     * @return the decision, always with a reason.
     */
    public static Decision decide(DataJob job, String environment, String optIn) {
        var env = environment == null ? "" : environment.strip().toLowerCase(Locale.ROOT);

        if (env.isEmpty()) {
            return new Decision(
                    false,
                    "refusing " + job + ": no environment named. Set " + ENV
                            + " to local, dev, or prod - an unnamed cluster is an unknown cluster.");
        }
        if (!"true".equalsIgnoreCase(optIn == null ? "" : optIn.strip())) {
            return new Decision(
                    false,
                    "refusing " + job + " against '" + env + "': set " + ALLOW
                            + "=true to confirm. A data job never runs because nobody said not to.");
        }
        if (PROD.equals(env) && job != DataJob.REINDEX) {
            return new Decision(
                    false,
                    "refusing " + job + " against production: it carries real data only, no seed. "
                            + "No flag overrides this - run it against a dev cluster instead.");
        }
        return new Decision(true, "running " + job + " against '" + env + "'.");
    }
}
