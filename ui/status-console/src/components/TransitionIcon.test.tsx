import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { TransitionKind } from "../mesh/activity.ts";
import { TransitionIcon } from "./TransitionIcon.tsx";

function glyphOf(kind: TransitionKind): string {
  const { container } = render(<TransitionIcon kind={kind} />);
  return container.querySelector("svg")?.getAttribute("data-testid") ?? "";
}

describe("TransitionIcon", () => {
  /**
   * The shape distinguishes the kinds, not only the colour.
   *
   * <p>The console's rule is that colour is never the sole indicator: a log telling "came back" from
   * "went quiet" by hue alone is unreadable to a colour-blind operator. This asserts the second
   * channel actually differs rather than rendering one glyph in several colours.
   */
  it("gives every kind of change its own shape", () => {
    const kinds: TransitionKind[] = [
      "peer-lost",
      "peer-returned",
      "peer-joined",
      "peer-health",
      "mesh-lost",
      "mesh-returned",
    ];

    const glyphs = kinds.map(glyphOf);

    expect(new Set(glyphs).size).toBe(kinds.length);
  });

  /**
   * A severed link and an unreachable peer look different on purpose.
   *
   * <p>"We are cut off" and "they are gone" are different incidents, and this is the panel where the
   * two sit one above the other - so the glyphs have to separate them at a glance, exactly as the
   * wording does.
   */
  it("draws a broken mesh link differently from a quiet peer", () => {
    expect(glyphOf("mesh-lost")).not.toEqual(glyphOf("peer-lost"));
  });

  /**
   * Decorative to assistive technology: the sentence beside it already says what happened, and
   * announcing the glyph too would say it twice.
   */
  it("is hidden from screen readers", () => {
    const { container } = render(<TransitionIcon kind="peer-lost" />);

    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });
});
