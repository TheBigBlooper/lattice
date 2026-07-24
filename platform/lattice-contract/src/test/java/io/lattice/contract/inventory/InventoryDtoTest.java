package io.lattice.contract.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the inventory REST DTO records. They pin the record components read back through
 * their accessors and the value semantics (equality) the service and status console rely on.
 */
class InventoryDtoTest {

    /** A SetStockRequest exposes the submitted absolute on-hand quantity. */
    @Test
    void setStockRequestExposesOnHand() {
        var request = new SetStockRequest(100);
        assertEquals(100, request.onHand());
    }

    /** An InventoryItem exposes its sku, on-hand, reserved, and computed available components. */
    @Test
    void inventoryItemExposesItsComponents() {
        var item = new InventoryItem("sku-42", 100, 12, 88);
        assertEquals("sku-42", item.sku());
        assertEquals(100, item.onHand());
        assertEquals(12, item.reserved());
        assertEquals(88, item.available());
    }

    /** A CreateReservationRequest exposes the order line and quantity to reserve. */
    @Test
    void createReservationRequestExposesItsComponents() {
        var request = new CreateReservationRequest("order-1", "sku-42", 3);
        assertEquals("order-1", request.orderId());
        assertEquals("sku-42", request.sku());
        assertEquals(3, request.quantity());
    }

    /** A Reservation exposes every server-set and echoed component through its accessors. */
    @Test
    void reservationExposesItsComponents() {
        var reservation = new Reservation("9c1f-uuid", "order-1", "sku-42", 3, "2026-07-24T00:00:00Z");
        assertEquals("9c1f-uuid", reservation.reservationId());
        assertEquals("order-1", reservation.orderId());
        assertEquals("sku-42", reservation.sku());
        assertEquals(3, reservation.quantity());
        assertEquals("2026-07-24T00:00:00Z", reservation.createdAt());
    }

    /** Records carry value semantics: equal components are equal, differing ones are not. */
    @Test
    void recordsHaveValueSemantics() {
        var a = new InventoryItem("sku-1", 10, 2, 8);
        var b = new InventoryItem("sku-1", 10, 2, 8);
        var c = new InventoryItem("sku-2", 10, 2, 8);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotNull(a.toString());
    }
}
