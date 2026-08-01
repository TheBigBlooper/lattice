import DnsIcon from "@mui/icons-material/Dns";
import HubIcon from "@mui/icons-material/Hub";
import type { TransitionScope } from "./activity.ts";

/** What the scope glyph needs. */
export interface ScopeIconProps {
  /** Which half of the system the entry belongs to. */
  scope: TransitionScope;
}

/**
 * The glyph for a half of the system, borrowed rather than invented.
 *
 * <p><b>Both are already in use on this console for exactly these concepts.</b> The hub is the
 * discovered-mesh panel's own glyph, sitting in the column beside this one; the server mark is what
 * the app bar carries in front of the baseline's name at the top of every screen. An operator has
 * therefore already learned both by the time they read a log line, where a scope symbol invented
 * for this panel alone would be a third thing to learn for a distinction the words already make.
 *
 * <p><b>Decorative, deliberately.</b> The scope word sits directly beside it and says the same
 * thing, so announcing the glyph would say it twice - and the word, not the glyph, is what carries
 * the scope for anyone who cannot see it.
 *
 * @param props which half of the system this is.
 * @returns the glyph.
 */
export function ScopeIcon({ scope }: ScopeIconProps) {
  const Glyph = scope === "mesh" ? HubIcon : DnsIcon;
  return <Glyph aria-hidden sx={{ flexShrink: 0, fontSize: 14 }} />;
}
