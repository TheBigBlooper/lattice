import Chip from "@mui/material/Chip";

/** What the chip has to say about this baseline. */
export interface BaselineChipProps {
  /** Where this baseline runs. Omitted when unknown. */
  region?: string;
  /** The versioned baseline it runs. Omitted when unknown. */
  version?: string;
}

/**
 * Where this baseline runs and which version it is.
 *
 * <p><b>It exists because that information used to disappear on sign-in.</b> The signed-out card
 * carries the region and the baseline version, and they vanished the moment an operator got in -
 * exactly when they start working across several baselines and need to know which one answered.
 *
 * <p>Nothing is invented when a value is missing: with neither known the chip is not rendered at
 * all, on the same principle the signed-out card follows. A confident wrong version on a status
 * screen is worse than an absent one, because nothing else on the page contradicts it.
 *
 * @param props whatever is known about this baseline.
 * @returns the chip, or nothing when there is nothing to say.
 */
export function BaselineChip({ region, version }: BaselineChipProps) {
  const details = [region, version].filter(Boolean);
  if (details.length === 0) {
    return null;
  }

  return (
    <Chip
      label={details.join(" · ")}
      size="small"
      sx={{ fontVariantNumeric: "tabular-nums", ml: 1.5 }}
      variant="outlined"
    />
  );
}
