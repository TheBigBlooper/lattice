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
 * Maps a service's reported readiness onto the console's shared three-state vocabulary.
 *
 * It returns a state rather than a colour on purpose: everything that ends in a palette path goes
 * through {@link toneForHealth}, so there is exactly one switch deciding what `degraded` looks like
 * and no second one to drift from it. This function answers the different question of what a
 * service's own two-word vocabulary means in the vocabulary the screen is drawn in.
 *
 * Anything that is not `UP` is down rather than an unknown third thing. A readiness this console
 * does not recognise is still not readiness, and drawing it neutrally would let a broken service
 * look merely unfamiliar.
 *
 * @param status the readiness exactly as the gateway reported it.
 * @returns the state the console draws for it.
 */
export function healthForService(status: string): ClusterHealth {
  return status === "UP" ? "ready" : "down";
}

/**
 * Maps an infrastructure component's coarse state onto that same vocabulary.
 *
 * <p>A component is not a service, and the difference is the middle state: Elasticsearch speaks
 * green/yellow/red and Artemis is connected or not, so the contract gives every component one
 * coarse `UP` / `DEGRADED` / `DOWN` state that the console can render without knowing which system
 * produced it. A datastore serving without its replicas is neither healthy nor unable to serve, and
 * the service vocabulary has no word for that.
 *
 * <p>A state this console does not recognise reads as down rather than as healthy. Baselines are
 * versioned independently, so a console older than the gateway it reads is the ordinary case -
 * guessing upwards would report a component as fine on the strength of not understanding it.
 *
 * @param status the coarse state exactly as the gateway reported it.
 * @returns the state the console draws for it.
 */
export function healthForComponent(status: string): ClusterHealth {
  switch (status) {
    case "UP":
      return "ready";
    case "DEGRADED":
      return "degraded";
    default:
      return "down";
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
 * <p>A degrading component is a warning rather than an error for the same kind of reason: it is
 * still serving, and colouring "lost a replica" the same as "cannot serve" would spend the console's
 * loudest signal on the state that has not stopped anything yet.
 *
 * @param kind the kind of change observed.
 * @returns the palette path for that kind.
 */
export function toneForTransition(kind: string, landing?: ClusterHealth): Tone {
  // A rollup change is the one kind whose news is not in its name: "went ready to degraded" and
  // "went down to ready" are the same kind and opposite outcomes. Drawn from the kind alone, both
  // rendered the same amber warning, so a baseline recovering looked exactly like one dying.
  if (kind === "peer-health" && landing) {
    return toneForHealth(landing);
  }
  switch (kind) {
    case "peer-lost":
    case "service-lost":
    case "component-lost":
      return "error.main";
    case "mesh-lost":
    case "peer-health":
    case "component-degraded":
      return "warning.main";
    case "peer-returned":
    case "mesh-returned":
    case "peer-joined":
    case "service-returned":
    case "component-returned":
      return "success.main";
    default:
      return "text.secondary";
  }
}
