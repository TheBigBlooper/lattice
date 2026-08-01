import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { ArrivalCard } from "../shared/index.ts";
import type { SignedOutReason } from "./useSession.ts";

/**
 * What the card says about why the operator is looking at it.
 *
 * <p>One sentence swapped, not a pile of conditionals: the card serves three arrivals - never
 * signed in, followed a peer redirect, and session expired - and only the last of those is
 * something that happened *to* the operator and needs accounting for.
 */
const REASONS = {
  expired: "Your session here expired. Signing in again picks up where you left off.",
  none: "Each baseline authenticates against its own identity provider. A session elsewhere does not carry here.",
} as const;

/** What the signed-out screen needs to explain itself and offer a way forward. */
export interface SignedOutProps {
  /** The baseline being signed in to, named because an operator may work across several. */
  baseline: string;
  /** Where this baseline runs, when the image was built with it. Omitted when empty. */
  region?: string;
  /** The baseline version this image was built for. Omitted when empty. */
  baselineVersion?: string;
  /** Starts the sign-in flow against this baseline's own identity provider. */
  onSignIn: () => void;
  /** The console to return to, when the browser confirmed where the operator came from. */
  returnTo?: string | undefined;
  /** Why the session ended, when one ended. Absent means there was never a session here to lose. */
  reason?: SignedOutReason | undefined;
}

/**
 * The signed-out screen.
 *
 * This is a real state, not an error. Identity belongs to the baseline that owns the data, so an
 * operator arriving without a session here is the system working correctly - including an operator
 * redirected from a peer, who may hold a perfectly good session somewhere else.
 *
 * **It is a landing page, not a transient state.** The peer redirect made it the first thing an
 * arriving operator sees, which is why it is a centred card rather than the block it used to share
 * with the cluster verdict. That earlier arrangement existed to stop the layout jumping on sign-in;
 * the guarantee was already partial, since signing in reveals the whole mesh panel regardless, and a
 * first impression is worth more than the remainder.
 *
 * **It names the baseline, and says why that matters.** The question an operator arriving by
 * redirect is actually asking is *which* baseline they are signing in to - and the sentence about a
 * session not carrying is what prevents the peer redirect being read as a broken federation the
 * first time it refuses someone.
 *
 * **Region and version are build config, or absent.** Both come from an endpoint that requires a
 * token, so before sign-in they cannot be read - they arrive inlined into the image or not at all.
 * An image built without them shows no pill rather than a confident guess, because this is the one
 * screen an operator has no way to cross-check.
 *
 * @param props the baseline name, whatever the image knows about it, and the sign-in action.
 * @returns the signed-out screen.
 */
export function SignedOut({
  baseline,
  region,
  baselineVersion,
  onSignIn,
  returnTo,
  reason,
}: SignedOutProps) {
  const details = [region, baselineVersion].filter(Boolean);

  return (
    /* Identity comes from the shell. Only what the image was actually built with reaches it: region
       and baseline version cannot be read before sign-in, since the endpoint reporting them needs a
       token, so they arrive as build config or not at all - and an image built without them shows
       no identity line rather than a confident guess on the one screen an operator cannot
       cross-check. */
    <ArrivalCard
      identity={details.length > 0 ? details.join(" · ") : undefined}
      state="neutral"
      title={baseline}
    >
      <Button fullWidth onClick={onSignIn} size="medium" sx={{ my: 3 }} variant="contained">
        Sign in
      </Button>

      {/* One sentence, chosen by why the operator is here. An expiry happened TO them and needs
            accounting for; the per-baseline explanation is what the other two arrivals need, and
            saying it to somebody whose session just lapsed would answer a question they did not
            ask. */}
      <Typography sx={{ color: "text.secondary", display: "block" }} variant="caption">
        {REASONS[reason ?? "none"]}
      </Typography>

      {/* Offered only where the browser confirmed the origin, so an operator who followed a
            redirect and does not want to sign in here is not stranded. Same confirmation as the
            refusal screen: a link this console cannot vouch for is not shown at all. */}
      {returnTo && (
        <Button href={returnTo} size="small" sx={{ mt: 2 }}>
          Back
        </Button>
      )}
    </ArrivalCard>
  );
}
