package io.lattice.inventory.service;

import io.lattice.common.RetryingGate;
import io.lattice.common.es.EsRepository.VersionConflictException;
import io.lattice.common.es.Page;
import io.lattice.contract.inventory.CreateReservationRequest;
import io.lattice.contract.inventory.InventoryItem;
import io.lattice.contract.inventory.Reservation;
import io.lattice.contract.inventory.SetStockRequest;
import io.lattice.inventory.repository.InventoryStore;
import io.lattice.inventory.repository.ReservationStatus;
import io.lattice.inventory.repository.StoredItem;
import io.lattice.inventory.repository.StoredReservation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
 *
 * <p><b>Cross-document consistency (the reservation lifecycle).</b> The counter and the reservation
 * record are two documents with no shared transaction, so the record carries a {@link ReservationStatus}
 * lifecycle to keep a concurrent reader consistent. The gate winner creates the record {@code PENDING},
 * increments the counter, then promotes the record to {@code CONFIRMED}; a hold that cannot complete
 * deletes the (still {@code PENDING}) record. A reader therefore trusts a record as a committed
 * reservation only when it is {@code CONFIRMED} - a state never rolled back - and waits for an in-flight
 * {@code PENDING} record to settle rather than returning one that may be about to be removed. This
 * closes the earlier post-gate rollback edge, where a duplicate could briefly observe a since-removed
 * reservation (a phantom success).
 */
