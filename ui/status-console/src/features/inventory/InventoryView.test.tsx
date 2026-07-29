import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { InventoryItem } from "../../api/useInventory.ts";
import { InventoryView } from "./InventoryView.tsx";

const ITEMS: InventoryItem[] = [
  { sku: "SKU-40119", onHand: 500, reserved: 24, available: 476 },
  { sku: "SKU-40204", onHand: 36, reserved: 30, available: 6 },
];

/** What the mocked hooks report. Each test sets it before rendering. */
const list = { data: undefined as InventoryItem[] | undefined, error: null };
const setStockMutate = vi.fn();

// Mocked at the data-layer seam: this test is about what the screen does with a page of stock, not
// about how the page is fetched.
vi.mock("../../api/useInventory.ts", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  useInventory: () => list,
  useSetStock: () => ({ error: null, isPending: false, mutate: setStockMutate }),
  useCreateReservation: () => ({
    data: undefined,
    error: null,
    isPending: false,
    mutate: vi.fn(),
  }),
}));

/** The realm role held here, as a value: written inline the a11y lint reads it as an ARIA role. */
const OPERATOR = "operator";
const VIEWER = "viewer";

/** The screen at a given grant. */
function view(role: string = OPERATOR) {
  render(
    <InventoryView
      baseUrl="http://hub-central:8082/api/v1"
      baseline="hub-central"
      role={role}
      token="a-token"
    />
  );
}

describe("InventoryView", () => {
  beforeEach(() => {
    list.data = ITEMS;
    setStockMutate.mockReset();
  });

  /** Every item shows what is held, what is spoken for, and what is left. */
  it("lists stock with the counts that decide whether an order can be filled", () => {
    view();

    const rows = within(screen.getByRole("table", { name: /inventory/i })).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("SKU-40119");
    expect(rows[1]).toHaveTextContent("476");
  });

  /**
   * Setting stock is confirmed before it runs, and only then does anything reach the service.
   *
   * <p>It is the one irreversible write: an absolute value that destroys what it replaces. This
   * asserts the gate exists rather than that a dialog appears - opening it must not write.
   */
  it("writes nothing until the confirmation is accepted", async () => {
    const user = userEvent.setup();
    view();

    await user.click(screen.getByRole("button", { name: /set stock for SKU-40119/i }));

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(setStockMutate).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText(/on hand/i), "120");
    await user.click(screen.getByRole("button", { name: /^set stock$/i }));

    expect(setStockMutate).toHaveBeenCalledWith({ onHand: 120, sku: "SKU-40119" });
  });

  /**
   * A viewer sees the stock and the controls, disabled, and is told which grant is missing.
   *
   * <p>Membership is deliberately unsynchronised, so the same person may hold operator on another
   * baseline - naming this one separates a missing capability from a missing grant.
   */
  it("shows a viewer the controls disabled, naming the baseline", () => {
    view(VIEWER);

    expect(screen.getByRole("button", { name: /set stock for SKU-40119/i })).toBeDisabled();
    expect(
      screen.getByText(/setting stock needs the operator role on hub-central/i)
    ).toBeInTheDocument();
  });

  /** A baseline with no stock says so rather than rendering a headers-only table. */
  it("says when there is no stock", () => {
    list.data = [];
    view();

    expect(screen.getByText(/no stock on this baseline yet/i)).toBeInTheDocument();
    // The headers stay: an operator scanning an empty list still needs to know what the columns
    // would be, and a bare sentence reads like a screen that failed to load.
    expect(screen.getByRole("table")).toBeInTheDocument();
  });
});
