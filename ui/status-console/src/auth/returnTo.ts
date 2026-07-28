/** The query parameter a peer redirect carries, naming the console the operator came from. */
const FROM_PARAM = "from";

/** Where a confirmed origin is kept for the rest of this tab session. */
const CONFIRMED_KEY = "lattice.returnTo";

/**
 * The console this operator arrived from, if the browser confirms it.
 *
 * **Why a parameter needs confirming at all.** A redirect to a peer carries the origin it came from
 * so that peer can offer a way back. Acting on that parameter alone is an open redirect: anyone can
 * send a link that lands on a trusted console and renders a friendly button pointing somewhere
 * hostile. The parameter is attacker-controlled; it is a hint, not evidence.
 *
 * **Why the referrer is the check, rather than the peer registry.** Validating against this
 * baseline's own registry of known peers would be the obvious answer, and it fails in exactly the
 * case this exists for: an operator with no role here cannot read that registry either, because it
 * sits behind the same authorization that just refused them. The referrer is set by the browser and
 * cannot be forged into a novel origin, so requiring the two to agree is both stronger and
 * available without a session.
 *
 * **When the referrer is absent the answer is nothing.** Referrer policies strip it on plenty of
 * ordinary navigations. Losing the button is correct: the alternative is trusting a value nothing
 * corroborates, and a missing convenience beats a redirect that can be aimed.
 *
 * @returns the confirmed origin to return to, or undefined when there is nothing safe to offer.
 */
export function returnTo(): string | undefined {
  // Confirmed on a previous load of this tab. The session check redirects to Keycloak and back,
  // which replaces the referrer with the provider's own origin - so by the time a screen renders,
  // the browser can no longer corroborate where the operator came from. Confirming once on arrival
  // and remembering the answer keeps the way back available without ever trusting an unconfirmed
  // parameter. Only confirmed values are ever stored, so a forged one cannot be laundered by this.
  const remembered = sessionStorage.getItem(CONFIRMED_KEY);
  if (remembered) {
    return remembered;
  }

  const claimed = new URLSearchParams(location.search).get(FROM_PARAM);
  if (!claimed) {
    return undefined;
  }

  const claimedOrigin = httpOrigin(claimed);
  const referrerOrigin = httpOrigin(document.referrer);

  // Both must parse as http(s) and agree. Comparing origins rather than whole URLs is deliberate:
  // the referrer carries whatever path the operator was on, which has no bearing on where "back" is.
  if (!claimedOrigin || claimedOrigin !== referrerOrigin) {
    return undefined;
  }

  sessionStorage.setItem(CONFIRMED_KEY, claimed);
  return claimed;
}

/**
 * The origin of an absolute http or https URL.
 *
 * The scheme is checked explicitly rather than left to URL parsing, which accepts `javascript:`
 * without complaint - and a `javascript:` destination handed to a link is the exact shape of the
 * problem this module exists to prevent.
 */
function httpOrigin(value: string): string | undefined {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:" ? url.origin : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Confirms the origin now, before anything can navigate away.
 *
 * **This must run at startup, and the reason is not obvious.** The session check redirects to
 * Keycloak on mount, and it does so before any screen that would call {@link returnTo} has
 * rendered - so by the time one does, the referrer is the provider rather than the peer console
 * and the parameter can no longer be corroborated. Confirming eagerly is the only moment the
 * browser can still vouch for where the operator arrived from.
 */
export function captureReturnTo(): void {
  returnTo();
}
