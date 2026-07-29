import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { InventoryItem } from "../../api/useInventory.ts";
import { SetStockDialog } from "./SetStockDialog.tsx";

const ITEM: InventoryItem = { sku: "SKU-40119", onHand: 500, reserved: 24, available: 476 };

/**
 * The dialog over an item, returning the spies a test asserts on.
 *
 * <p>The item is not defaulted: a default parameter is applied when `undefined` is passed, so a
 * closed-dialog case written that way would silently render the open one.
 */
function dialog(item: InventoryItem) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(<SetStockDialog item={item} onCancel={onCancel} onConfirm={onConfirm} />);
  return { onCancel, onConfirm };
}

describe("SetStockDialog", () => {
  /**
   * It says the value is absolute, and what it replaces.
   *
   * <p>Those are the two facts that decide whether the number being typed is a mistake: sending 10
   * to an item holding 500 destroys the 500, and the service accepts it silently unless the result
   * would fall below what is reserved.
   */
  it("states that the write is absolute and what it replaces", () => {
    dialog(ITEM);

    expect(screen.getByText(/absolute value, replacing 500/i)).toBeInTheDocument();
  });

  /** Nothing is written until a value is entered, so the confirm cannot fire on an empty field. */
  it("cannot be confirmed without a value", () => {
    dialog(ITEM);

    expect(screen.getByRole("button", { name: /set stock/i })).toBeDisabled();
  });

  /** The entered value reaches the caller as a number, not the string the input held. */
  it("confirms with the value as a number", async () => {
    const user = userEvent.setup();
    const { onConfirm } = dialog(ITEM);

    await user.type(screen.getByLabelText(/on hand/i), "120");
    await user.click(screen.getByRole("button", { name: /set stock/i }));

    expect(onConfirm).toHaveBeenCalledWith(120);
  });

  /**
   * A value below what is reserved is called out before it is sent.
   *
   * <p>The service refuses this case, and an operator told why in advance does not have to
   * interpret a conflict after the fact - the same reasoning as putting a validation error on the
   * field rather than in a banner.
   */
  it("warns when the value would strand what is already reserved", async () => {
    const user = userEvent.setup();
    dialog(ITEM);

    await user.type(screen.getByLabelText(/on hand/i), "10");

    expect(screen.getByText(/24 are already reserved/i)).toBeInTheDocument();
  });

  /** With no item there is nothing to confirm, so the dialog is absent rather than empty. */
  it("renders nothing when no item is being edited", () => {
    render(<SetStockDialog onCancel={vi.fn()} onConfirm={vi.fn()} />);

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
