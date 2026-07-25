import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { darkPalette, lightPalette } from "./tokens.ts";
import { useTheme } from "./useTheme.ts";

/**
 * Fakes `matchMedia` for a fixed preference, returning a handle that flips it and notifies
 * listeners the way a real browser does when the operator changes their system theme.
 */
function stubMatchMedia(prefersDark: boolean) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  let matches = prefersDark;

  vi.stubGlobal("matchMedia", (query: string) => ({
    matches,
    media: query,
    addEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.add(listener);
    },
    removeEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.delete(listener);
    },
  }));

  return {
    flipTo(nextMatches: boolean) {
      matches = nextMatches;
      for (const listener of listeners) {
        listener({ matches: nextMatches } as MediaQueryListEvent);
      }
    },
    listenerCount: () => listeners.size,
  };
}

describe("useTheme", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** With no dark preference the console renders the light palette. */
  it("returns the light palette when the operator prefers light", () => {
    stubMatchMedia(false);
    const { result } = renderHook(() => useTheme());
    expect(result.current).toBe(lightPalette);
  });

  /** With a dark preference the console renders the dark palette. */
  it("returns the dark palette when the operator prefers dark", () => {
    stubMatchMedia(true);
    const { result } = renderHook(() => useTheme());
    expect(result.current).toBe(darkPalette);
  });

  /**
   * The palette follows a preference change without a reload. Light and dark are reactive by
   * design: an operator who switches their system theme mid-shift should not be left reading a
   * console that no longer matches their screen.
   */
  it("re-themes when the preference changes", () => {
    const media = stubMatchMedia(false);
    const { result } = renderHook(() => useTheme());
    expect(result.current).toBe(lightPalette);

    act(() => {
      media.flipTo(true);
    });

    expect(result.current).toBe(darkPalette);
  });

  /** The listener is released on unmount, so a re-rendering console does not accumulate them. */
  it("removes its listener on unmount", () => {
    const media = stubMatchMedia(false);
    const { unmount } = renderHook(() => useTheme());
    expect(media.listenerCount()).toBe(1);

    unmount();

    expect(media.listenerCount()).toBe(0);
  });
});
