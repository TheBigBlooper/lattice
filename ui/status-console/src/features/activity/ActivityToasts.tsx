import Alert from "@mui/material/Alert";
import Snackbar from "@mui/material/Snackbar";
import Stack from "@mui/material/Stack";
import type { ActivityEntry } from "./useActivity.ts";

/** What the toasts need. */
export interface ActivityToastsProps {
  /** The changes still worth interrupting for, oldest first. */
  toasts: ActivityEntry[];
  /** Dismisses one, leaving its log entry alone. */
  onDismiss: (id: string) => void;
}

/** Maps a transition to the severity Material draws it at. */
function severityOf(kind: string): "error" | "warning" | "success" | "info" {
  switch (kind) {
    case "peer-lost":
      return "error";
    case "mesh-lost":
    case "peer-health":
      return "warning";
    case "peer-returned":
    case "mesh-returned":
    case "peer-joined":
      return "success";
    default:
      return "info";
  }
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
  return (
    <Snackbar
      anchorOrigin={{ horizontal: "right", vertical: "bottom" }}
      // Held open by the hook's own timers rather than Material's: each toast has to retire on its
      // own clock, and a shared autoHideDuration would let a later arrival extend an earlier one.
      open={toasts.length > 0}
      sx={{ maxWidth: 360 }}
    >
      <Stack spacing={1} sx={{ width: "100%" }}>
        {toasts.map((toast) => (
          <Alert
            key={toast.id}
            onClose={() => onDismiss(toast.id)}
            severity={severityOf(toast.kind)}
            variant="outlined"
          >
            {toast.message}
          </Alert>
        ))}
      </Stack>
    </Snackbar>
  );
}
