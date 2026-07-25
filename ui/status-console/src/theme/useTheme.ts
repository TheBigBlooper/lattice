import { useEffect, useState } from "react";
import { darkPalette, lightPalette, type Palette } from "./tokens.ts";

const DARK_QUERY = "(prefers-color-scheme: dark)";

/**
 * Returns the palette matching the operator's system colour preference, and follows it when that
 * preference changes.
 *
 * Light and dark are reactive rather than a build-time or menu choice: an operator who switches
 * their system theme mid-shift should not be left reading a console that no longer matches the rest
 * of their screen. The listener is released on unmount, because a console re-rendering on every
 * status tick would otherwise accumulate them.
 *
 * @returns the active {@link Palette}. Components read colours from this, never from a literal.
 */
export function useTheme(): Palette {
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

  return prefersDark ? darkPalette : lightPalette;
}
