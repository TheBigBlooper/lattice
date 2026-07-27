import { useBaseline } from "./api/useBaseline.ts";
import { usePeers } from "./api/usePeers.ts";
import { useSession } from "./auth/useSession.ts";
import { ClusterVerdict } from "./components/ClusterVerdict.tsx";
import { DiscoveredBaselines } from "./components/DiscoveredBaselines.tsx";
import { SignedOut } from "./components/SignedOut.tsx";
import { StatusBlock } from "./components/StatusBlock.tsx";
import type { ConsoleConfig } from "./config.ts";
import {
  leading,
  overviewMinColumn,
  overviewSplit,
  scale,
  type as typeScale,
} from "./theme/tokens.ts";
import { useTheme } from "./theme/useTheme.ts";

/** What the shell needs to render this baseline. */
export interface AppProps {
  /** The console's configuration, read once at the composition root. */
  config: ConsoleConfig;
}

/**
 * The console shell.
 *
 * It owns the page surface, the active palette, and the choice of what fills the status block -
 * which is always exactly one thing, so nothing else on the page moves as that choice changes.
 *
 * The session and the data are separate concerns joined here and nowhere else: the session yields a
 * token, the data layer takes one. Neither reaches for the other, which is why the identity flow
 * replaced a placeholder without any panel changing.
 *
 * @param props the console configuration.
 * @returns the shell.
 */
export function App({ config }: AppProps) {
  const palette = useTheme();
  const session = useSession(config);
  const { data, error, isPending } = useBaseline({
    baseUrl: config.apiBaseUrl,
    token: session.token,
  });
  const peers = usePeers({ baseUrl: config.apiBaseUrl, token: session.token });

  const signedIn = session.status === "signed-in";

  return (
    <main
      style={{
        backgroundColor: palette.surfacePage,
        color: palette.textPrimary,
        fontSize: typeScale.body,
        minHeight: "100vh",
        padding: scale.xl,
      }}
    >
      <header
        style={{
          alignItems: "baseline",
          display: "flex",
          gap: scale.md,
          justifyContent: "space-between",
          margin: `0 0 ${scale.lg}px`,
        }}
      >
        <h1 style={{ fontSize: typeScale.section, margin: 0 }}>
          {data?.clusterId ?? config.clusterId}
        </h1>
        {signedIn && (
          <span style={{ color: palette.textSecondary, fontSize: typeScale.meta }}>
            {session.username}
            <button
              onClick={session.signOut}
              style={{
                background: "none",
                border: "none",
                color: palette.textSecondary,
                cursor: "pointer",
                fontSize: typeScale.meta,
                padding: `0 0 0 ${scale.sm}px`,
                textDecoration: "underline",
              }}
              type="button"
            >
              Sign out
            </button>
          </span>
        )}
      </header>

      {session.status === "initialising" && (
        <StatusBlock palette={palette} tone={palette.textSecondary}>
          <span style={{ fontSize: typeScale.section, lineHeight: leading.verdict }}>
            Checking your session
          </span>
        </StatusBlock>
      )}

      {session.status === "signed-out" && (
        <SignedOut baseline={config.clusterId} onSignIn={session.signIn} palette={palette} />
      )}

      {signedIn && error && (
        <StatusBlock palette={palette} tone={palette.statusDown}>
          <span style={{ fontSize: typeScale.section, lineHeight: leading.verdict }}>
            {error.code === "UNAUTHORIZED" ? "Session rejected" : "Cannot reach this baseline"}
          </span>
          <span style={{ color: palette.textSecondary, lineHeight: leading.body }}>
            {error.message}
          </span>
        </StatusBlock>
      )}

      {signedIn && !error && isPending && (
        <StatusBlock palette={palette} tone={palette.textSecondary}>
          <span style={{ fontSize: typeScale.section, lineHeight: leading.verdict }}>
            Reading this baseline
          </span>
        </StatusBlock>
      )}

      {signedIn && !error && data && (
        // The mesh sits beside the verdict at the golden-section split, and wraps beneath it on a
        // narrow window. Wrapping rather than shrinking is deliberate: the cluster's own state
        // stays first in reading order at every width, which is the one thing this layout must
        // never trade away.
        <div style={{ display: "flex", flexWrap: "wrap", gap: scale.lg }}>
          <div
            style={{
              flex: `1 1 ${overviewSplit.verdict}`,
              minWidth: overviewMinColumn,
            }}
          >
            <ClusterVerdict
              health={data.health ?? "down"}
              palette={palette}
              services={data.services ?? []}
            />
          </div>
          <div
            style={{
              flex: `1 1 ${overviewSplit.mesh}`,
              minWidth: overviewMinColumn,
            }}
          >
            {peers.error ? (
              <span
                style={{
                  color: palette.statusDegraded,
                  fontSize: typeScale.body,
                  lineHeight: leading.body,
                }}
              >
                Cannot read the mesh registry: {peers.error.message}
              </span>
            ) : (
              <DiscoveredBaselines palette={palette} peers={peers.data ?? []} />
            )}
          </div>
        </div>
      )}
    </main>
  );
}
