package io.lattice.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.testing.FailOnUnexpectedLogExtension;
import io.lattice.common.testing.TestRealm;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Contract tests for the machine-readable API docs surface every service inherits from
 * {@link BaseVerticle}.
 *
 * <p>{@code /docs/json} serves the very OpenAPI document that drives router validation, so the
 * published contract and the enforced one cannot drift - there is no second, hand-written copy to
 * fall behind.
 *
 * <p><b>It is gated off in production</b> (deploy_protocol.md lists this among the caveats to settle
 * before any prod deploy). The gate lives here rather than in each service so a service added later
 * is covered by default with nothing to remember, which is the same argument that put the bearer
 * guard in {@link BaseVerticle}.
 */
@ExtendWith({VertxExtension.class, FailOnUnexpectedLogExtension.class})
class ApiDocsTest {

    /** A service that publishes no routes of its own, so only the inherited surface is under test. */
    static final class BareVerticle extends BaseVerticle {
        private final String realmUrl;
        private final boolean docsEnabled;

        BareVerticle(String realmUrl, boolean docsEnabled) {
            this.realmUrl = realmUrl;
            this.docsEnabled = docsEnabled;
        }

        @Override
        protected String keycloakRealmUrl() {
            return realmUrl;
        }

        @Override
        protected boolean apiDocsEnabled() {
            return docsEnabled;
        }

        @Override
        protected int httpPort() {
            return 0;
        }

        @Override
        protected void configureRoutes(Router router) {
            // deliberately empty
        }
    }

    private static TestRealm realm;

    @BeforeAll
    static void startRealm() {
        realm = TestRealm.start("lattice");
    }

    @AfterAll
    static void stopRealm() {
        realm.close();
    }

    private Future<WebClient> deploy(Vertx vertx, boolean docsEnabled) {
        var verticle = new BareVerticle(realm.realmUrl(), docsEnabled);
        return vertx.deployVerticle(verticle)
                .map(id -> WebClient.create(
                        vertx,
                        new WebClientOptions().setDefaultHost("localhost").setDefaultPort(verticle.actualPort())));
    }

    /**
     * Enabled, the spec is served as JSON - and it is recognisably the Lattice contract rather than
     * an empty document, which is the failure a smoke check would otherwise miss.
     */
    @Test
    void servesTheOpenApiSpecWhenDocsAreEnabled(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, true)
                .compose(client -> client.get("/docs/json").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    assertTrue(
                            resp.getHeader("content-type").startsWith("application/json"),
                            "the spec is served as JSON so a generator or viewer can consume it directly");

                    var spec = resp.bodyAsJsonObject();
                    assertNotNull(spec.getString("openapi"), "the document declares its OpenAPI version");
                    assertTrue(
                            spec.getJsonObject("paths").containsKey("/api/v1/baseline"),
                            "the served document is the real contract, not an empty shell");
                    ctx.completeNow();
                })));
    }

    /**
     * The gate itself: off, the surface is simply not there. A 404 rather than a 403, because in a
     * production baseline this endpoint does not exist - answering 403 would confirm it does and
     * invite someone to go looking for a way in.
     */
    @Test
    void doesNotServeTheSpecWhenDocsAreDisabled(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, false)
                .compose(client -> client.get("/docs/json").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(404, resp.statusCode());
                    ctx.completeNow();
                })));
    }

    /**
     * Gating the docs must not disturb anything else. The probes are the ones that matter: they are
     * unauthenticated by design so kubelet can call them, and a gate that took them down with it
     * would pull a healthy pod out of rotation.
     */
    @Test
    void leavesTheProbesUntouchedWhenDocsAreDisabled(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, false)
                .compose(client -> client.get("/health")
                        .send()
                        .compose(health -> client.get("/readiness").send().map(ready ->
                                new int[] {health.statusCode(), ready.statusCode()})))
                .onComplete(ctx.succeeding(codes -> ctx.verify(() -> {
                    assertEquals(200, codes[0], "/health must answer with the docs gated off");
                    assertEquals(200, codes[1], "/readiness must answer with the docs gated off");
                    ctx.completeNow();
                })));
    }

    /**
     * The spec is readable without a token. It sits outside {@code /api/v1} deliberately: the
     * document describes the API rather than exposing it, every operation it lists stays guarded, and
     * requiring a token to read a contract that a client generator needs before it can authenticate
     * would be circular.
     */
    @Test
    void servesTheSpecWithoutABearerToken(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, true)
                .compose(client -> client.get("/docs/json").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode(), "no Authorization header was sent");
                    ctx.completeNow();
                })));
    }

    /**
     * The browsable page is served from the same gate as the document, so "the container is running
     * in dev" is all it takes to read the contract - no external viewer, no copying a file about.
     */
    @Test
    void servesTheBrowsableDocsPageWhenDocsAreEnabled(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, true)
                .compose(client -> client.get("/docs").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    assertTrue(
                            resp.getHeader("content-type").startsWith("text/html"),
                            "the page is HTML, not the document");
                    ctx.completeNow();
                })));
    }

    /**
     * The page is assembled from assets bundled in the image, never fetched from a content delivery
     * network. A baseline may run air-gapped (locked #55), where a page that reached out for its own
     * scripts would render blank with no obvious cause.
     */
    @Test
    void servesTheDocsAssetsFromTheImageRatherThanTheInternet(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, true)
                .compose(client -> client.get("/docs/swagger-ui.css")
                        .send()
                        .compose(css -> client.get("/docs").send().map(page -> new Object[] {css, page})))
                .onComplete(ctx.succeeding(both -> ctx.verify(() -> {
                    var css = (io.vertx.ext.web.client.HttpResponse<?>) both[0];
                    var page = (io.vertx.ext.web.client.HttpResponse<?>) both[1];
                    assertEquals(200, css.statusCode(), "the stylesheet is served locally");
                    assertFalse(
                            page.bodyAsString().contains("//unpkg.com")
                                    || page.bodyAsString().contains("//cdn."),
                            "the page must not reference a content delivery network - a baseline may be air-gapped");
                    ctx.completeNow();
                })));
    }

    /** The page obeys the same gate as the document: off in prod means there is no page either. */
    @Test
    void doesNotServeTheDocsPageWhenDocsAreDisabled(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, false)
                .compose(client -> client.get("/docs").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(404, resp.statusCode());
                    ctx.completeNow();
                })));
    }

    /**
     * Enabling the docs must not open the API. The guard is mounted on {@code /api/v1}, and this
     * pins that the docs surface does not somehow bypass it.
     */
    @Test
    void doesNotUnprotectTheApiWhenDocsAreEnabled(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, true)
                .compose(client -> client.get("/api/v1/anything").send())
                .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                    assertEquals(401, resp.statusCode(), "an unauthenticated /api/v1 read stays refused");
                    assertFalse(resp.bodyAsString().contains("openapi"), "and it certainly does not return the spec");
                    ctx.completeNow();
                })));
    }
}
