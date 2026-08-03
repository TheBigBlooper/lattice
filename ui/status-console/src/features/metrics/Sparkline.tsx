import Box from "@mui/material/Box";
import type { Theme } from "@mui/material/styles";
import { memo } from "react";
import type { Tone } from "./cards.ts";

/** What the sparkline needs to draw one series. */
export interface SparklineProps {
  /** The readings, oldest first. */
  readings: readonly number[];
  /** What the current value means, which selects the stroke's palette role. */
  tone: Tone;
  /** What the line is, for anyone who cannot see it. */
  title: string;
}

/** The drawing box, in the viewBox's own units. */
const WIDTH = 220;
const HEIGHT = 40;

/** Keeps a flat series off the floor of the box, where it would read as missing rather than steady. */
const FLAT_LINE = HEIGHT / 2;

/**
 * How each tone's stroke is read from the theme.
 *
 * <p><b>Resolved through the theme rather than written as an `sx` palette path.</b> `sx` maps a
 * palette key like `success.main` only for the style props it knows - `color`, `bgcolor`,
 * `borderColor` and their kin. `stroke` is not among them, so the key was passed through as literal
 * CSS, which is invalid: the line drew with no stroke at all and the card showed an empty gap. The
 * component test did not catch it, because an invisible element is still in the document.
 */
const STROKE: Record<Tone, (theme: Theme) => string> = {
  error: (theme) => theme.palette.error.main,
  neutral: (theme) => theme.palette.text.disabled,
  success: (theme) => theme.palette.success.main,
  warning: (theme) => theme.palette.warning.main,
};

/** Maps readings onto the drawing box, scaled to their own range. */
function points(readings: readonly number[]): string {
  if (readings.length < 2) {
    return "";
  }
  const highest = Math.max(...readings);
  const lowest = Math.min(...readings);
  const span = highest - lowest;
  const step = WIDTH / (readings.length - 1);
  return readings
    .map((reading, index) => {
      // A series that never moved has no range to scale against, so it draws through the middle
      // rather than dividing by zero and vanishing.
      const y = span === 0 ? FLAT_LINE : HEIGHT - ((reading - lowest) / span) * HEIGHT;
      return `${(index * step).toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

/**
 * A small trend line for one series.
 *
 * <p><b>It is deliberately subordinate.</b> The card's number is the answer; this says which way it
 * has been moving. It is drawn at a fixed height so a card never becomes a chart with a caption,
 * and it carries no axes, ticks or labels - at this size they would be decoration rather than
 * information.
 *
 * <p>Scaled to its own readings rather than to zero, because the interesting thing about a counter
 * that sits at 46 is the shape of the last ten minutes, not its distance from the origin.
 *
 * @param props the readings, the tone selecting the stroke, and the accessible title.
 * @returns the sparkline, or nothing at all until two readings exist.
 */
export const Sparkline = memo(function Sparkline({ readings, title, tone }: SparklineProps) {
  const path = points(readings);
  if (!path) {
    return null;
  }
  return (
    <Box
      aria-label={title}
      component="svg"
      preserveAspectRatio="none"
      role="img"
      // Fills the slot the card reserved rather than setting its own height, so the space is the
      // same whether a line is drawn or not.
      sx={(theme) => ({
        display: "block",
        height: "100%",
        stroke: STROKE[tone](theme),
        width: "100%",
      })}
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
    >
      <title>{title}</title>
      <polyline fill="none" points={path} strokeWidth={2} vectorEffect="non-scaling-stroke" />
    </Box>
  );
});
