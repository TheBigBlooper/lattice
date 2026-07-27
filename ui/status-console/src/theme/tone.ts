import type { components } from "../api/generated/v1.ts";
import type { Palette } from "./tokens.ts";

/** A cluster's rolled-up state, exactly as the contract defines it. */
export type ClusterHealth = components["schemas"]["ClusterHealth"];

/**
 * Maps a rolled-up health to the colour that carries it.
 *
 * It lives here rather than inside a component because more than one surface renders a verdict: the
 * cluster's own, and each discovered peer's. Two copies of this switch would drift the first time a
 * status colour was tuned, and the screen would then show one baseline's `degraded` in a different
 * colour from another's - on a status console, a correctness problem rather than a cosmetic one.
 *
 * The switch is exhaustive and falls back to the secondary text colour, so a health value added to
 * the contract renders in a neutral tone rather than silently inheriting a wrong one.
 *
 * @param health the rollup as the owning baseline reports it. Never recomputed here.
 * @param palette the active palette.
 * @returns the colour for that state.
 */
export function toneForHealth(health: ClusterHealth, palette: Palette): string {
  switch (health) {
    case "ready":
      return palette.statusReady;
    case "degraded":
      return palette.statusDegraded;
    case "down":
      return palette.statusDown;
    default:
      return palette.textSecondary;
  }
}
