package io.lattice.common.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.StringReader;
import java.util.Optional;
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
 * fields to Elasticsearch dynamic guessing.
 */
public abstract class EsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(EsRepository.class);

    private static final String CONCRETE_INDEX_SUFFIX = "-000001";
    private static final String WRITE_ALIAS_SUFFIX = "-write";

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
     * Ensures the concrete index and its read + write aliases exist, creating them with the given
     * explicit mapping if absent and doing nothing if the read alias already resolves. Idempotent,
     * so it is safe to call on every service startup.
     *
     * @param name        the logical index name (also the read alias), e.g. {@code orders}.
     * @param mappingJson the explicit Elasticsearch mapping body (the {@code mappings} content, e.g.
     *                    {@code {"dynamic":"strict","properties":{...}}}).
     * @return a future completing when the index/aliases exist (already present, or freshly created).
     */
    public Future<Void> ensureIndex(String name, String mappingJson) {
        return vertx.executeBlocking(() -> {
            boolean present = client.indices().existsAlias(a -> a.name(name)).value();
            if (present) {
                LOG.debug("index {} already present; bootstrap is a no-op", name);
                return null;
            }
            var concrete = name + CONCRETE_INDEX_SUFFIX;
            client.indices()
                    .create(c -> c.index(concrete)
                            .mappings(m -> m.withJson(new StringReader(mappingJson)))
                            .aliases(name, a -> a.isWriteIndex(false))
                            .aliases(writeAlias(name), a -> a.isWriteIndex(true)));
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
}
