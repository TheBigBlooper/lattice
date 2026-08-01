import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ThemeMenu } from "./ThemeMenu.tsx";

describe("ThemeMenu", () => {
  /** The control names the current choice, since its glyph alone cannot say which of three it is. */
  it("names the current choice", () => {
    render(<ThemeMenu choice="system" onChoose={() => {}} />);

    expect(screen.getByRole("button", { name: /theme: follow this machine/i })).toBeInTheDocument();
  });

  /** Nothing is shown until it is asked for: this is a preference, not a notice. */
  it("says nothing until it is opened", () => {
    render(<ThemeMenu choice="system" onChoose={() => {}} />);

    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  /**
   * All three are offered, and System is one of them.
   *
   * <p>That third option is the reason this is a menu rather than a toggle: without it, the first
   * click would permanently retire following the machine, and the only way back would be clearing
   * storage.
   */
  it("offers system, light and dark", async () => {
    render(<ThemeMenu choice="system" onChoose={() => {}} />);

    await userEvent.click(screen.getByRole("button", { name: /theme/i }));

    expect(screen.getByRole("menuitemradio", { name: /follow this machine/i })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: /^light$/i })).toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: /^dark$/i })).toBeInTheDocument();
  });

  /** The active one is marked, so the menu answers "what am I on" as well as "what can I pick". */
  it("marks the active choice", async () => {
    render(<ThemeMenu choice="dark" onChoose={() => {}} />);

    await userEvent.click(screen.getByRole("button", { name: /theme/i }));

    expect(screen.getByRole("menuitemradio", { name: /^dark$/i })).toBeChecked();
    expect(screen.getByRole("menuitemradio", { name: /^light$/i })).not.toBeChecked();
  });

  /** Choosing reports the choice and closes, so the bar returns to what it was. */
  it("reports the chosen theme", async () => {
    const onChoose = vi.fn();
    render(<ThemeMenu choice="system" onChoose={onChoose} />);
    await userEvent.click(screen.getByRole("button", { name: /theme/i }));

    await userEvent.click(screen.getByRole("menuitemradio", { name: /^light$/i }));

    expect(onChoose).toHaveBeenCalledWith("light");
  });
});
