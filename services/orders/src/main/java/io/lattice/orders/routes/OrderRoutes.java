package io.lattice.orders.routes;

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

    private static CreateOrderRequest toRequest(JsonObject body) {
        List<CreateOrderLine> lines = body.getJsonArray("lines").stream()
                .map(JsonObject.class::cast)
                .map(line -> new CreateOrderLine(line.getString("sku"), line.getInteger("quantity")))
                .toList();
        return new CreateOrderRequest(body.getString("customerId"), lines);
    }
}
