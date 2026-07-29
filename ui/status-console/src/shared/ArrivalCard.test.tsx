import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ArrivalCard } from "./ArrivalCard.tsx";

describe("ArrivalCard", () => {
  /**
   * The shell exists so the two arrival screens are the same object rather than two things that
   * happen to look alike. A second implementation would drift the moment either was touched, which
   * is exactly what it looked like before: one screen designed, the other a bare block.
   */
  it("renders its contents inside the shared card", () => {
    render(
      <ArrivalCard>
        <p>anything</p>
      </ArrivalCard>
    );

    expect(screen.getByRole("status")).toHaveTextContent("anything");
  });

  /**
   * The product mark is decorative, and deliberately so: every screen using this shell names the
   * baseline in text directly beneath it, so an accessible name on the image would announce the
   * same thing twice to a screen-reader user.
   */
  it("carries the product mark without announcing it", () => {
    const { container } = render(
      <ArrivalCard>
        <p>anything</p>
      </ArrivalCard>
    );

    const mark = container.querySelector("img");
    expect(mark).toHaveAttribute("alt", "");
    expect(mark).toHaveAttribute("src", "/android-chrome-192x192.png");
  });
});
