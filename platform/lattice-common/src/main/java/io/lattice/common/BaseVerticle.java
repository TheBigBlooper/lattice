package io.lattice.common;

import io.lattice.common.config.LatticeConfig;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.CheckResult;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.List;

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

    /** The liveness endpoint path (process up, no dependency checks). */
    public static final String HEALTH_PATH = "/health";

    /** The readiness endpoint path (dependencies reachable, startup complete). */
    public static final String READINESS_PATH = "/readiness";

    /** The loaded shared configuration, available to subclasses once {@link #start()} has run. */
    protected LatticeConfig config;

    private HttpServer server;

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

            var livenessChecks = HealthChecks.create(vertx);
            livenessChecks.register("process", promise -> promise.complete(Status.OK()));
            router.get(HEALTH_PATH).handler(ctx -> respondHealth(ctx, livenessChecks));

            var readinessChecks = HealthChecks.create(vertx);
            registerReadinessChecks(readinessChecks);
            router.get(READINESS_PATH).handler(ctx -> respondHealth(ctx, readinessChecks));

            configureRoutes(router);

            return vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(httpPort())
                    .onSuccess(bound -> this.server = bound);
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
