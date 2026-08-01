import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { darkTheme, lightTheme } from "./theme.ts";
import { THEME_CHOICE_KEY, useThemeChoice } from "./useThemeChoice.ts";

/**
 * Fakes `matchMedia` for a fixed preference, returning a handle that flips it and notifies listeners
 * the way a real browser does when the operator changes their system theme.
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
  };
}

describe("useThemeChoice", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /**
   * With nothing chosen the console follows the machine, which is what it did before there was a
   * control at all. An operator who never opens the menu sees no change in behaviour.
   */
  it("follows the system until something is chosen", () => {
    stubMatchMedia(true);

    const { result } = renderHook(() => useThemeChoice());

    expect(result.current.choice).toBe("system");
    expect(result.current.theme).toBe(darkTheme);
  });

  /**
   * Following means following: a system change mid-shift re-themes the console, so an operator does
   * not end up reading a screen that no longer matches the rest of their machine.
   */
  it("re-themes when the system preference changes and nothing is chosen", () => {
    const media = stubMatchMedia(false);
    const { result } = renderHook(() => useThemeChoice());

    act(() => media.flipTo(true));

    expect(result.current.theme).toBe(darkTheme);
  });

  /** A choice overrides the machine, which is the whole point of having one. */
  it("overrides the system once a theme is chosen", () => {
    stubMatchMedia(true);
    const { result } = renderHook(() => useThemeChoice());

    act(() => result.current.setChoice("light"));

    expect(result.current.choice).toBe("light");
    expect(result.current.theme).toBe(lightTheme);
  });

  /**
   * An override stops following, so a system change no longer moves it.
   *
   * <p>This is the cost of the control and is deliberate: an operator who has said "light" means
   * light, and having the machine overrule them would make the choice meaningless.
   */
  it("stops following the system while a theme is chosen", () => {
    const media = stubMatchMedia(false);
    const { result } = renderHook(() => useThemeChoice());
    act(() => result.current.setChoice("light"));

    act(() => media.flipTo(true));

    expect(result.current.theme).toBe(lightTheme);
  });

  /**
   * System is reachable again, which is what a two-state toggle could not offer.
   *
   * <p>Without this the first click would permanently retire following the machine, and the only way
   * back would be clearing storage - a decision taken by accident rather than on purpose.
   */
  it("goes back to following the system", () => {
    stubMatchMedia(true);
    const { result } = renderHook(() => useThemeChoice());
    act(() => result.current.setChoice("light"));

    act(() => result.current.setChoice("system"));

    expect(result.current.choice).toBe("system");
    expect(result.current.theme).toBe(darkTheme);
  });

  /** The choice survives a reload, or an operator would set it every morning. */
  it("remembers the choice across a remount", () => {
    stubMatchMedia(true);
    const first = renderHook(() => useThemeChoice());
    act(() => first.result.current.setChoice("light"));
    first.unmount();

    const { result } = renderHook(() => useThemeChoice());

    expect(result.current.choice).toBe("light");
    expect(result.current.theme).toBe(lightTheme);
  });

  /**
   * A stored value this console does not recognise is read as no choice at all.
   *
   * <p>It is one string in storage an operator can edit, and a console that trusted it would render
   * against an undefined theme rather than falling back to the machine.
   */
  it("ignores a stored value it does not understand", () => {
    stubMatchMedia(true);
    localStorage.setItem(THEME_CHOICE_KEY, "chartreuse");

    const { result } = renderHook(() => useThemeChoice());

    expect(result.current.choice).toBe("system");
    expect(result.current.theme).toBe(darkTheme);
  });

  /**
   * Storage being unavailable is not a reason to fail to render.
   *
   * <p>A browser in private mode can throw on read, and a console that let that propagate would show
   * nothing at all rather than losing a preference nobody has set yet.
   */
  it("still themes when storage cannot be read", () => {
    stubMatchMedia(true);
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("storage disabled");
    });

    const { result } = renderHook(() => useThemeChoice());

    expect(result.current.choice).toBe("system");
    expect(result.current.theme).toBe(darkTheme);
    vi.restoreAllMocks();
  });
});
