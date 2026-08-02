import AddCircleIcon from "@mui/icons-material/AddCircle";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import CloudOffIcon from "@mui/icons-material/CloudOff";
import DoDisturbOnIcon from "@mui/icons-material/DoDisturbOn";
import ErrorIcon from "@mui/icons-material/Error";
import LinkIcon from "@mui/icons-material/Link";
import LinkOffIcon from "@mui/icons-material/LinkOff";
import TaskAltIcon from "@mui/icons-material/TaskAlt";
import VerifiedIcon from "@mui/icons-material/Verified";
import WarningIcon from "@mui/icons-material/Warning";
import WarningAmberIcon from "@mui/icons-material/WarningAmber";
import Box from "@mui/material/Box";
import { StatusIcon } from "../../shared/index.ts";
import { type ClusterHealth, toneForTransition } from "../../theme/tone.ts";
import type { TransitionKind } from "./activity.ts";

/** What the icon needs to draw itself. */
export interface TransitionIconProps {
  /** The kind of change this line reports. */
  kind: TransitionKind;
  /** The state the subject landed in, for the one kind whose outcome is not in its name. */
  landing?: ClusterHealth;
  /** Rendered size in pixels. */
  size?: number;
}

/**
 * The glyph for each kind, chosen so the shape says what the colour says.
 *
 * <p>All eleven differ, which a test enforces rather than trusting to review. The two halves stay
 * separable too: the mesh reaches for clouds and links, this baseline's own half for the plain
 * failure and recovery marks, so a glance separates "out there" from "in here" before the scope tag
 * is read.
 */
const GLYPHS = {
  "peer-lost": CloudOffIcon,
  "peer-returned": CheckCircleIcon,
  "peer-joined": AddCircleIcon,
  "peer-health": WarningIcon,
  "mesh-lost": LinkOffIcon,
  "mesh-returned": LinkIcon,
  "service-lost": ErrorIcon,
  "service-returned": TaskAltIcon,
  "component-degraded": WarningAmberIcon,
  "component-lost": DoDisturbOnIcon,
  "component-returned": VerifiedIcon,
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
export function TransitionIcon({ kind, landing, size = 18 }: TransitionIconProps) {
  const tone = toneForTransition(kind, landing);

  // A rollup change borrows the status vocabulary rather than keeping one of its own: the three
  // states already have glyphs elsewhere, and a second set for the same three words is the drift
  // the shared-component rule prevents. The one place the log and status draw the same shape.
  if (kind === "peer-health" && landing) {
    return (
      <Box sx={{ color: tone, display: "flex", flexShrink: 0 }}>
        <StatusIcon size={size} tone={landing} />
      </Box>
    );
  }

  const Glyph = GLYPHS[kind] ?? WarningIcon;
  return <Glyph aria-hidden sx={{ color: tone, flexShrink: 0, fontSize: size }} />;
}
