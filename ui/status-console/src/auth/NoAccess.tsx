import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { ArrivalCard } from "../shared/index.ts";

/** What the screen needs to explain a refusal and offer a way onward. */
export interface NoAccessProps {
  /** The baseline that refused, named because the operator may have just arrived from another. */
  baseline: string;
  /** Ends the session at the provider, so the operator can arrive as somebody else. */
  onSignOut: () => void;
  /** The console to return to, when the browser confirmed where the operator came from. */
  returnTo?: string | undefined;
}

/**
 * The screen an operator meets when they are signed in to a baseline but hold no role on it.
 *
 * **This is the system working, and it must read that way.** Role names are standard across
 * baselines but membership is deliberately unsynchronized, so an operator holds a separate grant per
 * baseline and a redirect can legitimately land them somewhere they cannot act. It is the most
 * likely first contact anyone has with per-baseline identity.
 *
 * **It exists because the console used to call this unreachable.** A refusal fell through to
 * "Cannot reach this baseline", which reports a broken federation when the baseline is serving
 * perfectly and only the grant is missing - the single most misleading thing this console could say
 * about a mesh. Naming the baseline as reachable is the whole point of the screen.
 *
 * The way back appears only when the browser confirmed where the operator came from; a link this
 * console cannot vouch for is not offered at all.
 *
 * @param props the refusing baseline, the sign-out action, and any confirmed origin.
 * @returns the refusal screen.
 */
export function NoAccess({ baseline, onSignOut, returnTo }: NoAccessProps) {
  return (
    <ArrivalCard>
      <Typography sx={{ mt: 2.5 }} variant="h5">
        No access on {baseline}
      </Typography>
      <Typography sx={{ color: "text.secondary", mt: 1.5 }} variant="body2">
        You are signed in, but your account here holds no role. Access is granted per baseline, so a
        role on another baseline does not carry to this one.
      </Typography>

      {/* Back leads, and carries the emphasis. An operator who has just been refused almost always
          wants the console they came from rather than a different identity, and signing out of a
          baseline that is working is rarely the thing that helps. Where no origin was confirmed,
          Back is absent entirely and Sign out is left holding the screen on its own. */}
      <Box sx={{ display: "flex", gap: 1, justifyContent: "center", my: 3.5 }}>
        {returnTo && (
          <Button href={returnTo} variant="contained">
            Back
          </Button>
        )}
        <Button onClick={onSignOut} variant={returnTo ? "text" : "contained"}>
          Sign out
        </Button>
      </Box>

      <Typography sx={{ color: "text.secondary", display: "block" }} variant="caption">
        {baseline} is reachable and healthy. Only your access to it is missing.
      </Typography>
    </ArrivalCard>
  );
}
