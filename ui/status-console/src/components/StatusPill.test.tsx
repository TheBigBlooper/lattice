import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { lightTheme } from "../theme/theme.ts";
import { StatusPill } from "./StatusPill.tsx";

describe("StatusPill", () => {
  /**
   * The word is always present, not just the colour. A status console that distinguishes up from
   * down by hue alone is unreadable to a colour-blind operator, so this is a correctness rule
   * rather than a stylistic one.
   */
  it("names the service and its state in words", () => {
    render(<StatusPill name="orders" status="UP" />);

    expect(screen.getByText("orders")).toBeInTheDocument();
    expect(screen.getByText(/up/i)).toBeInTheDocument();
  });

  /** A down service reads as down in words too, for the same reason. */
  it("names a down service in words", () => {
    render(<StatusPill name="inventory" status="DOWN" />);

    expect(screen.getByText("inventory")).toBeInTheDocument();
    expect(screen.getByText(/down/i)).toBeInTheDocument();
  });

  /** Colour comes from the palette, so the pill re-themes with everything else. */
  it("takes its colour from the theme rather than a literal", () => {
    const { rerender } = render(<StatusPill name="orders" status="UP" />);
    expect(screen.getByRole("listitem")).toHaveStyle({ color: lightTheme.palette.success.main });

    rerender(<StatusPill name="orders" status="DOWN" />);
    expect(screen.getByRole("listitem")).toHaveStyle({ color: lightTheme.palette.error.main });
  });

  /** The icon is decorative: it reinforces the word, and must not be announced twice. */
  it("hides its icon from assistive technology", () => {
    const { container } = render(<StatusPill name="orders" status="UP" />);

    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });
});
