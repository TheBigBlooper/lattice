import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { BaselineChip } from "./BaselineChip.tsx";

describe("BaselineChip", () => {
  /** Both facts, in one chip, so the app bar answers "which baseline, running what". */
  it("names where the baseline runs and which version it is", () => {
    render(<BaselineChip region="us-central" version="0.1.0-SNAPSHOT" />);

    expect(screen.getByText(/us-central/)).toBeInTheDocument();
    expect(screen.getByText(/0\.1\.0-SNAPSHOT/)).toBeInTheDocument();
  });

  /** One known fact is still worth stating; it does not wait for the other. */
  it("shows what it knows when only one is available", () => {
    render(<BaselineChip version="0.1.0-SNAPSHOT" />);

    expect(screen.getByText("0.1.0-SNAPSHOT")).toBeInTheDocument();
  });

  /**
   * With nothing known it renders nothing at all.
   *
   * <p>The same principle the signed-out card follows: on a status screen a confident wrong version
   * is worse than an absent one, because nothing else on the page contradicts it.
   */
  it("renders nothing rather than an empty chip", () => {
    const { container } = render(<BaselineChip />);

    expect(container).toBeEmptyDOMElement();
  });
});
