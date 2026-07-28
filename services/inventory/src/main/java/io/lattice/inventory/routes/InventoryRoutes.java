package io.lattice.inventory.routes;

import io.lattice.common.rest.Envelopes;
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

    /**
     * Handles {@code GET /api/v1/inventory}: one page of this baseline stock items, ordered by sku.
     *
     * <p>An empty page is a 200 with an empty array, never a 404 - a baseline holding no stock is
     * not a fault, and a browse request answered with "not found" sends an operator looking for a
     * problem that does not exist.
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
     * Reads a validated integer query parameter, falling back to the contract default.
     *
     * <p>The bounds are not re-checked here: the OpenAPI router rejected anything outside them at
     * the edge, and a second opinion in the handler is where the two quietly drift apart.
     */
    private static int queryInt(RoutingContext ctx, String name, int fallback) {
        ValidatedRequest validated = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
        var value = validated.getQuery().get(name);
        // An absent parameter arrives as an EMPTY parameter, not a null one, and reading an integer
        // off it yields null - which unboxes into a 500 on a request that was perfectly valid. The
        // emptiness check is the whole of what makes an omitted page work.
        return value.isEmpty() ? fallback : value.getInteger();
    }
}
