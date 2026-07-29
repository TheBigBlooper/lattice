import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { BarSegment } from "./BarSegment.tsx";

describe("BarSegment", () => {
  /** Label and value are separate nodes, so either can be found on its own. */
  it("states what the value is as well as the value", () => {
    render(<BarSegment label="Region" value="us-west" />);

    expect(screen.getByText("Region")).toBeInTheDocument();
    expect(screen.getByText("us-west")).toBeInTheDocument();
  });

  /**
   * With no value there is no segment at all.
   *
   * <p>A bar reading REGION with nothing beneath it invites an operator to wonder what broke, when
   * the honest answer is that this console was never told.
   */
  it("renders nothing rather than a label over a blank", () => {
    const { container } = render(<BarSegment label="Region" />);

    expect(container).toBeEmptyDOMElement();
  });

  /**
   * The baseline is the page heading.
   *
   * <p>Which baseline is being looked at is what the page is about, so it stays a heading rather
   * than becoming one more label-and-value pair among four.
   */
  it("makes the primary segment the page heading", () => {
    render(<BarSegment label="Baseline" primary value="hub-west" />);

    expect(screen.getByRole("heading", { name: "hub-west" })).toBeInTheDocument();
  });
});
