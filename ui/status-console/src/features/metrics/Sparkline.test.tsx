import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Sparkline } from "./Sparkline.tsx";

describe("Sparkline", () => {
  /**
   * The line must be drawn in a colour, not merely present.
   *
   * <p>It shipped invisible once: `sx` resolves a palette key like `success.main` only for the
   * style props it knows, and `stroke` is not one of them, so the key reached the browser as
   * invalid CSS. The element was in the document throughout, which is exactly why asserting its
   * presence proved nothing.
   */
  it("draws with a resolved stroke colour rather than a palette key", () => {
    render(
      <Sparkline readings={[1, 2, 3]} title="Announces over the last ten minutes" tone="success" />
    );

    const line = screen.getByLabelText("Announces over the last ten minutes");
    const stroke = getComputedStyle(line).stroke;

    expect(stroke).toBeTruthy();
    expect(stroke).not.toContain("main");
  });

  it("draws nothing until two readings exist, because one point is not a line", () => {
    render(<Sparkline readings={[1]} title="Announces over the last ten minutes" tone="success" />);

    expect(screen.queryByLabelText("Announces over the last ten minutes")).not.toBeInTheDocument();
  });
});
