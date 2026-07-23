package io.lattice.contract.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.openapi.contract.OpenAPIContract;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Contract test proving the v1 OpenAPI 3.1 spec is a valid, mountable contract: it loads into a
 * Vert.x {@link OpenAPIContract} (which validates it as OpenAPI 3.1), builds a Vert.x Web
 * {@link Router}, serves a spec-conformant {@code BaselineResponse} success envelope over HTTP, and
 * leaves the unimplemented operations on the router default. This is the service-side seam check
 * from api_structure.md: if the spec drifts from a shape the router cannot mount, this fails.
 */
@ExtendWith(VertxExtension.class)
class OpenApiContractTest {

    private static final String SPEC = "openapi/v1.yaml";

    private OpenAPIContract contract;
    private WebClient client;

    /**
     * Loads the spec, builds the router, wires a spec-conformant {@code getBaseline} handler, mounts
     * the remaining operations on the router default, and starts the HTTP test server + WebClient.
     */
    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        OpenAPIContract.from(vertx, SPEC)
                .compose(loaded -> {
                    this.contract = loaded;
                    RouterBuilder routerBuilder = RouterBuilder.create(vertx, loaded);
                    routerBuilder.getRoute("getBaseline").addHandler(rc -> {
                        JsonObject envelope = new JsonObject()
                                .put(
                                        "data",
                                        new JsonObject()
                                                .put("clusterId", "hub-west")
                                                .put("region", "us-west")
                                                .put("baselineVersion", "0.1.0")
                                                .put("apiVersions", new JsonArray().add("v1")))
                                .put(
                                        "meta",
                                        new JsonObject()
                                                .put(
                                                        "requestId",
                                                        UUID.randomUUID().toString())
                                                .put("apiVersion", "v1"));
                        rc.response()
                                .putHeader("content-type", "application/json")
                                .end(envelope.encode());
                    });
                    Router router = routerBuilder.createRouter();
                    return vertx.createHttpServer().requestHandler(router).listen(0);
                })
                .onComplete(ctx.succeeding(started -> {
                    this.client = WebClient.create(
                            vertx,
                            new WebClientOptions().setDefaultHost("localhost").setDefaultPort(started.actualPort()));
                    ctx.completeNow();
                }));
    }

    /** The loaded contract reports OpenAPI 3.1.0 - the spec parsed as a valid 3.1 document. */
    @Test
    void specLoadsAsOpenApi31() {
        assertEquals("3.1.0", contract.getRawContract().getString("openapi"));
    }

    /** GET /api/v1/baseline returns 200 with a success envelope carrying data.baselineVersion and meta.apiVersion v1. */
    @Test
    void baselineReturnsSuccessEnvelope(VertxTestContext ctx) {
        client.get("/api/v1/baseline")
                .send()
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    JsonObject body = resp.bodyAsJsonObject();
                    assertNotNull(body.getJsonObject("data").getString("baselineVersion"));
                    assertEquals("v1", body.getJsonObject("meta").getString("apiVersion"));
                    ctx.completeNow();
                })));
    }

    /** An unknown path is not part of the contract, so the router does not serve it 200. */
    @Test
    void unknownPathIsNotServed(VertxTestContext ctx) {
        client.get("/api/v1/does-not-exist")
                .send()
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertTrue(
                            resp.statusCode() == 404 || resp.statusCode() == 501,
                            "unknown path should be 404 or 501, was " + resp.statusCode());
                    ctx.completeNow();
                })));
    }
}
