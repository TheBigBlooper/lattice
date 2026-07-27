/** The query parameter a peer redirect carries, naming the console the operator came from. */
const FROM_PARAM = "from";

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
