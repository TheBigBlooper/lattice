package io.lattice.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.lattice.common.BaseVerticle;
import io.lattice.common.testing.ExpectedLogs;
import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Contract tests for the shared bearer-token guard every service inherits from {@link BaseVerticle}.
 * They pin the protected surface settled in {@code per_baseline_identity.md}: every {@code /api/v1}
 * operation requires a token from <em>this</em> baseline's own realm, a {@code viewer} may read but
 * not write, an {@code operator} may do both, and the operational probes answer without a token.
 *
 * <p>Tokens are minted by a {@link TestRealm} - a real RSA key pair and a real JWKS endpoint - so the
 * guard verifies signatures exactly as it does against Keycloak. A second realm stands in for a peer
 * baseline, which is how the "a peer's token is not accepted here" rule is tested rather than assumed.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class ApiSecurityTest {

    /**
     * A minimal service exposing one read and one write under {@code /api/v1}, plus an unprotected
     * route outside it, so the guard's path scope is observable. Its realm is injected rather than
     * read from the process environment, the same seam {@code corsAllowedOrigins()} provides.
     */
    static final class GuardedVerticle extends BaseVerticle {
        private final String realmUrl;

        GuardedVerticle(String realmUrl) {
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
            router.get("/api/v1/things").handler(ctx -> ctx.response().end("read"));
            router.post("/api/v1/things").handler(ctx -> ctx.response().end("written"));
            router.get("/api/v1/broken").handler(ctx -> ctx.fail(new IllegalStateException("boom")));
            router.get("/outside").handler(ctx -> ctx.response().end("open"));
        }
    }

    private static TestRealm realm;
    private static TestRealm peerRealm;

    private WebClient client;

    /**
     * Starts this baseline's realm and a peer baseline's. Two realms run side by side so the "a peer's
     * token is not accepted here" rule is tested against a genuinely different signing key rather than
     * a simulated one.
     */
    @BeforeAll
    static void startRealms() {
        realm = TestRealm.start("lattice");
        peerRealm = TestRealm.start("peer");
    }

    /** Releases both realms. */
    @AfterAll
    static void stopRealms() {
        realm.close();
        peerRealm.close();
    }

    /** Deploys the guarded service against this baseline's realm and points a WebClient at it. */
    private Future<Void> deploy(Vertx vertx) {
        var verticle = new GuardedVerticle(realm.realmUrl());
        return vertx.deployVerticle(verticle).map(id -> {
            this.client = WebClient.create(
                    vertx, new WebClientOptions().setDefaultHost("localhost").setDefaultPort(verticle.actualPort()));
            return null;
        });
    }

    /** A request carrying no Authorization header is rejected with the UNAUTHORIZED error envelope. */
    @Test
    void rejectsARequestWithNoToken(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> client.get("/api/v1/things").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(401, resp.statusCode());
                    assertEquals("UNAUTHORIZED", errorCode(resp.bodyAsJsonObject()));
                    ctx.completeNow();
                })));
    }

    /** A token this realm did not sign is rejected: a peer baseline's grant does not carry here. */
    @Test
    void rejectsATokenFromAnotherBaselinesRealm(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> bearer(client.get("/api/v1/things"), peerRealm.operatorToken())
                        .send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(401, resp.statusCode());
                    assertEquals("UNAUTHORIZED", errorCode(resp.bodyAsJsonObject()));
                    ctx.completeNow();
                })));
    }

    /** An expired token is rejected even though this realm signed it. */
    @Test
    void rejectsAnExpiredToken(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> bearer(client.get("/api/v1/things"), realm.expiredToken(TestRealm.OPERATOR))
                        .send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(401, resp.statusCode());
                    ctx.completeNow();
                })));
    }

    /** A malformed bearer value is rejected rather than raising an unhandled error. */
    @Test
    void rejectsAMalformedToken(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready ->
                        bearer(client.get("/api/v1/things"), "not-a-jwt").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(401, resp.statusCode());
                    ctx.completeNow();
                })));
    }

    /** A viewer token reads: every GET under /api/v1 is within the viewer role's grant. */
    @Test
    void allowsAViewerToRead(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> bearer(client.get("/api/v1/things"), realm.viewerToken())
                        .send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    assertEquals("read", resp.bodyAsString());
                    ctx.completeNow();
                })));
    }

    /** A viewer token cannot write: the write verbs need the operator role, so this is FORBIDDEN. */
    @Test
    void refusesAViewerAWrite(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> bearer(client.post("/api/v1/things"), realm.viewerToken())
                        .send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(403, resp.statusCode());
                    assertEquals("FORBIDDEN", errorCode(resp.bodyAsJsonObject()));
                    ctx.completeNow();
                })));
    }

    /** An operator token writes, and its response is the route's own, not the guard's. */
    @Test
    void allowsAnOperatorToWrite(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> bearer(client.post("/api/v1/things"), realm.operatorToken())
                        .send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    assertEquals("written", resp.bodyAsString());
                    ctx.completeNow();
                })));
    }

    /** An operator reads too: the operator role is a superset of the viewer role. */
    @Test
    void allowsAnOperatorToRead(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> bearer(client.get("/api/v1/things"), realm.operatorToken())
                        .send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    ctx.completeNow();
                })));
    }

    /**
     * The operational probes answer without a token. A Kubernetes probe cannot present one, so gating
     * them would take healthy pods out of rotation for no secrecy.
     */
    @Test
    void leavesTheProbesOpen(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> client.get("/health").send())
                .compose(health -> {
                    ctx.verify(() -> assertEquals(200, health.statusCode()));
                    return client.get("/readiness").send();
                })
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    ctx.completeNow();
                })));
    }

    /** A route outside /api/v1 is not touched by the guard, so the scope is exactly the business API. */
    @Test
    void leavesRoutesOutsideTheApiUntouched(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx)
                .compose(ready -> client.get("/outside").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    ctx.completeNow();
                })));
    }

    /**
     * A service with no realm configured refuses to start. Serving {@code /api/v1} unprotected because
     * a setting was missed is the one failure mode worse than not starting, so the omission is fatal
     * rather than silently degrading to an open API.
     */
    @Test
    void refusesToStartWithNoRealmConfigured(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectError("keycloak");
        vertx.deployVerticle(new GuardedVerticle(""))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertNotNull(err.getMessage());
                    ctx.completeNow();
                })));
    }

    /**
     * A failure that is neither an authentication nor an authorization problem is passed along rather
     * than dressed up as one. The guard sits in front of every {@code /api/v1} route, so swallowing
     * other failures here would relabel a service's own errors as auth errors.
     */
    @Test
    void passesNonAuthFailuresAlong(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        // The route fails deliberately and nothing downstream handles it, so the router reports it.
        // That log IS the evidence the failure was passed along rather than absorbed by the guard.
        logs.expectError("Unhandled exception in router");
        deploy(vertx)
                .compose(ready -> bearer(client.get("/api/v1/broken"), realm.operatorToken())
                        .send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(500, resp.statusCode());
                    ctx.completeNow();
                })));
    }

    /**
     * When the realm's signing keys cannot be fetched at all, a request is 503 UNAVAILABLE rather than
     * 401. The service cannot say whether the token is good - that is a dependency being down, not a
     * bad credential, and answering 401 would tell an operator holding a perfectly valid token that it
     * was rejected.
     */
    @Test
    void reportsIdentityUnavailableWhenTheRealmCannotBeReached(Vertx vertx, VertxTestContext ctx, ExpectedLogs logs) {
        logs.expectWarn("signing keys not loaded at startup");
        logs.expectWarn("signing keys are unavailable");
        // A port nothing listens on: the realm is configured but unreachable.
        var verticle = new GuardedVerticle("http://127.0.0.1:1/realms/lattice");
        vertx.deployVerticle(verticle)
                .compose(id -> {
                    var unreachable = WebClient.create(
                            vertx,
                            new WebClientOptions().setDefaultHost("localhost").setDefaultPort(verticle.actualPort()));
                    return bearer(unreachable.get("/api/v1/things"), realm.operatorToken())
                            .send();
                })
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(503, resp.statusCode());
                    assertEquals("UNAVAILABLE", errorCode(resp.bodyAsJsonObject()));
                    ctx.completeNow();
                })));
    }

    /** Reads the error code out of the failure envelope, asserting the envelope shape on the way. */
    private static String errorCode(JsonObject body) {
        assertNull(body.getJsonObject("data"), "a failure envelope carries error, never data");
        assertNotNull(body.getJsonObject("meta"), "every envelope carries meta");
        return body.getJsonObject("error").getString("code");
    }

    /** Attaches a bearer token to a request. */
    private static <T> HttpRequest<T> bearer(HttpRequest<T> request, String token) {
        return request.putHeader("Authorization", "Bearer " + token);
    }
}
