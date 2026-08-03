import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";
import { memo } from "react";
import { PanelHeader } from "../../shared/PanelHeader.tsx";
import type { CardDefinition, CardReading } from "./cards.ts";
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
      <PanelHeader label={card.label} />
      <Box sx={{ alignItems: "baseline", display: "flex", gap: 1 }}>
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
      {reading.trendKey ? (
        <Sparkline
          readings={readings}
          title={`${card.label} over the last ten minutes`}
          tone={isStale ? "neutral" : reading.tone}
        />
      ) : null}
      <Typography color="text.disabled" sx={{ mt: "auto", pt: 1 }} variant="caption">
        {isStale ? "stale - retrying" : "this session"}
      </Typography>
    </Paper>
  );
});
