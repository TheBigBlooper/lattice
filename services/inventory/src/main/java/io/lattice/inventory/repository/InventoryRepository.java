package io.lattice.inventory.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.lattice.common.es.EsRepository;
import io.lattice.common.es.InventoryMapping;
import io.lattice.common.es.Page;
import io.lattice.common.es.ReservationMapping;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.Optional;

/**
 * The inventory-specific Elasticsearch repository: a thin, typed wrapper over the shared
 * {@link EsRepository}. It bootstraps both single-writer indices ({@code inventory} and {@code
 * reservations}) from the mappings in {@code lattice-common} and reads/writes through their
 * read/write aliases. It adds no query DSL of its own; the oversell-safe reserve loop is built from
 * the shared optimistic-concurrency primitives ({@link EsRepository#getVersioned}, {@link
 * EsRepository#indexIfVersionMatches}, {@link EsRepository#createIfAbsent}).
 *
 * <p>Items persist as {@link StoredItem} (the strict mapping's {@code sku}, {@code onHand}, {@code
 * reserved}); the service adds the computed {@code available} when shaping the response. Reservations
 * persist as {@link StoredReservation} - the contract reservation fields plus the internal {@link
 * ReservationStatus} lifecycle the strict mapping stores; the service projects that back to the wire
 * shape (dropping {@code status}) when shaping the response.
 */
public final class InventoryRepository extends EsRepository implements InventoryStore {

    /**
     * Creates the repository over the shared Vert.x instance and Elasticsearch client.
     *
     * @param vertx  the Vert.x instance offloading the blocking client calls to a worker.
     * @param client the shared Elasticsearch client.
     */
    public InventoryRepository(Vertx vertx, ElasticsearchClient client) {
        super(vertx, client);
    }

    /**
     * Ensures both the {@code inventory} and {@code reservations} indices and their read/write aliases
     * exist, creating them from the single-writer mappings and settings if absent. Idempotent - safe to
     * call on every startup.
     *
     * @return a future completing when both indices and their aliases exist.
     */
    public Future<Void> bootstrap() {
        return ensureIndex(InventoryMapping.INDEX, InventoryMapping.MAPPING_JSON, InventoryMapping.SETTINGS_JSON)
                .compose(ready -> ensureIndex(
                        ReservationMapping.INDEX, ReservationMapping.MAPPING_JSON, ReservationMapping.SETTINGS_JSON));
    }

    /**
     * Gets an item by sku through the read alias (no version coordinates), for a plain availability
     * read.
     *
     * @param sku the stock-keeping unit.
     * @return a future of the stored item if present, otherwise an empty optional.
     */
    @Override
    public Future<Optional<StoredItem>> findItem(String sku) {
        return get(InventoryMapping.INDEX, sku, StoredItem.class);
    }

    /**
     * Reads one page of stock items through the read alias, ordered by sku.
     *
     * <p>By sku rather than by recency because inventory is a catalogue an operator scans for a
     * known item, where orders are a feed read from the top. {@code sku} is already a
     * {@code keyword} in the mapping, so sorting on it needs no mapping change.
     *
     * @param page the zero-based page index.
     * @param size the page size.
     * @return a future of the page of stored items, with the total across the whole index.
     */
    @Override
    public Future<Page<StoredItem>> findItemPage(int page, int size) {
        return searchPage(InventoryMapping.INDEX, "sku", true, page, size, StoredItem.class);
    }

    /**
     * Gets an item by sku with its optimistic-concurrency coordinates, for a conditional write in the
     * reserve/set-stock loop.
     *
     * @param sku the stock-keeping unit.
     * @return a future of the versioned stored item if present, otherwise an empty optional.
     */
    @Override
    public Future<Optional<VersionedDocument<StoredItem>>> findVersionedItem(String sku) {
        return getVersioned(InventoryMapping.INDEX, sku, StoredItem.class);
    }

    /**
     * Conditionally writes an item under optimistic concurrency, succeeding only if its coordinates
     * still match. Raises {@link EsRepository.VersionConflictException} (via the future) on a
     * concurrent update so the caller re-reads and retries.
     *
     * @param item        the item to write (its {@link StoredItem#sku()} is the document id).
     * @param seqNo       the sequence number the write is conditioned on.
     * @param primaryTerm the primary term the write is conditioned on.
     * @return a future of the stored document id.
     */
    @Override
    public Future<String> writeItemIfVersionMatches(StoredItem item, long seqNo, long primaryTerm) {
        return indexIfVersionMatches(writeAlias(InventoryMapping.INDEX), item.sku(), item, seqNo, primaryTerm);
    }

    /**
     * Creates an item only if none exists for its sku yet (a create-only write), reporting whether it
     * won the create.
     *
     * @param item the item to create (its {@link StoredItem#sku()} is the document id).
     * @return a future of {@code true} if created, {@code false} if an item already existed.
     */
    @Override
    public Future<Boolean> createItemIfAbsent(StoredItem item) {
        return createIfAbsent(writeAlias(InventoryMapping.INDEX), item.sku(), item);
    }

    /**
     * Gets a reservation by its order-line id ({@code "<orderId>:<sku>"}), the idempotency lookup. The
     * stored reservation carries its {@link ReservationStatus} so the caller can distinguish a committed
     * ({@code CONFIRMED}) reservation from one still in flight ({@code PENDING}).
     *
     * @param reservationDocId the reservations document id.
     * @return a future of the stored reservation if present, otherwise an empty optional.
     */
    @Override
    public Future<Optional<StoredReservation>> findReservation(String reservationDocId) {
        return get(ReservationMapping.INDEX, reservationDocId, StoredReservation.class);
    }

    /**
     * Creates a {@code PENDING} reservation record only if none exists for its order line yet (a
     * create-only write), reporting whether it won the create - the atomic idempotency gate.
     *
     * @param reservationDocId the reservations document id ({@code "<orderId>:<sku>"}).
     * @param reservation      the pending reservation to create.
     * @return a future of {@code true} if created, {@code false} if a reservation already existed.
     */
    @Override
    public Future<Boolean> createReservationIfAbsent(String reservationDocId, StoredReservation reservation) {
        return createIfAbsent(writeAlias(ReservationMapping.INDEX), reservationDocId, reservation);
    }

    /**
     * Promotes the gate winner's reservation record to {@code CONFIRMED} by replacing the document
     * (the winner exclusively owns it, so an unconditional index is safe).
     *
     * @param reservationDocId the reservations document id ({@code "<orderId>:<sku>"}).
     * @param reservation      the confirmed reservation to write.
     * @return a future completing when the confirmed record is persisted.
     */
    @Override
    public Future<Void> confirmReservation(String reservationDocId, StoredReservation reservation) {
        return index(writeAlias(ReservationMapping.INDEX), reservationDocId, reservation)
                .mapEmpty();
    }

    @Override
    public Future<Void> deleteReservation(String reservationDocId) {
        return delete(writeAlias(ReservationMapping.INDEX), reservationDocId);
    }
}
