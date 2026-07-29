package io.lattice.orders;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration + contract tests for the orders service against a real Elasticsearch (Testcontainers,
 * the pinned 8.19.19 image). Each test deploys the real {@link OrdersVerticle} on an ephemeral port
 * pointed at the container and drives it with a Vert.x {@link WebClient}, so the whole vertical slice
 * runs: the OpenAPI-validated router, the service layer, and the Elasticsearch round-trip. Responses
 * are asserted against the v1 contract envelope (a drift guard).
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class OrdersServiceIT {

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
            .withEnv("discovery.type", "single-node");

    @BeforeAll
    static void startContainer() {
        ES.start();
    }

    @AfterAll
    static void stopContainer() {
        ES.stop();
    }

    private static Future<OrdersVerticle> deploy(Vertx vertx) {
        var verticle = new OrdersVerticle("http://" + ES.getHttpHostAddress(), 0, REALM.realmUrl());
        return vertx.deployVerticle(verticle).map(id -> verticle);
    }

    private static JsonObject twoLineBody() {
        return new JsonObject()
                .put("customerId", "cust-1024")
                .put(
                        "lines",
                        new JsonArray()
                                .add(new JsonObject().put("sku", "sku-42").put("quantity", 3))
                                .add(new JsonObject().put("sku", "sku-77").put("quantity", 1)));
    }

    /**
     * A valid POST returns 201 with a server-minted UUID orderId, status RECEIVED, and the submitted
     * lines; the created order is then retrievable by that id (proving it persisted to Elasticsearch).
     */
    @Test
    void createOrderMintsIdPersistsAndIsRetrievable(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.post(v.actualPort(), "localhost", "/api/v1/orders")
                    .sendJsonObject(twoLineBody())
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(201, resp.statusCode());
                        var body = resp.bodyAsJsonObject();
                        assertNull(body.getValue("error"), "success envelope must not carry error");
                        var data = body.getJsonObject("data");
                        var id = data.getString("orderId");
                        assertDoesNotThrow(() -> UUID.fromString(id), "orderId must be a UUID");
                        assertEquals("cust-1024", data.getString("customerId"));
                        assertEquals("RECEIVED", data.getString("status"));
                        assertEquals(2, data.getJsonArray("lines").size());
                        assertNotNull(data.getString("createdAt"));
                        assertTrue(data.getString("createdAt").endsWith("Z"), "createdAt is UTC");
                        assertEquals("v1", body.getJsonObject("meta").getString("apiVersion"));
                        assertNotNull(body.getJsonObject("meta").getString("requestId"));

                        client.get(v.actualPort(), "localhost", "/api/v1/orders/" + id)
                                .send()
                                .onComplete(ctx.succeeding(got -> ctx.verify(() -> {
                                    assertEquals(200, got.statusCode());
                                    var gotData = got.bodyAsJsonObject().getJsonObject("data");
                                    assertEquals(id, gotData.getString("orderId"));
                                    assertEquals("cust-1024", gotData.getString("customerId"));
                                    assertEquals(
                                            2, gotData.getJsonArray("lines").size());
                                    client.close();
                                    ctx.completeNow();
                                })));
                    })));
        }));
    }

    /**
     * Listing returns a sorted page with the counts describing the whole collection.
     *
     * <p>Newest first, because orders are a feed an operator reads from the top. The assertion that
     * matters is the order of the two ids: a page that returns the right documents in the wrong
     * order is the failure a size-based check would miss entirely.
     */
    @Test
    void listReturnsAPageNewestFirst(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.post(v.actualPort(), "localhost", "/api/v1/orders")
                    .sendJsonObject(twoLineBody())
                    .compose(first -> client.post(v.actualPort(), "localhost", "/api/v1/orders")
                            .sendJsonObject(twoLineBody())
                            .map(second -> new String[] {
                                first.bodyAsJsonObject().getJsonObject("data").getString("orderId"),
                                second.bodyAsJsonObject().getJsonObject("data").getString("orderId")
                            }))
                    .compose(ids -> client.get(v.actualPort(), "localhost", "/api/v1/orders?page=0&size=10")
                            .send()
                            .map(resp -> new Object[] {ids, resp}))
                    .onComplete(ctx.succeeding(pair -> ctx.verify(() -> {
                        var ids = (String[]) pair[0];
                        var resp = (io.vertx.ext.web.client.HttpResponse<?>) pair[1];
                        assertEquals(200, resp.statusCode());
                        var body = resp.bodyAsJsonObject();
                        var data = body.getJsonArray("data");
                        assertTrue(data.size() >= 2, "both created orders are on the page");
                        var returned = data.stream()
                                .map(JsonObject.class::cast)
                                .map(o -> o.getString("orderId"))
                                .toList();
                        assertTrue(
                                returned.indexOf(ids[1]) < returned.indexOf(ids[0]),
                                "the order created second sorts before the first");

                        var pagination = body.getJsonObject("meta").getJsonObject("pagination");
                        assertEquals(0, pagination.getInteger("page"));
                        assertEquals(10, pagination.getInteger("size"));
                        assertTrue(pagination.getInteger("total") >= 2, "total counts the collection");
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * A page past the end of the collection is an empty page, not a 404.
     *
     * <p>Nothing is wrong when there is nothing there, and answering a browse request with "not
     * found" sends an operator looking for a fault that does not exist. The same shape covers a
     * baseline that has never taken an order.
     */
    @Test
    void aPagePastTheEndIsEmptyRatherThanMissing(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.get(v.actualPort(), "localhost", "/api/v1/orders?page=999&size=10")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode(), "an empty page is a success, not a 404");
                        var body = resp.bodyAsJsonObject();
                        assertTrue(body.getJsonArray("data").isEmpty());
                        assertNull(body.getValue("error"), "an empty page is a success envelope");
                        assertEquals(
                                999,
                                body.getJsonObject("meta")
                                        .getJsonObject("pagination")
                                        .getInteger("page"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * A request that omits page and size gets the contract declared defaults.
     *
     * <p>This is not a formality. The request validator does <b>not</b> apply a schema {@code
     * default} to a missing query parameter, so the handler carries its own copy of the two values -
     * and without this test the spec could be edited to a different default while the handler kept
     * serving the old one, with nothing failing. It also pins the more damaging version of that bug:
     * a size defaulting to zero would serve an empty page for every browse.
     */
    @Test
    void omittingThePageParametersUsesTheContractDefaults(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.get(v.actualPort(), "localhost", "/api/v1/orders")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        var pagination =
                                resp.bodyAsJsonObject().getJsonObject("meta").getJsonObject("pagination");
                        assertEquals(0, pagination.getInteger("page"), "defaults to the first page");
                        assertEquals(20, pagination.getInteger("size"), "defaults to twenty a page");
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** GET for an id that was never created returns 404 with a NOT_FOUND error envelope. */
    @Test
    void getUnknownOrderReturnsNotFoundEnvelope(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.get(v.actualPort(), "localhost", "/api/v1/orders/" + UUID.randomUUID())
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(404, resp.statusCode());
                        var body = resp.bodyAsJsonObject();
                        assertNull(body.getValue("data"), "error envelope must not carry data");
                        assertEquals("NOT_FOUND", body.getJsonObject("error").getString("code"));
                        assertNotNull(body.getJsonObject("error").getString("message"));
                        assertEquals("v1", body.getJsonObject("meta").getString("apiVersion"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** An empty lines array violates minItems: 1 and is rejected 400 VALIDATION_ERROR with details. */
    @Test
    void emptyLinesIsRejected(Vertx vertx, VertxTestContext ctx) {
        var body = new JsonObject().put("customerId", "cust-1").put("lines", new JsonArray());
        assertValidationRejected(vertx, ctx, body);
    }

    /** A quantity below the minimum of 1 is rejected 400 VALIDATION_ERROR with details. */
    @Test
    void zeroQuantityIsRejected(Vertx vertx, VertxTestContext ctx) {
        var body = new JsonObject()
                .put("customerId", "cust-1")
                .put(
                        "lines",
                        new JsonArray().add(new JsonObject().put("sku", "sku-1").put("quantity", 0)));
        assertValidationRejected(vertx, ctx, body);
    }

    /** An unknown key on the strict body is rejected 400 VALIDATION_ERROR with details. */
    @Test
    void unknownKeyIsRejected(Vertx vertx, VertxTestContext ctx) {
        var body = twoLineBody().put("surpriseField", "nope");
        assertValidationRejected(vertx, ctx, body);
    }

    private static void assertValidationRejected(Vertx vertx, VertxTestContext ctx, JsonObject body) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = REALM.operatorClient(vertx);
            client.post(v.actualPort(), "localhost", "/api/v1/orders")
                    .sendJsonObject(body)
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(400, resp.statusCode());
                        var envelope = resp.bodyAsJsonObject();
                        var error = envelope.getJsonObject("error");
                        assertEquals("VALIDATION_ERROR", error.getString("code"));
                        assertFalse(
                                error.getJsonArray("details").isEmpty(),
                                "a validation error must carry field-level details");
                        assertEquals("v1", envelope.getJsonObject("meta").getString("apiVersion"));
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

    private static void assertNull(Object value, String message) {
        assertTrue(value == null, message);
    }
}
