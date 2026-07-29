import { Route, Routes } from "react-router";
import type { ApiError } from "../api/client.ts";
import type { components } from "../api/generated/v1.ts";
import type { Peer } from "../api/usePeers.ts";
import { NoAccess } from "../auth/NoAccess.tsx";
import { SignedOut } from "../auth/SignedOut.tsx";
import type { Session } from "../auth/useSession.ts";
import type { ConsoleConfig } from "../config.ts";
import type { ActivityEntry } from "../features/activity/index.ts";
import { InventoryView } from "../features/inventory/index.ts";
import { OrdersView } from "../features/orders/index.ts";
import { StatusView } from "../features/status/index.ts";
import { LoadingScreen } from "./LoadingScreen.tsx";
import { Unreachable } from "./Unreachable.tsx";

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
  /** Whether a read is in flight right now, so a failure can be shown as still being worked. */
  isRetrying?: boolean;
  /** When this baseline last answered, if it ever has. Absent means it never has. */
  lastGoodRead?: number;
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
  const {
    config,
    session,
    baseline,
    error,
    isPending,
    peers,
    peersError,
    activity,
    returnTo,
    isRetrying,
    lastGoodRead,
  } = props;

  if (session.status === "initialising") {
    return <LoadingScreen label="Checking your session" />;
  }

  if (session.status === "signed-out") {
    return (
      <SignedOut
        baseline={config.clusterId}
        baselineVersion={config.baselineVersion}
        onSignIn={session.signIn}
        reason={session.signedOutReason}
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
      <Unreachable
        baseline={baseline?.clusterId ?? config.clusterId}
        detail={error.message}
        headline={error.code === "UNAUTHORIZED" ? "Session rejected" : "Cannot reach this baseline"}
        isRetrying={isRetrying}
        lastGoodRead={lastGoodRead}
      />
    );
  }

  // The same screen as the session check, deliberately: starting the console runs the two waits
  // back to back, and giving each its own size made the spinner jump between them. Only ever a
  // first paint, since isPending is false while data exists - so the poll refreshes the dashboard
  // underneath rather than replacing it.
  if (isPending || !baseline) {
    return <LoadingScreen label="Reading this baseline" />;
  }

  // Only the signed-in, baseline-read case is routed. Every screen above is an alternative to the
  // whole console rather than a destination within it - routing a sign-in card would offer an
  // operator three tabs onto reads that can only answer 401.
  return (
    <Routes>
      <Route
        element={
          <StatusView
            activity={activity}
            baseline={baseline}
            peers={peers}
            peersError={peersError}
          />
        }
        index
      />
      <Route
        element={
          <OrdersView
            baseUrl={config.ordersBaseUrl}
            baseline={baseline.clusterId}
            role={session.role}
            token={session.token}
          />
        }
        path="/orders"
      />
      <Route
        element={
          <InventoryView
            baseUrl={config.inventoryBaseUrl}
            baseline={baseline.clusterId}
            role={session.role}
            token={session.token}
          />
        }
        path="/inventory"
      />
    </Routes>
  );
}
