package io.lattice.inventory.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.lattice.common.RetryingGate;
import io.lattice.common.es.EsRepository.VersionConflictException;
import io.lattice.common.es.EsRepository.VersionedDocument;
import io.lattice.common.es.Page;
import io.lattice.contract.inventory.CreateReservationRequest;
import io.lattice.contract.inventory.SetStockRequest;
import io.lattice.inventory.repository.InventoryStore;
import io.lattice.inventory.repository.ReservationStatus;
import io.lattice.inventory.repository.StoredItem;
import io.lattice.inventory.repository.StoredReservation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for the {@link InventoryService} reserve and set-stock logic, driven against an
 * in-memory fake repository so the optimistic-concurrency retry, idempotency, and conflict branches
 * are exercised deterministically without a real Elasticsearch. The integration wiring (real router,
 * real Elasticsearch round-trip) is covered separately by {@code InventoryServiceIT}; these tests pin
 * the algorithm, including the version-conflict re-read, the bounded-retry ceiling, and the reservation
 * {@code PENDING -> CONFIRMED} lifecycle that closes the post-gate rollback edge, all hard to hit
 * deterministically against a live store.
 */
@ExtendWith(VertxExtension.class)
class InventoryServiceTest {

    private Vertx vertx;
    private FakeRepository repository;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        repository = new FakeRepository();
        service = new InventoryService(vertx, repository, new RetryingGate(Future::succeededFuture));
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    /** setStock creates an absent item with reserved 0 and returns it with computed available. */
    @Test
    void setStockCreatesAbsentItem(VertxTestContext ctx) {
        service.setStock("sku-1", new SetStockRequest(100))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertEquals(100, item.onHand());
                    assertEquals(0, item.reserved());
                    assertEquals(100, item.available());
                    assertEquals(new StoredItem("sku-1", 100, 0), repository.items.get("sku-1"));
                    ctx.completeNow();
                })));
    }

    /**
     * A write preserves a binLocation the baseline's own model records, rather than resetting it.
     *
     * <p>Both mutating paths rebuild the stored item from its parts, so each is a place the field can be
     * dropped silently: setStock changes on-hand and a reserve changes reserved, and neither is about
     * where the item is held. The loss would surface only on the next read, long after the write.
     */
    @Test
    void writesPreserveABinLocationThisBaselineRecords(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 3, "A-04-02"));
        service.setStock("sku-1", new SetStockRequest(20))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertEquals("A-04-02", item.binLocation(), "setStock kept the location");
                    assertEquals(
                            "A-04-02",
                            repository.items.get("sku-1").binLocation(),
                            "and it is still on the stored document");

                    service.reserve(new CreateReservationRequest("order-1", "sku-1", 2))
                            .onComplete(ctx.succeeding(reserved -> ctx.verify(() -> {
                                assertEquals(
                                        "A-04-02",
                                        repository.items.get("sku-1").binLocation(),
                                        "a reserve kept it too");
                                ctx.completeNow();
                            })));
                })));
    }

    /** A baseline whose model records no location reports null rather than inventing one. */
    @Test
    void reportsNoBinLocationWhereTheModelHasNoSuchField(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 3));
        service.getInventory("sku-1")
                .onComplete(ctx.succeeding(found -> ctx.verify(() -> {
                    assertNull(found.orElseThrow().binLocation());
                    ctx.completeNow();
                })));
    }

    /** setStock on an existing item updates on-hand, preserves reserved, and recomputes available. */
    @Test
    void setStockUpdatesExistingItemPreservingReserved(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 3));
        service.setStock("sku-1", new SetStockRequest(20))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertEquals(20, item.onHand());
                    assertEquals(3, item.reserved());
                    assertEquals(17, item.available());
                    ctx.completeNow();
                })));
    }

    /** setStock below the currently reserved quantity is a stock conflict (available would go negative). */
    @Test
    void setStockBelowReservedConflicts(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 5));
        service.setStock("sku-1", new SetStockRequest(3))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(StockConflictException.class, err);
                    ctx.completeNow();
                })));
    }

    /** setStock re-reads and retries when a conditional write hits a version conflict, then succeeds. */
    @Test
    void setStockRetriesOnVersionConflict(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 2));
        repository.conflictsToInject = 1;
        service.setStock("sku-1", new SetStockRequest(30))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertEquals(30, item.onHand());
                    assertEquals(2, item.reserved());
                    assertEquals(0, repository.conflictsToInject, "the injected conflict must have been consumed");
                    ctx.completeNow();
                })));
    }

    /** setStock that loses the create race (absent at read, present at create) retries as an update. */
    @Test
    void setStockRetriesAsUpdateOnLostCreateRace(VertxTestContext ctx) {
        repository.loseCreateItemRace = true;
        service.setStock("sku-1", new SetStockRequest(40))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertEquals(40, item.onHand());
                    ctx.completeNow();
                })));
    }

    /** setStock gives up with an internal error once the bounded concurrency retries are exhausted. */
    @Test
    void setStockExceedsRetryCeiling(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        repository.conflictsToInject = 100;
        service.setStock("sku-1", new SetStockRequest(50))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    ctx.completeNow();
                })));
    }

    /** getInventory returns the item with computed available; an unknown sku is an empty optional. */
    @Test
    void getInventoryComputesAvailableOrEmpty(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 4));
        service.getInventory("sku-1")
                .compose(present -> {
                    ctx.verify(() -> {
                        assertEquals(6, present.orElseThrow().available());
                    });
                    return service.getInventory("absent");
                })
                .onComplete(ctx.succeeding(missing -> ctx.verify(() -> {
                    assertEquals(Optional.empty(), missing);
                    ctx.completeNow();
                })));
    }

    /**
     * A reserve against sufficient stock increments the counter and persists a minted reservation, and
     * the persisted record is left {@code CONFIRMED} (the committed state a concurrent reader may trust).
     */
    @Test
    void reserveHappyPathIncrementsAndPersistsConfirmed(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        var request = new CreateReservationRequest("order-1", "sku-1", 3);
        service.reserve(request)
                .onComplete(ctx.succeeding(reservation -> ctx.verify(() -> {
                    assertEquals("order-1", reservation.orderId());
                    assertEquals("sku-1", reservation.sku());
                    assertEquals(3, reservation.quantity());
                    assertDoesNotThrow(() -> UUID.fromString(reservation.reservationId()));
                    assertEquals(3, repository.items.get("sku-1").reserved(), "the counter must be incremented");
                    var stored = repository.reservations.get("order-1:sku-1");
                    assertEquals(reservation.reservationId(), stored.reservationId(), "the record must persist");
                    assertEquals(
                            ReservationStatus.CONFIRMED,
                            stored.status(),
                            "a completed reserve leaves a CONFIRMED record");
                    ctx.completeNow();
                })));
    }

    /** A reserve for more than the available stock is a stock conflict and does not increment. */
    @Test
    void reserveInsufficientStockConflicts(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 2, 0));
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 5))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(StockConflictException.class, err);
                    assertEquals(0, repository.items.get("sku-1").reserved(), "no increment on a rejected reserve");
                    ctx.completeNow();
                })));
    }

    /** A reserve against an unknown sku is a not-found outcome. */
    @Test
    void reserveUnknownSkuIsNotFound(VertxTestContext ctx) {
        service.reserve(new CreateReservationRequest("order-1", "absent", 1))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(UnknownSkuException.class, err);
                    ctx.completeNow();
                })));
    }

    /** A repeat reserve for the same order line and quantity returns the existing reservation, no re-increment. */
    @Test
    void reserveIsIdempotentForSameQuantity(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        var request = new CreateReservationRequest("order-1", "sku-1", 3);
        service.reserve(request)
                .compose(first -> service.reserve(request).map(second -> Map.entry(first, second)))
                .onComplete(ctx.succeeding(pair -> ctx.verify(() -> {
                    assertEquals(
                            pair.getKey().reservationId(),
                            pair.getValue().reservationId(),
                            "the repeat must return the same reservation");
                    assertEquals(3, repository.items.get("sku-1").reserved(), "reserved must not double-increment");
                    ctx.completeNow();
                })));
    }

    /** A repeat reserve for the same order line with a different quantity is a stock conflict. */
    @Test
    void reserveDifferentQuantityConflicts(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .compose(first -> service.reserve(new CreateReservationRequest("order-1", "sku-1", 5)))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(StockConflictException.class, err);
                    assertEquals(
                            3, repository.items.get("sku-1").reserved(), "the differing repeat must not increment");
                    ctx.completeNow();
                })));
    }

    /** A reserve re-reads and retries when the counter write hits a version conflict, then succeeds. */
    @Test
    void reserveRetriesOnVersionConflict(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        repository.conflictsToInject = 1;
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .onComplete(ctx.succeeding(reservation -> ctx.verify(() -> {
                    assertEquals(3, reservation.quantity());
                    assertEquals(3, repository.items.get("sku-1").reserved());
                    assertEquals(0, repository.conflictsToInject, "the injected conflict must have been consumed");
                    ctx.completeNow();
                })));
    }

    /**
     * A reserve gives up with an internal error once the bounded concurrency retries are exhausted, and
     * rolls the gate record back so no phantom reservation is left holding no stock.
     */
    @Test
    void reserveExceedsRetryCeiling(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        repository.conflictsToInject = 100;
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    assertNull(repository.reservations.get("order-1:sku-1"), "the gate record must be rolled back");
                    ctx.completeNow();
                })));
    }

    /** When a concurrent request wins the record create, the reserve returns the persisted reservation. */
    @Test
    void reserveReturnsPersistedRecordOnLostCreateRace(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        repository.loseReservationRace = true;
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .onComplete(ctx.succeeding(reservation -> ctx.verify(() -> {
                    assertEquals(
                            FakeRepository.RACE_WINNER_ID,
                            reservation.reservationId(),
                            "the persisted (race-winning) record must be returned");
                    assertEquals(
                            0,
                            repository.items.get("sku-1").reserved(),
                            "the gate loser must not increment the counter");
                    ctx.completeNow();
                })));
    }

    /** Losing the gate to a winner whose record has since been rolled back surfaces a retryable conflict. */
    @Test
    void reserveGateLostWithVanishedRecordConflicts(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        repository.loseReservationRaceNoRecord = true;
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(StockConflictException.class, err);
                    assertEquals(0, repository.items.get("sku-1").reserved(), "no counter change on a lost gate");
                    ctx.completeNow();
                })));
    }

    /**
     * A post-gate race where the sku vanishes between the pre-check and the hold rolls the gate record
     * back and surfaces the unknown-sku outcome (no phantom record).
     */
    @Test
    void reservePostGateUnknownSkuRollsBackRecord(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        repository.vanishItemAfterGate = true;
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(UnknownSkuException.class, err);
                    assertNull(repository.reservations.get("order-1:sku-1"), "the gate record must be rolled back");
                    ctx.completeNow();
                })));
    }

    /**
     * A post-gate race where stock races out between the pre-check and the hold rolls the gate record
     * back and surfaces the insufficient-stock conflict (no phantom record, no increment).
     */
    @Test
    void reservePostGateInsufficientRollsBackRecord(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        repository.raceOutStockAfterGate = true;
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(StockConflictException.class, err);
                    assertNull(repository.reservations.get("order-1:sku-1"), "the gate record must be rolled back");
                    assertEquals(0, repository.items.get("sku-1").reserved(), "no counter change on rollback");
                    ctx.completeNow();
                })));
    }

    /**
     * The post-gate rollback edge is closed: a duplicate that observes an in-flight ({@code PENDING})
     * reservation record - the gate winner has not yet committed its stock hold, so the record may be
     * about to be rolled back - never returns it as a committed reservation. It resolves to a retryable
     * conflict instead of a phantom success, and touches no counter. (The complementary case, where the
     * winner does commit and the waiting duplicate then reads the {@code CONFIRMED} record, is proven
     * under real concurrency by {@code InventoryServiceIT}.)
     */
    @Test
    void reserveObservingInFlightPendingRecordNeverPhantoms(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 0));
        // A gate winner's in-flight record: created but never confirmed (it is about to be rolled back).
        repository.reservations.put(
                "order-1:sku-1",
                new StoredReservation(
                        "winner-uuid", "order-1", "sku-1", 3, "2026-07-24T00:00:00Z", ReservationStatus.PENDING));
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(
                            StockConflictException.class,
                            err,
                            "an in-flight PENDING record must not be returned as a committed reservation");
                    assertEquals(
                            0,
                            repository.items.get("sku-1").reserved(),
                            "observing an in-flight record touches no counter");
                    ctx.completeNow();
                })));
    }

    /**
     * A duplicate that observes a committed ({@code CONFIRMED}) reservation returns it as the idempotent
     * hit without re-incrementing - a CONFIRMED record is durable and safe to trust immediately.
     */
    @Test
    void reserveObservingConfirmedRecordReturnsIdempotentHit(VertxTestContext ctx) {
        repository.seed(new StoredItem("sku-1", 10, 3));
        repository.reservations.put(
                "order-1:sku-1",
                new StoredReservation(
                        "committed-uuid", "order-1", "sku-1", 3, "2026-07-24T00:00:00Z", ReservationStatus.CONFIRMED));
        service.reserve(new CreateReservationRequest("order-1", "sku-1", 3))
                .onComplete(ctx.succeeding(reservation -> ctx.verify(() -> {
                    assertEquals(
                            "committed-uuid", reservation.reservationId(), "the committed reservation is returned");
                    assertEquals(
                            3, repository.items.get("sku-1").reserved(), "an idempotent hit does not re-increment");
                    ctx.completeNow();
                })));
    }

    /**
     * An in-memory {@link InventoryStore} that never touches Elasticsearch: every persistence method
     * is backed by maps, plus scripted hooks to inject a version conflict and to lose the create
     * races, so the service's optimistic-concurrency, idempotency, and reservation-lifecycle branches
     * are exercised deterministically.
     */
    static final class FakeRepository implements InventoryStore {

        static final String RACE_WINNER_ID = "race-winner-reservation";

        final Map<String, StoredItem> items = new ConcurrentHashMap<>();
        final Map<String, Long> seqNos = new ConcurrentHashMap<>();
        final Map<String, StoredReservation> reservations = new ConcurrentHashMap<>();

        int conflictsToInject;
        boolean loseCreateItemRace;
        boolean loseReservationRace;
        boolean loseReservationRaceNoRecord;
        boolean vanishItemAfterGate;
        boolean raceOutStockAfterGate;

        void seed(StoredItem item) {
            items.put(item.sku(), item);
            seqNos.put(item.sku(), 0L);
        }

        @Override
        public Future<Page<StoredItem>> findItemPage(int page, int size) {
            var sorted = items.values().stream()
                    .sorted(java.util.Comparator.comparing(StoredItem::sku))
                    .toList();
            var from = Math.min(page * size, sorted.size());
            return Future.succeededFuture(
                    new Page<>(sorted.subList(from, Math.min(from + size, sorted.size())), sorted.size()));
        }

        @Override
        public Future<Optional<StoredItem>> findItem(String sku) {
            return Future.succeededFuture(Optional.ofNullable(items.get(sku)));
        }

        @Override
        public Future<Optional<VersionedDocument<StoredItem>>> findVersionedItem(String sku) {
            var item = items.get(sku);
            if (item == null) {
                return Future.succeededFuture(Optional.empty());
            }
            return Future.succeededFuture(Optional.of(new VersionedDocument<>(item, seqNos.get(sku), 1L)));
        }

        @Override
        public Future<String> writeItemIfVersionMatches(StoredItem item, long seqNo, long primaryTerm) {
            if (conflictsToInject > 0) {
                conflictsToInject--;
                return Future.failedFuture(new VersionConflictException("injected conflict", new RuntimeException()));
            }
            long current = seqNos.getOrDefault(item.sku(), 0L);
            if (current != seqNo) {
                return Future.failedFuture(new VersionConflictException("stale write", new RuntimeException()));
            }
            items.put(item.sku(), item);
            seqNos.put(item.sku(), current + 1);
            return Future.succeededFuture(item.sku());
        }

        @Override
        public Future<Boolean> createItemIfAbsent(StoredItem item) {
            if (loseCreateItemRace) {
                loseCreateItemRace = false;
                // Simulate a concurrent create landing first: seed the item, report the lost race.
                seed(new StoredItem(item.sku(), 0, 0));
                return Future.succeededFuture(false);
            }
            if (items.containsKey(item.sku())) {
                return Future.succeededFuture(false);
            }
            seed(item);
            return Future.succeededFuture(true);
        }

        @Override
        public Future<Optional<StoredReservation>> findReservation(String reservationDocId) {
            return Future.succeededFuture(Optional.ofNullable(reservations.get(reservationDocId)));
        }

        @Override
        public Future<Boolean> createReservationIfAbsent(String reservationDocId, StoredReservation reservation) {
            if (loseReservationRace) {
                loseReservationRace = false;
                // Simulate a concurrent identical request winning the gate and committing its record.
                reservations.put(
                        reservationDocId,
                        new StoredReservation(
                                RACE_WINNER_ID,
                                reservation.orderId(),
                                reservation.sku(),
                                reservation.quantity(),
                                reservation.createdAt(),
                                ReservationStatus.CONFIRMED));
                return Future.succeededFuture(false);
            }
            if (loseReservationRaceNoRecord) {
                loseReservationRaceNoRecord = false;
                // Simulate losing the gate to a winner that has since rolled its record back.
                return Future.succeededFuture(false);
            }
            if (reservations.containsKey(reservationDocId)) {
                return Future.succeededFuture(false);
            }
            reservations.put(reservationDocId, reservation);
            // Post-gate races: the sku vanishes or stock races out between the pre-check and the hold.
            if (vanishItemAfterGate) {
                vanishItemAfterGate = false;
                items.remove(reservation.sku());
                seqNos.remove(reservation.sku());
            }
            if (raceOutStockAfterGate) {
                raceOutStockAfterGate = false;
                var current = items.get(reservation.sku());
                if (current != null) {
                    items.put(reservation.sku(), new StoredItem(current.sku(), 0, current.reserved()));
                }
            }
            return Future.succeededFuture(true);
        }

        @Override
        public Future<Void> confirmReservation(String reservationDocId, StoredReservation reservation) {
            reservations.put(reservationDocId, reservation);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> deleteReservation(String reservationDocId) {
            reservations.remove(reservationDocId);
            return Future.succeededFuture();
        }
    }
}
