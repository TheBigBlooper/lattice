import { afterEach, describe, expect, it, vi } from "vitest";
import { returnTo } from "./returnTo.ts";

/** Points the document at a location and referrer, as a browser would after a navigation. */
function arriveFrom(search: string, referrer: string) {
  sessionStorage.clear();
  vi.stubGlobal("location", { search } as Location);
  vi.spyOn(document, "referrer", "get").mockReturnValue(referrer);
}

describe("returnTo", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  /**
   * The ordinary case: an operator followed the redirect from another baseline's console, so the
   * parameter and the referrer agree and there is somewhere real to send them back to.
   */
  it("accepts a destination the referrer confirms", () => {
    arriveFrom("?from=http%3A%2F%2Flocalhost%3A3000%2F", "http://localhost:3000/");

    expect(returnTo()).toBe("http://localhost:3000/");
  });

  /**
   * The attack this exists to stop. A crafted link can carry any `from` it likes, so the parameter
   * alone is an open redirect: a friendly-looking button on a trusted console that navigates
   * somewhere hostile. The referrer is set by the browser and cannot be forged into a novel
   * origin, so requiring the two to agree is what makes the parameter safe to act on.
   */
  it("refuses a destination the referrer does not confirm", () => {
    arriveFrom("?from=https%3A%2F%2Fevil.example%2F", "http://localhost:3000/");

    expect(returnTo()).toBeUndefined();
  });

  /**
   * Referrer policies strip the header on plenty of ordinary navigations. Losing the button is the
   * correct outcome: the alternative is trusting a value nothing corroborates, and a missing
   * convenience is better than a redirect that can be aimed.
   */
  it("refuses when there is no referrer to confirm against", () => {
    arriveFrom("?from=http%3A%2F%2Flocalhost%3A3000%2F", "");

    expect(returnTo()).toBeUndefined();
  });

  /** No parameter at all is the common case: a direct visit, not a redirect. */
  it("returns nothing when no origin was passed", () => {
    arriveFrom("", "http://localhost:3000/");

    expect(returnTo()).toBeUndefined();
  });

  /**
   * A value that is not an absolute http URL is refused rather than passed to the browser. A
   * `javascript:` destination is the reason this is checked explicitly rather than left to URL
   * parsing, which would accept it happily.
   */
  it.each([
    ["javascript:alert(1)", "javascript:alert(1)"],
    ["not-a-url", "not-a-url"],
  ])("refuses %s", (from) => {
    arriveFrom(`?from=${encodeURIComponent(from)}`, from);

    expect(returnTo()).toBeUndefined();
  });

  /**
   * The confirmation survives the Keycloak round trip.
   *
   * Checking for an existing session redirects to Keycloak and back, which replaces the referrer
   * with Keycloak own origin - so by the time the screen renders, the browser can no longer
   * corroborate where the operator came from. Confirming once on arrival, while the referrer is
   * still the peer console, and remembering the result is what keeps the way back available
   * without ever trusting an unconfirmed parameter.
   */
  it("remembers a destination confirmed before a provider round trip", () => {
    arriveFrom("?from=http%3A%2F%2Flocalhost%3A3000%2F", "http://localhost:3000/");
    expect(returnTo()).toBe("http://localhost:3000/");

    // Back from Keycloak: the parameter is gone and the referrer is the provider.
    vi.stubGlobal("location", { search: "" } as Location);
    vi.spyOn(document, "referrer", "get").mockReturnValue("http://localhost:8083/");

    expect(returnTo()).toBe("http://localhost:3000/");
  });

  /** Nothing is remembered that was never confirmed, so a forged parameter cannot be laundered. */
  it("remembers nothing it refused", () => {
    arriveFrom("?from=https%3A%2F%2Fevil.example%2F", "http://localhost:3000/");
    expect(returnTo()).toBeUndefined();

    vi.stubGlobal("location", { search: "" } as Location);
    expect(returnTo()).toBeUndefined();
  });

  /**
   * A fresh arrival wins over what this tab remembered.
   *
   * <p>The bug this exists to stop: hop from hub-central to hub-east, then later from hub-west to
   * hub-east in the same tab, and Back sent the operator to hub-central - the origin of the FIRST
   * visit. The remembered value is only meant to survive the Keycloak round trip, which strips the
   * parameter; it was never meant to outlive the arrival that produced it.
   */
  it("prefers a newly confirmed origin over one remembered from an earlier arrival", () => {
    arriveFrom("?from=http%3A%2F%2Flocalhost%3A3000%2F", "http://localhost:3000/");
    expect(returnTo()).toBe("http://localhost:3000/");

    // Same tab, a later hop from a different peer.
    vi.stubGlobal("location", { search: "?from=http%3A%2F%2Flocalhost%3A3002%2F" } as Location);
    vi.spyOn(document, "referrer", "get").mockReturnValue("http://localhost:3002/");

    expect(returnTo()).toBe("http://localhost:3002/");
  });

  /**
   * An unconfirmable parameter does not erase a good remembered origin either.
   *
   * <p>Coming back from Keycloak the parameter is gone and the referrer is the provider, which is
   * precisely when the remembered value earns its place - so a fresh arrival must only replace it
   * when the browser actually corroborates the new one.
   */
  it("keeps the remembered origin when a later claim cannot be confirmed", () => {
    arriveFrom("?from=http%3A%2F%2Flocalhost%3A3000%2F", "http://localhost:3000/");
    expect(returnTo()).toBe("http://localhost:3000/");

    vi.stubGlobal("location", { search: "?from=https%3A%2F%2Fevil.example%2F" } as Location);
    vi.spyOn(document, "referrer", "get").mockReturnValue("http://localhost:3000/");

    expect(returnTo()).toBe("http://localhost:3000/");
  });
});
