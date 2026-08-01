import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client.ts";
import { ReserveForm } from "./ReserveForm.tsx";

/** The form as an operator or a viewer sees it, with whatever the last attempt produced. */
function form(overrides: Partial<Parameters<typeof ReserveForm>[0]> = {}) {
  const onReserve = vi.fn();
  render(
    <ReserveForm
      baseline="hub-central"
      canWrite
      isReserving={false}
      onReserve={onReserve}
      {...overrides}
    />
  );
  return onReserve;
}

describe("ReserveForm", () => {
  /** A hold reaches the caller shaped as the contract wants it, quantity as a number. */
  it("submits the hold with the quantity as a number", async () => {
    const user = userEvent.setup();
    const onReserve = form();

    await user.type(screen.getByLabelText(/order/i), "ord-9c40ab");
    await user.type(screen.getByLabelText(/sku/i), "SKU-40119");
    await user.type(screen.getByLabelText(/quantity/i), "5");
    await user.click(screen.getByRole("button", { name: "Reserve" }));

    expect(onReserve).toHaveBeenCalledWith({
      orderId: "ord-9c40ab",
      quantity: 5,
      sku: "SKU-40119",
    });
  });

  /**
   * Insufficient stock is a banner, not a field error.
   *
   * <p>It is the conflict this form produces, and it is about the world rather than the input: no
   * amount of editing the quantity makes stock exist.
   */
  it("shows insufficient stock as a banner carrying the server's wording", () => {
    form({ error: new ApiError("CONFLICT", 409, "SKU-40119 has 12 available; 40 requested") });

    expect(screen.getByRole("alert")).toHaveTextContent("SKU-40119 has 12 available; 40 requested");
  });

  /**
   * A validation failure the form cannot place still reaches the operator.
   *
   * <p>The services name every validation problem against the field `body` rather than the
   * offending one, so nothing matches an input. Suppressed as a field error AND absent from the
   * banner, a refused write would explain itself nowhere - which is what submitting this form empty
   * did before this.
   */
  it("banners a validation failure it cannot place on a field", () => {
    form({
      error: new ApiError("VALIDATION_ERROR", 400, "Request body failed validation.", [
        { field: "body", issue: "orderId must not be blank" },
      ]),
    });

    expect(screen.getByRole("alert")).toHaveTextContent("Request body failed validation.");
  });

  /** A viewer sees the form disabled and is told the grant is needed on this baseline. */
  it("shows a viewer the form disabled, naming the baseline", () => {
    form({ canWrite: false });

    expect(screen.getByRole("button", { name: "Reserve" })).toBeDisabled();
    expect(screen.getByText(/signed in to hub-central as a viewer/i)).toBeInTheDocument();
  });

  /**
   * It is NOT confirmed, and that is deliberate.
   *
   * <p>A reservation is idempotent by `(orderId, sku)`, so repeating it is safe by construction.
   * Only `setStock` is gated, because it is the only write that destroys what it replaces -
   * confirming both would make the dialog reflexive and stop it protecting the case that matters.
   */
  it("reserves without a confirmation step", async () => {
    const user = userEvent.setup();
    const onReserve = form();

    await user.click(screen.getByRole("button", { name: "Reserve" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(onReserve).toHaveBeenCalled();
  });
});
