import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App.tsx";
import { lightPalette } from "./theme/tokens.ts";

describe("App", () => {
  beforeEach(() => {
    vi.stubGlobal("matchMedia", () => ({
      matches: false,
      media: "",
      addEventListener: () => {},
      removeEventListener: () => {},
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** The shell mounts and takes its surface colour from the active palette, not from a literal. */
  it("renders the shell against the themed surface", () => {
    render(<App />);

    expect(screen.getByRole("main")).toHaveStyle({ backgroundColor: lightPalette.surfacePage });
  });

  /** Without a session the console says so plainly, rather than showing an empty dashboard. */
  it("starts signed out", () => {
    render(<App />);

    expect(screen.getByRole("status")).toHaveTextContent(/signed out/i);
  });

  /**
   * Signing in swaps the verdict into the same block. The assertion that matters is that there is
   * still exactly one status block afterwards: the two screens are alternatives in one position,
   * not two things that could ever both be on screen.
   */
  it("swaps the verdict into the same block on sign-in", async () => {
    render(<App />);

    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(screen.getAllByRole("status")).toHaveLength(1);
    expect(screen.getByRole("status")).toHaveTextContent(/degraded/i);
  });
});
