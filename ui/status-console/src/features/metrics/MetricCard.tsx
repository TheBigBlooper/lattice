import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";
import { memo } from "react";
import { POLL_INTERVAL_MS } from "../../api/polling.ts";
import { PanelHeader } from "../../shared/PanelHeader.tsx";
import { PanelHelp } from "../../shared/PanelHelp.tsx";
import { PANEL_HELP } from "../../shared/panelHelpContent.ts";
import type { CardDefinition, CardReading } from "./cards.ts";
import { HISTORY_LENGTH } from "./metrics.ts";
import { Sparkline } from "./Sparkline.tsx";

/** What one card needs to render itself. */
export interface MetricCardProps {
  /** The card being rendered. */
  card: CardDefinition;
  /** Its current reading. */
  reading: CardReading;
  /** The readings behind it, oldest first. */
  readings: readonly number[];
  /** True when the read behind this card has failed, so the value is a memory. */
  isStale: boolean;
}

/**
 * The height the trend occupies, reserved whether or not a line is drawn.
 *
 * <p>Kept here rather than inside the sparkline because the empty slot needs the same number, and
 * two places holding one measurement is how a layout shift comes back.
 */
const TREND_HEIGHT = 38;

/**
 * What the trend line covers, given how many readings are behind it.
 *
 * <p>The full buffer is ten minutes, but a tab opened forty seconds ago holds forty seconds - and
 * saying "last 10 minutes" then would be a claim about data the console does not have. It reports
 * what it actually holds until the window fills.
 *
 * @param count how many readings the card has.
 * @returns the window as it should read.
 */
function windowLabel(count: number): string {
  if (count < 2) {
    return "nothing recorded yet";
  }
  const seconds = (count - 1) * (POLL_INTERVAL_MS / 1000);
  if (seconds >= HISTORY_LENGTH * (POLL_INTERVAL_MS / 1000) - 1) {
    return "last 10 minutes";
  }
  if (seconds < 60) {
    return `last ${Math.round(seconds)} seconds`;
  }
  const minutes = Math.round(seconds / 60);
  return `last ${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
}

/** The palette role each tone's glyph reads in. Roles, never colours. */
const ICON_COLOUR = {
  error: "error.main",
  neutral: "text.disabled",
  success: "success.main",
  warning: "warning.main",
} as const;

/** The palette role each tone reads in. Roles, never colours. */
const VALUE_COLOUR = {
  error: "error.main",
  neutral: "text.primary",
  success: "text.primary",
  warning: "warning.main",
} as const;

/**
 * One metric, as a number with the shape of its recent movement beneath it.
 *
 * <p><b>The number is the answer and the line is context.</b> The value takes the type scale's large
 * step; the trend is fixed at a small height so the card never becomes a chart with a caption.
 *
 * <p><b>A stale card keeps its value.</b> When the read behind it has failed the number stays,
 * dimmed and labelled, rather than being replaced by an error - a mesh incident is exactly when
 * these numbers matter most and also when the read is most likely to fail, so blanking them would
 * remove them at the worst moment. This is the view's one deliberate divergence from how the other
 * operational panels treat a failed read, and it is why they block where this annotates.
 *
 * @param props the card, its reading, its history, and whether that reading is now a memory.
 * @returns the card.
 */
export const MetricCard = memo(function MetricCard({
  card,
  isStale,
  reading,
  readings,
}: MetricCardProps) {
  return (
    <Paper
      sx={{ display: "flex", flexDirection: "column", height: "100%", p: 2 }}
      variant="outlined"
    >
      <PanelHeader
        help={<PanelHelp content={PANEL_HELP[card.helpKey]} label={card.label} />}
        label={card.label}
      />
      <Box sx={{ alignItems: "center", display: "flex", gap: 1.5 }}>
        <card.icon
          aria-hidden="true"
          sx={{ color: isStale ? "text.disabled" : ICON_COLOUR[reading.tone], fontSize: 28 }}
        />
        <Box sx={{ alignItems: "baseline", display: "flex", gap: 1, minWidth: 0 }}>
          <Typography
            sx={{
              color: isStale ? "text.disabled" : VALUE_COLOUR[reading.tone],
              fontVariantNumeric: "tabular-nums",
            }}
            variant="h4"
          >
            {reading.display}
          </Typography>
          {reading.unit ? (
            <Typography color="text.secondary" variant="body2">
              {reading.unit}
            </Typography>
          ) : null}
        </Box>
      </Box>
      {/* The trend's space is reserved whether or not there is a line to draw. Rendering it only
          once two readings exist made the card grow on the second poll, which bounced the page
          under it - a layout shift caused by data arriving, which is the normal case here. */}
      <Box sx={{ height: TREND_HEIGHT, mt: 1 }}>
        <Sparkline
          readings={readings}
          title={`${card.label} over the last ten minutes`}
          tone={isStale ? "neutral" : reading.tone}
        />
      </Box>
      {/* The window on the left and the cadence on the right, so the line says what it covers and
          how often it moves without a reader having to open the help. While the buffer is still
          filling it reports how much it actually holds rather than claiming ten minutes it does
          not have. */}
      <Box sx={{ display: "flex", gap: 1, justifyContent: "space-between", mt: "auto", pt: 1 }}>
        <Typography color="text.disabled" variant="caption">
          {isStale ? "stale - retrying" : windowLabel(readings.length)}
        </Typography>
        <Typography color="text.disabled" variant="caption">
          every 10 seconds
        </Typography>
      </Box>
    </Paper>
  );
});
