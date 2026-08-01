import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { lightTheme } from "../theme/theme.ts";
import { PanelRollup } from "./PanelRollup.tsx";

describe("PanelRollup", () => {
  /**
   * The rollup states its answer in a word.
   *
   * <p>Three panels on the overview each answer "how is this doing" with a glyph, a word and a
   * count, and each drew its own before this existed. A bespoke second implementation of a
   * component is a defect rather than a variant, and this was the third.
   */
  it("states the rollup in words", () => {
    render(<PanelRollup label="degraded" tone="degraded" />);

    expect(screen.getByText("degraded")).toBeInTheDocument();
  });

  /**
   * The tone lands on the word and nowhere else.
   *
   * <p>The cluster verdict used to set its tone on the whole panel, so the count line beneath it
   * inherited the status colour. Status colours are held to 3:1 rather than 4.5:1 by a deliberate
   * trade, and that trade was made for the status word - not for the supporting text that happened
   * to sit under it.
   */
  it("colours the word from the theme", () => {
    render(<PanelRollup label="down" tone="down" />);

    expect(screen.getByText("down")).toHaveStyle({ color: lightTheme.palette.error.main });
  });

  /**
   * The count is supporting text and is never toned.
   */
  it("renders the count neutrally beneath the word", () => {
    render(<PanelRollup count="2 of 3 services ready" label="degraded" tone="degraded" />);

    expect(screen.getByText("2 of 3 services ready")).toHaveStyle({
      color: lightTheme.palette.text.secondary,
    });
  });

  /**
   * A panel with nothing to count says nothing rather than counting to zero.
   */
  it("omits the count when there is none", () => {
    render(<PanelRollup label="down" tone="down" />);

    expect(screen.queryByText(/of/)).not.toBeInTheDocument();
  });

  /**
   * A state this console does not recognise is drawn neutrally and without a glyph.
   *
   * <p>Baselines are versioned independently, so a console older than the gateway it reads is the
   * ordinary case. Inventing a glyph for a word it cannot interpret would assert a meaning it does
   * not have.
   */
  it("draws an unrecognised state neutrally", () => {
    const { container } = render(<PanelRollup label="recovering" tone="recovering" />);

    expect(screen.getByText("recovering")).toHaveStyle({
      color: lightTheme.palette.text.secondary,
    });
    expect(container.querySelector("svg")).toBeNull();
  });

  /**
   * The heading element is the panel's to choose.
   *
   * <p>The cluster verdict announces itself through the live region around it, so its rollup is a
   * span; the infrastructure card is a plain section and its rollup is the heading that names it.
   * One component, two correct answers, decided by the panel rather than by this file.
   */
  it("renders as the element the panel asks for", () => {
    render(<PanelRollup component="h3" label="degraded" tone="degraded" />);

    expect(screen.getByRole("heading", { level: 3 })).toHaveTextContent("degraded");
  });
});
