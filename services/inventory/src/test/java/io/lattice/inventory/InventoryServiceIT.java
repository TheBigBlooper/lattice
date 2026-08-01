package io.lattice.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration + contract tests for the inventory service against a real Elasticsearch (Testcontainers,
 * the pinned 8.19.19 image). Each test deploys the real {@link InventoryVerticle} on an ephemeral port
 * pointed at the container and drives it with a Vert.x {@link WebClient}, so the whole vertical slice
 * runs: the OpenAPI-validated router, the service layer, and the Elasticsearch round-trip. Responses
 * are asserted against the v1 contract envelope (a drift guard). Each test uses unique skus / order ids
 * so the shared indices do not cross-contaminate.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class InventoryServiceIT {

    /**
     * This baseline's identity realm. Every service now refuses to start without one and rejects an
     * unauthenticated /api/v1 call, so a suite testing what the endpoints do runs as an operator.
     */
    private static TestRealm REALM;

    /** Starts the realm the deployed service validates tokens against. */
    @BeforeAll
    static void startRealm() {
        REALM = TestRealm.start("lattice");
    }

    /** Releases the realm. */
    @AfterAll
    static void stopRealm() {
        REALM.close();
    }

    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.19");

    // Singleton container: started once for the suite, stopped after. The suppression silences the
    // IDE resource-leak heuristic, which does not model the Testcontainers stop() lifecycle.
    @SuppressWarnings("resource")
    private static final ElasticsearchContainer ES = new ElasticsearchContainer(IMAGE)
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node")
            // Parity with the chart: a write to an unknown index is REFUSED rather than creating it.
            // Without this the suites would run permissively while a deployed baseline does not, and
            // code that quietly relies on auto-create would pass here and corrupt an alias there.
            .withEnv("action.auto_create_index", "+.*,-*");

    @BeforeAll
    static void startContainer() {
        ES.start();
    }

    @AfterAll
    static void stopContainer() {
        ES.stop();
    }

    private static Future<InventoryVerticle> deploy(Vertx vertx) {
        var verticle = new InventoryVerticle("http://" + ES.getHttpHostAddress(), 0, REALM.realmUrl());
        return vertx.deployVerticle(verticle).map(id -> verticle);
    }

    private static JsonObject setStockBody(int onHand) {
        return new JsonObject().put("onHand", onHand);
    }

    private static JsonObject reserveBody(String orderId, String sku, int quantity) {
        return new JsonObject().put("orderId", orderId).put("sku", sku).put("quantity", quantity);
    }

    private static String uniqueSku() {
        return "sku-" + UUID.randomUUID();
    }

    /**
     * setStock creates an absent item (reserved 0, available equal to on-hand), then a second setStock
     * updates the absolute on-hand and returns the recomputed item in the success envelope.
     */
    @Test
    void setStockCreatesThenUpdates(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(100))
                    .onComplete(ctx.succeeding(created -> ctx.verify(() -> {
                        assertEquals(200, created.statusCode());
                        var data = created.bodyAsJsonObject().getJsonObject("data");
                        assertEquals(sku, data.getString("sku"));
                        assertEquals(100, data.getInteger("onHand"));
                        assertEquals(0, data.getInteger("reserved"));
                        assertEquals(100, data.getInteger("available"));
                        assertEquals(
                                "v1",
                                created.bodyAsJsonObject().getJsonObject("meta").getString("apiVersion"));

                        client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                                .sendJsonObject(setStockBody(60))
                                .onComplete(ctx.succeeding(updated -> ctx.verify(() -> {
                                    assertEquals(200, updated.statusCode());
                                    var d = updated.bodyAsJsonObject().getJsonObject("data");
                                    assertEquals(60, d.getInteger("onHand"));
                                    assertEquals(0, d.getInteger("reserved"));
                                    assertEquals(60, d.getInteger("available"));
                                    client.close();
                                    ctx.completeNow();
                                })));
                    })));
        }));
    }

    /** getInventory returns the item with computed available; an unknown sku is 404 NOT_FOUND. */

    /**
     * Listing answers over a real index with a sorted page whose items carry computed availability,
     * and applies the contract page defaults when the request omits them.
     *
     * <p>The defaults matter more than they look: an absent query parameter arrives as an empty one
     * rather than a null, so a handler that read it naively would page with a size of zero and serve
     * an empty page for every browse.
     */
    @Test
    void listReturnsAPageOfItemsWithComputedAvailability(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(42))
                    .compose(created -> client.get(v.actualPort(), "localhost", "/api/v1/inventory")
                            .send())
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        var body = resp.bodyAsJsonObject();
                        var data = body.getJsonArray("data");
                        assertTrue(data.size() >= 1, "the item just written is on the page");
                        var mine = data.stream()
                                .map(io.vertx.core.json.JsonObject.class::cast)
                                .filter(item -> sku.equals(item.getString("sku")))
                                .findFirst()
                                .orElseThrow();
                        assertEquals(42, mine.getInteger("onHand"));
                        assertEquals(42, mine.getInteger("available"), "available is computed at read");

                        var pagination = body.getJsonObject("meta").getJsonObject("pagination");
                        assertEquals(0, pagination.getInteger("page"), "defaults to the first page");
                        assertEquals(20, pagination.getInteger("size"), "defaults to twenty a page");
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    @Test
    void getInventoryReturnsItemOrNotFound(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(40))
                    .onComplete(ctx.succeeding(set -> client.get(
                                    v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                            .send()
                            .onComplete(ctx.succeeding(got -> ctx.verify(() -> {
                                assertEquals(200, got.statusCode());
                                var data = got.bodyAsJsonObject().getJsonObject("data");
                                assertEquals(40, data.getInteger("onHand"));
                                assertEquals(0, data.getInteger("reserved"));
                                assertEquals(40, data.getInteger("available"));

                                client.get(v.actualPort(), "localhost", "/api/v1/inventory/" + uniqueSku())
                                        .send()
                                        .onComplete(ctx.succeeding(missing -> ctx.verify(() -> {
                                            assertEquals(404, missing.statusCode());
                                            assertEquals(
                                                    "NOT_FOUND",
                                                    missing.bodyAsJsonObject()
                                                            .getJsonObject("error")
                                                            .getString("code"));
                                            client.close();
                                            ctx.completeNow();
                                        })));
                            })))));
        }));
    }

    /**
     * A reserve against sufficient stock returns 201 with a minted reservation, decrements availability
     * (the item counter persists), and the reservation is retrievable via the item's reserved count.
     */
    @Test
    void reserveHappyPathDecrementsAvailability(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        var orderId = "order-" + UUID.randomUUID();
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(10))
                    .onComplete(ctx.succeeding(set -> client.post(
                                    v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                            .sendJsonObject(reserveBody(orderId, sku, 3))
                            .onComplete(ctx.succeeding(reserved -> ctx.verify(() -> {
                                assertEquals(201, reserved.statusCode());
                                var data = reserved.bodyAsJsonObject().getJsonObject("data");
                                assertEquals(orderId, data.getString("orderId"));
                                assertEquals(sku, data.getString("sku"));
                                assertEquals(3, data.getInteger("quantity"));
                                assertNotNull(data.getString("reservationId"));
                                assertTrue(data.getString("createdAt").endsWith("Z"), "createdAt is UTC");

                                client.get(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                                        .send()
                                        .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                                            var d = item.bodyAsJsonObject().getJsonObject("data");
                                            assertEquals(3, d.getInteger("reserved"));
                                            assertEquals(7, d.getInteger("available"));
                                            client.close();
                                            ctx.completeNow();
                                        })));
                            })))));
        }));
    }

    /** A reserve beyond available stock is 409 CONFLICT; an unknown sku is 404 NOT_FOUND. */
    @Test
    void reserveInsufficientAndUnknownSku(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(2))
                    .onComplete(ctx.succeeding(set -> client.post(
                                    v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                            .sendJsonObject(reserveBody("order-x", sku, 5))
                            .onComplete(ctx.succeeding(insufficient -> ctx.verify(() -> {
                                assertEquals(409, insufficient.statusCode());
                                assertEquals(
                                        "CONFLICT",
                                        insufficient
                                                .bodyAsJsonObject()
                                                .getJsonObject("error")
                                                .getString("code"));

                                client.post(v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                                        .sendJsonObject(reserveBody("order-y", uniqueSku(), 1))
                                        .onComplete(ctx.succeeding(unknown -> ctx.verify(() -> {
                                            assertEquals(404, unknown.statusCode());
                                            assertEquals(
                                                    "NOT_FOUND",
                                                    unknown.bodyAsJsonObject()
                                                            .getJsonObject("error")
                                                            .getString("code"));
                                            client.close();
                                            ctx.completeNow();
                                        })));
                            })))));
        }));
    }

    /**
     * A repeated reserve for the same order line and quantity returns the same reservation and does not
     * double-increment reserved; a repeat with a different quantity is 409 CONFLICT.
     */
    @Test
    void reserveIsIdempotentAndRejectsQuantityChange(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        var orderId = "order-" + UUID.randomUUID();
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(10))
                    .onComplete(ctx.succeeding(set -> client.post(
                                    v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                            .sendJsonObject(reserveBody(orderId, sku, 3))
                            .onComplete(ctx.succeeding(first -> ctx.verify(() -> {
                                var firstId = first.bodyAsJsonObject()
                                        .getJsonObject("data")
                                        .getString("reservationId");

                                client.post(v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                                        .sendJsonObject(reserveBody(orderId, sku, 3))
                                        .onComplete(ctx.succeeding(repeat -> ctx.verify(() -> {
                                            assertEquals(201, repeat.statusCode());
                                            assertEquals(
                                                    firstId,
                                                    repeat.bodyAsJsonObject()
                                                            .getJsonObject("data")
                                                            .getString("reservationId"),
                                                    "the repeat must return the same reservation");

                                            client.get(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                                                    .send()
                                                    .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                                                        assertEquals(
                                                                3,
                                                                item.bodyAsJsonObject()
                                                                        .getJsonObject("data")
                                                                        .getInteger("reserved"),
                                                                "reserved must not double-increment");

                                                        client.post(
                                                                        v.actualPort(),
                                                                        "localhost",
                                                                        "/api/v1/inventory/reservations")
                                                                .sendJsonObject(reserveBody(orderId, sku, 5))
                                                                .onComplete(ctx.succeeding(mismatch ->
                                                                        ctx.verify(() -> {
                                                                            assertEquals(409, mismatch.statusCode());
                                                                            assertEquals(
                                                                                    "CONFLICT",
                                                                                    mismatch.bodyAsJsonObject()
                                                                                            .getJsonObject("error")
                                                                                            .getString("code"));
                                                                            client.close();
                                                                            ctx.completeNow();
                                                                        })));
                                                    })));
                                        })));
                            })))));
        }));
    }

    /** setStock below the currently reserved quantity is rejected 409 CONFLICT. */
    @Test
    void setStockBelowReservedConflicts(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(10))
                    .onComplete(ctx.succeeding(set -> client.post(
                                    v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                            .sendJsonObject(reserveBody("order-z", sku, 4))
                            .onComplete(ctx.succeeding(reserved -> client.put(
                                            v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                                    .sendJsonObject(setStockBody(3))
                                    .onComplete(ctx.succeeding(below -> ctx.verify(() -> {
                                        assertEquals(409, below.statusCode());
                                        assertEquals(
                                                "CONFLICT",
                                                below.bodyAsJsonObject()
                                                        .getJsonObject("error")
                                                        .getString("code"));
                                        client.close();
                                        ctx.completeNow();
                                    })))))));
        }));
    }

    /**
     * The oversell invariant under contention: with on-hand 5, twenty concurrent single-unit reserves
     * (distinct order lines) settle so that exactly five succeed (201) and the rest are 409, the item's
     * reserved equals the number of successes, and available never goes negative. This asserts the
     * settled state, not a mid-race snapshot.
     */
    @Test
    void concurrentReservesNeverOversell(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        int stock = 5;
        int attempts = 20;
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(stock))
                    .onComplete(ctx.succeeding(set -> {
                        List<Future<HttpResponse<Buffer>>> calls = new ArrayList<>();
                        for (int i = 0; i < attempts; i++) {
                            calls.add(client.post(v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                                    .sendJsonObject(reserveBody("order-" + i, sku, 1)));
                        }
                        Future.all(calls)
                                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                                    int created = 0;
                                    for (int i = 0; i < attempts; i++) {
                                        HttpResponse<Buffer> resp = cf.resultAt(i);
                                        if (resp.statusCode() == 201) {
                                            created++;
                                        } else {
                                            assertEquals(
                                                    409,
                                                    resp.statusCode(),
                                                    "a non-created reserve must be a 409 conflict, not "
                                                            + resp.statusCode());
                                        }
                                    }
                                    assertEquals(stock, created, "exactly the available units may be reserved");

                                    client.get(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                                            .send()
                                            .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                                                var data =
                                                        item.bodyAsJsonObject().getJsonObject("data");
                                                assertEquals(
                                                        stock,
                                                        data.getInteger("reserved"),
                                                        "reserved equals successes");
                                                assertTrue(
                                                        data.getInteger("available") >= 0,
                                                        "available must never go negative");
                                                assertEquals(0, data.getInteger("available"));
                                                client.close();
                                                ctx.completeNow();
                                            })));
                                })));
                    }));
        }));
    }

    /**
     * The exactly-once counter under concurrent duplicates: with ample stock, many concurrent reserves
     * for the SAME order line ({@code (orderId, sku)}) settle so that reserved ends at exactly the
     * quantity (incremented once, not once per duplicate) and every caller gets the same reservationId.
     * This is the record-first idempotency-gate regression guard. Asserts the settled state, not a
     * mid-race snapshot.
     */
    @Test
    void reserveConcurrentDuplicatesIncrementReservedOnce(Vertx vertx, VertxTestContext ctx) {
        var sku = uniqueSku();
        var orderId = "order-" + UUID.randomUUID();
        int quantity = 3;
        int duplicates = 15;
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                    .sendJsonObject(setStockBody(100))
                    .onComplete(ctx.succeeding(set -> {
                        List<Future<HttpResponse<Buffer>>> calls = new ArrayList<>();
                        for (int i = 0; i < duplicates; i++) {
                            calls.add(client.post(v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                                    .sendJsonObject(reserveBody(orderId, sku, quantity)));
                        }
                        Future.all(calls)
                                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                                    String firstId = null;
                                    for (int i = 0; i < duplicates; i++) {
                                        HttpResponse<Buffer> resp = cf.resultAt(i);
                                        assertEquals(201, resp.statusCode(), "every identical duplicate must succeed");
                                        var id = resp.bodyAsJsonObject()
                                                .getJsonObject("data")
                                                .getString("reservationId");
                                        if (firstId == null) {
                                            firstId = id;
                                        }
                                        assertEquals(firstId, id, "every duplicate must return the same reservation");
                                    }
                                    client.get(v.actualPort(), "localhost", "/api/v1/inventory/" + sku)
                                            .send()
                                            .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                                                var data =
                                                        item.bodyAsJsonObject().getJsonObject("data");
                                                assertEquals(
                                                        quantity,
                                                        data.getInteger("reserved"),
                                                        "reserved must be incremented exactly once, not per duplicate");
                                                assertEquals(100 - quantity, data.getInteger("available"));
                                                client.close();
                                                ctx.completeNow();
                                            })));
                                })));
                    }));
        }));
    }

    /** A negative on-hand violates the strict schema (minimum 0) and is rejected 400 VALIDATION_ERROR. */
    @Test
    void negativeOnHandIsRejected(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + uniqueSku())
                    .sendJsonObject(setStockBody(-1))
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(400, resp.statusCode());
                        var error = resp.bodyAsJsonObject().getJsonObject("error");
                        assertEquals("VALIDATION_ERROR", error.getString("code"));
                        assertFalse(error.getJsonArray("details").isEmpty(), "validation carries field details");
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** A reserve with quantity below the minimum of 1 is rejected 400 VALIDATION_ERROR. */
    @Test
    void zeroQuantityReserveIsRejected(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.post(v.actualPort(), "localhost", "/api/v1/inventory/reservations")
                    .sendJsonObject(reserveBody("order-1", "sku-1", 0))
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(400, resp.statusCode());
                        assertEquals(
                                "VALIDATION_ERROR",
                                resp.bodyAsJsonObject().getJsonObject("error").getString("code"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** An unknown key on the strict set-stock body is rejected 400 VALIDATION_ERROR. */
    @Test
    void unknownKeyIsRejected(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.put(v.actualPort(), "localhost", "/api/v1/inventory/" + uniqueSku())
                    .sendJsonObject(setStockBody(10).put("surpriseField", "nope"))
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(400, resp.statusCode());
                        assertEquals(
                                "VALIDATION_ERROR",
                                resp.bodyAsJsonObject().getJsonObject("error").getString("code"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** With Elasticsearch reachable, /readiness reports UP (200) with the elasticsearch check UP. */
    @Test
    void readinessIsUpWhenElasticsearchReachable(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.get(v.actualPort(), "localhost", "/readiness")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        var body = resp.bodyAsJsonObject();
                        assertEquals("UP", body.getString("status"));
                        assertTrue(
                                body.getJsonArray("checks").stream()
                                        .map(JsonObject.class::cast)
                                        .anyMatch(c -> "elasticsearch".equals(c.getString("name"))
                                                && "UP".equals(c.getString("status"))),
                                "the elasticsearch readiness check must be UP");
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }
}
