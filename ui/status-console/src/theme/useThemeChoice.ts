import type { Theme } from "@mui/material/styles";
import { useCallback, useEffect, useState } from "react";
import { darkTheme, lightTheme } from "./theme.ts";

/** What an operator can ask for. `system` is the default and means "whatever this machine says". */
export type ThemeChoice = "system" | "light" | "dark";

/** Where the choice is kept. Exported so a test can plant a value rather than guess the key. */
export const THEME_CHOICE_KEY = "lattice.theme";

const DARK_QUERY = "(prefers-color-scheme: dark)";

/** The three the console understands. Anything else in storage is read as no choice at all. */
const CHOICES: ReadonlySet<string> = new Set(["system", "light", "dark"]);

/** What the console needs to render a theme and to offer the choice back. */
export interface ThemeControl {
  /** The theme to render. */
  theme: Theme;
  /** What the operator has asked for, which is not the same as what is being rendered. */
  choice: ThemeChoice;
  /** Records a new choice, and remembers it. */
  setChoice: (choice: ThemeChoice) => void;
}

/**
 * Reads the stored choice, treating anything unexpected as none.
 *
 * <p>Storage can throw outright - a browser in private mode is the usual case - and a console that
 * let that propagate would render nothing at all rather than lose a preference nobody had set. It
 * can also hold a string an operator has edited by hand, which is why the value is checked against
 * the three this console knows rather than trusted.
 */
function storedChoice(): ThemeChoice {
  try {
    const stored = localStorage.getItem(THEME_CHOICE_KEY);
    return stored && CHOICES.has(stored) ? (stored as ThemeChoice) : "system";
  } catch {
    return "system";
  }
}

/**
 * The theme to render, the choice behind it, and a way to change it.
 *
 * <p><b>Three states rather than two, and that is the whole design.</b> A plain light/dark toggle
 * would retire following the machine the moment it was first touched, with no way back except
 * clearing storage - so a behaviour this console chose deliberately would be dropped by accident
 * rather than decided against. Keeping `system` reachable means an operator who never opens the menu
 * sees exactly what they saw before there was one, and an operator who does can return.
 *
 * <p><b>Following means following.</b> While the choice is `system` a change to the machine's
 * preference re-themes the console live, so an operator switching their system theme mid-shift is
 * not left reading a screen that no longer matches the rest of it. An explicit choice stops that,
 * which is the cost of having said something: being overruled by the machine would make the choice
 * meaningless.
 *
 * <p><b>The preference is per origin, so per baseline.</b> Each console is served on its own
 * address, so a choice made on one baseline does not reach the others and an operator watching three
 * sets it three times. There is no clean fix here - the consoles share no backend for operator
 * preferences - so it is a known cost rather than an oversight.
 *
 * @returns the active theme, the current choice, and the setter.
 */
export function useThemeChoice(): ThemeControl {
  const [choice, setStoredChoice] = useState<ThemeChoice>(storedChoice);
  const [prefersDark, setPrefersDark] = useState(() => matchMedia(DARK_QUERY).matches);

  // Listening regardless of the current choice, rather than only while following. The listener is
  // cheap, and attaching it conditionally would mean a console that had been on an explicit choice
  // and was switched back to `system` sat on a stale reading until the next system change.
  useEffect(() => {
    const query = matchMedia(DARK_QUERY);
    const onChange = (event: MediaQueryListEvent) => {
      setPrefersDark(event.matches);
    };
    query.addEventListener("change", onChange);
    return () => {
      query.removeEventListener("change", onChange);
    };
  }, []);

  const setChoice = useCallback((next: ThemeChoice) => {
    setStoredChoice(next);
    try {
      localStorage.setItem(THEME_CHOICE_KEY, next);
    } catch {
      // A preference that cannot be written is still honoured for this session. Refusing the change
      // because it could not be remembered would be a worse answer than remembering it briefly.
    }
  }, []);

  const dark = choice === "system" ? prefersDark : choice === "dark";

  return { choice, setChoice, theme: dark ? darkTheme : lightTheme };
}
