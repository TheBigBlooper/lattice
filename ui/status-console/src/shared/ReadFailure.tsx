import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

/** What the line needs to say what failed and whether it is still being worked. */
export interface ReadFailureProps {
  /** What the read failed with, in the client's own words. */
  detail: string;
  /** Whether a read is in flight right now. */
  isRetrying?: boolean;
  /** When this list last answered, if it ever has. */
  lastGoodRead?: number;
}

/**
 * How long ago, in the coarsest unit that is still true.
 *
 * <p>Coarse on purpose: the question behind it is "blip or outage", and a seconds-accurate figure
 * ticking on an error invites an operator to watch a number instead of the cluster.
 *
 * @param since when the last successful read landed.
 * @returns a short human interval.
 */
export function ago(since: number): string {
  const seconds = Math.max(0, Math.round((Date.now() - since) / 1000));
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  const minutes = Math.round(seconds / 60);
  return minutes < 60 ? `${minutes}m ago` : `${Math.round(minutes / 60)}h ago`;
}

/**
 * A read that failed, inside a panel.
 *
 * <p>The same three facts the whole-screen failure gives - what went wrong, whether it is still
 * being tried, and how long it has been wrong - in the shape a panel can hold. Without the last
 * two, a transient blip and a service that has genuinely gone render identically, which is the one
 * distinction an operator wants at that moment.
 *
 * <p>It is a line rather than a card because the panel around it already says which read failed.
 * The card is for a failure that takes the whole console; this is for one of several panels.
 *
 * @param props what failed, and the retry state.
 * @returns the failure line.
 */
export function ReadFailure({ detail, isRetrying, lastGoodRead }: ReadFailureProps) {
  const status = [
    isRetrying ? "Retrying" : undefined,
    lastGoodRead ? `last read ${ago(lastGoodRead)}` : undefined,
  ].filter(Boolean);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, py: 1 }}>
      <Typography sx={{ color: "error.main" }} variant="body2">
        {detail}
      </Typography>
      {status.length > 0 && (
        <Typography sx={{ color: "text.secondary" }} variant="caption">
          {status.join(" · ")}
        </Typography>
      )}
    </Box>
  );
}
