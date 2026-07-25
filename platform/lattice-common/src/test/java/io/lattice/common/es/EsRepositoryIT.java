package io.lattice.common.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
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
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
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

    // The same mapping with one field added - an additive evolution ensureIndex must apply to an
    // already-existing index (Elasticsearch put-mapping adds new fields; it never mutates existing ones).
    private static final String MAPPING_EVOLVED = """
            {
              "dynamic": "strict",
              "properties": {
                "name": { "type": "keyword" },
                "count": { "type": "integer" },
                "label": { "type": "keyword" }
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

    /** The evolved document shape, carrying the field an additive mapping update adds. */
    record WidgetV2(String name, int count, String label) {}

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
     * ensureIndex applies an additive mapping evolution to an already-existing index: a second call
     * with a mapping that adds a field puts the new field onto the live index (Elasticsearch put-mapping
     * is additive), so a document carrying the new field indexes without a strict-mapping rejection.
     * This is the upgrade-in-place path a create-if-absent-only bootstrap would miss (an existing index
     * keeps its old mapping and rejects the new field). Uses the documented Elasticsearch-mapping TDD
     * exception.
     */
    @Test
    void ensureIndexAppliesAdditiveMappingEvolution(VertxTestContext ctx) throws Exception {
        var index = "widgets-evolve";
        var writeAlias = EsRepository.writeAlias(index);
        repository
                .ensureIndex(index, MAPPING) // initial mapping: {name, count}
                .compose(done -> repository.ensureIndex(index, MAPPING_EVOLVED)) // evolves: adds {label}
                .compose(done -> repository.index(writeAlias, "w1", new WidgetV2("bolt", 7, "shiny")))
                .compose(id -> repository.get(index, "w1", WidgetV2.class))
                .onComplete(ctx.succeeding(found -> ctx.verify(() -> {
                    if (found.isEmpty() || !"shiny".equals(found.get().label())) {
                        ctx.failNow("expected the added 'label' field to persist after an additive mapping "
                                + "evolution on an existing index, got " + found);
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

    /**
     * getVersioned returns a present document with its seq_no / primary_term for an indexed id, and an
     * empty optional for a missing id (the read half of an optimistic-concurrency read-modify-write).
     */
    @Test
    void getVersionedReturnsCoordinatesOrEmpty(VertxTestContext ctx) throws Exception {
        var index = "widgets-d";
        repository
                .ensureIndex(index, MAPPING)
                .compose(done -> repository.index(EsRepository.writeAlias(index), "w1", new Widget("gear", 4)))
                .compose(id -> repository.getVersioned(index, "w1", Widget.class))
                .compose(present -> {
                    if (present.isEmpty()
                            || present.get().primaryTerm() < 1
                            || !present.get().document().name().equals("gear")) {
                        return io.vertx.core.Future.failedFuture("expected a present versioned document");
                    }
                    return repository.getVersioned(index, "absent", Widget.class);
                })
                .onComplete(ctx.succeeding(missing -> ctx.verify(() -> {
                    if (missing.isPresent()) {
                        ctx.failNow("expected empty optional for a missing document");
                        return;
                    }
                    ctx.completeNow();
                })));
        ctx.awaitCompletion(60, TimeUnit.SECONDS);
    }

    /**
     * indexIfVersionMatches succeeds when the seq_no / primary_term still match, and raises a
     * VersionConflictException when a concurrent write has moved them on (a stale write is rejected,
     * not silently applied).
     */
    @Test
    void indexIfVersionMatchesEnforcesTheCondition(VertxTestContext ctx) throws Exception {
        var index = "widgets-e";
        var writeAlias = EsRepository.writeAlias(index);
        repository
                .ensureIndex(index, MAPPING)
                .compose(done -> repository.index(writeAlias, "w1", new Widget("cog", 1)))
                .compose(id -> repository.getVersioned(index, "w1", Widget.class))
                .compose(read -> {
                    var vd = read.orElseThrow();
                    // First conditional write on the read coordinates succeeds and advances the version.
                    return repository
                            .indexIfVersionMatches(writeAlias, "w1", new Widget("cog", 2), vd.seqNo(), vd.primaryTerm())
                            .map(ignored -> vd);
                })
                .compose(staleCoordinates -> repository
                        .indexIfVersionMatches(
                                writeAlias,
                                "w1",
                                new Widget("cog", 3),
                                staleCoordinates.seqNo(),
                                staleCoordinates.primaryTerm())
                        .transform(attempt -> {
                            // The second write reuses the now-stale coordinates: it must be a conflict.
                            if (attempt.succeeded()) {
                                return io.vertx.core.Future.failedFuture("expected a version conflict on stale write");
                            }
                            if (!(attempt.cause() instanceof EsRepository.VersionConflictException)) {
                                return io.vertx.core.Future.failedFuture(
                                        "expected VersionConflictException but got " + attempt.cause());
                            }
                            return io.vertx.core.Future.succeededFuture();
                        }))
                .onComplete(ctx.succeeding(done -> ctx.completeNow()));
        ctx.awaitCompletion(60, TimeUnit.SECONDS);
    }

    /**
     * delete removes a document (immediately readable as gone), treats an already-absent id as a no-op
     * success (so it is safe as a best-effort rollback), and propagates any other error (here a write to
     * the read alias, which has no write index, is a non-404 error).
     */
    @Test
    void deleteRemovesToleratesMissingAndPropagatesOtherErrors(VertxTestContext ctx) throws Exception {
        var index = "widgets-h";
        var writeAlias = EsRepository.writeAlias(index);
        repository
                .ensureIndex(index, MAPPING)
                .compose(done -> repository.index(writeAlias, "w1", new Widget("nut", 1)))
                .compose(id -> repository.delete(writeAlias, "w1"))
                .compose(done -> repository.get(index, "w1", Widget.class))
                .compose(found -> {
                    if (found.isPresent()) {
                        return io.vertx.core.Future.failedFuture("expected the document to be deleted");
                    }
                    // Deleting an already-absent id is a no-op success.
                    return repository.delete(writeAlias, "absent");
                })
                .compose(done -> repository
                        // The read alias has is_write_index=false, so a delete through it is a non-404 error.
                        .delete(index, "w1")
                        .transform(attempt -> {
                            if (attempt.succeeded()) {
                                return io.vertx.core.Future.failedFuture(
                                        "expected a non-404 delete error to propagate");
                            }
                            return io.vertx.core.Future.succeededFuture();
                        }))
                .onComplete(ctx.succeeding(done -> ctx.completeNow()));
        ctx.awaitCompletion(60, TimeUnit.SECONDS);
    }

    /**
     * A non-conflict Elasticsearch error (here a strict-mapping violation, HTTP 400) from either
     * optimistic-concurrency write propagates as-is rather than being misread as a version conflict
     * (indexIfVersionMatches) or a lost create (createIfAbsent). Both conditional writes classify only
     * the 409 conflict specially; every other error surfaces unchanged.
     */
    @Test
    void conditionalWritesPropagateNonConflictErrors(VertxTestContext ctx) throws Exception {
        var index = "widgets-g";
        var writeAlias = EsRepository.writeAlias(index);
        // A document with a field the strict mapping does not declare: Elasticsearch rejects it 400.
        var offMapping = java.util.Map.of("name", "bad", "surprise", "nope");
        repository
                .ensureIndex(index, MAPPING)
                .compose(done -> repository.index(writeAlias, "w1", new Widget("seed", 1)))
                .compose(id -> repository.getVersioned(index, "w1", Widget.class))
                .compose(read -> {
                    var vd = read.orElseThrow();
                    return repository
                            .indexIfVersionMatches(writeAlias, "w1", offMapping, vd.seqNo(), vd.primaryTerm())
                            .transform(attempt -> {
                                if (attempt.succeeded()
                                        || attempt.cause() instanceof EsRepository.VersionConflictException) {
                                    return io.vertx.core.Future.failedFuture(
                                            "expected a non-conflict error to propagate, got " + attempt.cause());
                                }
                                return io.vertx.core.Future.succeededFuture();
                            });
                })
                .compose(done -> repository
                        .createIfAbsent(writeAlias, "w2", offMapping)
                        .transform(attempt -> {
                            if (attempt.succeeded()) {
                                return io.vertx.core.Future.failedFuture("expected the off-mapping create to fail");
                            }
                            return io.vertx.core.Future.succeededFuture();
                        }))
                .onComplete(ctx.succeeding(done -> ctx.completeNow()));
        ctx.awaitCompletion(60, TimeUnit.SECONDS);
    }

    /**
     * createIfAbsent reports true when it wins the create and false when a document with that id
     * already exists (the create-only write does not clobber the existing document).
     */
    @Test
    void createIfAbsentWinsOnceThenReportsExisting(VertxTestContext ctx) throws Exception {
        var index = "widgets-f";
        var writeAlias = EsRepository.writeAlias(index);
        repository
                .ensureIndex(index, MAPPING)
                .compose(done -> repository.createIfAbsent(writeAlias, "w1", new Widget("first", 1)))
                .compose(created -> {
                    if (!Boolean.TRUE.equals(created)) {
                        return io.vertx.core.Future.failedFuture("expected the first create to win");
                    }
                    return repository.createIfAbsent(writeAlias, "w1", new Widget("second", 2));
                })
                .compose(secondCreate -> {
                    if (!Boolean.FALSE.equals(secondCreate)) {
                        return io.vertx.core.Future.failedFuture("expected the second create to report existing");
                    }
                    // The original document is untouched (not clobbered by the second create).
                    return repository.get(index, "w1", Widget.class);
                })
                .onComplete(ctx.succeeding(found -> ctx.verify(() -> {
                    if (found.isEmpty() || !found.get().name().equals("first")) {
                        ctx.failNow("expected the original document to be preserved, got " + found);
                        return;
                    }
                    ctx.completeNow();
                })));
        ctx.awaitCompletion(60, TimeUnit.SECONDS);
    }
}
