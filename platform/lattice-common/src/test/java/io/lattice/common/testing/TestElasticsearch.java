package io.lattice.common.testing;

import java.time.Duration;
import java.util.List;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The Elasticsearch container every integration suite starts, configured once.
 *
 * <p><b>Why this exists rather than a container per suite.</b> Six suites had each built the same
 * container with the same three settings, which is six copies of one fact: the next suite to be
 * written copies whichever it happened to look at, and a setting corrected in one is corrected in
 * one. The image tag is the sharpest case, because it must track the client version the parent pom
 * pins and a suite left behind fails at query time rather than at compile time.
 *
 * <p><b>The readiness wait is the load-bearing part.</b> Testcontainers considers this image started
 * when it answers {@code GET /}, which Elasticsearch does while the cluster state is still
 * recovering, so a real request in that window comes back {@code 503 Service Unavailable}. A
 * verticle deployed there logs its datastore as unreachable and the log-hygiene extension fails the
 * test, on a machine-speed race that has nothing to do with the behaviour under test. Waiting for
 * the cluster to report yellow closes the window at the source. Yellow rather than green because a
 * single node cannot allocate a replica, which is the same reading a real single-node baseline gives
 * and the reason locked #67 sets {@code auto_expand_replicas}.
 *
 * <p><b>Tolerating that warning instead was rejected</b>: these suites read and write documents, so
 * "the datastore was unreachable" is a signal they exist to catch, and a test that suppresses it
 * passes just as loudly when the datastore is genuinely broken.
 *
 * @see #container()
 * @see #container(int)
 */
public final class TestElasticsearch {

    /**
     * The image tag, which must match the {@code elasticsearch.version} the parent pom pins for the
     * client: a client and server that disagree fail at query time, not at compile time.
     */
    public static final DockerImageName IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.19");

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private TestElasticsearch() {}

    /**
     * A single-node container on a random host port, ready to serve requests when {@code start()}
     * returns.
     *
     * @return the configured container, not yet started.
     */
    public static ElasticsearchContainer container() {
        // The IDE's resource-leak heuristic does not model the Testcontainers stop() lifecycle, and
        // the caller owns stopping this in teardown.
        @SuppressWarnings("resource")
        var container = new ElasticsearchContainer(IMAGE)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                // Parity with the chart: a write to an unknown index is REFUSED rather than creating
                // it. Without this the suites would run permissively while a deployed baseline does
                // not, and code quietly relying on auto-create would pass here and corrupt an alias
                // there.
                .withEnv("action.auto_create_index", "+.*,-*")
                .withStartupTimeout(STARTUP_TIMEOUT);
        return container.waitingFor(readyToServe());
    }

    /**
     * The same container bound to a fixed host port, for the suites that stop and restart
     * Elasticsearch at an address a verticle was already configured with.
     *
     * @param hostPort the host port to bind to the container's 9200.
     * @return the configured container, not yet started.
     */
    public static ElasticsearchContainer container(int hostPort) {
        var container = container();
        container.setPortBindings(List.of(hostPort + ":9200"));
        return container;
    }

    /**
     * Waits until the cluster answers its own health endpoint as yellow or green, which is the point
     * a request against an index stops returning 503.
     *
     * @return the wait strategy.
     */
    private static WaitStrategy readyToServe() {
        return Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=60s")
                .forPort(9200)
                .forStatusCode(200)
                .withStartupTimeout(STARTUP_TIMEOUT);
    }
}
