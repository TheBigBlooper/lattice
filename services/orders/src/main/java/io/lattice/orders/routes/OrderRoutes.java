package io.lattice.orders.routes;

import io.lattice.common.rest.Envelopes;
import io.lattice.contract.orders.CreateOrderLine;
import io.lattice.contract.orders.CreateOrderRequest;
import io.lattice.orders.service.OrderService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.validation.ValidatedRequest;
import java.util.List;

/**
 * The thin orders route handlers: they read the already-validated request (the OpenAPI router has
 * enforced the strict body at the edge), delegate to the {@link OrderService}, and shape the
 * {@code {data|error, meta}} envelope. No business logic or query building lives here.
 */
public final class OrderRoutes {

    private static final String JSON = "application/json";

    /**
     * The page defaults, which must match the {@code default:} the contract declares.
     *
     * <p>They are repeated here because the request validator does <b>not</b> apply schema defaults
     * to a missing query parameter - verified by running the service, not assumed. A request that
     * omits them arrives with nothing, so without these the handler would page from zero with a size
     * of zero and serve an empty page for every browse. The duplication is the cost of that; the
     * test that omits both parameters is what keeps the two in step.
     */
    private static final int DEFAULT_PAGE = 0;

    private static final int DEFAULT_SIZE = 20;

    private final OrderService service;

    /**
     * Creates the handlers over the orders service.
     *
     * @param service the orders business-logic service.
     */
    public OrderRoutes(OrderService service) {
        this.service = service;
    }

    /**
     * Handles {@code POST /api/v1/orders}: builds the typed request from the validated body, creates
     * the order, and returns 201 with the success envelope. A downstream failure is passed to the
     * router's failure handler.
     *
     * @param ctx the routing context.
     */
    public void create(RoutingContext ctx) {
        ValidatedRequest validated = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
        CreateOrderRequest request = toRequest(validated.getBody().getJsonObject());
        service.create(request)
                .onSuccess(order -> ctx.response()
                        .setStatusCode(201)
                        .putHeader("content-type", JSON)
                        .end(Envelopes.success(order).encode()))
                .onFailure(ctx::fail);
    }

    /**
     * Handles {@code GET /api/v1/orders/{orderId}}: returns 200 with the order when present, or 404
     * with a NOT_FOUND error envelope when it does not exist.
     *
     * @param ctx the routing context.
     */
    public void get(RoutingContext ctx) {
        String orderId = ctx.pathParam("orderId");
        service.get(orderId)
                .onSuccess(found -> {
                    if (found.isPresent()) {
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", JSON)
                                .end(Envelopes.success(found.get()).encode());
                    } else {
                        ctx.response()
                                .setStatusCode(404)
                                .putHeader("content-type", JSON)
                                .end(Envelopes.error("NOT_FOUND", "Order " + orderId + " was not found.", null)
                                        .encode());
                    }
                })
                .onFailure(ctx::fail);
    }

    /**
     * Handles {@code GET /api/v1/orders}: one page of this baseline's orders, newest first.
     *
     * <p>An empty page is a 200 with an empty array, never a 404. A baseline that holds no orders is
     * not a fault, and answering a browse request with "not found" would send an operator looking
     * for a problem that does not exist.
     *
     * @param ctx the routing context.
     */
    public void list(RoutingContext ctx) {
        var page = queryInt(ctx, "page", DEFAULT_PAGE);
        var size = queryInt(ctx, "size", DEFAULT_SIZE);
        service.list(page, size)
                .onSuccess(found -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", JSON)
                        .end(Envelopes.successPage(found, page, size).encode()))
                .onFailure(ctx::fail);
    }

    /**
     * Reads a validated integer query parameter, falling back to the contract's default.
     *
     * <p>The bounds are not re-checked here. The OpenAPI router has already rejected anything outside
     * them at the edge, and a second opinion in the handler is where the two quietly drift apart.
     */
    private static int queryInt(RoutingContext ctx, String name, int fallback) {
        ValidatedRequest validated = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
        var value = validated.getQuery().get(name);
        // An absent parameter arrives as an EMPTY parameter, not a null one, and reading an integer
        // off it yields null - which unboxes into a 500 on a request that was perfectly valid. The
        // emptiness check is the whole of what makes an omitted page work.
        return value.isEmpty() ? fallback : value.getInteger();
    }

    private static CreateOrderRequest toRequest(JsonObject body) {
        List<CreateOrderLine> lines = body.getJsonArray("lines").stream()
                .map(JsonObject.class::cast)
                .map(line -> new CreateOrderLine(line.getString("sku"), line.getInteger("quantity")))
                .toList();
        return new CreateOrderRequest(body.getString("customerId"), lines);
    }
}
