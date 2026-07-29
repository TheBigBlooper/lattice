import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { lightTheme } from "../theme/theme.ts";
import type { ClusterHealth } from "../theme/tone.ts";
import { StatusRow } from "./StatusRow.tsx";

/** Renders one row inside the list it is only ever used within, and returns its rendered result. */
function renderRow(state: ClusterHealth, detail?: string) {
  return render(
    <ul>
      <StatusRow detail={detail} name="elasticsearch" state={state} />
    </ul>
  );
}

/** The glyph a row draws for a state, identified by the shape rather than by its colour. */
function glyphOf(state: ClusterHealth): string {
  const { container } = renderRow(state);
  return container.querySelector("svg")?.getAttribute("data-testid") ?? "";
}

describe("StatusRow", () => {
  /**
   * The row names the thing and states what it is doing, both as plain text, so either can be
   * found on its own. This is the whole contract the row offers its two callers: the service
   * breakdown and the infrastructure list ask for exactly the same shape.
   */
  it("names the thing and states what it is doing", () => {
    renderRow("ready");

    expect(screen.getByText("elasticsearch")).toBeInTheDocument();
    expect(screen.getByText("up")).toBeInTheDocument();
  });

  /**
   * Every state renders its word, including the middle one.
   *
   * <p>The row could not say `degraded` at all while it was hardcoded to a binary, which is the
   * state the infrastructure list exists to preserve: a datastore serving without its replicas is
   * neither healthy nor unable to serve.
   */
  it.each([
    ["ready", "up"],
    ["degraded", "degraded"],
    ["down", "down"],
  ] as const)("says the %s state in words", (state, word) => {
    renderRow(state);

    expect(screen.getByText(word)).toBeInTheDocument();
  });

  /**
   * The shape distinguishes the states, not only the hue.
   *
   * <p>Colour is never the sole indicator on this console, and a row rendering one glyph in three
   * colours would be unreadable to a colour-blind operator no matter how many words sit beside it.
   */
  it("gives every state its own glyph", () => {
    const glyphs = (["ready", "degraded", "down"] as const).map(glyphOf);

    expect(new Set(glyphs).size).toBe(3);
  });

  /** Every state's glyph takes its colour from the theme palette, never from a colour written here. */
  it.each([
    ["ready", lightTheme.palette.success.main],
    ["degraded", lightTheme.palette.warning.main],
    ["down", lightTheme.palette.error.main],
  ] as const)("colours the %s state from the theme", (state, expected) => {
    const { container } = renderRow(state);

    expect(container.querySelector("svg")?.parentElement).toHaveStyle({ color: expected });
  });

  /**
   * A healthy row stays quiet while an unhealthy one does not.
   *
   * <p>The right-hand column exists to answer "which one is wrong", and it can only do that if the
   * rows that are right do not shout as loudly. The glyph still carries the healthy state's colour;
   * the word is what steps back.
   */
  it.each([
    ["ready", lightTheme.palette.text.secondary],
    ["degraded", lightTheme.palette.warning.main],
    ["down", lightTheme.palette.error.main],
  ] as const)("weights the %s word so a failure is what stands out", (state, expected) => {
    renderRow(state);

    expect(screen.getByText(state === "ready" ? "up" : state)).toHaveStyle({ color: expected });
  });

  /**
   * A component's own reading is shown beside it.
   *
   * <p>The shared three-word state is what the console colours and counts; the detail is what an
   * operator reads to learn why. Losing it would leave `degraded` with no way to tell a missing
   * replica from a failing management endpoint.
   */
  it("shows the reading a component carries", () => {
    renderRow("degraded", "cluster status yellow");

    expect(screen.getByText("cluster status yellow")).toBeInTheDocument();
  });

  /**
   * Nothing is rendered where a detail is absent.
   *
   * <p>A service never carries one, and an empty caption on every service row would put a column of
   * blanks down a list whose whole job is to be scanned.
   */
  it("renders nothing in the detail's place when there is none", () => {
    renderRow("degraded");

    expect(screen.getByRole("listitem")).toHaveTextContent(/^elasticsearchdegraded$/);
  });
});
