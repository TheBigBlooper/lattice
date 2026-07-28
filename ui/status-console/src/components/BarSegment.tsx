import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

/** One labelled fact in the title bar. */
export interface BarSegmentProps {
  /** What the value is. */
  label: string;
  /** The value itself. The segment renders nothing when there is none. */
  value?: string;
  /**
   * Whether this is the bar subject, drawn at full weight rather than as detail.
   *
   * <p>It also becomes the page heading. Which baseline is being looked at is what this page is
   * about, so it is the one thing here that should be a heading rather than a label and a value.
   */
  primary?: boolean;
}

/**
 * One labelled fact in the title bar.
 *
 * <p>The same vocabulary the status panels use - a micro-label in uppercase over its value - so the
 * bar reads as part of the page rather than a different piece of design sitting on top of it.
 *
 * <p>Nothing is rendered when the value is missing, rather than a label over a blank. A bar that
 * says <em>REGION</em> with nothing under it invites an operator to wonder what went wrong, when the
 * honest answer is that this console was simply never told.
 *
 * @param props the label, its value, and whether it is the bar's subject.
 * @returns the segment, or nothing when there is no value.
 */
export function BarSegment({ label, value, primary = false }: BarSegmentProps) {
  if (!value) {
    return null;
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column", lineHeight: 1.25, minWidth: 0 }}>
      <Typography
        sx={{
          color: "text.secondary",
          fontSize: "0.63rem",
          letterSpacing: "0.09em",
          textTransform: "uppercase",
        }}
      >
        {label}
      </Typography>
      <Typography
        component={primary ? "h1" : "span"}
        sx={{
          fontVariantNumeric: "tabular-nums",
          fontWeight: primary ? 500 : 400,
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
        }}
        variant={primary ? "body1" : "body2"}
      >
        {value}
      </Typography>
    </Box>
  );
}
