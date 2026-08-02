package io.lattice.orders;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.lattice.common.BaseVerticle;
import io.lattice.common.RetryingGate;
import io.lattice.common.auth.ApiSecurity;
import io.lattice.common.config.LatticeConfig;
import io.lattice.common.es.ElasticsearchClientFactory;
import io.lattice.common.rest.Envelopes;
import io.lattice.orders.repository.OrdersRepository;
import io.lattice.orders.routes.OrderRoutes;
import io.lattice.orders.service.OrderService;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.healthchecks.Status;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.openapi.contract.OpenAPIContract;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The orders service verticle. It extends {@link BaseVerticle} for config, the health/readiness
 * surface, and graceful shutdown, and contributes only what is orders-specific: the OpenAPI-validated
 * {@code /api/v1/orders} routes ({@link #configureRoutes(Router)}) and an Elasticsearch readiness
 * check ({@link #registerReadinessChecks(HealthChecks)}), so {@code /readiness} is DOWN until
 * Elasticsearch is reachable.
 *
 * <p>Startup loads the shared config, builds the Elasticsearch client and repository, fires the
 * one-time create-if-absent index bootstrap (not awaited, so a not-ready Elasticsearch does not block
 * the server from binding - readiness gates traffic instead), then loads the v1 OpenAPI contract
 * before delegating to the base start. The build is sequenced so the client is available by the time
 * the base mounts routes and readiness checks.
 */
public final class OrdersVerticle extends BaseVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(OrdersVerticle.class);

    private final String esUrlOverride;
    private final int portOverride;
    private final String realmUrlOverride;

    private ElasticsearchClient client;
    private OrderService orderService;
    private OrderRoutes routes;
    private OpenAPIContract contract;

    /** Creates the verticle using the shared config for the Elasticsearch URL, HTTP port, and realm. */
    public OrdersVerticle() {
        this(null, -1, null);
    }

    /**
     * Creates the verticle with test overrides for the Elasticsearch URL, HTTP port, and identity realm.
     *
     * @param esUrlOverride    the Elasticsearch URL to use, or {@code null} to read it from config.
     * @param portOverride     the HTTP port to bind, or a negative value to read it from config (0 binds
     *                         an ephemeral port).
     * @param realmUrlOverride the realm URL the API guard validates tokens against, or {@code null} to
     *                         read it from config.
     */
    OrdersVerticle(String esUrlOverride, int portOverride, String realmUrlOverride) {
        this.esUrlOverride = esUrlOverride;
        this.portOverride = portOverride;
        this.realmUrlOverride = realmUrlOverride;
    }

    @Override
    protected String keycloakRealmUrl() {
        return realmUrlOverride != null ? realmUrlOverride : super.keycloakRealmUrl();
    }

    @Override
    public Future<?> start() {
        return LatticeConfig.load(vertx)
                .compose(cfg -> {
                    var url = esUrlOverride != null ? esUrlOverride : cfg.elasticsearchUrl();
                    this.client = ElasticsearchClientFactory.create(url);
                    var repository = new OrdersRepository(vertx, client);
                    // Retrying gate rather than a bare future: a bootstrap that fails because
                    // Elasticsearch is not reachable yet is re-attempted on the next request, so the
                    // service recovers on its own instead of staying wedged until a restart.
                    var indexBootstrap = new RetryingGate(repository::bootstrap);
                    // Surfaced without being swallowed: readiness stays DOWN until the index exists.
                    // Elasticsearch not being up yet is the expected startup race, so it is a concise
                    // WARN; a genuine bootstrap failure is an ERROR carrying the cause.
                    indexBootstrap.ready().onFailure(err -> {
                        if (isDependencyUnavailable(err)) {
                            LOG.warn(
                                    "orders index bootstrap deferred - Elasticsearch not reachable at startup: {}",
                                    err.toString());
                        } else {
                            LOG.error("orders index bootstrap failed", err);
                        }
                    });
                    this.orderService = new OrderService(repository, indexBootstrap);
                    this.routes = new OrderRoutes(orderService);
                    return ownedContract();
                })
                .compose(loaded -> {
                    this.contract = loaded;
                    return super.start();
                })
                .onSuccess(started -> LOG.info("orders service started on port {}", actualPort()));
    }

    @Override
    public Future<?> stop() {
        // stop() runs only after a successful start(), so the client is always constructed here.
        return vertx.executeBlocking(() -> {
                    client.close();
                    return null;
                })
                .compose(ignored -> super.stop());
    }

    @Override
    protected int httpPort() {
        return portOverride >= 0 ? portOverride : super.httpPort();
    }

    /**
     * The three operations orders serves. Declaring them with their handlers is what stops this
     * service publishing - or being warned about - the six operations that belong to its peers.
     */
    @Override
    protected Map<String, Handler<RoutingContext>> apiOperations() {
        return Map.of(
                "listOrders", routes::list,
                "createOrder", routes::create,
                "getOrder", routes::get);
    }

    @Override
    protected void configureRoutes(Router router) {
        var apiRouter =
                ApiSecurity.enforcedByBaseVerticle(boundApiRouter(contract)).createRouter();
        apiRouter.route().failureHandler(this::handleFailure);
        router.route("/*").subRouter(apiRouter);
    }

    @Override
    protected void registerReadinessChecks(HealthChecks readiness) {
        readiness.register(
                "elasticsearch",
                5000,
                promise -> vertx.executeBlocking(() -> client.ping().value())
                        .onComplete(result -> promise.complete(result.succeeded() ? Status.OK() : Status.KO())));
    }

    /**
     * Maps a routing failure to an error envelope, classified per the api_structure taxonomy: a
     * contract-validation failure (HTTP 400 raised by the OpenAPI router) becomes a VALIDATION_ERROR
     * with field-level details; a failure caused by an unreachable dependency (Elasticsearch down)
     * becomes a 503 UNAVAILABLE; anything else becomes a 500 INTERNAL that never leaks internals.
     *
     * @param ctx the failed routing context.
     */
    private void handleFailure(RoutingContext ctx) {
        if (ctx.statusCode() == 400) {
            // Validation is an expected client error, not server noise: log at DEBUG only.
            LOG.debug("request validation failed on {}", ctx.request().path());
            var issue = truncate(String.valueOf(ctx.failure()));
            var details =
                    new JsonArray().add(new JsonObject().put("field", "body").put("issue", issue));
            ctx.response()
                    .setStatusCode(400)
                    .putHeader("content-type", "application/json")
                    .end(Envelopes.error("VALIDATION_ERROR", "Request body failed validation.", details)
                            .encode());
        } else if (isDependencyUnavailable(ctx.failure())) {
            // Anomalous but recoverable (a dependency is down): WARN, not ERROR. Log the concise cause
            // (type + message), not the full throwable - this is an expected, handled condition (a clean
            // 503 is returned), so the Elasticsearch client's connection stack trace is noise here.
            LOG.warn("dependency unavailable handling {}: {}", ctx.request().path(), String.valueOf(ctx.failure()));
            ctx.response()
                    .setStatusCode(503)
                    .putHeader("content-type", "application/json")
                    .end(Envelopes.error("UNAVAILABLE", "A required dependency is currently unavailable.", null)
                            .encode());
        } else {
            LOG.error("unhandled request failure on {}", ctx.request().path(), ctx.failure());
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", "application/json")
                    .end(Envelopes.error("INTERNAL", "An unexpected server error occurred.", null)
                            .encode());
        }
    }

    /**
     * Walks a failure's cause chain for an {@link IOException} - the transport-level exception the
     * Elasticsearch client raises when the cluster is unreachable, distinguishing a down dependency
     * (503) from a genuine internal error (500).
     *
     * @param failure the failure to classify (may be {@code null}).
     * @return {@code true} if an {@link IOException} appears anywhere in the cause chain.
     */
    static boolean isDependencyUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String issue) {
        return issue.substring(0, Math.min(issue.length(), 512));
    }
}
