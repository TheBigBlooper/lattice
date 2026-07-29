import Typography from "@mui/material/Typography";
import { ArrivalCard, ago } from "../shared/index.ts";

/** What the screen needs to say what is wrong and how long it has been wrong. */
export interface UnreachableProps {
  /** The baseline that did not answer, named because an operator may work across several. */
  baseline: string;
  /** The headline, which distinguishes a rejected session from a baseline that did not answer. */
  headline: string;
  /** What the read actually failed with, in the client's own words. */
  detail: string;
  /** Whether a read is in flight right now. */
  isRetrying?: boolean;
  /** When this baseline last answered, if it ever has. */
  lastGoodRead?: number;
}

/**
 * The screen for a baseline that did not answer.
 *
 * <p><b>The same card as signing in and being refused.</b> All three are the console explaining its
 * own state to an operator who has just lost the dashboard, and they were not built to look like it
 * - this one was a full-width banner while the other two were centred cards, so the same product
 * spoke in two visual registers depending on which thing had gone wrong. Sharing the shell rather
 * than resembling it is what stops that drifting again.
 *
 * <p><b>It says whether it is still trying, and how long it has been wrong.</b> Without those a
 * transient blip and a baseline that has genuinely gone render identically, and telling them apart
 * is the only thing an operator wants at that moment. Both come from the query layer, which was
 * already tracking them and being ignored.
 *
 * <p><b>There is no retry button.</b> The console is already retrying, on a poll it does not need
 * an operator to drive; a button would be a second path to the same request and would imply the
 * first had stopped.
 *
 * @param props the baseline, what went wrong, and the retry state.
 * @returns the unreachable screen.
 */
export function Unreachable({
  baseline,
  headline,
  detail,
  isRetrying,
  lastGoodRead,
}: UnreachableProps) {
  const status = [
    isRetrying ? "Retrying" : undefined,
    lastGoodRead ? `last read ${ago(lastGoodRead)}` : undefined,
  ].filter(Boolean);

  return (
    <ArrivalCard>
      <Typography sx={{ color: "error.main", mt: 2.5 }} variant="h5">
        {headline}
      </Typography>
      <Typography sx={{ color: "text.secondary", mt: 0.5 }} variant="body2">
        {baseline}
      </Typography>

      <Typography sx={{ color: "text.secondary", my: 3.5 }} variant="body2">
        {detail}
      </Typography>

      {/* Absent entirely rather than rendered empty: on a first read that has never succeeded there
          is no age to give, and a zero would date the outage to 1970. */}
      {status.length > 0 && (
        <Typography sx={{ color: "text.secondary", display: "block" }} variant="caption">
          {status.join(" · ")}
        </Typography>
      )}
    </ArrivalCard>
  );
}
