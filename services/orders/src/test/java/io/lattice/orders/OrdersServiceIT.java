package io.lattice.orders;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.testing.FailOnUnexpectedLogExtension;
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
        var verticle = new OrdersVerticle("http://" + ES.getHttpHostAddress(), 0);
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
            var client = WebClient.create(vertx);
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

    /** GET for an id that was never created returns 404 with a NOT_FOUND error envelope. */
    @Test
    void getUnknownOrderReturnsNotFoundEnvelope(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx).onComplete(ctx.succeeding(v -> {
            var client = WebClient.create(vertx);
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
            var client = WebClient.create(vertx);
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
            var client = WebClient.create(vertx);
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
