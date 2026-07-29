import { describe, expect, it } from "vitest";
import { healthForComponent, healthForService } from "./tone.ts";

describe("healthForService", () => {
  /** A service that answered its readiness probe is the only service that is up. */
  it("reads UP as ready", () => {
    expect(healthForService("UP")).toBe("ready");
  });

  /**
   * Anything that is not UP is down, rather than an unknown third thing.
   *
   * <p>A readiness this console does not recognise is still not readiness, and drawing it neutrally
   * would let a broken service look merely unfamiliar.
   */
  it("treats a readiness it does not recognise as down", () => {
    expect(healthForService("STARTING")).toBe("down");
    expect(healthForService("DOWN")).toBe("down");
  });
});

describe("healthForComponent", () => {
  /**
   * The component vocabulary keeps its middle state, which is the reason it is a separate
   * vocabulary at all: a datastore serving without its replicas is neither healthy nor unable to
   * serve, and the service vocabulary cannot express that.
   */
  it.each([
    ["UP", "ready"],
    ["DEGRADED", "degraded"],
    ["DOWN", "down"],
  ] as const)("maps %s onto %s", (status, expected) => {
    expect(healthForComponent(status)).toBe(expected);
  });

  /**
   * A coarse state this console has not seen is read as down rather than as healthy.
   *
   * <p>Baselines are versioned independently, so a console can be older than the gateway it reads.
   * Guessing upwards would report a component as fine on the strength of not understanding it.
   */
  it("treats a state it does not recognise as down", () => {
    expect(healthForComponent("RECOVERING")).toBe("down");
  });
});
