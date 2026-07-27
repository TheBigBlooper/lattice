import { afterEach, describe, expect, it, vi } from "vitest";
import { returnTo } from "./returnTo.ts";

/** Points the document at a location and referrer, as a browser would after a navigation. */
function arriveFrom(search: string, referrer: string) {
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
});
