package io.lattice.common.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link EsRepository} against a real Elasticsearch (Testcontainers, the
 * pinned 8.19.19 image with security disabled). Documented Elasticsearch-mapping TDD exception: a
 * mapping cannot be queried until the index exists, so these spec-driven integration tests ship in
 * the same change and are driven to green. They pin the create-if-absent bootstrap (concrete index
 * plus read and write aliases), its idempotency, and the index/get round-trip.
 */
@ExtendWith(VertxExtension.class)
class EsRepositoryIT {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.19");

    private static final String MAPPING = """
            {
              "dynamic": "strict",
              "properties": {
                "name": { "type": "keyword" },
                "count": { "type": "integer" }
              }
            }
            """;

    // Singleton container pattern: started in @BeforeAll, stopped in @AfterAll. The suppression
    // silences the IDE resource-leak heuristic, which does not model the Testcontainers stop()
    // lifecycle; the container is closed deterministically below.
    @SuppressWarnings("resource")
    private static final ElasticsearchContainer ES = new ElasticsearchContainer(IMAGE)
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    private Vertx vertx;
    private ElasticsearchClient client;
    private WidgetRepository repository;

    /** A tiny document indexed and read back by id. */
    record Widget(String name, int count) {}

    /** A minimal concrete repository over the shared base, exercising its bootstrap + index/get. */
    static final class WidgetRepository extends EsRepository {
        WidgetRepository(Vertx vertx, ElasticsearchClient client) {
            super(vertx, client);
        }
    }

    @BeforeAll
    static void startContainer() {
        ES.start();
    }

    @AfterAll
    static void stopContainer() {
        ES.stop();
    }

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        client = ElasticsearchClientFactory.create("http://" + ES.getHttpHostAddress());
        repository = new WidgetRepository(vertx, client);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        vertx.close();
    }

    /**
     * ensureIndex creates the concrete index and both the read and write aliases when absent, and a
     * second call is a no-op (idempotent) that leaves exactly one concrete index in place.
     */
    @Test
    void ensureIndexCreatesAliasesAndIsIdempotent(VertxTestContext ctx) throws Exception {
        var index = "widgets-a";
        repository
                .ensureIndex(index, MAPPING)
                .compose(first -> repository.ensureIndex(index, MAPPING)) // second call must be a no-op
                .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    var concrete = index + "-000001";
                    if (!client.indices().exists(e -> e.index(concrete)).value()) {
                        ctx.failNow("expected concrete index " + concrete + " to exist");
                        return;
                    }
                    var readAlias =
                            client.indices().getAlias(a -> a.name(index)).result();
                    var writeAlias = client.indices()
                            .getAlias(a -> a.name(EsRepository.writeAlias(index)))
                            .result();
                    if (!readAlias.containsKey(concrete) || !writeAlias.containsKey(concrete)) {
                        ctx.failNow("expected read + write aliases to point at " + concrete);
                        return;
                    }
                    ctx.completeNow();
                })));
        ctx.awaitCompletion(60, TimeUnit.SECONDS);
    }

    /**
     * A document indexed through the write alias is read back by id through the read alias with its
     * fields intact (the index/get round-trip).
     */
    @Test
    void indexThenGetRoundTripsDocument(VertxTestContext ctx) throws Exception {
        var index = "widgets-b";
        repository
                .ensureIndex(index, MAPPING)
                .compose(done -> repository.index(EsRepository.writeAlias(index), "w1", new Widget("bolt", 7)))
                .compose(id -> repository.get(index, "w1", Widget.class))
                .onComplete(ctx.succeeding(found -> ctx.verify(() -> {
                    if (found.isEmpty()
                            || !found.get().name().equals("bolt")
                            || found.get().count() != 7) {
                        ctx.failNow("expected round-tripped widget {bolt,7} but got " + found);
                        return;
                    }
                    ctx.completeNow();
                })));
        ctx.awaitCompletion(60, TimeUnit.SECONDS);
    }

    /** A get for an id that was never indexed resolves to an empty optional, not an error. */
    @Test
    void getMissingDocumentReturnsEmpty(VertxTestContext ctx) throws Exception {
        var index = "widgets-c";
        repository
                .ensureIndex(index, MAPPING)
                .compose(done -> repository.get(index, "absent", Widget.class))
                .onComplete(ctx.succeeding((Optional<Widget> found) -> ctx.verify(() -> {
                    if (found.isPresent()) {
                        ctx.failNow("expected empty optional for a missing document");
                        return;
                    }
                    ctx.completeNow();
                })));
        ctx.awaitCompletion(60, TimeUnit.SECONDS);
    }
}
