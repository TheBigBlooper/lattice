package io.lattice.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves the shared {@code /api/v1} guard is actually in force on the inventory service's own routes.
 * The guard's own rules are pinned once in {@code ApiSecurityTest}; what these add is that this
 * service mounts it - a service that quietly served its API unprotected would pass every other suite
 * here.
 *
 * <p>Elasticsearch is deliberately unreachable: a rejected request never reaches the data layer, so
 * proving that requires no container. It also proves the rejection happens <em>before</em> the
 * handler rather than as a side effect of the dependency being down.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class InventoryApiSecurityIT {

    // A port nothing listens on: a guarded request must be rejected long before this would matter.
    private static final String UNREACHABLE_ES = "http://127.0.0.1:1";

    private static TestRealm realm;

    /** Starts the realm this service validates tokens against. */
    @BeforeAll
    static void startRealm() {
        realm = TestRealm.start("lattice");
    }

    /** Releases the realm. */
    @AfterAll
    static void stopRealm() {
        realm.close();
    }

    /** Reading an item without a token is rejected with the UNAUTHORIZED error envelope. */
    @Test
    void getInventoryWithoutATokenIsUnauthorized(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectWarn("bootstrap deferred");
        var verticle = new InventoryVerticle(UNREACHABLE_ES, 0, realm.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/api/v1/inventory/sku-42")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(401, resp.statusCode());
                        assertEquals(
                                "UNAUTHORIZED",
                                resp.bodyAsJsonObject().getJsonObject("error").getString("code"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /** Setting stock with a viewer token is FORBIDDEN: a write needs the operator role. */
    @Test
    void setStockAsViewerIsForbidden(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectWarn("bootstrap deferred");
        var verticle = new InventoryVerticle(UNREACHABLE_ES, 0, realm.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = realm.viewerClient(vertx);
            client.put(verticle.actualPort(), "localhost", "/api/v1/inventory/sku-42")
                    .sendJsonObject(new JsonObject().put("onHand", 5))
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(403, resp.statusCode());
                        assertEquals(
                                "FORBIDDEN",
                                resp.bodyAsJsonObject().getJsonObject("error").getString("code"));
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }

    /**
     * With no realm configured the service refuses to start rather than serving {@code /api/v1}
     * unprotected. A pod that will not start is visible immediately; an open API is not.
     */
    @Test
    void refusesToStartWithNoRealmConfigured(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectError("keycloak is not configured");
        logs.expectWarn("bootstrap deferred");
        vertx.deployVerticle(new InventoryVerticle(UNREACHABLE_ES, 0, null))
                .onComplete(ctx.failing(err -> ctx.verify(ctx::completeNow)));
    }

    /** The operational probes answer without a token, so an orchestrator can still reach them. */
    @Test
    void probesStayOpen(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectWarn("bootstrap deferred");
        var verticle = new InventoryVerticle(UNREACHABLE_ES, 0, realm.realmUrl());
        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            var client = WebClient.create(vertx);
            client.get(verticle.actualPort(), "localhost", "/health")
                    .send()
                    .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                        assertEquals(200, resp.statusCode());
                        client.close();
                        ctx.completeNow();
                    })));
        }));
    }
}
