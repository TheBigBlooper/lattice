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

  it("draws a dashed flat line until two readings exist, rather than an empty box", () => {
    render(<Sparkline readings={[1]} title="Announces over the last ten minutes" tone="success" />);

    const line = screen
      .getByLabelText("Announces over the last ten minutes")
      .querySelector("polyline");

    // Dashed, so it cannot be read as a measured flat line. A counter never incremented has no
    // series at all, and an empty card sat among five with lines read as broken.
    expect(line?.getAttribute("stroke-dasharray")).toBe("3 3");
  });
});
