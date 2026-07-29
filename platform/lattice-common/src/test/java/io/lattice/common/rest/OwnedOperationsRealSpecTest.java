package io.lattice.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.openapi.contract.OpenAPIContract;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Narrowing the <em>real</em> baseline contract, not a hand-built fixture.
 *
 * <p>The shape of the document Vert.x hands back is not obvious - whether references survive, how
 * path items are keyed - and a filter that works on a tidy fixture can still mangle the real thing.
 * This runs the same call the docs endpoint makes.
 */
@ExtendWith(VertxExtension.class)
class OwnedOperationsRealSpecTest {

    /** The mesh-gateway's pair of operations survive; the seven belonging to its peers do not. */
    @Test
    void narrowsTheRealContractToOneServiceOperations(Vertx vertx, VertxTestContext ctx) {
        OpenAPIContract.from(vertx, "openapi/v1.yaml")
                .onComplete(ctx.succeeding(contract -> ctx.verify(() -> {
                    var filtered =
                            OwnedOperations.filteredTo(contract.getRawContract(), Set.of("getPeers", "getBaseline"));

                    var paths = filtered.getJsonObject("paths");
                    // The loader keeps its base-URI marker among the paths; it is not a path item
                    // and is deliberately left alone.
                    var remaining = new java.util.TreeSet<>(paths.fieldNames());
                    remaining.remove("__absolute_uri__");
                    assertEquals(
                            Set.of("/api/v1/baseline", "/api/v1/peers"),
                            remaining,
                            "only the mesh-gateway own paths remain");
                    assertTrue(filtered.containsKey("components"), "components are left whole so refs resolve");
                    // The docs endpoint encodes what this returns, so the encode is part of the contract.
                    // The docs endpoint encodes what this returns, so the encode is part of the contract.
                    assertTrue(filtered.encode().length() > 1000, "the narrowed document encodes");
                    ctx.completeNow();
                })));
    }

    /**
     * A service that owns nothing serves a document with no paths rather than failing. The probes-only
     * verticle in the docs test is exactly that, and it must still be able to publish.
     */
    @Test
    void aServiceThatOwnsNothingGetsAnEmptyPathsObject(Vertx vertx, VertxTestContext ctx) {
        OpenAPIContract.from(vertx, "openapi/v1.yaml")
                .onComplete(ctx.succeeding(contract -> ctx.verify(() -> {
                    var filtered = OwnedOperations.filteredTo(contract.getRawContract(), Set.of());

                    var left = new java.util.TreeSet<>(
                            filtered.getJsonObject("paths").fieldNames());
                    left.remove("__absolute_uri__");
                    assertTrue(left.isEmpty(), "no operation survives when none is owned");
                    ctx.completeNow();
                })));
    }
}
