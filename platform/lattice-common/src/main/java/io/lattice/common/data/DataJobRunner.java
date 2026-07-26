package io.lattice.common.data;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.lattice.common.es.ElasticsearchClientFactory;
import io.lattice.common.es.InventoryMapping;
import io.lattice.common.es.OrdersMapping;
import io.lattice.common.es.ReservationMapping;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point for a baseline's data-maintenance jobs, run as a Kubernetes Job or a
 * {@code docker run}.
 *
 * <p>It runs from the <b>service image that is already built</b>, by overriding the container's
 * command - there is no second artifact to build, tag, and keep in step. Every service image
 * contains {@code lattice-common}, which is where the mappings live.
 *
 * <p><b>It reuses the committed mappings, and that is the point.</b> A shell script driving the
 * Elasticsearch HTTP API by hand would restate every mapping, and the copy would drift from the one
 * the services actually bootstrap - which is exactly the class of defect a reindex is supposed to
 * fix. Here the job and the service read the same {@code OrdersMapping.MAPPING_JSON}.
 *
 * <pre>
 *   java -cp app.jar io.lattice.common.data.DataJobRunner reindex orders
 *   java -cp app.jar io.lattice.common.data.DataJobRunner seed
 *   java -cp app.jar io.lattice.common.data.DataJobRunner reset
 * </pre>
 *
 * <p>Nothing runs until {@link DataJobGuard} says so; see there for why silence means refuse.
 */
public final class DataJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DataJobRunner.class);

    /** The logical indices a baseline owns, with the mapping each one is built from. */
    private static final Map<String, String> INDICES = indices();

    private DataJobRunner() {}

    private static Map<String, String> indices() {
        var byName = new LinkedHashMap<String, String>();
        byName.put(OrdersMapping.INDEX, OrdersMapping.MAPPING_JSON);
        byName.put(InventoryMapping.INDEX, InventoryMapping.MAPPING_JSON);
        byName.put(ReservationMapping.INDEX, ReservationMapping.MAPPING_JSON);
        return Map.copyOf(byName);
    }

    /**
     * Runs one data job, or refuses and exits non-zero.
     *
     * @param args the job name, optionally followed by a single logical index to limit it to.
     */
    public static void main(String[] args) {
        System.exit(run(
                args,
                System.getenv(DataJobGuard.ENV),
                System.getenv(DataJobGuard.ALLOW),
                System.getenv().getOrDefault("ELASTICSEARCH_URL", "http://localhost:9200")));
    }

    /**
     * Everything {@link #main(String[])} does, as a value rather than a process exit.
     *
     * <p>Split out so the argument handling and the refusals are testable: a {@code main} that calls
     * {@code System.exit} cannot be asserted against without taking the test JVM down with it, and
     * these are exactly the paths worth pinning - the ones that decide whether a destructive job
     * runs.
     *
     * @param args the job name, optionally followed by a single logical index.
     * @param environment the value of {@code LATTICE_ENV}.
     * @param optIn the value of {@code LATTICE_ALLOW_DATA_JOBS}.
     * @param elasticsearchUrl the cluster to act against; a parameter rather than a hidden read of
     *     the environment, so the jobs can be driven against a test cluster.
     * @return {@code 0} on success, {@code 1} when refused or failed, {@code 2} on bad usage.
     */
    static int run(String[] args, String environment, String optIn, String elasticsearchUrl) {
        if (args.length == 0) {
            LOG.error("usage: DataJobRunner <reindex|seed|reset> [index]");
            return 2;
        }

        DataJob job;
        try {
            job = DataJob.valueOf(args[0].strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            LOG.error("unknown job {} - expected one of reindex, seed, reset", oneLine(args[0]));
            return 2;
        }

        var only = args.length > 1 ? args[1].strip() : null;
        if (only != null && !INDICES.containsKey(only)) {
            LOG.error("unknown index {} - this baseline owns {}", oneLine(only), INDICES.keySet());
            return 2;
        }

        var decision = DataJobGuard.decide(job, environment, optIn);
        if (!decision.allowed()) {
            // Logged at ERROR and returned non-zero so a Kubernetes Job shows as failed rather than
            // completing quietly, which would read as "the reseed ran" to whoever checks later.
            LOG.error("{}", decision.because());
            return 1;
        }
        LOG.info("{}", decision.because());

        var client = ElasticsearchClientFactory.create(elasticsearchUrl);
        var jobs = new DataJobs(client);
        try {
            switch (job) {
                case REINDEX -> jobs.reindex(targets(only));
                case SEED -> jobs.seed();
                case RESET -> jobs.reset(targets(only));
            }
            LOG.info("{} complete", job);
            return 0;
        } catch (Exception failed) {
            LOG.error("{} failed: {}", job, String.valueOf(failed));
            return 1;
        } finally {
            close(client);
        }
    }

    /**
     * Renders a value that came from the command line onto a single line, quoted.
     *
     * <p>Log lines are read to work out what a job did, and a value carrying a newline can forge a
     * second line that looks like the job's own output - a mistyped index name is not a threat, but
     * the arguments here arrive from a Kubernetes Job spec that more than one person can edit.
     *
     * @param value the untrusted value.
     * @return the value on one line, in quotes.
     */
    private static String oneLine(String value) {
        return "'" + value.replaceAll("[\\r\\n]", " ") + "'";
    }

    private static Map<String, String> targets(String only) {
        return only == null ? INDICES : Map.of(only, INDICES.get(only));
    }

    private static void close(ElasticsearchClient client) {
        try {
            client._transport().close();
        } catch (Exception ignored) {
            // A job that has done its work must not fail because the transport objected on the way
            // out - the exit status should describe the data, not the teardown.
        }
    }
}
