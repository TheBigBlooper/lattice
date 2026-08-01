package io.lattice.common.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.lattice.common.es.ElasticsearchClientFactory;
import io.lattice.common.es.EsRepository;
import io.lattice.common.es.IndexDefinition;
import io.lattice.common.es.InventoryMapping;
import io.lattice.common.es.OrdersMapping;
import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.contract.inventory.InventoryItem;
import io.lattice.contract.orders.Order;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.List;
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
 * The data jobs against a real Elasticsearch, because every interesting thing they do is something
 * only Elasticsearch can confirm: that a reindex actually moved both aliases, that the documents
 * came across, and that a reset really removed the indices.
 *
 * <p>Mocking the client here would assert that this code calls the methods this code calls. It also
 * would not have caught either defect a live run did: the mapping being passed as a whole
 * create-index body rather than nested under {@code mappings}, and the seed inventing a field that a
 * {@code "dynamic": "strict"} mapping rejects.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class DataJobsIT {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.19");

    @SuppressWarnings("resource")
    private static final ElasticsearchContainer ES = new ElasticsearchContainer(IMAGE)
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node")
            // Parity with the chart: a write to an unknown index is REFUSED rather than creating it.
            // Without this the suites would run permissively while a deployed baseline does not, and
            // code that quietly relies on auto-create would pass here and corrupt an alias there.
            .withEnv("action.auto_create_index", "+.*,-*");

    private static final IndexDefinition ORDERS =
            new IndexDefinition(OrdersMapping.MAPPING_JSON, OrdersMapping.SETTINGS_JSON);

    private static final IndexDefinition INVENTORY =
            new IndexDefinition(InventoryMapping.MAPPING_JSON, InventoryMapping.SETTINGS_JSON);

    private static final Map<String, IndexDefinition> INDICES =
            Map.of(OrdersMapping.INDEX, ORDERS, InventoryMapping.INDEX, INVENTORY);

    /** The orders index on its own, the target of every case that reindexes a single index. */
    private static final Map<String, IndexDefinition> ORDERS_ONLY = Map.of(OrdersMapping.INDEX, ORDERS);

    private Vertx vertx;
    private ElasticsearchClient client;
    private DataJobs jobs;

    /** A concrete repository purely to bootstrap the indices the jobs then act on. */
    static final class Bootstrapper extends EsRepository {
        Bootstrapper(Vertx vertx, ElasticsearchClient client) {
            super(vertx, client);
        }
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
    void bootstrap() {
        vertx = Vertx.vertx();
        client = ElasticsearchClientFactory.create("http://" + ES.getHttpHostAddress());
        jobs = new DataJobs(client);

        // Bootstrapped exactly as a service does, settings included, so the index a reindex starts
        // from is the one production would hand it.
        var bootstrapper = new Bootstrapper(vertx, client);
        INDICES.forEach((name, definition) -> bootstrapper
                .ensureIndex(name, definition)
                .toCompletionStage()
                .toCompletableFuture()
                .join());
    }

    @AfterEach
    void wipe() throws Exception {
        // Teardown deletes directly rather than through jobs.reset(): cleanup should not run the code
        // under test, and reset deliberately logs a WARN that would then have to be tolerated by
        // every case for a reason that has nothing to do with what the case asserts.
        var leftovers =
                client.indices().get(g -> g.index("orders-*", "inventory-*").ignoreUnavailable(true));
        if (!leftovers.result().isEmpty()) {
            client.indices().delete(d -> d.index(List.copyOf(leftovers.result().keySet())));
        }
        vertx.close();
    }

    /**
     * A seed that arrives before the services have bootstrapped must REFUSE, and must leave nothing
     * behind - because the damage it otherwise does is permanent rather than merely inconvenient.
     *
     * <p>This is a real incident, not a hypothetical. On a cold three-cluster start the seed beat the
     * owning service and wrote to the write alias {@code orders-write}. Elasticsearch auto-creates an
     * index for an unknown write target, so an INDEX appeared carrying the alias's name - and the
     * service's bootstrap then failed for good, because an alias cannot be created over an existing
     * index:
     *
     * <pre>Invalid alias name [orders-write]: an index or data stream exists with the same name</pre>
     *
     * <p>Every later seed then failed with {@code no such index [orders]}, and recovery meant deleting
     * the bogus indices by hand and rolling the owners. Ordering the seed after the owners removes the
     * known trigger; this pins the behaviour that makes the hazard itself harmless.
     *
     * <p>The second assertion is the one that matters. A refusal that still left {@code orders-write}
     * behind would have failed loudly and broken the baseline anyway.
     */
    @Test
    void seedRefusesBeforeTheIndicesExistAndLeavesNothingBehind() throws Exception {
        // A cluster whose services have never started: the @BeforeEach bootstrap is undone. Resolved
        // then deleted by name, because Elasticsearch refuses a wildcard delete - the same two-step
        // the teardown uses.
        var bootstrapped =
                client.indices().get(g -> g.index("orders-*", "inventory-*").ignoreUnavailable(true));
        client.indices().delete(d -> d.index(List.copyOf(bootstrapped.result().keySet())));

        var refused = assertThrows(IllegalStateException.class, () -> jobs.seed());
        assertTrue(
                refused.getMessage().contains("orders"),
                "the refusal must name what is missing, not report an index nobody asked about: "
                        + refused.getMessage());

        var leftovers = client.indices()
                .get(g -> g.index("orders-write", "inventory-write").ignoreUnavailable(true));
        assertTrue(
                leftovers.result().isEmpty(),
                "a refused seed created " + leftovers.result().keySet()
                        + " - an index carrying an alias's name permanently blocks that alias");
    }

    /** The seed puts the dev dataset where a service reads it: through the alias, not an index name. */
    @Test
    void seedsThroughTheAliases() throws Exception {
        jobs.seed();

        assertEquals(DevDataset.orders().size(), count(OrdersMapping.INDEX));
        assertEquals(DevDataset.inventory().size(), count(InventoryMapping.INDEX));
    }

    /**
     * Every seeded order must read back <b>as the contract type a service returns</b>, not merely as
     * a document Elasticsearch accepted.
     *
     * <p>This is the case that was missing, and its absence shipped a broken dataset twice. The
     * mapping types {@code status} as a keyword, so Elasticsearch stored invented values like
     * {@code FULFILLED} without complaint; the service then failed to decode its own documents and
     * answered {@code UNAVAILABLE}. Counting documents cannot see that - only deserialising through
     * {@link Order} can.
     */
    @Test
    void seedsOrdersThatDeserialiseAsTheContractType() throws Exception {
        jobs.seed();

        for (var seeded : DevDataset.orders()) {
            var id = String.valueOf(seeded.get("orderId"));
            var stored = client.get(g -> g.index(OrdersMapping.INDEX).id(id), Order.class);

            assertTrue(stored.found(), "seeded order " + id + " is present");
            var order = stored.source();
            assertNotNull(order, "seeded order " + id + " decodes as an Order");
            assertNotNull(order.status(), "its status is a value OrderStatus actually declares");
            assertEquals(id, order.orderId());
            assertFalse(order.lines().isEmpty(), "an order with no lines would render as an empty row");
        }
    }

    /** The same for inventory, whose mapping is strict and whose fields a service reads by name. */
    @Test
    void seedsInventoryThatDeserialisesAsTheContractType() throws Exception {
        jobs.seed();

        for (var seeded : DevDataset.inventory()) {
            var sku = String.valueOf(seeded.get("sku"));
            var stored = client.get(g -> g.index(InventoryMapping.INDEX).id(sku), InventoryItem.class);

            assertTrue(stored.found(), "seeded item " + sku + " is present");
            assertNotNull(stored.source(), "seeded item " + sku + " decodes as an InventoryItem");
            assertEquals(sku, stored.source().sku());
        }
    }

    /**
     * Seeded ids are fixed, so running the seed twice overwrites rather than accumulating - a reseed
     * of a drifted cluster should leave the intended dataset, not two of it.
     */
    @Test
    void seedsIdempotently() throws Exception {
        jobs.seed();
        jobs.seed();

        assertEquals(DevDataset.orders().size(), count(OrdersMapping.INDEX));
    }

    /**
     * The reindex moves both aliases onto the next concrete index and brings every document with it.
     * Checking the alias rather than the index is the point: a caller only ever addresses the alias,
     * so that is where a half-finished reindex would show.
     */
    @Test
    void reindexMovesBothAliasesAndKeepsTheDocuments() throws Exception {
        jobs.seed();
        var before = count(OrdersMapping.INDEX);

        jobs.reindex(ORDERS_ONLY);

        var readTarget = indexBehind(OrdersMapping.INDEX);
        var writeTarget = indexBehind(EsRepository.writeAlias(OrdersMapping.INDEX));
        assertEquals("orders-000002", readTarget, "the read alias moved to the next concrete index");
        assertEquals(readTarget, writeTarget, "reads and writes address the same index");
        assertEquals(before, count(OrdersMapping.INDEX), "every document came across");
    }

    /**
     * The index a reindex builds carries the committed settings, not just the committed mapping, so it
     * still resolves to zero replicas on a single node (locked decision #67).
     *
     * <p>This is the case that was missing. A reindex that rebuilt from the mapping alone produced an
     * index on the cluster default replica count, which a single node can never allocate - so running
     * the maintenance job silently put a green local baseline back to permanently yellow, undoing the
     * fix without touching it. Asserting the alias moved and the documents came across cannot see
     * that; only reading the new index's settings can.
     *
     * <p>Two consecutive reindexes are run rather than one, because the counter walking on is what
     * would make a settings body applied only to the first rebuild look correct.
     */
    @Test
    void reindexKeepsTheCommittedIndexSettings() throws Exception {
        jobs.reindex(ORDERS_ONLY);
        jobs.reindex(ORDERS_ONLY);

        var rebuilt = indexBehind(OrdersMapping.INDEX);
        var settings = client.indices().getSettings(g -> g.index(rebuilt)).get(rebuilt);

        assertEquals("orders-000003", rebuilt, "the aliases moved to the twice-rebuilt index");
        assertEquals(
                "0-1",
                settings.settings().index().autoExpandReplicas(),
                rebuilt + " should carry the committed auto_expand_replicas setting");
        assertEquals(
                "0",
                settings.settings().index().numberOfReplicas(),
                rebuilt + " should resolve to zero replicas on a single node");
    }

    /** The previous index is kept, so a mapping change that turns out wrong can be walked back. */
    @Test
    void reindexLeavesThePreviousIndexInPlace() throws Exception {
        jobs.reindex(ORDERS_ONLY);

        assertTrue(
                client.indices().exists(e -> e.index("orders-000001")).value(),
                "the index the aliases moved off is still there to move back to");
    }

    /** Reindexing twice walks the counter rather than colliding on an index that already exists. */
    @Test
    void reindexIsRepeatable() throws Exception {
        jobs.reindex(ORDERS_ONLY);
        jobs.reindex(ORDERS_ONLY);

        assertEquals("orders-000003", indexBehind(OrdersMapping.INDEX));
    }

    /** A write after the swap lands in the new index, which is what makes the write alias load-bearing. */
    @Test
    void writesAfterAReindexLandInTheNewIndex() throws Exception {
        jobs.reindex(ORDERS_ONLY);

        client.index(i -> i.index(EsRepository.writeAlias(OrdersMapping.INDEX))
                .id("written-after")
                .document(Map.of("orderId", "written-after", "customerId", "CUST-9", "status", "RECEIVED")));
        client.indices().refresh(r -> r.index("orders-000002"));

        assertEquals(1, countIn("orders-000002"));
    }

    /**
     * Reset removes the indices; a service recreates them from the mapping on its next start.
     *
     * <p>The WARN is asserted rather than tolerated: a job that deletes indices should say so loudly,
     * and pinning the log here means a future change that quietly downgraded it would fail.
     */
    @Test
    void resetRemovesTheIndices(ExpectedLogs logs) throws Exception {
        logs.expectWarn("deleting");

        jobs.seed();
        jobs.reset(INDICES);

        // Resolved by name rather than asked with a wildcard: an exists() check against a pattern
        // that matches nothing is not the same question, and answers it inconsistently.
        var remaining = client.indices()
                .get(g -> g.index("orders-*", "inventory-*").ignoreUnavailable(true))
                .result();
        assertTrue(
                remaining.isEmpty(), "nothing behind either alias survives a reset, but found " + remaining.keySet());
    }

    /**
     * Resetting a cluster that has nothing to reset succeeds quietly. A reset is the thing someone
     * runs twice while working out what is wrong, and the second run failing would suggest a problem
     * that is not there.
     */
    @Test
    void resetIsHarmlessWhenThereIsNothingToDelete(ExpectedLogs logs) throws Exception {
        logs.expectWarn("deleting");
        jobs.reset(INDICES);

        // Second time: nothing left, and no warning to declare, because nothing was deleted.
        jobs.reset(INDICES);
    }

    /**
     * Reindexing an alias that does not exist says what to do about it. The obvious cause is running
     * the job against a cluster whose services have never started, and "no index behind alias" is a
     * far better thing to read than a stack trace from the alias lookup.
     */
    @Test
    void reindexExplainsItselfWhenTheAliasIsMissing() throws Exception {
        client.indices().delete(d -> d.index("orders-000001"));

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> jobs.reindex(ORDERS_ONLY));
        assertTrue(
                failure.getMessage().contains("bootstraps"), "the message says how to fix it: " + failure.getMessage());
    }

    /**
     * The runner end to end against a real cluster: the guard allows it, the job runs, and the exit
     * code says so. This is the path a Kubernetes Job actually takes, and the unit tests deliberately
     * stop short of it - they cover every refusal, and none of them proves a permitted job works.
     */
    @Test
    void runsASeedEndToEndAndReportsSuccess() {
        var exit = DataJobRunner.run(new String[] {"seed"}, "local", "true", esUrl());

        assertEquals(0, exit, "a permitted seed exits zero");
    }

    /** Limiting a job to one index runs only that one, which is how a targeted reindex is asked for. */
    @Test
    void runsAReindexLimitedToOneIndex() throws Exception {
        var exit = DataJobRunner.run(new String[] {"reindex", "orders"}, "local", "true", esUrl());

        assertEquals(0, exit);
        assertEquals("orders-000002", indexBehind(OrdersMapping.INDEX), "the named index moved");
        assertEquals("inventory-000001", indexBehind(InventoryMapping.INDEX), "the others were left alone");
    }

    /**
     * A job that cannot reach its cluster exits non-zero rather than reporting success, so a
     * Kubernetes Job shows as failed instead of looking like a completed reseed.
     */
    @Test
    void reportsFailureWhenTheClusterIsUnreachable(ExpectedLogs logs) {
        logs.expectError("failed");

        var exit = DataJobRunner.run(new String[] {"seed"}, "local", "true", "http://127.0.0.1:1");

        assertEquals(1, exit);
    }

    private String esUrl() {
        return "http://" + ES.getHttpHostAddress();
    }

    private long count(String alias) throws Exception {
        client.indices().refresh(r -> r.index(alias));
        return client.count(c -> c.index(alias)).count();
    }

    private long countIn(String index) throws Exception {
        return client.count(c -> c.index(index)).count();
    }

    private String indexBehind(String alias) throws Exception {
        return client.indices()
                .getAlias(a -> a.name(alias))
                .result()
                .keySet()
                .iterator()
                .next();
    }
}
