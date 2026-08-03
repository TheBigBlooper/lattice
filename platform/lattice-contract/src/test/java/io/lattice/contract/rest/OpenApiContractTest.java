package io.lattice.contract.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
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

    /** The OpenAPI path-item members that are operations, as opposed to shared path metadata. */
    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

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
                    // The bearer requirement is enforced centrally, ahead of the OpenAPI router, so
                    // none is registered here - and left on, the router would refuse to build. This
                    // test is about the spec mounting and serving its envelope, not about the token.
                    routerBuilder.getRoute("getBaseline").setDoSecurity(false).addHandler(rc -> {
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

    /**
     * The spec declares the bearer security scheme services validate against: an HTTP bearer scheme
     * carrying a JSON Web Token. This is what tells the generated console client to attach the token
     * and the interactive docs to offer an Authorize button.
     */
    @Test
    void specDeclaresBearerSecurityScheme() {
        JsonObject scheme = contract.getRawContract()
                .getJsonObject("components")
                .getJsonObject("securitySchemes")
                .getJsonObject("bearerAuth");
        assertNotNull(scheme, "components.securitySchemes.bearerAuth must be declared");
        assertEquals("http", scheme.getString("type"));
        assertEquals("bearer", scheme.getString("scheme"));
        assertEquals("JWT", scheme.getString("bearerFormat"));
    }

    /**
     * Every business operation under /api/v1 requires the bearer token, whether it inherits the
     * document-level requirement or declares its own. This is the protected surface: no /api/v1
     * operation is reachable without a token from this baseline's own realm.
     */
    @Test
    void everyApiOperationRequiresTheBearerToken() {
        JsonObject raw = contract.getRawContract();
        JsonArray documentLevel = raw.getJsonArray("security");
        JsonObject paths = raw.getJsonObject("paths");
        for (String path : paths.fieldNames()) {
            if (!path.startsWith("/api/v1")) {
                continue;
            }
            JsonObject pathItem = paths.getJsonObject(path);
            // A path item also carries non-operation members (summary, parameters, servers), so only
            // the HTTP methods are operations to check.
            for (String method : HTTP_METHODS) {
                JsonObject operation = pathItem.getJsonObject(method);
                if (operation == null) {
                    continue;
                }
                JsonArray required = operation.getJsonArray("security", documentLevel);
                assertTrue(
                        requiresBearerAuth(required),
                        path + " " + method + " must require bearerAuth, was " + required);
            }
        }
    }

    /**
     * The operational probes stay open: a Kubernetes probe cannot present a token, so gating them
     * would take a healthy pod out of rotation for no meaningful secrecy. Each overrides the
     * document-level requirement with an explicit empty one.
     */
    @Test
    void probesAreNotSecured() {
        JsonObject paths = contract.getRawContract().getJsonObject("paths");
        for (String path : List.of("/health", "/readiness")) {
            JsonArray required = paths.getJsonObject(path).getJsonObject("get").getJsonArray("security");
            assertNotNull(required, path + " must override the document-level security requirement");
            assertTrue(required.isEmpty(), path + " must declare an empty security requirement, was " + required);
        }
    }

    /**
     * The metrics operation is declared, and it sits on the versioned business surface rather than
     * beside the probes.
     *
     * <p><b>This is the whole reason the operation exists.</b> The scrape endpoint is on a separate
     * management port with no token, and its Service is never published, so a browser cannot reach
     * it. This operation is what carries the numbers to the console - and it therefore has to be
     * guarded like every other read, which putting it under {@code /api/v1} is what achieves. A
     * second unauthenticated path would have reproduced the surface the separate port exists to
     * avoid, on the one port a browser can reach.
     */
    @Test
    void metricsIsDeclaredAsAGuardedApiOperation() {
        JsonObject paths = contract.getRawContract().getJsonObject("paths");
        JsonObject metrics = paths.getJsonObject("/api/v1/metrics");
        assertNotNull(metrics, "the contract must declare /api/v1/metrics");

        JsonObject operation = metrics.getJsonObject("get");
        assertEquals("getMetrics", operation.getString("operationId"));
        assertTrue(
                requiresBearerAuth(operation.getJsonArray("security")) || operation.getJsonArray("security") == null,
                "getMetrics must inherit or declare the bearer requirement, never an empty one");
        assertNotNull(
                operation.getJsonObject("responses").getJsonObject("200"),
                "getMetrics must declare its success response");
    }

    /** True when the requirement list names the bearerAuth scheme. */
    private static boolean requiresBearerAuth(JsonArray requirements) {
        if (requirements == null) {
            return false;
        }
        return requirements.stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .anyMatch(requirement -> requirement.containsKey("bearerAuth"));
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

    /**
     * Both collections are browsable, not only addressable by id.
     *
     * <p>Before this the contract had no list operation at all: {@code getOrder} takes an order id and
     * {@code getInventory} takes a sku, so "what is on this baseline" was unanswerable without already
     * knowing an identifier - which in practice meant one created moments earlier in the same session.
     */
    @Test
    void theCollectionsCanBeListed() {
        var paths = contract.getRawContract().getJsonObject("paths");

        assertEquals(
                "listOrders",
                paths.getJsonObject("/api/v1/orders").getJsonObject("get").getString("operationId"));
        assertEquals(
                "listInventory",
                paths.getJsonObject("/api/v1/inventory").getJsonObject("get").getString("operationId"));
    }

    /**
     * A page size and a position, and nothing else.
     *
     * <p>No filter and no free-text query, and the second is a harder promise than it looks: a shared
     * query contract would commit every baseline to identical query semantics, while each keeps its
     * own possibly-divergent data model. A sorted page maps onto a plain search and commits to
     * nothing. Extra parameters here would be that promise arriving by the back door.
     */
    @Test
    void listOperationsTakeAPageAndNothingElse() {
        var paths = contract.getRawContract().getJsonObject("paths");

        for (var path : List.of("/api/v1/orders", "/api/v1/inventory")) {
            var params = paths.getJsonObject(path).getJsonObject("get").getJsonArray("parameters");
            var names = new java.util.TreeSet<String>();
            params.forEach(param -> names.add(((JsonObject) param).getString("name")));
            assertEquals(java.util.Set.of("page", "size"), names, path + " takes a page and a size only");
        }
    }

    /**
     * The baseline describes its infrastructure as well as its services (locked #66): a sibling array
     * of components, each carrying an explicit kind so the console can render one it has never seen,
     * and a coarse state widened by {@code DEGRADED} because that is the reading this exists to
     * preserve. The two arrays stay separate because the shapes differ - a service has no kind and no
     * detail line.
     */
    @Test
    void theBaselineDescribesItsInfrastructure() {
        var baseline = contract.getRawContract()
                .getJsonObject("components")
                .getJsonObject("schemas")
                .getJsonObject("Baseline");

        var infrastructure = baseline.getJsonObject("properties").getJsonObject("infrastructure");
        assertNotNull(infrastructure, "Baseline must carry an infrastructure array");
        assertEquals("array", infrastructure.getString("type"));

        // The contract is served dereferenced, so the component schema is inlined here rather than
        // present as a $ref.
        var component = infrastructure.getJsonObject("items");
        assertEquals(
                List.of("name", "kind", "status"),
                component.getJsonArray("required").getList(),
                "a component names itself, its kind and its state; detail is optional");
        assertEquals(
                List.of("elasticsearch", "artemis", "keycloak"),
                component
                        .getJsonObject("properties")
                        .getJsonObject("kind")
                        .getJsonArray("enum")
                        .getList());
        assertEquals(
                List.of("UP", "DEGRADED", "DOWN"),
                component
                        .getJsonObject("properties")
                        .getJsonObject("status")
                        .getJsonArray("enum")
                        .getList());
    }

    /**
     * The infrastructure array is additive, so a reader built before it existed keeps working: it is
     * not required, and the services array it sits beside is untouched. A baseline with nothing
     * configured omits it entirely rather than being invalid.
     */
    @Test
    void theInfrastructureArrayIsAdditive() {
        var baseline = contract.getRawContract()
                .getJsonObject("components")
                .getJsonObject("schemas")
                .getJsonObject("Baseline");

        assertFalse(
                baseline.getJsonArray("required").contains("infrastructure"),
                "an unconfigured baseline omits infrastructure, so it cannot be required");
        assertNotNull(
                baseline.getJsonObject("properties").getJsonObject("services"),
                "the existing services array is untouched");
    }

    /**
     * A list response carries its pagination counts in {@code meta}, using the block the envelope
     * already declares. A second paging shape alongside it would leave two answers to "how many are
     * there" for a client to choose between.
     */
    @Test
    void listResponsesReuseTheEnvelopePagination() {
        var schemas = contract.getRawContract().getJsonObject("components").getJsonObject("schemas");

        for (var name : List.of("OrderListResponse", "InventoryListResponse")) {
            var response = schemas.getJsonObject(name);
            assertNotNull(response, name + " must be declared");
            assertEquals(
                    "array",
                    response.getJsonObject("properties").getJsonObject("data").getString("type"),
                    name + " carries an array in data");
            // The contract is served dereferenced, so the shared block is inlined rather than a $ref.
            // What must hold is that a client reads the page counts from the envelope's own meta - a
            // second paging block beside it would leave two answers to "how many are there".
            var meta = response.getJsonObject("properties").getJsonObject("meta");
            assertNotNull(
                    meta.getJsonObject("properties").getJsonObject("pagination"),
                    name + " carries its page counts in the shared meta");
            assertTrue(
                    meta.getJsonArray("required").contains("apiVersion"),
                    name + " uses the envelope meta, not a bespoke one");
        }
    }
}
