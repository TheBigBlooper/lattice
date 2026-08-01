import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { ReactNode } from "react";

/** What a panel's header says. */
export interface PanelHeaderProps {
  /** What this panel is about. */
  label: string;
  /** A qualifier the reader needs, shown quietly on the right. Omitted when there is none. */
  caption?: string;
  /** The control that explains this panel, where the panel has one. */
  help?: ReactNode;
}

/**
 * The header every status panel opens with.
 *
 * <p><b>Structure rather than content.</b> The three panels used to head themselves differently -
 * two led with a metric, one with a label, and one had no rule under it at all - so the eye had to
 * re-learn the shape on each card. Making the header the same object everywhere lets the metric stay
 * in the body at whatever size it deserves, instead of shrinking to fit a header slot.
 *
 * <p><b>The label says what the panel is about</b>, which the screen never stated out loud: this
 * baseline, its history, and everyone else. The layout already encoded that split by putting the
 * first two on the left and the third on the right; this names it.
 *
 * @param props the label and its optional qualifier.
 * @returns the header.
 */
export function PanelHeader({ label, caption, help }: PanelHeaderProps) {
  return (
    <Box
      sx={{
        alignItems: "center",
        borderBottom: 1,
        borderColor: "divider",
        display: "flex",
        gap: 1,
        mb: 1.5,
        pb: 1,
      }}
    >
      <Typography
        component="h2"
        sx={{ color: "text.secondary", letterSpacing: "0.08em", textTransform: "uppercase" }}
        variant="caption"
      >
        {label}
      </Typography>
      {caption && (
        <Typography sx={{ color: "text.secondary", ml: "auto" }} variant="caption">
          {caption}
        </Typography>
      )}
      {/* After the caption, and pushed right when there is none, so the control sits in the same
          place on every panel whether or not that panel carries a caption. */}
      {help && <Box sx={{ display: "flex", ml: caption ? 0 : "auto" }}>{help}</Box>}
    </Box>
  );
}
