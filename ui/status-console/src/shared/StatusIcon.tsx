import CancelIcon from "@mui/icons-material/Cancel";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import WarningIcon from "@mui/icons-material/Warning";

/** What glyph to draw, and how large. */
export interface StatusIconProps {
  /** Which state the glyph stands for. */
  tone: "ready" | "degraded" | "down";
  /** Edge length in pixels; the caller passes a value from the type scale. */
  size: number;
}

/** One glyph per state, from Material's own set. */
const ICONS = {
  ready: CheckCircleIcon,
  degraded: WarningIcon,
  down: CancelIcon,
} as const;

/**
 * The glyph paired with every status word.
 *
 * It is always decorative and always hidden from assistive technology: the word beside it carries
 * the meaning, and announcing both would say the same thing twice. Its job is for sighted operators
 * scanning a wall of services, where a shape registers faster than a word does.
 *
 * The three shapes are deliberately unalike in outline - a circle with a tick, a triangle, and a
 * circle with a cross - so a state survives being read by an operator who cannot resolve the
 * colours. That property now comes from Material's icon set rather than from glyphs drawn by hand.
 *
 * It inherits its colour from the element around it, which is what keeps the theme the single
 * source of colour.
 *
 * @param props which glyph to draw and how large.
 * @returns the icon, hidden from assistive technology.
 */
export function StatusIcon({ tone, size }: StatusIconProps) {
  const Icon = ICONS[tone];
  return <Icon aria-hidden="true" sx={{ fontSize: size }} />;
}
