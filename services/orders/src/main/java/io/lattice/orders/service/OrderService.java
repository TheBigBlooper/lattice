package io.lattice.orders.service;

import io.lattice.common.es.IndexBootstrap;
import io.lattice.contract.orders.CreateOrderRequest;
import io.lattice.contract.orders.Order;
import io.lattice.contract.orders.OrderLine;
import io.lattice.contract.orders.OrderStatus;
import io.lattice.orders.repository.OrdersRepository;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The orders business logic: minting a new order from a create request and fetching one by id. It
 * owns the server-set fields a client must never supply - the {@code orderId} (a UUID), the {@code
 * createdAt} timestamp (UTC), and the initial {@link OrderStatus#RECEIVED} status - then persists
 * through the {@link OrdersRepository}. Handlers stay thin by delegating here.
 *
 * <p>Every read and write is sequenced behind the index bootstrap, so the {@code orders} index is
 * guaranteed to exist before the first document is written, without blocking service startup on
 * Elasticsearch (readiness gates traffic instead). The bootstrap is an {@link IndexBootstrap} rather
 * than a bare future so that a provisioning attempt which failed because Elasticsearch was not yet
 * reachable is retried on the next request, instead of wedging the service until a restart.
 */
public final class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    private final OrdersRepository repository;
    private final IndexBootstrap indexBootstrap;

    /**
     * Creates the service over its repository and the index bootstrap to sequence behind.
     *
     * @param repository     the orders repository.
     * @param indexBootstrap the retrying gate that provisions the {@code orders} index.
     */
    public OrderService(OrdersRepository repository, IndexBootstrap indexBootstrap) {
        this.repository = repository;
        this.indexBootstrap = indexBootstrap;
    }

    /**
     * Creates and persists an order from a validated request, minting its id, timestamp, and initial
     * status. The client-supplied fields ({@code customerId}, {@code lines}) are copied through; no
     * client id is trusted.
     *
     * @param request the validated create-order request.
     * @return a future of the created order (as persisted).
     */
    public Future<Order> create(CreateOrderRequest request) {
        List<OrderLine> lines = request.lines().stream()
                .map(line -> new OrderLine(line.sku(), line.quantity()))
                .toList();
        var order = new Order(
                UUID.randomUUID().toString(),
                request.customerId(),
                OrderStatus.RECEIVED,
                lines,
                Instant.now().toString());
        return indexBootstrap
                .ready()
                .compose(ready -> repository.save(order))
                .map(id -> order)
                .onSuccess(created -> LOG.info(
                        "order created id={} customer={} lines={}",
                        created.orderId(),
                        created.customerId(),
                        created.lines().size()));
    }

    /**
     * Fetches an order by id.
     *
     * @param orderId the order id.
     * @return a future of the order if present, otherwise an empty optional.
     */
    public Future<Optional<Order>> get(String orderId) {
        LOG.debug("fetching order id={}", orderId);
        return indexBootstrap.ready().compose(ready -> repository.findById(orderId));
    }
}
