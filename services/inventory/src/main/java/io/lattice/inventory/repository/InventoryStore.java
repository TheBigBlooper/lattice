package io.lattice.inventory.repository;

import io.lattice.common.es.EsRepository.VersionedDocument;
import io.vertx.core.Future;
import java.util.Optional;

/**
 * The persistence seam the {@link io.lattice.inventory.service.InventoryService} depends on: the
 * item and reservation reads/writes its oversell-safe, idempotent logic needs, decoupled from the
 * concrete Elasticsearch repository. {@link InventoryRepository} is the production implementation;
 * the seam lets the service's optimistic-concurrency retry, retry-ceiling, and idempotency branches
 * be driven deterministically in a unit test without a live Elasticsearch (they cannot be forced
 * reliably through the HTTP + Elasticsearch integration path).
 */
public interface InventoryStore {

    /**
     * Builds the reservations document id from an order line, the idempotency key
     * {@code "<orderId>:<sku>"}.
     *
     * @param orderId the order id.
     * @param sku     the stock-keeping unit.
     * @return the reservations document id for that order line.
     */
    static String reservationId(String orderId, String sku) {
        return orderId + ":" + sku;
    }

    /**
     * Gets an item by sku (no version coordinates), for a plain availability read.
     *
     * @param sku the stock-keeping unit.
     * @return a future of the stored item if present, otherwise an empty optional.
     */
    Future<Optional<StoredItem>> findItem(String sku);

    /**
     * Gets an item by sku with its optimistic-concurrency coordinates, for a conditional write.
     *
     * @param sku the stock-keeping unit.
     * @return a future of the versioned stored item if present, otherwise an empty optional.
     */
    Future<Optional<VersionedDocument<StoredItem>>> findVersionedItem(String sku);

    /**
     * Conditionally writes an item under optimistic concurrency, succeeding only if its coordinates
     * still match. Signals a version conflict (via the future) on a concurrent update so the caller
     * re-reads and retries.
     *
     * @param item        the item to write (its {@link StoredItem#sku()} is the document id).
     * @param seqNo       the sequence number the write is conditioned on.
     * @param primaryTerm the primary term the write is conditioned on.
     * @return a future of the stored document id.
     */
    Future<String> writeItemIfVersionMatches(StoredItem item, long seqNo, long primaryTerm);

    /**
     * Creates an item only if none exists for its sku yet (a create-only write), reporting whether it
     * won the create.
     *
     * @param item the item to create (its {@link StoredItem#sku()} is the document id).
     * @return a future of {@code true} if created, {@code false} if an item already existed.
     */
    Future<Boolean> createItemIfAbsent(StoredItem item);

    /**
     * Gets a reservation by its order-line id ({@code "<orderId>:<sku>"}), the idempotency lookup. The
     * returned {@link StoredReservation} carries the lifecycle {@link ReservationStatus} so the caller
     * can tell a committed reservation from one still in flight.
     *
     * @param reservationDocId the reservations document id.
     * @return a future of the stored reservation if present, otherwise an empty optional.
     */
    Future<Optional<StoredReservation>> findReservation(String reservationDocId);

    /**
     * Creates a reservation record ({@link ReservationStatus#PENDING}) only if none exists for its order
     * line yet (a create-only write), reporting whether it won the create - the atomic idempotency gate.
     *
     * @param reservationDocId the reservations document id ({@code "<orderId>:<sku>"}).
     * @param reservation      the pending reservation to create.
     * @return a future of {@code true} if created, {@code false} if a reservation already existed.
     */
    Future<Boolean> createReservationIfAbsent(String reservationDocId, StoredReservation reservation);

    /**
     * Promotes the gate winner's reservation record to {@link ReservationStatus#CONFIRMED} once the
     * stock hold has completed (a full replace by document id - the winner exclusively owns the record).
     * After this write the reservation is committed and a concurrent reader may trust it.
     *
     * @param reservationDocId the reservations document id ({@code "<orderId>:<sku>"}).
     * @param reservation      the confirmed reservation to write.
     * @return a future completing when the confirmed record is persisted.
     */
    Future<Void> confirmReservation(String reservationDocId, StoredReservation reservation);

    /**
     * Deletes a reservation record by its order-line id, tolerating an already-absent one. Used to roll
     * back the idempotency-gate record on the narrow post-gate races where the stock hold cannot be
     * completed (the sku vanished, stock raced out, or the retry ceiling was exceeded). Because a
     * committed record is {@code CONFIRMED} and only an uncompleted {@code PENDING} record is ever
     * deleted, a concurrent reader never observes a since-removed committed reservation.
     *
     * @param reservationDocId the reservations document id ({@code "<orderId>:<sku>"}).
     * @return a future completing when the record is gone (deleted now, or already absent).
     */
    Future<Void> deleteReservation(String reservationDocId);
}