public final class InventoryService {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryService.class);

    /** The bounded retry ceiling for the optimistic-concurrency read-modify-write loop. */
    private static final int MAX_ATTEMPTS = 5;

    /** The bounded number of re-reads a reader makes waiting for an in-flight reservation to settle. */
    private static final int MAX_SETTLE_ATTEMPTS = 40;

    /** The backoff between settle re-reads, in milliseconds (yielding the event loop between polls). */
    private static final long SETTLE_BACKOFF_MS = 25;

    private final Vertx vertx;
    private final InventoryStore repository;
    private final RetryingGate indexBootstrap;

    /**
     * Creates the service over its persistence store and the index-bootstrap future to sequence behind.
     * The store is a shared, injected collaborator (the standard dependency-injection pattern), held by
     * reference not copied. The Vert.x instance supplies the settle-poll backoff timer.
     *
     * @param vertx      the Vert.x instance whose timer backs the reservation settle-poll.
     * @param repository the inventory persistence store.
     * @param indexBootstrap the retrying gate that provisions both indices.
     */
    public InventoryService(Vertx vertx, InventoryStore repository, RetryingGate indexBootstrap) {
        this.vertx = vertx;
        this.repository = repository;
        this.indexBootstrap = indexBootstrap;
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
        return indexBootstrap
                .ready()
                .compose(ready -> repository.findItem(sku))
                .map(found -> found.map(InventoryService::toItem));
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
        return indexBootstrap.ready().compose(ready -> attemptSetStock(sku, request, 1));
    }

    /**
     * Reserves stock against the order line {@code (orderId, sku)}, oversell-safe and idempotent. A
     * repeat for the same order line returns the existing reservation when the quantity matches, or
     * rejects with {@link StockConflictException} when it differs.
     *
     * <p><b>Record-first idempotency gate.</b> The reservation record is the exactly-once gate: a new
     * order line first passes a rollback-free pre-check (unknown sku -&gt; {@link UnknownSkuException};
     * insufficient available -&gt; {@link StockConflictException}), then a create-if-absent write of a
     * {@code PENDING} record decides ownership. Only the gate winner ({@code created == true}) goes on
     * to increment {@code reserved} and promote its record to {@code CONFIRMED}, so the counter is
     * incremented at most once per order line - two concurrent identical requests cannot double-count,
     * because the loser resolves against the winner's record instead of incrementing.
     *
     * <p><b>Post-gate rollback edge (closed).</b> The counter hold ({@link #holdStock}) runs after the
     * gate, and on the narrow post-gate races (the sku vanished, stock raced out between the pre-check
     * and the hold, or the retry ceiling was exceeded) it deletes the still-{@code PENDING} record and
     * fails. Because only a {@code PENDING} record is ever deleted and a reader trusts only a {@code
     * CONFIRMED} one, a concurrent duplicate never observes a since-removed committed reservation: it
     * waits for the in-flight record to become {@code CONFIRMED} or disappear ({@link #awaitSettled}).
     *
     * @param request the validated create-reservation request.
     * @return a future of the created (or existing) reservation.
     */
    public Future<Reservation> reserve(CreateReservationRequest request) {
        var docId = InventoryStore.reservationId(request.orderId(), request.sku());
        return indexBootstrap
                .ready()
                .compose(ready -> repository.findReservation(docId))
                .compose(existing -> {
                    if (existing.isPresent()) {
                        return resolveExisting(existing.get(), request, docId, 0);
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
                        // Atomic gate: create a PENDING record; only the create-if-absent winner increments.
                        var pending = new StoredReservation(
                                UUID.randomUUID().toString(),
                                request.orderId(),
                                request.sku(),
                                request.quantity(),
                                Instant.now().toString(),
                                ReservationStatus.PENDING);
                        return repository
                                .createReservationIfAbsent(docId, pending)
                                .compose(won -> {
                                    if (Boolean.TRUE.equals(won)) {
                                        return holdStock(request, docId, pending, 1);
                                    }
                                    // A concurrent identical request won the gate: resolve against its record, no
                                    // counter change - waiting for it to settle if it is still in flight.
                                    return repository
                                            .findReservation(docId)
                                            .compose(raced -> raced.isPresent()
                                                    ? resolveExisting(raced.get(), request, docId, 0)
                                                    : Future.failedFuture(new StockConflictException("reservation "
                                                            + docId + " is being rolled back concurrently; retry")));
                                });
                    });
                });
    }

    /**
     * Resolves an already-present reservation record for a repeat or concurrent-duplicate request. A
     * {@code CONFIRMED} record is committed and returned as the idempotent hit immediately; a {@code
     * PENDING} record is still in flight (the gate winner has not yet completed its hold), so the caller
     * waits for it to settle rather than trusting a record that may be about to be rolled back.
     */
    private Future<Reservation> resolveExisting(
            StoredReservation existing, CreateReservationRequest request, String docId, int settleAttempt) {
        return switch (existing.status()) {
            case CONFIRMED -> idempotentResult(existing, request);
            case PENDING -> awaitSettled(request, docId, settleAttempt);
        };
    }

    /**
     * Waits for an in-flight ({@code PENDING}) reservation to settle, re-reading after a short backoff
     * until it becomes {@code CONFIRMED} (return the idempotent hit) or disappears (the winner rolled it
     * back). Bounded by {@link #MAX_SETTLE_ATTEMPTS}; a still-unsettled or rolled-back record surfaces a
     * retryable {@link StockConflictException} rather than a phantom success. Never returns a record it
     * has not seen reach {@code CONFIRMED}, which is what closes the post-gate rollback edge.
     */
    private Future<Reservation> awaitSettled(CreateReservationRequest request, String docId, int settleAttempt) {
        if (settleAttempt >= MAX_SETTLE_ATTEMPTS) {
            return Future.failedFuture(
                    new StockConflictException("reservation " + docId + " is still settling; retry"));
        }
        return vertx.timer(SETTLE_BACKOFF_MS)
                .compose(tick -> repository.findReservation(docId))
                .compose(found -> {
                    if (found.isEmpty()) {
                        // The gate winner rolled its record back: the order line is free again; retry.
                        return Future.failedFuture(
                                new StockConflictException("reservation " + docId + " was rolled back; retry"));
                    }
                    var settled = found.get();
                    return switch (settled.status()) {
                        case CONFIRMED -> idempotentResult(settled, request);
                        case PENDING -> awaitSettled(request, docId, settleAttempt + 1);
                    };
                });
    }

    /**
     * Resolves an existing reservation for a repeat request: the same quantity is the idempotent hit
     * (return it), a different quantity is a conflict (a changed reservation is not the same request).
     */
    private Future<Reservation> idempotentResult(StoredReservation existing, CreateReservationRequest request) {
        if (existing.quantity() == request.quantity()) {
            LOG.debug(
                    "reservation idempotent hit order={} sku={} qty={}",
                    request.orderId(),
                    request.sku(),
                    request.quantity());
            return Future.succeededFuture(existing.toReservation());
        }
        return Future.failedFuture(new StockConflictException("reservation "
                + InventoryStore.reservationId(request.orderId(), request.sku()) + " already exists with quantity "
                + existing.quantity() + ", not " + request.quantity()));
    }

    /**
     * Holds the reserved stock under optimistic concurrency for the gate winner - the one and only
     * place {@code reserved} is incremented. Re-reads and retries on a version conflict, bounded by
     * {@link #MAX_ATTEMPTS}. On success it promotes the gate record from {@code PENDING} to {@code
     * CONFIRMED} (making it committed and safe for a concurrent reader to trust) before returning. On a
     * post-gate race that cannot complete (the sku vanished, stock raced out since the pre-check, or the
     * retry ceiling was exceeded) it deletes the still-{@code PENDING} record before failing, so no
     * phantom record is left holding no stock.
     */
    private Future<Reservation> holdStock(
            CreateReservationRequest request, String docId, StoredReservation pending, int attempt) {
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
                    .compose(ignored -> {
                        var confirmed = pending.confirmed();
                        // Promote the gate record to CONFIRMED so a concurrent reader may trust it. The
                        // counter is already held, so a confirm failure would leave a durable PENDING
                        // record; propagate it (the request fails) rather than silently swallowing it.
                        return repository.confirmReservation(docId, confirmed).map(done -> {
                            LOG.info(
                                    "reserved sku={} qty={} order={}",
                                    request.sku(),
                                    request.quantity(),
                                    request.orderId());
                            return confirmed.toReservation();
                        });
                    })
                    .recover(err -> err instanceof VersionConflictException
                            ? holdStock(request, docId, pending, attempt + 1)
                            : Future.failedFuture(err));
        });
    }

    /** Best-effort roll back of the still-pending idempotency record, then fail with the given error. */
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

    /**
     * Reads one page of this baseline stock items, ordered by sku.
     *
     * <p>{@code available} is computed at read for every item on the page, exactly as the
     * single-item read computes it. It is never stored: a persisted availability is a third number
     * that can disagree with the two it is derived from, and a page is precisely where that
     * disagreement would be visible side by side.
     *
     * @param page the zero-based page index.
     * @param size the page size.
     * @return a future of the page, empty when this baseline holds no stock items.
     */
    public Future<Page<InventoryItem>> list(int page, int size) {
        LOG.debug("listing inventory page={} size={}", page, size);
        return indexBootstrap
                .ready()
                .compose(ready -> repository.findItemPage(page, size))
                .map(found -> new Page<>(
                        found.items().stream().map(InventoryService::toItem).toList(), found.total()));
    }

    private static InventoryItem toItem(StoredItem item) {
        return new InventoryItem(item.sku(), item.onHand(), item.reserved(), item.onHand() - item.reserved());
    }
}
