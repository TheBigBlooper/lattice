package io.lattice.common.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The guard on the data jobs, and the reason it is written test-first even though the mapping work
 * it protects is the documented exception to that rule: "refuses to run against production" is
 * ordinary behaviour, and it is the half where a mistake destroys data rather than merely failing.
 *
 * <p>Two asymmetries are deliberate and are what these cases pin.
 *
 * <p><b>Silence means refuse.</b> Every other setting in this codebase defaults to the useful thing
 * when unset - the API docs publish, a peer list is empty, a broker is this baseline's own. A
 * destructive job is the opposite: an unset environment is an <em>unknown</em> environment, and
 * running a reset against an unknown cluster is exactly the accident worth preventing. The cost is
 * that a developer types one more variable; the alternative cost is a wiped index.
 *
 * <p><b>Not every job is equally dangerous.</b> A reindex rebuilds an index from the mapping and
 * keeps the data, so it is legitimate in production and merely needs saying out loud. A seed writes
 * invented records and a reset deletes real ones - `deploy_protocol.md` says production is "real data
 * only, no seed", so those two are refused there whatever flags are set. Collapsing all three into
 * one blanket rule would either block a legitimate production reindex or permit a seed.
 */
class DataJobGuardTest {

    @Test
    void refusesEveryJobWhenTheEnvironmentIsUnknown() {
        for (var job : DataJob.values()) {
            var decision = DataJobGuard.decide(job, "", "true");
            assertFalse(decision.allowed(), job + " must refuse when no environment is named");
            assertTrue(
                    decision.because().contains("LATTICE_ENV"), "the refusal names what to set: " + decision.because());
        }
    }

    @Test
    void refusesEveryJobWhenTheOptInIsAbsent() {
        for (var job : DataJob.values()) {
            var decision = DataJobGuard.decide(job, "local", "");
            assertFalse(decision.allowed(), job + " must refuse without an explicit opt-in");
            assertTrue(
                    decision.because().contains("LATTICE_ALLOW_DATA_JOBS"),
                    "the refusal names what to set: " + decision.because());
        }
    }

    @Test
    void allowsEveryJobLocallyWhenSaidOutLoud() {
        for (var job : DataJob.values()) {
            assertTrue(DataJobGuard.decide(job, "local", "true").allowed(), job + " is fine locally when opted in");
        }
    }

    /** The case the whole guard exists for: production keeps its data. */
    @Test
    void refusesSeedAndResetInProductionEvenWithTheOptIn() {
        assertFalse(DataJobGuard.decide(DataJob.SEED, "prod", "true").allowed());
        assertFalse(DataJobGuard.decide(DataJob.RESET, "prod", "true").allowed());
        assertTrue(
                DataJobGuard.decide(DataJob.SEED, "prod", "true").because().contains("real data only"),
                "the refusal quotes the rule it is enforcing");
    }

    /**
     * A reindex is not data loss - it rebuilds an index from the committed mapping and keeps the
     * documents - so production may do it. Refusing it here would push someone toward doing it by
     * hand, which is worse than doing it with a job that logs what it did.
     */
    @Test
    void allowsReindexInProductionWhenSaidOutLoud() {
        assertTrue(DataJobGuard.decide(DataJob.REINDEX, "prod", "true").allowed());
    }

    /** The environment name is compared without case or padding, since it arrives from a shell. */
    @Test
    void readsTheEnvironmentLeniently() {
        assertFalse(DataJobGuard.decide(DataJob.SEED, "  PROD  ", "true").allowed(), "PROD is prod");
        assertTrue(DataJobGuard.decide(DataJob.SEED, " Local ", "true").allowed());
    }

    /**
     * Only an exact opt-in counts. A shell that exports an empty value, or someone typing "yes",
     * must not arm a destructive job - the same strictness the docs gate applies, in the direction
     * that matters more here.
     */
    @Test
    void treatsAnythingButTrueAsNoOptIn() {
        for (var attempt : new String[] {"", " ", "yes", "1", "TRUE-ish", "false"}) {
            assertFalse(
                    DataJobGuard.decide(DataJob.RESET, "local", attempt).allowed(),
                    "'" + attempt + "' must not arm a reset");
        }
        assertTrue(DataJobGuard.decide(DataJob.RESET, "local", "TRUE").allowed(), "exact opt-in, any case");
    }

    /** A refusal has to say what to do about it, or it becomes a mystery in a job log. */
    @Test
    void everyRefusalExplainsItself() {
        var refusal = DataJobGuard.decide(DataJob.SEED, "prod", "true");
        assertFalse(refusal.allowed());
        assertFalse(refusal.because().isBlank(), "a refusal always carries a reason");
        assertEquals(refusal.because().strip(), refusal.because(), "the reason is a clean one-liner");
    }
}
