package io.lattice.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lattice.common.BaseVerticle;
import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The cold-start case: a service that bound its port before its realm could serve signing keys.
 *
 * <p>This is ordinary in a cluster - nothing sequences a service's pod behind Keycloak's - and the
 * guard is deliberately built to survive it: the startup fetch is allowed to fail, and the first
 * request that arrives triggers the retry rather than being rejected. What that left unproven, until
 * these tests, is what happens to the request's BODY while it waits.
 *
 * <p><b>The window is wider than a Keycloak outage.</b> Deployment does not await the startup key
 * fetch, so a request can reach an unsettled gate even when the realm is perfectly healthy - which
 * means every service start had a window in which a write lost its body, not only the starts where
 * something was wrong. Both cases are pinned here.
 *
 * <p>It is its own class rather than more cases in {@link ApiSecurityTest} because every case needs a
 * service deployed against a gate that has not settled, which is the opposite of that suite's
 * ready-realm fixture.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class ApiSecurityDeferredKeysTest {

    /**
     * A service whose write route actually READS its body. That detail is the test: a write handler
     * that only replies would pass whether or not the body survived the wait, which is exactly why
     * the existing suite did not catch this.
     */
    static final class EchoVerticle extends BaseVerticle {
        private final String realmUrl;

        EchoVerticle(String realmUrl) {
            this.realmUrl = realmUrl;
        }

        @Override
        protected String keycloakRealmUrl() {
            return realmUrl;
        }

        @Override
        protected int httpPort() {
            return 0;
        }

        @Override
        protected void configureRoutes(Router router) {
            // A real service gets its body handling from the OpenAPI router; BaseVerticle installs
            // none of its own. Mounting one here is what makes this verticle model a real service
            // rather than a stripped-down one that could never have shown the defect.
            router.route().handler(BodyHandler.create());
            router.post("/api/v1/things").handler(ctx -> {
                var received = ctx.body().asJsonObject();
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("echoed", received).encode());
            });
        }
    }

    /**
     * A realm per test, not one for the class: each case needs a realm whose first key fetch is still
     * ahead of it, and a shared one would have that fetch consumed by whichever test ran first.
     */
    private TestRealm realm;

    @BeforeEach
    void startRealm() {
        realm = TestRealm.startRefusingKeys("lattice", 1);
    }

    @AfterEach
    void stopRealm() {
        realm.close();
    }

    /**
     * The regression this class exists for: with the signing keys arriving late, the first
     * authenticated write must still be served its body.
     *
     * <p>Before the fix this failed with a 500 and {@code IllegalStateException: Request has already
     * been read}. The guard waited for the keys without pausing the request, so the body streamed in
     * with nothing consuming it and was gone by the time the route ran. Every later request succeeded,
     * because the keys were in hand by then and the wait was no longer asynchronous - which is what
     * made it read in a cluster as one flaky 500 rather than as a startup ordering problem.
     */
    @Test
    void servesTheBodyOfTheFirstWriteWhenSigningKeysArriveLate(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectWarn("realm signing keys not loaded at startup");
        var verticle = new EchoVerticle(realm.realmUrl());
        var sent = new JsonObject().put("sku", "SKU-1").put("quantity", 2);

        vertx.deployVerticle(verticle)
                .onSuccess(id -> {
                    // The startup key fetch has already been refused, so this FIRST request is the
                    // one that triggers the retry - and the one whose body has to survive the wait.

                    var client = WebClient.create(
                            vertx,
                            new WebClientOptions().setDefaultHost("localhost").setDefaultPort(verticle.actualPort()));

                    client.post("/api/v1/things")
                            .putHeader("Authorization", "Bearer " + realm.operatorToken())
                            .sendJsonObject(sent)
                            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                                assertEquals(
                                        200, resp.statusCode(), "the first write after a cold start must be served");
                                assertEquals(
                                        sent,
                                        resp.bodyAsJsonObject().getJsonObject("echoed"),
                                        "the request body must survive the wait for signing keys");
                                ctx.completeNow();
                            })));
                })
                .onFailure(ctx::failNow);
    }

    /**
     * The same loss, against a realm that was healthy the whole time - which is the part of this
     * defect the original report understated.
     *
     * <p>Deployment does not await the startup key fetch (deliberately: a slow realm must cost a
     * request, not a restart), so a request arriving in that window waits on a gate that has not
     * settled yet even though nothing is wrong. The body was lost there too. In other words this was
     * never only a "Keycloak was down" bug - it was a cold-start bug, and every service start had a
     * window.
     */
    @Test
    void servesTheBodyOfTheFirstWriteEvenWhenTheRealmIsHealthy(Vertx vertx, VertxTestContext ctx) {
        var healthy = TestRealm.start("lattice");
        var verticle = new EchoVerticle(healthy.realmUrl());
        var sent = new JsonObject().put("sku", "SKU-1").put("quantity", 2);

        vertx.deployVerticle(verticle)
                .onSuccess(id -> {
                    var client = WebClient.create(
                            vertx,
                            new WebClientOptions().setDefaultHost("localhost").setDefaultPort(verticle.actualPort()));

                    client.post("/api/v1/things")
                            .putHeader("Authorization", "Bearer " + healthy.operatorToken())
                            .sendJsonObject(sent)
                            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                                assertEquals(200, resp.statusCode());
                                assertEquals(sent, resp.bodyAsJsonObject().getJsonObject("echoed"));
                                healthy.close();
                                ctx.completeNow();
                            })));
                })
                .onFailure(err -> {
                    healthy.close();
                    ctx.failNow(err);
                });
    }

    /**
     * The harness control: once the gate has settled, the echo route demonstrably returns the body.
     *
     * <p>It earns its place because both cases above failed on the first draft of this class for a
     * reason that had nothing to do with the defect - the test verticle mounted no body handler, so
     * it could never have echoed anything. This case passes with or without the fix, so if it ever
     * fails the harness is wrong rather than the guard.
     */
    @Test
    void echoesTheBodyOnceTheKeysAreLoaded(Vertx vertx, VertxTestContext ctx) {
        var ready = TestRealm.start("lattice");
        var verticle = new EchoVerticle(ready.realmUrl());
        var sent = new JsonObject().put("sku", "SKU-1").put("quantity", 2);

        vertx.deployVerticle(verticle)
                .onSuccess(id -> {
                    var client = WebClient.create(
                            vertx,
                            new WebClientOptions().setDefaultHost("localhost").setDefaultPort(verticle.actualPort()));

                    // Warm the gate first, so the request under test is not the one that waits.
                    client.get("/api/v1/things")
                            .putHeader("Authorization", "Bearer " + ready.operatorToken())
                            .send()
                            .compose(warmed -> client.post("/api/v1/things")
                                    .putHeader("Authorization", "Bearer " + ready.operatorToken())
                                    .sendJsonObject(sent))
                            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                                assertEquals(200, resp.statusCode());
                                assertEquals(sent, resp.bodyAsJsonObject().getJsonObject("echoed"));
                                ready.close();
                                ctx.completeNow();
                            })));
                })
                .onFailure(err -> {
                    ready.close();
                    ctx.failNow(err);
                });
    }

    static {
        // Keep the suite's own timeout ahead of the gate's retry, so a genuine hang is reported as a
        // failure rather than as a timeout nobody can attribute.
        System.setProperty("vertx.junit5.timeout", String.valueOf(TimeUnit.SECONDS.toMillis(30)));
    }
}
