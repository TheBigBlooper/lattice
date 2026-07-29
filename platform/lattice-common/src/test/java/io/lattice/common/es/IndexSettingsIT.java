package io.lattice.common.es;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The committed index settings against a real single-node Elasticsearch (Testcontainers, the pinned
 * 8.19.19 image with security disabled), which is the only thing that can confirm what this change
 * is for: that a baseline bootstrapped on one node is genuinely green rather than described as green.
 *
 * <p>Documented Elasticsearch index TDD exception: an index's settings cannot be queried until the
 * index exists, so this spec-driven integration test ships in the same change as the settings it pins
 * and is driven to green rather than strictly written red first.
 *
 * <p>Both cases exercise the setting through the shared bootstrap the services actually call, not
 * through a hand-written create-index body, so a bootstrap that stopped passing the settings would
 * fail here.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class IndexSettingsIT {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.19");

    /** How long to let the cluster settle before reading its health, so an assertion is not a race. */
    private static final long SETTLE_BUDGET_MILLIS = 30_000L;

    private static final long POLL_INTERVAL_MILLIS = 250L;

    // Singleton container pattern (as in EsRepositoryIT): started in @BeforeAll, stopped in @AfterAll.
    // The suppression silences the IDE resource-leak heuristic, which does not model the Testcontainers
    // stop() lifecycle; the container is closed deterministically below. A single node is the point of
    // this suite, so discovery.type stays single-node.
    @SuppressWarnings("resource")
    private static final ElasticsearchContainer ES = new ElasticsearchContainer(IMAGE)
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    private Vertx vertx;
    private ElasticsearchClient client;

    /** A minimal concrete repository over the shared base, used purely to run the bootstrap. */
    static final class Bootstrapper extends EsRepository {
        Bootstrapper(Vertx vertx, ElasticsearchClient client) {
            super(vertx, client);
        }
    }

    /** The three indices a baseline owns, each with the definition it is created from. */
    private static Map<String, IndexDefinition> baselineIndices() {
        var byName = new LinkedHashMap<String, IndexDefinition>();
        byName.put(OrdersMapping.INDEX, new IndexDefinition(OrdersMapping.MAPPING_JSON, OrdersMapping.SETTINGS_JSON));
        byName.put(
                InventoryMapping.INDEX,
                new IndexDefinition(InventoryMapping.MAPPING_JSON, InventoryMapping.SETTINGS_JSON));
        byName.put(
                ReservationMapping.INDEX,
                new IndexDefinition(ReservationMapping.MAPPING_JSON, ReservationMapping.SETTINGS_JSON));
        return Map.copyOf(byName);
    }

    @BeforeAll
    static void startEs() {
        ES.start();
    }

    @AfterAll
    static void stopEs() {
        ES.stop();
    }

    @BeforeEach
    void bootstrapBaselineIndices() {
        vertx = Vertx.vertx();
        client = ElasticsearchClientFactory.create("http://" + ES.getHttpHostAddress());

        var bootstrapper = new Bootstrapper(vertx, client);
        baselineIndices()
                .forEach((name, definition) -> bootstrapper
                        .ensureIndex(name, definition.mappingJson(), definition.settingsJson())
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        vertx.close();
    }

    /**
     * Every index a baseline bootstraps carries {@code auto_expand_replicas: "0-1"} and therefore
     * resolves to zero replicas on a single node, rather than the Elasticsearch default of one replica
     * that a single node can never allocate. The effective replica count is asserted alongside the
     * setting itself, because the setting is only useful if Elasticsearch actually derived the count
     * from it.
     */
    @Test
    void everyBaselineIndexResolvesToZeroReplicasOnASingleNode() throws IOException {
        for (var name : baselineIndices().keySet()) {
            var concrete = name + "-000001";
            var settings = client.indices().getSettings(g -> g.index(concrete)).get(concrete);

            assertEquals(
                    "0-1",
                    settings.settings().index().autoExpandReplicas(),
                    concrete + " should carry the committed auto_expand_replicas setting");
            assertEquals(
                    "0",
                    settings.settings().index().numberOfReplicas(),
                    concrete + " should resolve to zero replicas on a single node");
        }
    }

    /**
     * With the baseline's indices bootstrapped, a single-node cluster reports health green with no
     * unassigned shards, rather than the permanent yellow a default one-replica index produces there.
     * This is the reading the mesh-gateway's cluster-health poll returns, so it is what an operator
     * would see on the infrastructure row.
     */
    @Test
    void aSingleNodeClusterCarryingTheBaselineIndicesIsGreen() throws Exception {
        var health = settledHealth();

        assertEquals(HealthStatus.Green, health.status(), "a single-node baseline should be green, not yellow");
        assertEquals(0, health.unassignedShards(), "no shard should be left unassigned");
    }

    /**
     * Reads cluster health once allocation has settled, polling until the cluster reports green or the
     * budget runs out. Polling rather than reading once keeps the assertion about the setting instead
     * of about how quickly Elasticsearch happened to finish allocating a freshly created shard; a
     * cluster that never goes green simply returns its last reading for the assertion to report.
     *
     * @return the last cluster-health reading taken.
     * @throws Exception if Elasticsearch refuses the health request, or the wait is interrupted.
     */
    private HealthResponse settledHealth() throws Exception {
        var deadline = System.nanoTime() + SETTLE_BUDGET_MILLIS * 1_000_000L;
        var health = client.cluster().health();
        while (health.status() != HealthStatus.Green && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            health = client.cluster().health();
        }
        return health;
    }
}
