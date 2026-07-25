import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App.tsx";
import { lightPalette } from "./theme/tokens.ts";

describe("App", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** The shell mounts and takes its surface colour from the active palette, not from a literal. */
  it("renders the shell against the themed surface", () => {
    vi.stubGlobal("matchMedia", () => ({
      matches: false,
      media: "",
      addEventListener: () => {},
      removeEventListener: () => {},
    }));

    render(<App />);

    const shell = screen.getByRole("main");
    expect(shell).toHaveStyle({ backgroundColor: lightPalette.surfacePage });
    expect(screen.getByRole("heading", { name: /status console/i })).toBeInTheDocument();
  });
});
