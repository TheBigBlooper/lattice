import LockIcon from "@mui/icons-material/Lock";
import Alert from "@mui/material/Alert";
import Typography from "@mui/material/Typography";

/** What the notice needs to say which grant is missing, and where. */
export interface ViewerNoticeProps {
  /** The baseline the grant would have to be held on. */
  baseline: string;
  /** What the operator cannot do here, as a sentence opener - e.g. "Placing an order". */
  action: string;
}

/**
 * Why a write surface is unavailable to this operator.
 *
 * <p><b>Above the controls, not beside them.</b> The reason has to be read *before* the disabled
 * inputs, or an operator scanning a greyed-out form has already concluded something is broken by
 * the time they reach the explanation. A caption below the button said the right words at the wrong
 * volume.
 *
 * <p><b>The second sentence is the load-bearing one.</b> Realm membership is deliberately
 * unsynchronised, so the same person may hold `operator` on a peer and `viewer` here, and a Shape A
 * redirect can land them exactly there. Saying the grant may exist elsewhere is the difference
 * between understanding the federation and concluding the product is broken - which is also why the
 * controls are shown at all rather than hidden.
 *
 * <p>It is one component rather than one per surface: three forms make the same statement, and
 * three copies would drift the first time the wording was tuned.
 *
 * @param props the baseline, and what cannot be done on it.
 * @returns the notice.
 */
export function ViewerNotice({ baseline, action }: ViewerNoticeProps) {
  return (
    <Alert icon={<LockIcon fontSize="small" />} severity="info">
      <Typography sx={{ fontWeight: 600 }} variant="body2">
        You are signed in to {baseline} as a viewer.
      </Typography>
      <Typography sx={{ color: "text.secondary" }} variant="caption">
        {action} needs the operator role on this baseline. You may hold it on another.
      </Typography>
    </Alert>
  );
}
