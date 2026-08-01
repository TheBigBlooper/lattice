import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ListPanel } from "./ListPanel.tsx";

/** The panel with a read that returned rows. */
function withRows() {
  return render(
    <ListPanel caption="newest first" count={2} emptyMessage="No orders yet." label="Orders">
      <table aria-label="orders">
        <tbody>
          <tr>
            <td>ord-1</td>
          </tr>
        </tbody>
      </table>
    </ListPanel>
  );
}

describe("ListPanel", () => {
  /** The panel names itself and renders the read beneath. */
  it("renders its table under its own heading", () => {
    withRows();

    expect(screen.getByText("Orders")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "orders" })).toBeInTheDocument();
  });

  /**
   * An empty read keeps its headers and says so beneath them.
   *
   * <p>Deliberate, and recorded here because it looks like a defect from the code alone: the table
   * is drawn whenever a read has returned, and an empty array has returned. An operator scanning a
   * list with nothing in it still needs to know what the columns would have been, and a bare
   * sentence on its own reads like a screen that failed to load rather than a baseline with no rows.
   */
  it("keeps the headers on an empty read and says it is empty", () => {
    render(
      <ListPanel
        caption="by sku"
        count={0}
        emptyMessage="No stock on this baseline yet."
        label="Inventory"
      >
        <table aria-label="inventory">
          <tbody />
        </table>
      </ListPanel>
    );

    expect(screen.getByRole("table", { name: "inventory" })).toBeInTheDocument();
    expect(screen.getByText("No stock on this baseline yet.")).toBeInTheDocument();
  });

  /**
   * Before the first read completes there is neither a table nor an emptiness to report.
   *
   * <p>Saying "no orders yet" while the answer is still arriving states something this console does
   * not know, and on a screen an operator acts from that is worse than saying nothing.
   */
  it("claims nothing before the first read returns", () => {
    render(
      <ListPanel caption="newest first" emptyMessage="No orders yet." label="Orders">
        <table aria-label="orders">
          <tbody />
        </table>
      </ListPanel>
    );

    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("No orders yet.")).not.toBeInTheDocument();
  });
});
