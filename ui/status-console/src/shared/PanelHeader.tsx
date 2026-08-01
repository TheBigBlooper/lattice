import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { ReactNode } from "react";

/** What a panel's header says. */
export interface PanelHeaderProps {
  /** What this panel is about. */
  label: string;
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
export function PanelHeader({ label, help }: PanelHeaderProps) {
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
      {/* Pushed right, so the control sits in the same place on every panel.

          THE CAPTION IS GONE, and this is why: some panels carried a scrap of text here and some did
          not, so the header read as inconsistent before it read as informative. What those captions
          were doing - naming the ordering, or warning that the activity log covers this session only
          - is now said properly in the help dialog rather than in four words nobody could act on. */}
      {help && <Box sx={{ display: "flex", ml: "auto" }}>{help}</Box>}
    </Box>
  );
}
