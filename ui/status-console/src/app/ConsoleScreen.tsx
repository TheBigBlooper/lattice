import Typography from "@mui/material/Typography";
import type { ApiError } from "../api/client.ts";
import type { components } from "../api/generated/v1.ts";
import type { Peer } from "../api/usePeers.ts";
import { NoAccess } from "../auth/NoAccess.tsx";
import { SignedOut } from "../auth/SignedOut.tsx";
import type { Session } from "../auth/useSession.ts";
import type { ConsoleConfig } from "../config.ts";
import type { ActivityEntry } from "../features/mesh/index.ts";
import { StatusView } from "../features/status/index.ts";
import { StatusBlock } from "../shared/index.ts";
import { LoadingScreen } from "./LoadingScreen.tsx";

/** Everything the choice of screen depends on. */
export interface ConsoleScreenProps {
  /** The console's configuration, for the screens that cannot ask the gateway. */
  config: ConsoleConfig;
  /** The operator's session with this baseline. */
  session: Session;
  /** This baseline as its gateway reports it, once read. */
  baseline?: components["schemas"]["Baseline"];
  /** Why the baseline could not be read, when it could not. */
  error?: ApiError | null;
  /** Whether the first read is still outstanding. */
  isPending: boolean;
  /** The peers discovered, and why the registry could not be read. */
  peers: Peer[];
  /** Why the registry could not be read, when it could not. */
  peersError?: ApiError | null;
  /** What has changed since this page was opened. */
  activity: ActivityEntry[];
  /** Where to send an operator back to, when the browser confirmed it. */
  returnTo?: string;
}

/**
 * Picks the one screen this console should be showing.
 *
 * <p>The screens are alternatives, never stacked: checking the session, signed out, refused,
 * unreachable, reading, or the dashboard. Keeping the choice in one place is what makes it possible
 * to read the whole set at once - it used to live in the shell alongside the page frame, where the
 * branches were interleaved with layout and the complexity gate objected twice.
 *
 * <p><b>A refusal is not an outage.</b> Conflating the two is the most misleading thing this console
 * can say about a mesh: an operator redirected to a peer where they hold no role used to be told the
 * baseline was unreachable, when it was serving perfectly and only their grant was missing.
 *
 * @param props everything the choice depends on.
 * @returns the screen to show.
 */
export function ConsoleScreen(props: ConsoleScreenProps) {
  const { config, session, baseline, error, isPending, peers, peersError, activity, returnTo } =
    props;

  if (session.status === "initialising") {
    return <LoadingScreen label="Checking your session" />;
  }

  if (session.status === "signed-out") {
    return (
      <SignedOut
        baseline={config.clusterId}
        baselineVersion={config.baselineVersion}
        onSignIn={session.signIn}
        region={config.region}
        returnTo={returnTo}
      />
    );
  }

  if (error?.code === "FORBIDDEN") {
    return (
      <NoAccess
        baseline={baseline?.clusterId ?? config.clusterId}
        onSignOut={session.signOut}
        returnTo={returnTo}
      />
    );
  }

  if (error) {
    return (
      <StatusBlock tone="error.main">
        <Typography component="span" variant="h6">
          {error.code === "UNAUTHORIZED" ? "Session rejected" : "Cannot reach this baseline"}
        </Typography>
        <Typography component="span" sx={{ color: "text.secondary" }} variant="body2">
          {error.message}
        </Typography>
      </StatusBlock>
    );
  }

  // The same screen as the session check, deliberately: starting the console runs the two waits
  // back to back, and giving each its own size made the spinner jump between them. Only ever a
  // first paint, since isPending is false while data exists - so the poll refreshes the dashboard
  // underneath rather than replacing it.
  if (isPending || !baseline) {
    return <LoadingScreen label="Reading this baseline" />;
  }

  return (
    <StatusView activity={activity} baseline={baseline} peers={peers} peersError={peersError} />
  );
}
