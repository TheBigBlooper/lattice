import AddCircleIcon from "@mui/icons-material/AddCircle";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import CloudOffIcon from "@mui/icons-material/CloudOff";
import LinkIcon from "@mui/icons-material/Link";
import LinkOffIcon from "@mui/icons-material/LinkOff";
import WarningIcon from "@mui/icons-material/Warning";
import type { TransitionKind } from "../mesh/activity.ts";
import { toneForTransition } from "../theme/tone.ts";

/** What the icon needs to draw itself. */
export interface TransitionIconProps {
  /** The kind of change this line reports. */
  kind: TransitionKind;
  /** Rendered size in pixels. */
  size?: number;
}

/** The glyph for each kind, chosen so the shape says what the colour says. */
const GLYPHS = {
  "peer-lost": CloudOffIcon,
  "peer-returned": CheckCircleIcon,
  "peer-joined": AddCircleIcon,
  "peer-health": WarningIcon,
  "mesh-lost": LinkOffIcon,
  "mesh-returned": LinkIcon,
} as const;

/**
 * The glyph for one kind of mesh change.
 *
 * <p><b>A second channel, not decoration.</b> The console's rule is that colour is never the sole
 * indicator - a log that distinguishes "came back" from "went quiet" by hue alone is unreadable to a
 * colour-blind operator. Every line already carries its own sentence; the shape makes the kind
 * scannable without reading each one.
 *
 * <p><b>A broken link and a quiet peer get deliberately different shapes.</b> One is a severed link,
 * the other a cloud out of reach - the same distinction the wording draws, because "we are cut off"
 * and "they are gone" are different incidents and this is the panel where they sit side by side.
 *
 * <p>Decorative to assistive technology: the sentence beside it already says everything the glyph
 * does, and announcing both would say it twice.
 *
 * @param props the kind and size.
 * @returns the glyph.
 */
export function TransitionIcon({ kind, size = 18 }: TransitionIconProps) {
  const Glyph = GLYPHS[kind] ?? WarningIcon;
  return (
    <Glyph aria-hidden sx={{ color: toneForTransition(kind), flexShrink: 0, fontSize: size }} />
  );
}
