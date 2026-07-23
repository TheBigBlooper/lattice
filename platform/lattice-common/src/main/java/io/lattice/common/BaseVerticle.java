package io.lattice.common;

import io.lattice.common.config.LatticeConfig;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;

/**
 * The abstract base every Lattice service verticle extends. It centralizes the boilerplate a
 * service should not re-implement: loading shared {@link LatticeConfig configuration}, standing up
 * an HTTP {@link Router}, mounting the uniform health surface, and shutting the server down
 * gracefully. A subclass contributes only its own routes ({@link #configureRoutes(Router)}) and
 * readiness checks ({@link #registerReadinessChecks(HealthChecks)}).
 *
 * <p>Two probe endpoints are mounted, built on vertx-health-check (its native JSON shape):
 *
 * <ul>
 *   <li><b>{@code /health}</b> - liveness. The process is up; no dependency checks. Always UP once
 *       the verticle has started.
 *   <li><b>{@code /readiness}</b> - readiness. Runs the dependency checks a subclass registered; it
 *       reports UP (HTTP 200) only when every check passes and DOWN (HTTP 503) when any fails, so an
 *       orchestrator pulls a not-ready pod out of rotation until its dependencies recover.
 * </ul>
 *
 * <p>The exact probe response shape is provisional: it is the vertx-health-check native shape until
 * the REST envelope specification fixes a project-wide shape, at which point this is revisited.
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

            var livenessHandler = HealthCheckHandler.create(vertx);
            livenessHandler.register("process", promise -> promise.complete(Status.OK()));
            router.get(HEALTH_PATH).handler(livenessHandler);

            var readinessChecks = HealthChecks.create(vertx);
            registerReadinessChecks(readinessChecks);
            router.get(READINESS_PATH).handler(HealthCheckHandler.createWithHealthChecks(readinessChecks));

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
