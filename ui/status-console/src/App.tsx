import { useState } from "react";
import { useBaseline } from "./api/useBaseline.ts";
import { ClusterVerdict } from "./components/ClusterVerdict.tsx";
import { SignedOut } from "./components/SignedOut.tsx";
import { StatusBlock } from "./components/StatusBlock.tsx";
import type { ConsoleConfig } from "./config.ts";
import { leading, scale, type as typeScale } from "./theme/tokens.ts";
import { useTheme } from "./theme/useTheme.ts";

/** What the shell needs to render this baseline. */
export interface AppProps {
  /** The console's configuration, read once at the composition root. */
  config: ConsoleConfig;
}

/**
 * The console shell.
 *
 * It owns the page surface, the active palette, the session, and the choice of what fills the
 * status block - which is always exactly one thing, so nothing else on the page moves as that
 * choice changes.
 *
 * The token is local state for now; the identity flow replaces this state with a real session and
 * changes nothing else here, because everything downstream already takes a token rather than
 * reaching for one.
 *
 * @param props the console configuration.
 * @returns the shell.
 */
export function App({ config }: AppProps) {
  const palette = useTheme();
  const [token, setToken] = useState<string | undefined>(undefined);
  const { data, error, isPending } = useBaseline({ baseUrl: config.apiBaseUrl, token });

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
      <h1 style={{ fontSize: typeScale.section, margin: `0 0 ${scale.lg}px` }}>
        {data?.clusterId ?? config.clusterId}
      </h1>

      {token === undefined && (
        <SignedOut
          baseline={config.clusterId}
          onSignIn={() => {
            setToken("placeholder-until-the-identity-flow-lands");
          }}
          palette={palette}
        />
      )}

      {token !== undefined && error && (
        <StatusBlock palette={palette} tone={palette.statusDown}>
          <span style={{ fontSize: typeScale.section, lineHeight: leading.verdict }}>
            {error.code === "UNAUTHORIZED" ? "Session rejected" : "Cannot reach this baseline"}
          </span>
          <span style={{ color: palette.textSecondary, lineHeight: leading.body }}>
            {error.message}
          </span>
        </StatusBlock>
      )}

      {token !== undefined && !error && isPending && (
        <StatusBlock palette={palette} tone={palette.textSecondary}>
          <span style={{ fontSize: typeScale.section, lineHeight: leading.verdict }}>
            Reading this baseline
          </span>
        </StatusBlock>
      )}

      {token !== undefined && !error && data && (
        <ClusterVerdict
          health={data.health ?? "down"}
          palette={palette}
          services={data.services ?? []}
        />
      )}
    </main>
  );
}
