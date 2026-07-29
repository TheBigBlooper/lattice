package io.lattice.contract.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the orders REST DTO records and the {@link OrderStatus} enum. They pin the record
 * components read back through their accessors and the value semantics (equality) the service and
 * status console rely on, and exercise the declared lifecycle enum.
 */
class OrdersDtoTest {

    /** A CreateOrderRequest and its lines expose the submitted customer and line values. */
    @Test
    void createRequestExposesItsComponents() {
        var request = new CreateOrderRequest("cust-1", List.of(new CreateOrderLine("sku-1", 3)));
        assertEquals("cust-1", request.customerId());
        assertEquals(1, request.lines().size());
        assertEquals("sku-1", request.lines().get(0).sku());
        assertEquals(3, request.lines().get(0).quantity());
    }

    /** An Order exposes every server-set and echoed component through its accessors. */
    @Test
    void orderExposesItsComponents() {
        var order = new Order(
                "b3f1c2a8", "cust-1", OrderStatus.RECEIVED, List.of(new OrderLine("sku-1", 2)), "2026-07-24T00:00:00Z");
        assertEquals("b3f1c2a8", order.orderId());
        assertEquals("cust-1", order.customerId());
        assertEquals(OrderStatus.RECEIVED, order.status());
        assertEquals(1, order.lines().size());
        assertEquals("sku-1", order.lines().get(0).sku());
        assertEquals(2, order.lines().get(0).quantity());
        assertEquals("2026-07-24T00:00:00Z", order.createdAt());
    }

    /** Records carry value semantics: equal components are equal, differing ones are not. */
    @Test
    void recordsHaveValueSemantics() {
        var a = new OrderLine("sku-1", 2);
        var b = new OrderLine("sku-1", 2);
        var c = new OrderLine("sku-2", 2);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotNull(a.toString());
    }

    /** The declared lifecycle enum resolves its RECEIVED..DELIVERED values by name and ordinal order. */
    @Test
    void orderStatusDeclaresTheLifecycle() {
        assertEquals(5, OrderStatus.values().length);
        assertEquals(OrderStatus.RECEIVED, OrderStatus.valueOf("RECEIVED"));
        assertTrue(OrderStatus.RECEIVED.ordinal() < OrderStatus.DELIVERED.ordinal());
    }
}
