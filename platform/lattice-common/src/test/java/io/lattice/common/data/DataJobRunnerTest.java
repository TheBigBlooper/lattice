package io.lattice.common.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The runner's argument handling and refusals - every path that decides whether a destructive job
 * runs, without touching a cluster.
 *
 * <p>These assert the exit code rather than the log, because the exit code is what a Kubernetes Job
 * reads: a refusal that logged loudly but exited zero would show as a completed reseed to whoever
 * checked afterwards, which is worse than not refusing at all.
 */
@ExtendWith(FailOnUnexpectedLogExtension.class)
class DataJobRunnerTest {

    @Test
    void reportsBadUsageWhenNoJobIsNamed(ExpectedLogs logs) {
        logs.expectError("usage");
        assertEquals(2, DataJobRunner.run(new String[] {}, "local", "true", "http://unused"));
    }

    @Test
    void reportsBadUsageForAnUnknownJob(ExpectedLogs logs) {
        logs.expectError("unknown job");
        assertEquals(2, DataJobRunner.run(new String[] {"drop-everything"}, "local", "true", "http://unused"));
    }

    @Test
    void reportsBadUsageForAnIndexThisBaselineDoesNotOwn(ExpectedLogs logs) {
        logs.expectError("unknown index");
        assertEquals(2, DataJobRunner.run(new String[] {"reindex", "customers"}, "local", "true", "http://unused"));
    }

    /**
     * Usage is checked before the guard, so a typo is reported as a typo rather than as a refusal -
     * being told to set LATTICE_ALLOW_DATA_JOBS when the real problem is a misspelled index name
     * sends someone off arming a job they did not mean to run.
     */
    @Test
    void reportsBadUsageAheadOfTheGuard(ExpectedLogs logs) {
        logs.expectError("unknown index");
        assertEquals(2, DataJobRunner.run(new String[] {"reset", "customers"}, "", "", "http://unused"));
    }

    @Test
    void refusesWhenTheEnvironmentIsUnnamed(ExpectedLogs logs) {
        logs.expectError("no environment named");
        assertEquals(1, DataJobRunner.run(new String[] {"seed"}, "", "true", "http://unused"));
    }

    @Test
    void refusesWithoutTheOptIn(ExpectedLogs logs) {
        logs.expectError("LATTICE_ALLOW_DATA_JOBS");
        assertEquals(1, DataJobRunner.run(new String[] {"seed"}, "dev", "", "http://unused"));
    }

    /** The one that matters: a seed aimed at production exits non-zero and never reaches a cluster. */
    @Test
    void refusesASeedAimedAtProduction(ExpectedLogs logs) {
        logs.expectError("real data only");
        assertEquals(1, DataJobRunner.run(new String[] {"seed"}, "prod", "true", "http://unused"));
    }

    @Test
    void refusesAResetAimedAtProduction(ExpectedLogs logs) {
        logs.expectError("real data only");
        assertEquals(1, DataJobRunner.run(new String[] {"reset"}, "prod", "true", "http://unused"));
    }

    /** Job names arrive from a shell, so case and padding must not decide whether one runs. */
    @Test
    void acceptsAJobNameInAnyCase(ExpectedLogs logs) {
        logs.expectError("no environment named");
        assertEquals(1, DataJobRunner.run(new String[] {" SeEd "}, "", "true", "http://unused"));
    }
}
