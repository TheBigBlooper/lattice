import type { components } from "../api/generated/v1.ts";

/** A cluster's rolled-up state, exactly as the contract defines it. */
export type ClusterHealth = components["schemas"]["ClusterHealth"];

/** A colour named in the theme's palette, resolved by Material UI rather than written here. */
export type Tone = "success.main" | "warning.main" | "error.main" | "text.secondary";

/**
 * Maps a rolled-up health to the theme palette entry that carries it.
 *
 * It returns a **palette path**, not a colour. That is the point: no colour is written outside the
 * theme, and a component passes what comes back straight to `sx` for Material UI to resolve against
 * whichever mode is active. A function returning a hex would have to know the mode, and would put a
 * second colour source in the codebase.
 *
 * It lives here rather than inside a component because more than one surface renders a verdict: the
 * cluster's own, and each discovered peer's. Two copies of this switch would drift the first time a
 * status colour was tuned, and the screen would then show one baseline's `degraded` in a different
 * colour from another's - on a status console, a correctness problem rather than a cosmetic one.
 *
 * The switch is exhaustive and falls back to the secondary text colour, so a health value added to
 * the contract renders neutrally rather than silently inheriting a wrong meaning.
 *
 * @param health the rollup as the owning baseline reports it. Never recomputed here.
 * @returns the palette path for that state.
 */
export function toneForHealth(health: ClusterHealth): Tone {
  switch (health) {
    case "ready":
      return "success.main";
    case "degraded":
      return "warning.main";
    case "down":
      return "error.main";
    default:
      return "text.secondary";
  }
}

/**
 * Maps a mesh transition to the theme palette entry that carries it.
 *
 * <p>Same reasoning as {@link toneForHealth}: a palette path rather than a colour, so no colour is
 * written outside the theme and the active mode resolves it.
 *
 * <p>The two "lost" kinds are deliberately different weights. A peer going quiet is one baseline's
 * problem and reads as an error; losing this baseline's own mesh link is a warning about what the
 * screen can still be trusted to say, which is a different thing to be told.
 *
 * @param kind the kind of change observed.
 * @returns the palette path for that kind.
 */
export function toneForTransition(kind: string): Tone {
  switch (kind) {
    case "peer-lost":
      return "error.main";
    case "mesh-lost":
    case "peer-health":
      return "warning.main";
    case "peer-returned":
    case "mesh-returned":
    case "peer-joined":
      return "success.main";
    default:
      return "text.secondary";
  }
}
