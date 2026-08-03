import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { PanelHelp } from "./PanelHelp.tsx";
import { PANEL_HELP } from "./panelHelpContent.ts";

describe("PanelHelp", () => {
  /** The control is available without competing with the panel's own reading. */
  it("offers a control named for its panel", () => {
    render(<PanelHelp content={PANEL_HELP.mesh} label="Discovered mesh" />);

    expect(screen.getByRole("button", { name: "About Discovered Mesh" })).toBeInTheDocument();
  });

  /** Nothing is said until it is asked for: this is help, not a notice. */
  it("says nothing until it is opened", () => {
    render(<PanelHelp content={PANEL_HELP.mesh} label="Discovered mesh" />);

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  /**
   * The same three headings on every panel, in the same order.
   *
   * <p>An operator learns where the answer is once rather than reading each dialog as a fresh
   * document, which is the whole reason this is one component rather than six explanations.
   */
  it("answers the same three questions in the same order", async () => {
    render(<PanelHelp content={PANEL_HELP.mesh} label="Discovered mesh" />);

    await userEvent.click(screen.getByRole("button", { name: "About Discovered Mesh" }));

    const headings = screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent);
    expect(headings).toEqual([
      "What it shows",
      "Where it comes from",
      "What it deliberately does not show",
    ]);
  });

  /**
   * The exclusion is stated, which is the section that earns the dialog.
   *
   * <p>This console makes a great many considered exclusions, and every one is recorded in a design
   * document an operator will never read. An exclusion nobody can discover in the product is
   * indistinguishable from a bug at the moment it matters.
   */
  it("states what the panel deliberately leaves out", async () => {
    render(<PanelHelp content={PANEL_HELP.mesh} label="Discovered mesh" />);

    await userEvent.click(screen.getByRole("button", { name: "About Discovered Mesh" }));

    expect(screen.getByRole("dialog")).toHaveTextContent(/last announced/i);
  });

  /**
   * It closes, and closing leaves the panel exactly as it was.
   *
   * <p>Awaited rather than asserted immediately: the dialog animates out, so it is still mounted the
   * instant the click returns. Snapshotting that instant would pin a mid-transition frame, which is
   * flaky by construction.
   */
  it("closes again", async () => {
    render(<PanelHelp content={PANEL_HELP.mesh} label="Discovered mesh" />);
    await userEvent.click(screen.getByRole("button", { name: "About Discovered Mesh" }));

    await userEvent.click(screen.getByRole("button", { name: /close/i }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  /**
   * Every panel that offers help has all three answers.
   *
   * <p>A missing one would render a heading over nothing, which reads as a screen that failed
   * rather than a panel with nothing to say - and the honest fix for having nothing to say is a
   * better sentence, not a shorter dialog.
   */
  it("has all three answers for every panel that offers help", () => {
    for (const [panel, content] of Object.entries(PANEL_HELP)) {
      expect(content.shows, panel).toBeTruthy();
      expect(content.source, panel).toBeTruthy();
      expect(content.omits, panel).toBeTruthy();
    }
  });
});

describe("the dialog title", () => {
  /** The heading reads as a title, while the panel header keeps its quieter label form. */
  it("titles the panel name without altering the label elsewhere", async () => {
    render(<PanelHelp content={PANEL_HELP.mesh} label="Failed reads" />);

    await userEvent.click(screen.getByRole("button", { name: "About Failed Reads" }));

    expect(screen.getByText("Failed Reads")).toBeInTheDocument();
  });
});
