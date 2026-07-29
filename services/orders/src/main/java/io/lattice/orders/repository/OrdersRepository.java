package io.lattice.orders.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.lattice.common.es.EsRepository;
import io.lattice.common.es.OrdersMapping;
import io.lattice.common.es.Page;
import io.lattice.contract.orders.Order;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.Optional;

/**
 * The orders-specific Elasticsearch repository: a thin, typed wrapper over the shared
 * {@link EsRepository}. It bootstraps the {@code orders} index from the single-writer mapping in
 * {@code lattice-common} and reads/writes {@link Order} documents through the read/write aliases. All
 * Elasticsearch access goes through the shared client the base holds; this class adds no query DSL of
 * its own (the MVP needs only create-if-absent bootstrap and index/get by id).
 */
public final class OrdersRepository extends EsRepository {

    /**
     * Creates the repository over the shared Vert.x instance and Elasticsearch client.
     *
     * @param vertx  the Vert.x instance offloading the blocking client calls to a worker.
     * @param client the shared Elasticsearch client.
     */
    public OrdersRepository(Vertx vertx, ElasticsearchClient client) {
        super(vertx, client);
    }

    /**
     * Ensures the {@code orders} index and its read/write aliases exist, creating them from the
     * single-writer mapping and settings if absent. Idempotent - safe to call on every startup.
     *
     * @return a future completing when the index and aliases exist.
     */
    public Future<Void> bootstrap() {
        return ensureIndex(OrdersMapping.INDEX, OrdersMapping.DEFINITION);
    }

    /**
     * Indexes an order by its id through the write alias, refreshing so it is immediately readable.
     *
     * @param order the order to persist (its {@link Order#orderId()} is the document id).
     * @return a future of the stored document id.
     */
    public Future<String> save(Order order) {
        return index(writeAlias(OrdersMapping.INDEX), order.orderId(), order);
    }

    /**
     * Gets an order by id through the read alias.
     *
     * @param orderId the order id.
     * @return a future of the order if present, otherwise an empty optional.
     */
    public Future<Optional<Order>> findById(String orderId) {
        return get(OrdersMapping.INDEX, orderId, Order.class);
    }

    /**
     * Reads one page of orders through the read alias, newest first.
     *
     * <p>Sorted by {@code createdAt} descending because orders are a feed an operator reads from the
     * top - the most recent work is what they came to see. {@code createdAt} is already a
     * {@code date} in the mapping, so this needs no mapping change; if it ever did, that would be a
     * signal the contract's list shape had drifted rather than something to absorb here.
     *
     * @param page the zero-based page index.
     * @param size the page size.
     * @return a future of the page, with the total across every order this baseline holds.
     */
    public Future<Page<Order>> findPage(int page, int size) {
        return searchPage(OrdersMapping.INDEX, "createdAt", false, page, size, Order.class);
    }
}
