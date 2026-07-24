package io.lattice.inventory.routes;

import io.lattice.contract.inventory.CreateReservationRequest;
import io.lattice.contract.inventory.SetStockRequest;
import io.lattice.inventory.service.InventoryService;
import io.lattice.inventory.service.StockConflictException;
import io.lattice.inventory.service.UnknownSkuException;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.validation.ValidatedRequest;

/**
 * The thin inventory route handlers: they read the already-validated request (the OpenAPI router has
 * enforced the strict body at the edge), delegate to the {@link InventoryService}, and shape the
 * {@code {data|error, meta}} envelope. The service's typed business outcomes map to their HTTP codes
 * here (unknown sku -&gt; 404, a stock conflict -&gt; 409); a dependency failure is passed to the
 * router's failure handler (503/500). No business logic or query building lives here.
 */
public final class InventoryRoutes {

    private static final String JSON = "application/json";

    private final InventoryService service;

    /**
     * Creates the handlers over the inventory service. The service is a shared, injected collaborator
     * (the standard dependency-injection pattern), held by reference not copied.
     *
     * @param service the inventory business-logic service.
     */
    public InventoryRoutes(InventoryService service) {
        this.service = service;
    }

    /**
     * Handles {@code PUT /api/v1/inventory/{sku}}: sets the item's absolute on-hand and returns 200
     * with the item, or 409 when the new on-hand would fall below what is reserved.
     *
     * @param ctx the routing context.
     */
    public void setStock(RoutingContext ctx) {
        var sku = ctx.pathParam("sku");
        ValidatedRequest validated = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
        var body = validated.getBody().getJsonObject();
        var request = new SetStockRequest(body.getInteger("onHand"));
        service.setStock(sku, request)
                .onSuccess(item -> ctx.response()
                        .setStatusCode(200)
                        .putHeader("content-type", JSON)
                        .end(Envelopes.success(item).encode()))
                .onFailure(err -> failBusiness(ctx, err));
    }

    /**
     * Handles {@code GET /api/v1/inventory/{sku}}: returns 200 with the item (including computed
     * available) when present, or 404 with a NOT_FOUND error envelope when it does not exist.
     *
     * @param ctx the routing context.
     */
    public void getInventory(RoutingContext ctx) {
        var sku = ctx.pathParam("sku");
        service.getInventory(sku)
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
                                .end(Envelopes.error("NOT_FOUND", "Inventory item " + sku + " was not found.", null)
                                        .encode());
                    }
                })
                .onFailure(ctx::fail);
    }

    /**
     * Handles {@code POST /api/v1/inventory/reservations}: reserves stock against the order line and
     * returns 201 with the reservation, 404 when the sku is unknown, or 409 on insufficient stock or
     * an idempotency conflict.
     *
     * @param ctx the routing context.
     */
    public void createReservation(RoutingContext ctx) {
        ValidatedRequest validated = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
        var body = validated.getBody().getJsonObject();
        var request = new CreateReservationRequest(
                body.getString("orderId"), body.getString("sku"), body.getInteger("quantity"));
        service.reserve(request)
                .onSuccess(reservation -> ctx.response()
                        .setStatusCode(201)
                        .putHeader("content-type", JSON)
                        .end(Envelopes.success(reservation).encode()))
                .onFailure(err -> failBusiness(ctx, err));
    }

    /**
     * Maps a service business outcome to its response: an unknown sku to 404 NOT_FOUND, a stock
     * conflict to 409 CONFLICT; anything else (a dependency failure) is passed to the router's failure
     * handler for the 503/500 classification.
     *
     * @param ctx the routing context.
     * @param err the failure from the service.
     */
    private static void failBusiness(RoutingContext ctx, Throwable err) {
        if (err instanceof UnknownSkuException) {
            ctx.response()
                    .setStatusCode(404)
                    .putHeader("content-type", JSON)
                    .end(Envelopes.error("NOT_FOUND", err.getMessage(), null).encode());
        } else if (err instanceof StockConflictException) {
            ctx.response()
                    .setStatusCode(409)
                    .putHeader("content-type", JSON)
                    .end(Envelopes.error("CONFLICT", err.getMessage(), null).encode());
        } else {
            ctx.fail(err);
        }
    }
}
