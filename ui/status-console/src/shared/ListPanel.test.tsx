import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ListPanel } from "./ListPanel.tsx";
import { PANEL_HELP } from "./panelHelpContent.ts";

/** The panel with a read that returned rows. */
function withRows() {
  return render(
    <ListPanel count={2} emptyMessage="No orders yet." label="Orders">
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
      <ListPanel count={0} emptyMessage="No stock on this baseline yet." label="Inventory">
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
      <ListPanel emptyMessage="No orders yet." label="Orders">
        <table aria-label="orders">
          <tbody />
        </table>
      </ListPanel>
    );

    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("No orders yet.")).not.toBeInTheDocument();
  });
  /**
   * While the first read is out, the panel waits in the shape of a list.
   *
   * <p>A centred spinner would discard the panel's shape and make the layout jump when rows land.
   * The skeleton keeps it, and announces itself so the wait is not silent to a screen reader.
   */
  it("waits in the shape of a list rather than showing nothing", () => {
    render(
      <ListPanel emptyMessage="No stock yet." label="Inventory">
        <table aria-label="inventory">
          <tbody />
        </table>
      </ListPanel>
    );

    expect(screen.getByRole("status", { name: /loading inventory/i })).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("No stock yet.")).not.toBeInTheDocument();
  });

  /** A panel with an entry offers it; one without shows no control at all. */
  it("offers its help only when it has something to say", () => {
    const { rerender } = render(
      <ListPanel count={0} emptyMessage="No stock yet." label="Inventory">
        <table aria-label="inventory">
          <tbody />
        </table>
      </ListPanel>
    );
    expect(screen.queryByRole("button", { name: /about inventory/i })).not.toBeInTheDocument();

    rerender(
      <ListPanel
        count={0}
        emptyMessage="No stock yet."
        help={PANEL_HELP.inventory}
        label="Inventory"
      >
        <table aria-label="inventory">
          <tbody />
        </table>
      </ListPanel>
    );
    expect(screen.getByRole("button", { name: /about inventory/i })).toBeInTheDocument();
  });
});
