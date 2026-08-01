import Alert from "@mui/material/Alert";
import Snackbar from "@mui/material/Snackbar";
import Stack from "@mui/material/Stack";
import { useEffect, useState } from "react";
import { ENTER_RIGHT, EXIT_MS, EXIT_RIGHT } from "../../shared/index.ts";
import { type ClusterHealth, toneForTransition } from "../../theme/tone.ts";
import type { ActivityEntry } from "./useActivity.ts";

/** What the toasts need. */
export interface ActivityToastsProps {
  /** The changes still worth interrupting for, oldest first. */
  toasts: ActivityEntry[];
  /** Dismisses one, leaving its log entry alone. */
  onDismiss: (id: string) => void;
}

/** The alert severity each palette path maps onto. Alert takes a fixed set; the theme does not. */
const SEVERITIES = {
  "error.main": "error",
  "warning.main": "warning",
  "success.main": "success",
  "text.secondary": "info",
} as const;

/**
 * Maps a transition to the severity Material draws it at, THROUGH the shared tone.
 *
 * <p><b>This used to be a second mapping over kinds, and it was incomplete.</b> It named the six
 * mesh kinds and defaulted everything else to `info`, so all five local kinds toasted blue while
 * the very same event rendered red in the log directly beneath. That is the drift the shared tone
 * function exists to prevent, arriving by a route its reasoning did not anticipate: not a tuned
 * colour, but a set of kinds added later that this copy never learned about, and defaulted rather
 * than failed.
 *
 * <p>Alert takes a fixed severity set while the tone returns a palette path, so the two are not the
 * same type - but the translation between them belongs in one place, derived from the one mapping,
 * rather than as a parallel switch that can fall out of step again. `info` now means only what the
 * tone itself could not classify, which is a kind this console does not recognise.
 */
function severityOf(
  kind: string,
  landing?: ClusterHealth
): "error" | "warning" | "success" | "info" {
  return SEVERITIES[toneForTransition(kind, landing)];
}

/**
 * The changes worth interrupting for, as they happen.
 *
 * <p><b>A toast is an interruption, so it ends.</b> What it cannot carry is the sequence - which
 * order things happened in, and when - so everything shown here is also written to the activity log
 * beside the mesh. Dismissing a toast never removes the entry.
 *
 * <p><b>They stack rather than replace</b>, and are capped: two peers can move on one poll, and an
 * operator who is told about the first and not the second has been misled by the interface rather
 * than by the mesh. The cap stops a burst covering the page it is reporting on.
 *
 * <p>One region holds them all, so a screen reader announces them in the order they arrived rather
 * than fighting over one live region per toast.
 *
 * @param props the live toasts and the dismiss action.
 * @returns the toast stack.
 */
export function ActivityToasts({ toasts, onDismiss }: ActivityToastsProps) {
  const shown = useLeavingToasts(toasts);

  return (
    <Snackbar
      anchorOrigin={{ horizontal: "right", vertical: "bottom" }}
      // Held open by the hook's own timers rather than Material's: each toast has to retire on its
      // own clock, and a shared autoHideDuration would let a later arrival extend an earlier one.
      //
      // Held open while anything is leaving, too, or the last toast's exit would be cut off by the
      // Snackbar closing underneath it.
      open={shown.length > 0}
      // The width is given here rather than left to the content. A burst arrives together - a
      // baseline going down produces its rollup line and its service lines at once - and at the
      // content's own width a sentence as short as "hub-east came back" wrapped to three lines,
      // which is what made a row of them run out of screen.
      sx={{ maxWidth: 380, width: "calc(100% - 48px)" }}
    >
      {/* An explicit column. These stack downwards, never along the bottom edge: laid out in a row
          the third arrival was clipped by the viewport with its dismiss control out of reach, and
          the one an operator most needs to read is the newest. */}
      <Stack direction="column" spacing={1} sx={{ width: "100%" }}>
        {shown.map((toast) => (
          <Alert
            key={toast.id}
            // A leaving toast keeps its dismiss control but can no longer be dismissed twice: it is
            // already gone from the hook's list, and calling again would report an id nothing holds.
            onClose={toast.leaving ? undefined : () => onDismiss(toast.id)}
            severity={severityOf(toast.kind, toast.landing)}
            sx={toast.leaving ? EXIT_RIGHT : ENTER_RIGHT}
            variant="outlined"
          >
            {toast.message}
          </Alert>
        ))}
      </Stack>
    </Snackbar>
  );
}

/** A toast on screen, and whether it is on its way out. */
interface ShownToast extends ActivityEntry {
  /** True once the hook has dropped it and it is only still here to animate away. */
  leaving?: boolean;
}

/**
 * Keeps a dismissed toast mounted long enough to leave.
 *
 * <p><b>Why this exists at all.</b> The hook that owns the toasts drops one the instant its timer
 * expires or an operator dismisses it, so the element unmounted mid-air and the exit animation had
 * nowhere to run. This holds the departed entry in place - in its original position, not appended,
 * so nothing below it jumps up before it has gone.
 *
 * <p><b>Removal is on a timer, not on the animation ending.</b> An animation turned off by reduced
 * motion never fires its end event, so a component waiting for one would keep a dismissed toast on
 * screen forever. That failure would be silent, and would land only on the machines least able to
 * tolerate a toast that will not go away.
 *
 * @param toasts the live toasts, as the hook holds them.
 * @returns those toasts plus any still animating out, in the order they were shown.
 */
function useLeavingToasts(toasts: ActivityEntry[]): ShownToast[] {
  const [shown, setShown] = useState<ShownToast[]>(toasts);

  useEffect(() => {
    setShown((current) => {
      const live = new Set(toasts.map((toast) => toast.id));
      const known = new Set(current.map((toast) => toast.id));
      // Anything gone is marked in place rather than removed, so it fades where it sat.
      const kept = current.map((toast) =>
        live.has(toast.id) ? { ...toast, leaving: false } : { ...toast, leaving: true }
      );
      const arrived = toasts.filter((toast) => !known.has(toast.id));
      return [...kept, ...arrived];
    });
  }, [toasts]);

  useEffect(() => {
    if (!shown.some((toast) => toast.leaving)) {
      return;
    }
    const timer = setTimeout(
      () => setShown((current) => current.filter((toast) => !toast.leaving)),
      EXIT_MS
    );
    return () => clearTimeout(timer);
  }, [shown]);

  return shown;
}
