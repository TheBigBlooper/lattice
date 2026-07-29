import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client.ts";
import type { Order } from "../../api/useOrders.ts";
import { NewOrderForm } from "./NewOrderForm.tsx";

const ORDER: Order = {
  orderId: "ord-9c40ab",
  customerId: "acme-northwest",
  status: "RECEIVED",
  lines: [{ sku: "SKU-40119", quantity: 2 }],
  createdAt: "2026-07-29T14:11:37Z",
};

/** The form as an operator or a viewer sees it, with whatever the last attempt produced. */
function form(overrides: Partial<Parameters<typeof NewOrderForm>[0]> = {}) {
  const onCreate = vi.fn();
  render(
    <NewOrderForm
      baseline="hub-central"
      canWrite
      isCreating={false}
      onCreate={onCreate}
      {...overrides}
    />
  );
  return onCreate;
}

describe("NewOrderForm", () => {
  /**
   * An operator composes an order and it reaches the caller as the contract shapes it.
   *
   * <p>Quantity is held as text while being typed - a half-entered number must not be coerced - so
   * this also pins that it leaves as a number rather than the string the input held.
   */
  it("submits what was composed, with the quantity as a number", async () => {
    const user = userEvent.setup();
    const onCreate = form();

    await user.type(screen.getByLabelText(/customer/i), "acme-northwest");
    await user.type(screen.getByLabelText(/sku/i), "SKU-40119");
    await user.type(screen.getByLabelText(/quantity/i), "2");
    await user.click(screen.getByRole("button", { name: /create order/i }));

    expect(onCreate).toHaveBeenCalledWith({
      customerId: "acme-northwest",
      lines: [{ sku: "SKU-40119", quantity: 2 }],
    });
  });

  /**
   * A viewer sees the form and is told which grant is missing, and where.
   *
   * <p>The last three words are the point. Realm membership is deliberately unsynchronised, so the
   * same person may hold operator on another baseline - naming this one is what separates "the
   * product cannot do this" from "you cannot do this here".
   */
  it("shows a viewer the form disabled, naming the baseline", () => {
    form({ canWrite: false });

    expect(screen.getByRole("button", { name: /create order/i })).toBeDisabled();
    expect(screen.getByLabelText(/customer/i)).toBeDisabled();
    expect(screen.getByText(/operator role on hub-central/i)).toBeInTheDocument();
  });

  /**
   * A validation failure renders against the field the service named, not as a banner.
   *
   * <p>The split is about what the operator can do next: their input is fixed where they will fix
   * it. The field name comes from the service, so the console holds no mapping that could drift.
   */
  it("puts a validation failure on the field the service named", () => {
    form({
      error: new ApiError("VALIDATION_ERROR", 400, "Request failed validation", [
        { field: "lines[0].quantity", issue: "must be at least 1" },
      ]),
    });

    expect(screen.getByText(/must be at least 1/i)).toBeInTheDocument();
    // The summary is deliberately NOT also shown as a banner: it would say the same thing twice,
    // and the useful half is already sitting on the input.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  /**
   * A conflict is a banner, because no amount of editing the form changes the world.
   *
   * <p>It carries the server's own wording rather than a rewrite - the service is what knows why it
   * refused.
   */
  it("shows a conflict as a banner carrying the server's wording", () => {
    form({ error: new ApiError("CONFLICT", 409, "Insufficient stock for SKU-40119") });

    expect(screen.getByRole("alert")).toHaveTextContent("Insufficient stock for SKU-40119");
  });

  /**
   * What was created is rendered, id and all.
   *
   * <p>The id is server-generated and the contract has no search operation, so this is the
   * operator's only precise handle on the record. A toast would show it and then throw it away.
   */
  it("renders the created order in place", () => {
    form({ created: ORDER });

    expect(screen.getByText(/ord-9c40ab/)).toBeInTheDocument();
    expect(screen.getByText(/acme-northwest/)).toBeInTheDocument();
  });

  /**
   * A line removed from the middle takes its own values with it.
   *
   * <p>This is what keying by identity buys. Keyed by position, React reuses the row and the
   * remaining line inherits the removed one's inputs - a defect that looks like data corruption
   * and reads as a mystery.
   */
  it("keeps the surviving line's values when one is removed", async () => {
    const user = userEvent.setup();
    form();

    await user.click(screen.getByRole("button", { name: /add line/i }));
    const skus = screen.getAllByLabelText(/sku/i);
    await user.type(skus[0] as HTMLElement, "FIRST");
    await user.type(skus[1] as HTMLElement, "SECOND");

    await user.click(screen.getByRole("button", { name: /remove line 1/i }));

    expect(screen.getByLabelText(/sku/i)).toHaveValue("SECOND");
  });
});
