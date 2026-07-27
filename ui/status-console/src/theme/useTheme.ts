import type { Theme } from "@mui/material/styles";
import { useEffect, useState } from "react";
import { darkTheme, lightTheme } from "./theme.ts";

const DARK_QUERY = "(prefers-color-scheme: dark)";

/**
 * Returns the Material UI theme matching the operator's system colour preference, and follows it
 * when that preference changes.
 *
 * Light and dark are reactive rather than a build-time or menu choice: an operator who switches
 * their system theme mid-shift should not be left reading a console that no longer matches the rest
 * of their screen. The listener is released on unmount, because a console re-rendering on every
 * status tick would otherwise accumulate them.
 *
 * @returns the active {@link Theme}. Components read colours through it, never from a literal.
 */
export function useTheme(): Theme {
  const [prefersDark, setPrefersDark] = useState(() => matchMedia(DARK_QUERY).matches);

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

  return prefersDark ? darkTheme : lightTheme;
}
