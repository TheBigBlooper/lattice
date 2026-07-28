package io.lattice.common;

import io.lattice.common.auth.ApiSecurity;
import io.lattice.common.config.LatticeConfig;
import io.lattice.common.rest.Envelopes;
import io.lattice.common.rest.OwnedOperations;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.CheckResult;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.contract.OpenAPIContract;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The abstract base every Lattice service verticle extends. It centralizes the boilerplate a
 * service should not re-implement: loading shared {@link LatticeConfig configuration}, standing up
 * an HTTP {@link Router}, mounting the uniform health surface, and shutting the server down
 * gracefully. A subclass contributes only its own routes ({@link #configureRoutes(Router)}) and
 * readiness checks ({@link #registerReadinessChecks(HealthChecks)}).
 *
 * <p>Two probe endpoints are mounted. Both emit the fixed operational health shape defined in the
 * architecture design (see {@code docs/design/architecture/api_structure.md}, "Operational health
 * surface"): a non-enveloped body {@code {"status":"UP"|"DOWN","checks":[{"name":...,"status":...}]}}.
 * The vertx-health-check result (which keys each check by {@code id} and adds an {@code outcome}) is
 * mapped to that shape here; the native shape is never exposed.
 *
 * <ul>
 *   <li><b>{@code /health}</b> - liveness. The process is up; no dependency checks. Always UP (HTTP
 *       200) once the verticle has started.
 *   <li><b>{@code /readiness}</b> - readiness. Runs the dependency checks a subclass registered; it
 *       reports UP (HTTP 200) only when every check passes and DOWN (HTTP 503) when any fails, so an
 *       orchestrator pulls a not-ready pod out of rotation until its dependencies recover.
 * </ul>
 *
 * <p>Nothing here blocks the event loop: configuration load and server bind are asynchronous, and
 * the returned futures gate startup completion.
 *
 * @see LatticeConfig
 */
public abstract class BaseVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(BaseVerticle.class);

    /** The liveness endpoint path (process up, no dependency checks). */
    public static final String HEALTH_PATH = "/health";

    /** The readiness endpoint path (dependencies reachable, startup complete). */
    public static final String READINESS_PATH = "/readiness";

    /** Where the OpenAPI document is published, when this environment publishes it at all. */
    public static final String API_DOCS_PATH = "/docs/json";

    /** Where the browsable docs page is served, when this environment publishes it. */
    public static final String API_DOCS_PAGE_PATH = "/docs";

    /** Pinned in the parent pom alongside the dependency, so the asset path cannot drift from it. */
    private static final String SWAGGER_UI_VERSION = "5.25.3";

    /**
     * The Swagger UI initializer this service serves in place of the one the bundle ships.
     *
     * <p><b>Written out rather than patched.</b> The bundled initializer names Swagger's public demo
     * API and configures no authentication, so it needed rewriting either way - and string surgery on
     * a file we do not own is where this went wrong the first time. Appending {@code initOAuth} after
     * the upstream script ran it at parse time, when {@code window.ui} does not exist yet because the
     * bundle assigns it inside {@code window.onload}. It threw, the OAuth configuration was silently
     * never applied, and the Authorize dialog fell back to whatever had last been typed into it.
     *
     * <p><b>{@code oauth2RedirectUrl} is set explicitly</b> for a related reason: left to itself
     * Swagger derives it from the page address, and {@code /docs} carries no trailing slash, so it
     * resolves to the site root - an address the realm has never been told about and will refuse.
     *
     * <p>Filled by name rather than by {@code formatted}: the script is JavaScript going over the
     * wire, where a newline is a newline - a format string would have it emitted per platform.
     */
    private static final String INITIALIZER = """
            window.onload = function () {
              window.ui = SwaggerUIBundle({
                url: "{{document}}",
                dom_id: "#swagger-ui",
                deepLinking: true,
                presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                plugins: [SwaggerUIBundle.plugins.DownloadUrl],
                layout: "StandaloneLayout",
                oauth2RedirectUrl: window.location.origin + "{{docsPage}}/oauth2-redirect.html"
              });

              window.ui.initOAuth({
                clientId: "{{clientId}}",
                scopes: "openid",
                usePkceWithAuthorizationCodeGrant: true
              });
            };
            """;

    /**
     * The contract on the classpath, shipped by {@code lattice-contract} and depended on by every
     * service. It is the same document the OpenAPI router validates against, which is the point: the
     * published contract and the enforced one are one file, so they cannot drift.
     */
    private static final String API_SPEC_RESOURCE = "openapi/v1.yaml";

    /** Config key naming the origins allowed to read this service cross-origin (comma-separated). */
    public static final String CORS_ALLOWED_ORIGINS = "CORS_ALLOWED_ORIGINS";

    /** The loaded shared configuration, available to subclasses once {@link #start()} has run. */
    protected LatticeConfig config;

    private HttpServer server;
    private ApiSecurity apiSecurity;

    /**
     * Loads configuration, builds the router with the health/readiness surface plus the subclass
     * routes, and starts the HTTP server. The returned future completes once the server is bound.
     *
     * @return a future completing when startup finishes (config loaded, server listening).
     */
    @Override
    public Future<?> start() {
        return LatticeConfig.load(vertx).compose(loaded -> {
            this.config = loaded;
            var router = Router.router(vertx);

            configureCors(router);

            var livenessChecks = HealthChecks.create(vertx);
            livenessChecks.register("process", promise -> promise.complete(Status.OK()));
            router.get(HEALTH_PATH).handler(ctx -> respondHealth(ctx, livenessChecks));

            var readinessChecks = HealthChecks.create(vertx);
            registerReadinessChecks(readinessChecks);
            router.get(READINESS_PATH).handler(ctx -> respondHealth(ctx, readinessChecks));

            // The OpenAPI document, when this environment publishes it. Mounted alongside the probes
            // and OUTSIDE /api/v1 on purpose: it describes the API rather than exposing it, every
            // operation it lists stays guarded, and requiring a token to read the contract a client
            // generator needs before it can authenticate would be circular.
            //
            // When gated off the route is simply never mounted, so the path 404s like any other
            // address a service does not serve. A 403 would confirm the endpoint exists and invite
            // someone to go looking for a way past it.
            if (apiDocsEnabled()) {
                router.get(API_DOCS_PATH).handler(this::respondWithApiSpec);
                mountApiDocsPage(router);
            }

            // The guard is mounted AFTER the probes and BEFORE the service's routes, which is what
            // makes the protected surface exactly /api/v1: a probe is already matched and answered,
            // and nothing a subclass contributes under /api/v1 can be reached ahead of the check.
            try {
                this.apiSecurity = ApiSecurity.create(vertx, keycloakRealmUrl(), keycloakInternalRealmUrl());
            } catch (IllegalArgumentException misconfigured) {
                // Fail the deployment rather than serve an unprotected API. A pod that will not start
                // is visible immediately; an open /api/v1 is not visible at all.
                LOG.error("refusing to start - {}", misconfigured.getMessage());
                return Future.failedFuture(misconfigured);
            }
            apiSecurity.protect(router);
            // Not awaited: Keycloak may still be coming up, and the guard re-fetches on the first token
            // naming a key it does not hold, so a slow realm costs a request rather than a restart.
            apiSecurity
                    .loadKeys()
                    .onFailure(err -> LOG.warn(
                            "realm signing keys not loaded at startup - retrying on first use: {}",
                            String.valueOf(err)));

            configureRoutes(router);

            return vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(httpPort())
                    .onSuccess(bound -> {
                        this.server = bound;
                        LOG.info("{} listening on port {}", getClass().getSimpleName(), bound.actualPort());
                    });
        });
    }

    /**
     * Closes the HTTP server for a graceful shutdown when the verticle is undeployed.
     *
     * @return a future completing when the server has closed.
     */
    @Override
    public Future<?> stop() {
        return server == null ? Future.succeededFuture() : server.close();
    }

    /**
     * Runs the given check registry and writes the operational health shape to the response: HTTP
     * 200 when the aggregate is UP, HTTP 503 when any check is DOWN. Non-blocking - the registry is
     * evaluated asynchronously and the response is written on completion.
     *
     * @param ctx the routing context to write the response to.
     * @param checks the check registry to evaluate (the liveness or the readiness set).
     */
    private void respondHealth(RoutingContext ctx, HealthChecks checks) {
        checks.checkStatus().onSuccess(result -> {
            var body = toOperationalShape(result);
            var code = Boolean.TRUE.equals(result.getUp()) ? 200 : 503;
            ctx.response()
                    .setStatusCode(code)
                    .putHeader("content-type", "application/json")
                    .end(body.encode());
        });
    }

    /**
     * Restores every {@code $ref} to its authored, document-local form.
     *
     * <p>Vert.x resolves a contract against a base URI when it loads one from the classpath, so the
     * parsed document comes back with {@code app:///#/components/schemas/Foo} in place of
     * {@code #/components/schemas/Foo}. That is meaningful only inside Vert.x: any other reader
     * reports "could not resolve reference" for every one of them.
     *
     * <p>It fails in the most awkward way possible - the endpoint returns 200, the page renders, the
     * document is well-formed JSON, and only someone actually reading the docs sees a wall of
     * resolver errors. It reached a browser here before anything caught it, which is why
     * {@code ApiDocsTest} now follows every reference rather than checking the document merely
     * parses.
     *
     * @param spec the encoded contract as Vert.x produced it.
     * @return the same document with document-local references.
     */
    /**
     * The placeholder realm the shared contract ships with, replaced per baseline as it is served.
     */
    private static final String PLACEHOLDER_REALM = "https://realm.invalid";

    /**
     * Points the document authorization URLs at <em>this</em> baseline own realm.
     *
     * <p>The contract is one shared, static file while each baseline authenticates against its own
     * Keycloak, so the URLs cannot be baked in. Serving the placeholder unchanged would give every
     * baseline docs page the same address - correct on exactly one of them, and silently sending
     * operators on every other baseline to a realm that has never heard of them.
     *
     * @param spec the encoded document.
     * @return the same document naming this baseline realm.
     */
    private String thisBaselineRealm(String spec) {
        return spec.replace(PLACEHOLDER_REALM, keycloakRealmUrl());
    }

    private static String documentLocalRefs(String spec) {
        return spec.replace("\"app:///#/", "\"#/");
    }

    /**
     * Mounts the browsable API docs page at {@code /docs}, from Swagger UI assets bundled in the
     * image.
     *
     * <p><b>Bundled, never from a content delivery network.</b> A baseline may run air-gapped (locked
     * #55), and a page that reached out for its own scripts would render blank there with nothing in
     * the logs to explain it.
     *
     * <p>The webjar ships an {@code index.html} wired to Swagger's public demo API, so it is rewritten
     * on the way out to point at this service's own {@link #API_DOCS_PATH}. That rewrite is the only
     * reason the page is not served as a plain static file: shipping it unmodified would produce a
     * documentation page for somebody else's API, which looks like it works.
     *
     * @param router the router to mount the page on.
     */
    private void mountApiDocsPage(Router router) {
        var assetRoot = "META-INF/resources/webjars/swagger-ui/" + SWAGGER_UI_VERSION;

        // The page itself, with its relative asset references rewritten to absolute ones. The bundle
        // links them as "./swagger-ui.css", which resolves correctly only when the page is served
        // from a path ending in a slash - and Vert.x normalises "/docs/" to "/docs", so it never is.
        // Rewriting is what makes the page work at /docs without a redirect that would loop.
        router.get(API_DOCS_PAGE_PATH)
                .handler(ctx -> serveDocsAsset(
                        ctx,
                        assetRoot + "/index.html",
                        "text/html",
                        page -> page.replace("href=\"./", "href=\"" + API_DOCS_PAGE_PATH + "/")
                                .replace("src=\"./", "src=\"" + API_DOCS_PAGE_PATH + "/")
                                .replace("href=\"index.css\"", "href=\"" + API_DOCS_PAGE_PATH + "/index.css\"")));

        // The initializer is where the bundle names the document to load, and out of the box it names
        // Swagger's public demo API. Left alone, /docs would render a perfectly working page for
        // somebody else's service - which looks like success, so it is rewritten rather than trusted.
        router.get(API_DOCS_PAGE_PATH + "/swagger-initializer.js")
                .handler(ctx -> ctx.response()
                        .putHeader("content-type", "application/javascript")
                        .end(docsInitializer()));

        // Everything else (stylesheets, bundles, icons) straight from the image. This also serves
        // oauth2-redirect.html, which is what Keycloak returns the operator to after they sign in -
        // it ships with the bundle, so the flow needs no page of our own.
        router.route(API_DOCS_PAGE_PATH + "/*").handler(StaticHandler.create(assetRoot));
    }

    /**
     * Rewrites the Swagger initializer so the page loads this service document and can obtain its
     * own token.
     *
     * <p>Out of the box the bundle names Swagger public demo API, so left alone {@code /docs} would
     * render a perfectly working page for somebody else service - which looks like success.
     *
     * <p><b>PKCE, because the page is a public client.</b> A browser cannot keep a secret, and
     * without PKCE an intercepted authorization code could be exchanged by anyone. It is the same
     * flow and the same realm the status console already uses.
     */
    private String docsInitializer() {
        return INITIALIZER
                .replace("{{document}}", API_DOCS_PATH)
                .replace("{{docsPage}}", API_DOCS_PAGE_PATH)
                .replace("{{clientId}}", docsClientId());
    }

    /**
     * The public client the docs page authenticates as.
     *
     * <p>Deliberately not the console client. The two surfaces have different redirect URIs - one
     * per console origin, one per service port - and folding them into a single client would widen
     * the console registered URIs to cover every service on every baseline. Redirect URIs are the
     * part of a public client that must stay tight, so the two are kept apart and each stays exact.
     *
     * @return the docs client id.
     */
    protected String docsClientId() {
        return "lattice-docs";
    }

    /**
     * Reads one bundled Swagger UI asset from the classpath, rewrites it, and writes it out.
     *
     * @param ctx the routing context to write to.
     * @param resource the classpath resource to read.
     * @param contentType the content type to declare.
     * @param rewrite applied to the asset before it is sent.
     */
    private void serveDocsAsset(
            RoutingContext ctx, String resource, String contentType, java.util.function.UnaryOperator<String> rewrite) {
        vertx.fileSystem()
                .readFile(resource)
                .onSuccess(content -> ctx.response()
                        .putHeader("content-type", contentType + "; charset=utf-8")
                        .end(rewrite.apply(content.toString())))
                .onFailure(err -> {
                    LOG.warn("the API docs page asset {} could not be read: {}", resource, String.valueOf(err));
                    ctx.fail(503);
                });
    }

    /**
     * Serves the OpenAPI document as JSON.
     *
     * <p>It is read through {@code OpenAPIContract}, the same loader the routers use, rather than by
     * streaming the YAML file back: that yields the parsed document, so what is published is what is
     * actually enforced, and a spec that failed to parse cannot be served as though it were fine.
     *
     * <p>A failure here is a 503 rather than a 500. The contract is a resource this service depends
     * on, and being unable to read it is the dependency being unavailable - the same reasoning the
     * bearer guard applies when the realm's signing keys cannot be fetched.
     *
     * @param ctx the routing context to write the response to.
     */
    private void respondWithApiSpec(RoutingContext ctx) {
        // The same narrowing the router was built from, so the page cannot advertise an operation
        // this host does not serve. Publishing the whole baseline contract here is what let the
        // orders service list setStock and getPeers, both of which answer 404 to anyone who tries
        // them from the page.
        OpenAPIContract.from(vertx, API_SPEC_RESOURCE)
                .onSuccess(contract -> {
                    // Encoded first, then narrowed. The loader hands back a lazily-resolved view of
                    // the document; reading through it from an ordinary parent expands every
                    // reference inline and exposes the loader's own bookkeeping. Encoding first
                    // yields exactly the document this endpoint published before narrowing existed,
                    // and parsing that back gives a plain tree whose references are still
                    // references - which is what a viewer wants and what keeps this readable.
                    var whole = new JsonObject(
                            documentLocalRefs(contract.getRawContract().encode()));
                    var narrowed =
                            OwnedOperations.filteredTo(whole, apiOperations().keySet());
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(thisBaselineRealm(narrowed.encode()));
                })
                .onFailure(err -> {
                    LOG.warn(
                            "the OpenAPI document could not be read from {}: {}",
                            API_SPEC_RESOURCE,
                            String.valueOf(err));
                    ctx.response()
                            .setStatusCode(503)
                            .putHeader("content-type", "application/json")
                            .end(Envelopes.error("UNAVAILABLE", "The API document is currently unavailable.", null)
                                    .encode());
                });
    }

    /**
     * Maps a vertx-health-check {@link CheckResult} to the fixed operational shape
     * {@code {"status":"UP"|"DOWN","checks":[{"name":...,"status":...}]}}. Each nested check's
     * {@code id} becomes {@code name} and its up/down state becomes {@code status}; the native
     * {@code outcome} field is dropped.
     *
     * @param result the aggregate check result to map.
     * @return the operational-shape body.
     */
    private static JsonObject toOperationalShape(CheckResult result) {
        var checks = new JsonArray();
        List<CheckResult> children = result.getChecks();
        if (children != null) {
            for (CheckResult child : children) {
                checks.add(new JsonObject().put("name", child.getId()).put("status", label(child.getUp())));
            }
        }
        return new JsonObject().put("status", label(result.getUp())).put("checks", checks);
    }

    /**
     * Renders a check's up/down boolean as the operational status label.
     *
     * @param up the check's up state (may be {@code null}, treated as DOWN).
     * @return {@code "UP"} when up, otherwise {@code "DOWN"}.
     */
    private static String label(Boolean up) {
        return Boolean.TRUE.equals(up) ? "UP" : "DOWN";
    }

    /**
     * Hook for a subclass to mount its service-specific routes on the shared router. Called after
     * the health/readiness endpoints are mounted and before the server binds.
     *
     * @param router the shared router the service adds its routes to.
     */
    protected abstract void configureRoutes(Router router);

    /**
     * The API operations this service serves, each with the handler that serves it.
     *
     * <p><b>One map rather than a list and a set of registrations.</b> Which operations a host owns
     * used to be implicit - whatever {@code getRoute} happened to be called for - which let the
     * published document advertise operations the host answers 404 for, and made the router warn
     * about every operation it had not claimed. Declaring the pair together means the document, the
     * router, and the handlers are read from one place and cannot drift: a declared operation with
     * no handler is not expressible, and a handler for an undeclared operation is not reachable.
     *
     * @return the owned operation ids and their handlers; empty for a service with no API surface.
     */
    protected Map<String, Handler<RoutingContext>> apiOperations() {
        return Map.of();
    }

    /**
     * Loads the baseline contract narrowed to this service's own operations.
     *
     * <p>Filtered <b>before</b> the router is built, so no unmounted operation ever exists. That is
     * what makes the no-handler warnings stop happening rather than be suppressed, and it is why the
     * document served at {@code /docs/json} describes the host serving it.
     *
     * @return a future of the contract carrying only {@link #apiOperations()}.
     */
    protected Future<OpenAPIContract> ownedContract() {
        return OpenAPIContract.from(vertx, API_SPEC_RESOURCE)
                // Encoded and re-parsed before narrowing, for the same reason the docs endpoint does
                // it: the loader's view carries bookkeeping members that the OpenAPI validator
                // rejects outright when they are handed back to it. Encoding drops them. References
                // are also brought back document-local: the loader writes them against its own
                // app:/// base, which it resolves while the document is the one it loaded and cannot
                // resolve in a document handed to it - the attempt recurses until the stack gives
                // out rather than reporting anything useful.
                .map(full -> OwnedOperations.filteredTo(
                        new JsonObject(documentLocalRefs(full.getRawContract().encode())),
                        apiOperations().keySet()))
                .compose(filtered -> OpenAPIContract.from(vertx, filtered));
    }

    /**
     * Binds every declared operation to its handler on a router built from the narrowed contract.
     *
     * <p>The registration walks {@link #apiOperations()} rather than naming operations again, which
     * is what keeps the declaration and the wiring from disagreeing.
     *
     * @param contract this service's narrowed contract.
     * @return the builder with every owned operation bound, ready for the security guard.
     */
    protected RouterBuilder boundApiRouter(OpenAPIContract contract) {
        var builder = RouterBuilder.create(vertx, contract);
        apiOperations()
                .forEach((operationId, handler) -> builder.getRoute(operationId).addHandler(handler));
        return builder;
    }

    /**
     * Returns the comma-separated origins allowed to read this service cross-origin. Defaults to the
     * configured {@code CORS_ALLOWED_ORIGINS}; overridable so a test can exercise the behavior without
     * a process-wide environment variable, the same seam {@link #httpPort()} provides.
     *
     * @return the configured origins, or an empty string when none are set.
     */
    protected String corsAllowedOrigins() {
        return config.getString(CORS_ALLOWED_ORIGINS).orElse("");
    }

    /**
     * Returns this baseline's own realm URL, which the {@code /api/v1} guard validates tokens against.
     * Composed from the configured {@code KEYCLOAK_URL} and {@code KEYCLOAK_REALM}; overridable so a
     * test can point at a realm it controls, the same seam {@link #corsAllowedOrigins()} provides.
     *
     * @return the realm URL, or an empty string when either setting is unset (which fails startup).
     */
    protected String keycloakRealmUrl() {
        return config.keycloakRealmUrl();
    }

    /**
     * Returns the realm URL this service <em>reaches</em> Keycloak at, which is not always the one a
     * token's issuer claims. In a cluster the console's browser reaches Keycloak through a published
     * address while a service reaches it by an internal one, so the issuer to trust and the address to
     * fetch signing keys from are two different things; {@code KEYCLOAK_INTERNAL_URL} names the second
     * when they differ, and defaults to the first when they do not.
     *
     * @return the realm URL to fetch signing keys from.
     */
    protected String keycloakInternalRealmUrl() {
        var internal = config.keycloakInternalRealmUrl();
        // Fall back to the overridable seam rather than the raw config, so a test that points
        // keycloakRealmUrl() at a realm it controls fetches that realm's keys too.
        return internal.isBlank() ? keycloakRealmUrl() : internal;
    }

    /**
     * Whether this service publishes its OpenAPI document at {@code /docs/json}.
     *
     * <p>On locally and in dev, where the contract is a testing surface; <b>off in prod</b>, which
     * `deploy_protocol.md` lists among the caveats to settle before any prod deploy. Serving it there
     * would publish the exact shape of every endpoint - field bounds, error codes, the lot - to anyone
     * who can reach the service. The document is not secret, but serving it from production is a
     * choice, and this is where that choice is made rather than discovered.
     *
     * <p>It defaults to <b>on</b> deliberately, so a value nobody set does not silently take the
     * contract away from a developer; a prod environment turns it off explicitly, which is a thing a
     * deploy can be checked for.
     *
     * @return {@code true} when the spec should be served.
     */
    protected boolean apiDocsEnabled() {
        return config.apiDocsEnabled();
    }

    /**
     * Mounts cross-origin access ahead of every route, from the {@code CORS_ALLOWED_ORIGINS} setting
     * (comma-separated; unset means no cross-origin access is granted).
     *
     * <p>This lives here rather than in a service because the status console's browser reads <b>each
     * discovered peer's</b> API directly to build its unified view - so every service a peer console
     * can reach needs identical behavior, and implementing it per service would fork one concern
     * across the tree. Only the read verbs are allowed: the unified view is read-only by design, and an
     * operator who wants to change something on a peer is redirected to that baseline's own console
     * rather than writing to it across origins.
     *
     * @param router the router to mount the handler on.
     */
    private void configureCors(Router router) {
        var origins = corsAllowedOrigins();
        if (origins.isBlank()) {
            LOG.debug("no CORS origins configured; cross-origin reads are not permitted");
            return;
        }
        var allowed = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (allowed.isEmpty()) {
            return;
        }
        router.route()
                .handler(CorsHandler.create()
                        .addOrigins(allowed)
                        .allowedMethod(HttpMethod.GET)
                        .allowedMethod(HttpMethod.OPTIONS)
                        .allowedHeader("content-type")
                        // Every /api/v1 operation requires a bearer token, and a browser asks
                        // permission for the Authorization header on the preflight. Without this the
                        // preflight refuses it and EVERY cross-origin read fails - which would
                        // silently disable the unified view, the one thing cross-origin access
                        // exists for here.
                        .allowedHeader("authorization"));
        LOG.info("cross-origin reads allowed from {}", allowed);
    }

    /**
     * Hook for a subclass to register readiness checks (its dependency reachability probes) on the
     * readiness registry. The default registers none - a service with no external dependency is
     * ready as soon as it has started.
     *
     * @param readiness the readiness check registry to add checks to.
     */
    protected void registerReadinessChecks(HealthChecks readiness) {
        // No dependency checks by default; a subclass with dependencies overrides this.
    }

    /**
     * Returns the port the HTTP server binds. Defaults to the configured {@code HTTP_PORT}; a
     * subclass (notably a test) may override to bind an ephemeral port.
     *
     * @return the port to bind (0 selects an ephemeral port).
     */
    protected int httpPort() {
        return config.httpPort();
    }

    /**
     * Returns the port the server actually bound, resolving an ephemeral (0) port to its real
     * value. Useful for tests that bind port 0 and then address the server.
     *
     * @return the bound port, or -1 if the server is not yet listening.
     */
    public int actualPort() {
        return server == null ? -1 : server.actualPort();
    }
}
