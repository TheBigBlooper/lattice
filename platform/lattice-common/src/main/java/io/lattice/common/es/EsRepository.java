package io.lattice.common.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.StringReader;
import java.util.Objects;
import java.util.Optional;
import org.elasticsearch.client.ResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The base class every per-service Elasticsearch repository extends. It concentrates the shared
 * data-layer mechanics so a service repository is a thin, typed wrapper: create-if-absent index
 * bootstrap behind read/write aliases, and index/get by id. No repository builds its own client or
 * scatters ad-hoc connection logic.
 *
 * <p><b>Index shape (per the data-model design).</b> {@link #ensureIndex(String, String)} provisions
 * an index-per-entity behind two aliases: for a logical name {@code orders} it creates the concrete
 * index {@code orders-000001}, a read alias {@code orders} pointing at it, and a write alias
 * {@code orders-write} (the write index). Callers only ever address the aliases, so a later mapping
 * change (reindex into {@code orders-000002}, repoint the aliases) is invisible to them. The call is
 * create-if-absent: present is a no-op, so a service can safely bootstrap on every startup.
 *
 * <p><b>Off the event loop.</b> The Elasticsearch client's calls are synchronous, so every operation
 * here runs on a worker via {@link Vertx#executeBlocking} and returns a Vert.x {@link Future}; the
 * event loop is never blocked.
 *
 * <p><b>Explicit mappings only.</b> {@code ensureIndex} takes the mapping body verbatim; callers pass
 * an explicit mapping (a constrained {@code dynamic} policy, deliberate field types), never leaving
 * fields to Elasticsearch dynamic guessing. {@link #ensureIndex(String, String, String)} takes an
 * index settings body the same way, applied when the index is created.
 *
 * <p><b>Optimistic concurrency.</b> For a read-modify-write that must not lose a concurrent update
 * (e.g. a stock counter), {@link #getVersioned(String, String, Class)} returns a document with its
 * {@code seq_no} / {@code primary_term}, and {@link #indexIfVersionMatches} writes the modified
 * document only if those coordinates still hold, raising {@link VersionConflictException} otherwise so
 * the caller can re-read and retry. {@link #createIfAbsent} writes only when no document has the id
 * yet (a create-only write), reporting whether it won the create rather than clobbering an existing
 * document. {@link #delete(String, String)} removes a document by id, tolerating an already-absent one
 * so it can serve as a best-effort rollback. These primitives are generic and belong on the shared
 * base, not any one service.
 */
public abstract class EsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(EsRepository.class);

    private static final String CONCRETE_INDEX_SUFFIX = "-000001";
    private static final String WRITE_ALIAS_SUFFIX = "-write";
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_NOT_FOUND = 404;

    /** The Vert.x instance whose worker pool runs the blocking client calls. */
    protected final Vertx vertx;

    /** The shared Elasticsearch client this repository reads and writes through. */
    protected final ElasticsearchClient client;

    /**
     * Creates a repository over the shared Vert.x instance and Elasticsearch client.
     *
     * @param vertx  the Vert.x instance offloading blocking client calls to a worker.
     * @param client the shared Elasticsearch client.
     */
    protected EsRepository(Vertx vertx, ElasticsearchClient client) {
        this.vertx = vertx;
        this.client = client;
    }

    /**
     * Returns the write-alias name for a logical index name (the alias writes should target).
     *
     * @param name the logical index name.
     * @return the write alias, e.g. {@code orders-write} for {@code orders}.
     */
    public static String writeAlias(String name) {
        return name + WRITE_ALIAS_SUFFIX;
    }

    /**
     * Ensures the concrete index and its read + write aliases exist and carry the given mapping,
     * creating them with the explicit mapping if absent and otherwise applying the mapping additively
     * to the already-existing index. Idempotent, so it is safe to call on every service startup.
     *
     * <p><b>Additive mapping evolution.</b> When the index already exists, the mapping is re-applied via
     * Elasticsearch put-mapping, which <em>adds</em> newly declared fields to the live index and no-ops
     * fields that are unchanged; an incompatible change (mutating an existing field's type) is rejected
     * by Elasticsearch rather than silently applied. This is what lets a new field reach an
     * already-bootstrapped index (an upgrade in place) - a create-if-absent-only bootstrap would leave
     * the old mapping, so a document carrying the new field would fail against the strict mapping. It is
     * additive only by design; a breaking mapping change still needs a reindex-behind-alias (per the
     * data-model design), which this does not attempt.
     *
     * @param name        the logical index name (also the read alias), e.g. {@code orders}.
     * @param mappingJson the explicit Elasticsearch mapping body (the {@code mappings} content, e.g.
     *                    {@code {"dynamic":"strict","properties":{...}}}).
     * @return a future completing when the index/aliases exist and carry the mapping (freshly created,
     *     or an existing index with the mapping additively applied).
     */
    public Future<Void> ensureIndex(String name, String mappingJson) {
        return ensureIndex(name, mappingJson, null);
    }

    /**
     * Ensures the index and its aliases exist as {@link #ensureIndex(String, String)} does, and applies
     * an explicit index settings body when the index is created.
     *
     * <p><b>Settings apply on create only.</b> A create-index call is the one moment the whole index
     * shape is chosen at once, and most index settings are static (they cannot be changed on a live
     * index). Callers pass the settings their index was designed with, e.g.
     * {@link OrdersMapping#SETTINGS_JSON}. An index that already exists keeps the settings it was
     * created with; this call does not reconcile them, so a settings change reaches an existing index
     * only through a reindex into a new concrete index (per the data-model design).
     *
     * @param name         the logical index name (also the read alias), e.g. {@code orders}.
     * @param mappingJson  the explicit Elasticsearch mapping body (the {@code mappings} content).
     * @param settingsJson the explicit Elasticsearch index settings body (the {@code settings} content,
     *                     e.g. {@code {"auto_expand_replicas":"0-1"}}), or {@code null} to create the
     *                     index with the cluster defaults.
     * @return a future completing when the index/aliases exist and carry the mapping (freshly created
     *     with the given settings, or an existing index with the mapping additively applied).
     */
    public Future<Void> ensureIndex(String name, String mappingJson, String settingsJson) {
        return vertx.executeBlocking(() -> {
            boolean present = client.indices().existsAlias(a -> a.name(name)).value();
            if (present) {
                // Existing index: apply the mapping additively so a newly declared field reaches it.
                client.indices().putMapping(pm -> pm.index(writeAlias(name)).withJson(new StringReader(mappingJson)));
                LOG.debug("index {} already present; applied additive mapping update", name);
                return null;
            }
            var concrete = name + CONCRETE_INDEX_SUFFIX;
            client.indices().create(c -> {
                c.index(concrete)
                        .mappings(m -> m.withJson(new StringReader(mappingJson)))
                        .aliases(name, a -> a.isWriteIndex(false))
                        .aliases(writeAlias(name), a -> a.isWriteIndex(true));
                if (settingsJson != null) {
                    c.settings(s -> s.withJson(new StringReader(settingsJson)));
                }
                return c;
            });
            LOG.info("bootstrapped index {}", name);
            return null;
        });
    }

    /**
     * Indexes (creates or replaces) a document by id, refreshing so it is immediately searchable.
     * The target is normally the write alias ({@link #writeAlias(String)}).
     *
     * @param target   the index or write alias to write to.
     * @param id       the document id.
     * @param document the document value (a record/POJO bound via the Jackson mapper).
     * @return a future of the indexed document's id.
     */
    public Future<String> index(String target, String id, Object document) {
        return vertx.executeBlocking(() -> client.index(
                        i -> i.index(target).id(id).document(document).refresh(Refresh.True))
                .id());
    }

    /**
     * Gets a document by id, deserialized into the given type. The target is normally the read alias.
     *
     * @param target the index or read alias to read from.
     * @param id     the document id.
     * @param type   the type to deserialize the source into.
     * @param <T>    the document type.
     * @return a future of the document if found, otherwise an empty optional.
     */
    public <T> Future<Optional<T>> get(String target, String id, Class<T> type) {
        return vertx.executeBlocking(() -> {
            var response = client.get(g -> g.index(target).id(id), type);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.<T>empty();
        });
    }

    /**
     * Gets a document by id along with its Elasticsearch optimistic-concurrency coordinates
     * ({@code seq_no} / {@code primary_term}), for a subsequent conditional write. The target is
     * normally the read alias; because the read and write aliases resolve to the same concrete index,
     * the coordinates read here are the ones {@link #indexIfVersionMatches} checks on write.
     *
     * @param target the index or read alias to read from.
     * @param id     the document id.
     * @param type   the type to deserialize the source into.
     * @param <T>    the document type.
     * @return a future of the versioned document if found, otherwise an empty optional.
     */
    public <T> Future<Optional<VersionedDocument<T>>> getVersioned(String target, String id, Class<T> type) {
        return vertx.executeBlocking(() -> {
            var response = client.get(g -> g.index(target).id(id), type);
            if (!response.found()) {
                return Optional.<VersionedDocument<T>>empty();
            }
            return Optional.of(new VersionedDocument<>(response.source(), response.seqNo(), response.primaryTerm()));
        });
    }

    /**
     * Conditionally indexes a document by id, succeeding only if its {@code seq_no} /
     * {@code primary_term} still match the coordinates a prior {@link #getVersioned} read returned
     * (no concurrent write has landed in between). On a version conflict this raises a
     * {@link VersionConflictException} so the caller can re-read and retry, rather than silently
     * overwriting a concurrent update. Refreshes so the write is immediately readable. The target is
     * normally the write alias ({@link #writeAlias(String)}).
     *
     * @param target      the index or write alias to write to.
     * @param id          the document id.
     * @param document    the document value (a record/POJO bound via the Jackson mapper).
     * @param seqNo       the sequence number the write is conditioned on.
     * @param primaryTerm the primary term the write is conditioned on.
     * @return a future of the indexed document's id.
     * @throws VersionConflictException (via the future) if a concurrent write changed the document.
     */
    public Future<String> indexIfVersionMatches(
            String target, String id, Object document, long seqNo, long primaryTerm) {
        return vertx.executeBlocking(() -> {
            try {
                return client.index(i -> i.index(target)
                                .id(id)
                                .document(document)
                                .ifSeqNo(seqNo)
                                .ifPrimaryTerm(primaryTerm)
                                .refresh(Refresh.True))
                        .id();
            } catch (ResponseException | ElasticsearchException e) {
                // A version conflict is the one error retried on; anything else propagates unchanged.
                if (httpStatus(e) == HTTP_CONFLICT) {
                    throw new VersionConflictException("version conflict indexing id=" + id, e);
                }
                throw e;
            }
        });
    }

    /**
     * Creates a document by id only if none exists yet (a create-only write), refreshing so it is
     * immediately readable. Reports whether this call won the create ({@code true}) or a document with
     * that id already existed ({@code false}), so a caller can treat an existing document as a no-op
     * rather than clobbering it. The target is normally the write alias ({@link #writeAlias(String)}).
     *
     * @param target   the index or write alias to write to.
     * @param id       the document id.
     * @param document the document value (a record/POJO bound via the Jackson mapper).
     * @return a future of {@code true} if the document was created, {@code false} if it already existed.
     */
    public Future<Boolean> createIfAbsent(String target, String id, Object document) {
        return vertx.executeBlocking(() -> {
            try {
                client.index(i -> i.index(target)
                        .id(id)
                        .document(document)
                        .opType(OpType.Create)
                        .refresh(Refresh.True));
                return true;
            } catch (ResponseException | ElasticsearchException e) {
                // An existing id is an expected outcome (no-op), not a failure; other errors propagate.
                if (httpStatus(e) == HTTP_CONFLICT) {
                    return false;
                }
                throw e;
            }
        });
    }

    /**
     * Reads one sorted page of an index.
     *
     * <p><b>It lives here rather than in each repository</b> because paging is the same operation
     * whatever the documents are: an offset, a size, a sort, and a total. Two copies would be two
     * places for the page arithmetic to drift, and the arithmetic is exactly where an off-by-one on
     * the last page hides.
     *
     * <p><b>Offset paging, honestly.</b> {@code from}/{@code size} is what this is, and it is what
     * the contract declares - a page index and a size. A search-after cursor would be stabler under
     * concurrent writes, but emulating one behind an offset-shaped contract, or the reverse, would
     * make the query quietly disagree with what a client was told it asked for.
     *
     * <p><b>The total is tracked exactly.</b> Elasticsearch caps hit counts at 10,000 by default and
     * reports anything beyond as a lower bound, which would silently understate the last page number
     * on a large index. Asking for an exact count trades some cost for a page count that is true.
     *
     * @param target    the index or alias to search.
     * @param sortField the field to sort by.
     * @param ascending whether to sort ascending; descending puts the newest first.
     * @param page      the zero-based page index.
     * @param size      the page size.
     * @param type      the document type to deserialize into.
     * @param <T>       the document type.
     * @return the requested slice, with the total across the whole index.
     */
    public <T> Future<Page<T>> searchPage(
            String target, String sortField, boolean ascending, int page, int size, Class<T> type) {
        return vertx.executeBlocking(() -> {
            var response = client.search(
                    search -> search.index(target)
                            .from(page * size)
                            .size(size)
                            .trackTotalHits(track -> track.enabled(true))
                            .sort(sort -> sort.field(
                                    field -> field.field(sortField).order(ascending ? SortOrder.Asc : SortOrder.Desc))),
                    type);
            var items = response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .toList();
            var total = response.hits().total();
            return new Page<>(items, total == null ? items.size() : total.value());
        });
    }

    /**
     * Deletes a document by id, refreshing so the removal is immediately visible. A missing document
     * (HTTP 404) is treated as a no-op success, so the call is safe to use as a best-effort rollback
     * (deleting a record that may already be gone). Any other error propagates. The target is normally
     * the write alias ({@link #writeAlias(String)}).
     *
     * @param target the index or write alias to delete from.
     * @param id     the document id.
     * @return a future completing when the document is gone (deleted now, or already absent).
     */
    public Future<Void> delete(String target, String id) {
        return vertx.executeBlocking(() -> {
            try {
                client.delete(d -> d.index(target).id(id).refresh(Refresh.True));
            } catch (ResponseException | ElasticsearchException e) {
                // Already gone is success; anything else (a real failure) propagates unchanged.
                if (httpStatus(e) != HTTP_NOT_FOUND) {
                    throw e;
                }
            }
            return null;
        });
    }

    /**
     * Reads the HTTP status from an Elasticsearch client error, which the typed client surfaces either
     * as the low-level {@link ResponseException} or as an {@link ElasticsearchException}, so both
     * carriers are read. Used to treat specific statuses (a 409 conflict on a conditional write, a 404
     * not-found on a delete) as expected outcomes rather than failures.
     *
     * @param error the client error to read.
     * @return the HTTP status carried by the error.
     */
    private static int httpStatus(Exception error) {
        return error instanceof ResponseException re
                ? re.getResponse().getStatusLine().getStatusCode()
                : ((ElasticsearchException) error).status();
    }

    /**
     * A document read together with its Elasticsearch optimistic-concurrency coordinates, returned by
     * {@link EsRepository#getVersioned(String, String, Class)} and fed back into
     * {@link EsRepository#indexIfVersionMatches} for a conditional write.
     *
     * @param document    the deserialized document.
     * @param seqNo       the document's sequence number at read time.
     * @param primaryTerm the document's primary term at read time.
     * @param <T>         the document type.
     */
    public record VersionedDocument<T>(T document, long seqNo, long primaryTerm) {}

    /**
     * Signals that a conditional write ({@link EsRepository#indexIfVersionMatches}) was rejected
     * because the document changed since it was read - the distinct outcome a caller retries on
     * (re-read, recompute, write again), separate from a genuine failure.
     */
    public static final class VersionConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception with a message and the underlying Elasticsearch conflict as its cause.
         *
         * @param message the detail message.
         * @param cause   the underlying conflict cause.
         */
        public VersionConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
