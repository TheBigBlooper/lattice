import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { ClusterHealth } from "../../theme/tone.ts";
import type { TransitionKind } from "./activity.ts";
import { TransitionIcon } from "./TransitionIcon.tsx";

function glyphOf(kind: TransitionKind, landing?: ClusterHealth): string {
  const { container } = render(<TransitionIcon kind={kind} landing={landing} />);
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
      "service-lost",
      "service-returned",
      "component-degraded",
      "component-lost",
      "component-returned",
    ];

    const glyphs = kinds.map((kind) => glyphOf(kind));

    expect(new Set(glyphs).size).toBe(kinds.length);
  });

  /**
   * A rollup change is drawn by the state it landed in, not by its kind.
   *
   * <p>The kind names an edge rather than an outcome: "went ready to degraded" and "went down to
   * ready" are the same kind and opposite news. Drawn from the kind alone both rendered one amber
   * warning, so a baseline recovering looked exactly like a baseline dying.
   *
   * <p><b>This is why the every-kind-differs assertion above no longer covers all eleven drawings.</b>
   * It was updated deliberately rather than relaxed: the ten fixed kinds still each hold their own
   * shape, and this kind now holds three. Those three come from the status vocabulary the rest of
   * the console already speaks, so a rollup landing on ready shares its shape with other good news
   * - which the sentence beside it distinguishes, as the rule requires.
   */
  it("draws a rollup change by where it landed", () => {
    const landings: ClusterHealth[] = ["ready", "degraded", "down"];

    const glyphs = landings.map((landing) => glyphOf("peer-health", landing));

    expect(new Set(glyphs).size).toBe(landings.length);
  });

  /**
   * A local failure and a mesh failure are not the same picture.
   *
   * <p>They land in one timeline, so an operator scanning it separates "our service died" from "a
   * peer went quiet" by shape before reading either line. Sharing a glyph would make the single
   * stream the design chose harder to read than the two panels it chose over.
   */
  it("draws a local service failure differently from a peer going quiet", () => {
    expect(glyphOf("service-lost")).not.toEqual(glyphOf("peer-lost"));
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
