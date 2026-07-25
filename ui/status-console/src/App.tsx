import { scale, type as typeScale } from "./theme/tokens.ts";
import { useTheme } from "./theme/useTheme.ts";

/**
 * The console shell.
 *
 * It owns the page surface and the active palette, and nothing else: the cluster verdict and the
 * per-service breakdown mount here as they land. Keeping the shell this thin is what lets the
 * signed-out state swap into the same position as the verdict without the layout moving.
 */
export function App() {
  const palette = useTheme();

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
      <h1 style={{ fontSize: typeScale.section, margin: 0 }}>Lattice status console</h1>
    </main>
  );
}
