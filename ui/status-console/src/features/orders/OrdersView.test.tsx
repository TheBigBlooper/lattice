import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client.ts";
import type { Order } from "../../api/useOrders.ts";
import { OrdersView } from "./OrdersView.tsx";

const ORDERS: Order[] = [
  {
    orderId: "ord-9c40ab",
    customerId: "acme-northwest",
    status: "RECEIVED",
    lines: [{ sku: "SKU-40119", quantity: 2 }],
    createdAt: "2026-07-29T14:11:37Z",
  },
  {
    orderId: "ord-8f21c4",
    customerId: "brightline-foods",
    status: "RECEIVED",
    lines: [
      { sku: "SKU-40204", quantity: 1 },
      { sku: "SKU-41880", quantity: 4 },
    ],
    createdAt: "2026-07-29T14:02:11Z",
  },
];

/** What the mocked hooks report. Each test sets it before rendering. */
const list = {
  data: undefined as Order[] | undefined,
  error: null as ApiError | null,
};

// Mocked at the data-layer seam rather than at fetch: this test is about what the screen does with
// a page of orders, and driving a real query would test the query.
vi.mock("../../api/useOrders.ts", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  useOrders: () => list,
  useCreateOrder: () => ({ data: undefined, error: null, isPending: false, mutate: vi.fn() }),
}));

/**
 * The realm role held here, as a value rather than a literal.
 *
 * <p>`role` is the operator's grant on this baseline, not an ARIA role - but written inline the
 * a11y lint reads it as one. Passing it as a value says the same thing without an exception.
 */
const REALM_ROLE = "operator";

/** The screen as a signed-in operator sees it. */
function view() {
  render(
    <OrdersView
      baseUrl="http://hub-central:8082/api/v1"
      baseline="hub-central"
      role={REALM_ROLE}
      token="a-token"
    />
  );
}

describe("OrdersView", () => {
  beforeEach(() => {
    list.data = undefined;
    list.error = null;
  });

  /** The page renders one row per order, newest first as the service sorted them. */
  it("lists the orders the baseline returned", () => {
    list.data = ORDERS;
    view();

    const rows = within(screen.getByRole("table", { name: /orders/i })).getAllByRole("row");
    // One header row plus one per order.
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("ord-9c40ab");
    expect(rows[2]).toHaveTextContent("brightline-foods");
  });

  /**
   * A baseline with no orders says so.
   *
   * <p>An empty table with headers and no rows reads as a screen that failed to load. Saying there
   * are none distinguishes "nothing here yet" from "something went wrong", which is the same
   * distinction the failed-read surface exists to draw.
   */
  it("says when there are no orders rather than showing an empty table", () => {
    list.data = [];
    view();

    expect(screen.getByText(/no orders on this baseline yet/i)).toBeInTheDocument();
    // The headers stay: an operator scanning an empty list still needs to know what the columns
    // would be, and a bare sentence reads like a screen that failed to load.
    expect(screen.getByRole("table")).toBeInTheDocument();
  });

  /** A failed read says why, rather than rendering as an empty baseline. */
  it("reports why the list could not be read", () => {
    list.error = new ApiError("UNAVAILABLE", 503, "Could not reach this baseline");
    view();

    expect(screen.getByText(/could not reach this baseline/i)).toBeInTheDocument();
  });

  /** The create panel is part of this screen, so an operator never navigates to place an order. */
  it("offers the create panel above the list", () => {
    list.data = ORDERS;
    view();

    expect(screen.getByRole("button", { name: /create order/i })).toBeInTheDocument();
  });
});
