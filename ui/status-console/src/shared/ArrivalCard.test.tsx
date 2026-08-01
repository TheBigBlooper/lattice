import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ArrivalCard } from "./ArrivalCard.tsx";

describe("ArrivalCard", () => {
  /**
   * The shell exists so the arrival screens are the same object rather than three things that
   * happen to look alike. A second implementation would drift the moment either was touched, which
   * is exactly what it looked like before: one screen designed, the other a bare block.
   */
  it("renders its contents inside the shared card", () => {
    render(
      <ArrivalCard title="hub-central">
        <p>anything</p>
      </ArrivalCard>
    );

    expect(screen.getByRole("status")).toHaveTextContent("anything");
  });

  /**
   * The heading belongs to the shell, not to each screen.
   *
   * <p>This is what stops the three drifting apart again: the line beneath the mark used to mean
   * the product name on one screen, the baseline on another, and nothing at all on the third. A
   * screen now supplies its message and its actions and cannot spell the shared part differently.
   */
  it("renders the heading it is given", () => {
    render(
      <ArrivalCard title="No access on hub-east">
        <p>anything</p>
      </ArrivalCard>
    );

    expect(screen.getByRole("heading", { name: "No access on hub-east" })).toBeInTheDocument();
  });

  /**
   * Identity is shown only when the screen has one to show.
   *
   * <p>The sign-in screen cannot know its region or version - the endpoint reporting them needs a
   * token - so it renders no identity line rather than an empty pill, on the one screen an operator
   * has no way to cross-check.
   */
  it("renders no identity line when there is none", () => {
    const { container } = render(
      <ArrivalCard title="hub-central">
        <p>anything</p>
      </ArrivalCard>
    );

    expect(container.querySelector(".MuiChip-root")).toBeNull();
  });

  /**
   * The state glyph is decorative, and deliberately so.
   *
   * <p>It replaces the product mark, which was decorative for a weaker reason - the mark said
   * nothing at all. The glyph says something, but it says what the heading already says in words,
   * so announcing it would say it twice. What it buys is that the three arrivals are
   * distinguishable before they are read, and that the state is never carried by colour alone:
   * each renders its glyph, its tone, and its heading.
   */
  it("carries a state glyph without announcing it", () => {
    const { container } = render(
      <ArrivalCard state="error" title="Cannot reach this baseline">
        <p>anything</p>
      </ArrivalCard>
    );

    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(screen.getByRole("status")).toHaveTextContent("Cannot reach this baseline");
  });
});
