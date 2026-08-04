import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Typography from "@mui/material/Typography";

/** What the dialog needs to say what is unreachable and how long it has been. */
export interface ConnectionLostProps {
  /** What the read failed with, in the client's own words. Absent means the read is fine. */
  detail?: string | undefined;
  /** Whether a read is in flight right now. */
  isRetrying?: boolean;
  /** When this read last succeeded, if it ever has. */
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
 * A screen whose service cannot be reached, blocked until it can.
 *
 * <p><b>It blocks on purpose, and it cannot be dismissed.</b> Behind it sits a form and a stale
 * list. An operator who dismissed this could fill that form in and submit it into a service that is
 * not answering, or read the list as current when it is a memory - so the screen is held rather
 * than merely annotated. This is the one place the console takes the page away, and it does it
 * because the alternative is letting an operator act on something that is not there.
 *
 * <p><b>It holds the screen, not the console.</b> Defect note. Symptom: an operator on Orders or
 * Inventory whose service stopped answering could not get back to Status. A dialog is modal over the
 * whole viewport by default, so its backdrop covered the app bar too and every tab with it - which
 * left the one screen still working, served by a different service, unreachable from the one that
 * was not. Leaving is the safe action here and it was the only one being prevented. So this sits
 * below the app bar rather than above it, and does not hold focus: the form and the stale list stay
 * unusable, and the way out stays open.
 *
 * <p><b>It closes itself when the read succeeds.</b> There is no acknowledge button, because
 * acknowledging would not change anything: the poll is already retrying, and the honest end of this
 * state is the service answering. Saying how long since the last good read is what separates a blip
 * from an outage while an operator waits.
 *
 * @param props what failed, and the retry state.
 * @returns the blocking dialog, or nothing while the read is healthy.
 */
export function ConnectionLost({ detail, isRetrying, lastGoodRead }: ConnectionLostProps) {
  if (!detail) {
    return null;
  }

  const status = [
    isRetrying ? "Retrying" : undefined,
    lastGoodRead ? `last read ${ago(lastGoodRead)}` : undefined,
  ].filter(Boolean);

  return (
    // No onClose, and the backdrop does not dismiss: there is no way out of this state except the
    // service answering, so offering one would only let an operator hide it.
    <Dialog
      aria-labelledby="connection-lost"
      // Focus is not trapped, so tabbing reaches the navigation above rather than cycling inside a
      // dialog with nothing actionable in it.
      disableEnforceFocus
      open
      // Below the app bar (1100) rather than the default dialog layer above it. The backdrop then
      // covers exactly what must not be acted on and nothing else.
      sx={{ zIndex: 1050 }}
    >
      <DialogTitle id="connection-lost" sx={{ color: "error.main" }}>
        Cannot reach this service
      </DialogTitle>
      <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
        <Typography sx={{ color: "text.secondary" }} variant="body2">
          {detail}
        </Typography>
        <Box sx={{ alignItems: "center", display: "flex", gap: 1.5 }}>
          {isRetrying && <CircularProgress size={16} />}
          {status.length > 0 && (
            <Typography sx={{ color: "text.secondary" }} variant="caption">
              {status.join(" · ")}
            </Typography>
          )}
        </Box>
        <Typography sx={{ color: "text.secondary" }} variant="caption">
          This screen will return on its own when the service answers.
        </Typography>
      </DialogContent>
    </Dialog>
  );
}
