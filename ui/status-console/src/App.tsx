import { useState } from "react";
import { ClusterVerdict } from "./components/ClusterVerdict.tsx";
import { SignedOut } from "./components/SignedOut.tsx";
import { scale, type as typeScale } from "./theme/tokens.ts";
import { useTheme } from "./theme/useTheme.ts";

/**
 * The console shell.
 *
 * It owns the page surface, the active palette, and the choice between the verdict and the
 * signed-out screen - which are the same block in the same position, so that choice never moves
 * anything else on the page.
 *
 * The session is local state and the cluster payload is a placeholder for now: the identity flow
 * and the typed data hook land next, and both slot in behind this decision without changing the
 * screens themselves.
 */
export function App() {
  const palette = useTheme();
  const [signedIn, setSignedIn] = useState(false);

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
      <h1 style={{ fontSize: typeScale.section, margin: `0 0 ${scale.lg}px` }}>hub-local</h1>
      {signedIn ? (
        <ClusterVerdict
          health="degraded"
          palette={palette}
          services={[
            { name: "orders", status: "UP" },
            { name: "inventory", status: "UP" },
            { name: "mesh-gateway", status: "DOWN" },
          ]}
        />
      ) : (
        <SignedOut
          baseline="hub-local"
          onSignIn={() => {
            setSignedIn(true);
          }}
          palette={palette}
        />
      )}
    </main>
  );
}
