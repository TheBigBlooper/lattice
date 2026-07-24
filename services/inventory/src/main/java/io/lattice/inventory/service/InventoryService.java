package io.lattice.inventory.service;

import io.lattice.common.es.EsRepository.VersionConflictException;
import io.lattice.contract.inventory.CreateReservationRequest;
import io.lattice.contract.inventory.InventoryItem;
import io.lattice.contract.inventory.Reservation;
import io.lattice.contract.inventory.SetStockRequest;
import io.lattice.inventory.repository.InventoryStore;
import io.lattice.inventory.repository.StoredItem;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The inventory business logic: setting absolute stock, reading availability, and the oversell-safe,
 * idempotent reserve. It owns the invariants the data layer must enforce (available never negative,
 * on-hand never below reserved) and the server-set fields a client must never supply (the {@code
 * reservationId} UUID and the {@code createdAt} UTC timestamp). Handlers stay thin by delegating here;
 * this class holds no Vert.x web types.
 *
 * <p>Every read and write is sequenced after the one-time index bootstrap future, so both indices are
 * guaranteed to exist before the first document is written, without blocking startup on Elasticsearch
 * (readiness gates traffic instead).
 *
 * <p><b>Oversell safety.</b> Reserve and set-stock read the item with its {@code seq_no} /
 * {@code primary_term} and write the modified counter conditionally; a concurrent update makes the
 * conditional write conflict, which re-reads and retries (bounded by {@link #MAX_ATTEMPTS}). Two
 * racing reserves cannot both pass the availability check, because the loser's conditional write fails
 * and it re-reads against the already-incremented counter.
 */
public final class InventoryService {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryService.class);

    /** The bounded retry ceiling for the optimistic-concurrency read-modify-write loop. */
    private static final int MAX_ATTEMPTS = 5;

    private final InventoryStore repository;
    private final Future<Void> indexReady;

    /**
     * Creates the service over its persistence store and the index-bootstrap future to sequence behind.
     * The store is a shared, injected collaborator (the standard dependency-injection pattern), held by
     * reference not copied.
     *
     * @param repository the inventory persistence store.
     * @param indexReady the future that completes when both indices are provisioned.
     */
    public InventoryService(InventoryStore repository, Future<Void> indexReady) {
        this.repository = repository;
        this.indexReady = indexReady;
    }

    /**
     * Reads an item's availability by sku, computing {@code available} ({@code onHand - reserved}) at
     * read.
     *
     * @param sku the stock-keeping unit.
     * @return a future of the item (with computed available) if present, otherwise an empty optional.
     */
    public Future<Optional<InventoryItem>> getInventory(String sku) {
        LOG.debug("reading inventory sku={}", sku);
        return indexReady.compose(ready -> repository.findItem(sku)).map(found -> found.map(InventoryService::toItem));
    }

    /**
     * Sets an item's on-hand to the absolute requested value under optimistic concurrency, creating
     * the item ({@code reserved = 0}) when absent. Rejects with {@link StockConflictException} when the
     * new on-hand would fall below the currently reserved quantity (available would go negative).
     *
     * @param sku     the stock-keeping unit.
     * @param request the validated set-stock request.
     * @return a future of the resulting item (with computed available).
     */
    public Future<InventoryItem> setStock(String sku, SetStockRequest request) {
        return indexReady.compose(ready -> attemptSetStock(sku, request, 1));
    }

    /**
     * Reserves stock against the order line {@code (orderId, sku)}, oversell-safe and idempotent. A
     * repeat for the same order line returns the existing reservation when the quantity matches, or
     * rejects with {@link StockConflictException} when it differs.
     *
     * <p><b>Record-first idempotency gate.</b> The reservation record is the exactly-once gate: a new
     * order line first passes a rollback-free pre-check (unknown sku -&gt; {@link UnknownSkuException};
     * insufficient available -&gt; {@link StockConflictException}), then a create-if-absent write of the
     * record decides ownership. Only the gate winner ({@code created == true}) goes on to increment
     * {@code reserved}, so the counter is incremented at most once per order line - two concurrent
     * identical requests cannot double-count, because the loser observes the winner's record instead of
     * incrementing. This is the fix for the earlier counter-first flow, where both concurrent duplicates
     * passed the existence check and both incremented.
     *
     * <p><b>Accepted MVP edge.</b> The counter hold ({@link #holdStock}) runs after the gate, and on
     * the narrow post-gate races (the sku vanished, stock raced out between the pre-check and the hold,
     * or the retry ceiling was exceeded) it rolls the record back with a best-effort delete. A
     * concurrent duplicate that read the record in the fast path just before that rollback deletes it
     * can observe a since-removed reservation; this residual edge is rare and accepted for the MVP
     * (documented in the design spec).
     *
     * @param request the validated create-reservation request.
     * @return a future of the created (or existing) reservation.
     */
    public Future<Reservation> reserve(CreateReservationRequest request) {
        var docId = InventoryStore.reservationId(request.orderId(), request.sku());
        return indexReady.compose(ready -> repository.findReservation(docId)).compose(existing -> {
            if (existing.isPresent()) {
                return idempotentResult(existing.get(), request);
            }
            // Pre-check availability without writing a record, so the common rejects stay rollback-free.
            return repository.findVersionedItem(request.sku()).compose(versioned -> {
                if (versioned.isEmpty()) {
                    return Future.failedFuture(new UnknownSkuException(request.sku()));
                }
                var item = versioned.get().document();
                if (available(item) < request.quantity()) {
                    return Future.failedFuture(insufficient(request, item));
                }
                // Atomic gate: only the create-if-absent winner increments the counter.
                var reservation = new Reservation(
                        UUID.randomUUID().toString(),
                        request.orderId(),
                        request.sku(),
                        request.quantity(),
                        Instant.now().toString());
                return repository.createReservationIfAbsent(docId, reservation).compose(won -> {
                    if (Boolean.TRUE.equals(won)) {
                        return holdStock(request, docId, reservation, 1);
                    }
                    // A concurrent identical request won the gate: return its record, no counter change.
                    return repository
                            .findReservation(docId)
                            .compose(raced -> raced.isPresent()
                                    ? idempotentResult(raced.get(), request)
                                    : Future.failedFuture(new StockConflictException(
                                            "reservation " + docId + " is being rolled back concurrently; retry")));
                });
            });
        });
    }

    /**
     * Resolves an existing reservation for a repeat request: the same quantity is the idempotent hit
     * (return it), a different quantity is a conflict (a changed reservation is not the same request).
     */
    private Future<Reservation> idempotentResult(Reservation existing, CreateReservationRequest request) {
        if (existing.quantity() == request.quantity()) {
            LOG.debug(
                    "reservation idempotent hit order={} sku={} qty={}",
                    request.orderId(),
                    request.sku(),
                    request.quantity());
            return Future.succeededFuture(existing);
        }
        return Future.failedFuture(new StockConflictException("reservation "
                + InventoryStore.reservationId(request.orderId(), request.sku()) + " already exists with quantity "
                + existing.quantity() + ", not " + request.quantity()));
    }

    /**
     * Holds the reserved stock under optimistic concurrency for the gate winner - the one and only
     * place {@code reserved} is incremented. Re-reads and retries on a version conflict, bounded by
     * {@link #MAX_ATTEMPTS}. On a post-gate race that cannot complete (the sku vanished, stock raced out
     * since the pre-check, or the retry ceiling was exceeded) it rolls the idempotency record back with
     * a best-effort delete before failing, so no phantom record is left holding no stock.
     */
    private Future<Reservation> holdStock(
            CreateReservationRequest request, String docId, Reservation reservation, int attempt) {
        if (attempt > MAX_ATTEMPTS) {
            return rollbackThenFail(
                    docId, new IllegalStateException("reserve exceeded " + MAX_ATTEMPTS + " concurrency retries"));
        }
        return repository.findVersionedItem(request.sku()).compose(versioned -> {
            if (versioned.isEmpty()) {
                return rollbackThenFail(docId, new UnknownSkuException(request.sku()));
            }
            var vd = versioned.get();
            var item = vd.document();
            if (available(item) < request.quantity()) {
                return rollbackThenFail(docId, insufficient(request, item));
            }
            var incremented = new StoredItem(item.sku(), item.onHand(), item.reserved() + request.quantity());
            return repository
                    .writeItemIfVersionMatches(incremented, vd.seqNo(), vd.primaryTerm())
                    .map(ignored -> {
                        LOG.info(
                                "reserved sku={} qty={} order={}",
                                request.sku(),
                                request.quantity(),
                                request.orderId());
                        return reservation;
                    })
                    .recover(err -> err instanceof VersionConflictException
                            ? holdStock(request, docId, reservation, attempt + 1)
                            : Future.failedFuture(err));
        });
    }

    /** Best-effort roll back of the idempotency record, then fail with the given error. */
    private Future<Reservation> rollbackThenFail(String docId, Throwable error) {
        return repository.deleteReservation(docId).compose(rolledBack -> Future.failedFuture(error));
    }

    private static int available(StoredItem item) {
        return item.onHand() - item.reserved();
    }

    private static StockConflictException insufficient(CreateReservationRequest request, StoredItem item) {
        return new StockConflictException("insufficient stock for sku " + request.sku() + ": available "
                + available(item) + ", requested " + request.quantity());
    }

    private Future<InventoryItem> attemptSetStock(String sku, SetStockRequest request, int attempt) {
        if (attempt > MAX_ATTEMPTS) {
            return Future.failedFuture(
                    new IllegalStateException("set-stock exceeded " + MAX_ATTEMPTS + " concurrency retries"));
        }
        return repository.findVersionedItem(sku).compose(versioned -> {
            if (versioned.isEmpty()) {
                var created = new StoredItem(sku, request.onHand(), 0);
                return repository.createItemIfAbsent(created).compose(won -> {
                    if (Boolean.TRUE.equals(won)) {
                        LOG.info("stock set sku={} onHand={} (created)", sku, request.onHand());
                        return Future.succeededFuture(toItem(created));
                    }
                    // Lost the create race: an item now exists, so retry as an update.
                    return attemptSetStock(sku, request, attempt + 1);
                });
            }
            var vd = versioned.get();
            int reserved = vd.document().reserved();
            if (request.onHand() < reserved) {
                return Future.failedFuture(new StockConflictException(
                        "cannot set onHand " + request.onHand() + " below reserved " + reserved + " for sku " + sku));
            }
            var updated = new StoredItem(sku, request.onHand(), reserved);
            return repository
                    .writeItemIfVersionMatches(updated, vd.seqNo(), vd.primaryTerm())
                    .map(ignored -> {
                        LOG.info("stock set sku={} onHand={} reserved={}", sku, request.onHand(), reserved);
                        return toItem(updated);
                    })
                    .recover(err -> err instanceof VersionConflictException
                            ? attemptSetStock(sku, request, attempt + 1)
                            : Future.failedFuture(err));
        });
    }

    private static InventoryItem toItem(StoredItem item) {
        return new InventoryItem(item.sku(), item.onHand(), item.reserved(), item.onHand() - item.reserved());
    }
}
