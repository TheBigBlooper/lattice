# Live status transport: how node status reaches the console

Settles **P6**.

The question was recorded as "Server-Sent Events vs WebSocket for node status". Measuring the thing it was meant to improve changes the answer: **neither, for now.** The console keeps polling, and this document records why, along with what would make that wrong.

---

## The decision, in one line

**Polling is the live-status transport.** The console reads `/api/v1/baseline` every 10 seconds. No push transport is added until a view exists that measurably needs one.

---

## Why the framing was wrong

P6 assumed the transport was what made status stale. It is not the dominant term.

A peer is discovered and kept alive by announcements: a **10-second** heartbeat, and a **30-second** `PEER_TTL` after which an unheard peer flips to `UNREACHABLE`. The harness observes that transition at around 25 seconds. The console then polls every **10 seconds**.

So for peer state, worst-case staleness is roughly:

```
   ~30s   the gateway noticing (PEER_TTL)
 +  10s   the console asking (poll interval)
 = ~40s   what an operator waits for
```

**A push transport removes the second term only.** It takes about 40 seconds to about 30 - a quarter of the wait - and leaves untouched the three quarters that come from the mesh's own liveness model. Building a second protocol to win the smaller half of a latency budget is the wrong trade, and it would have been easy to make without doing this arithmetic.

If the wait is ever genuinely too long, `PEER_TTL` is the number to argue about. That is a **mesh-correctness** decision, not a console one: shortening it detects a lost peer sooner and also makes it likelier that a merely slow peer is declared unreachable. It belongs to the mesh design, not here.

---

## The second reason: there is no consumer

The console today reads **only** `/api/v1/baseline` - its own cluster id, region, baseline version, health rollup, and services. It does not read `/api/v1/peers` at all.

The views that would want live *peer* status are the unified view of discovered baselines (#12) and the mesh-link state (#56). Both are `needs-mockup`, so neither has a settled shape. Choosing a transport now would be designing for a screen nobody has drawn, and the shape of that screen is exactly what determines whether push is worth it - a wall display refreshed continuously has different needs from a page an operator opens when something is wrong.

---

## The third reason: both options need new auth surface

Every `/api/v1` operation requires a bearer token from this baseline's own realm (locked #48-#50), and the console attaches it as an `authorization` header.

**A browser cannot set that header on either candidate transport.** `EventSource` has no header API. The `WebSocket` constructor has none either. So both options need a new mechanism to carry identity:

- a **short-lived stream ticket** minted by an authenticated call and redeemed on connect,
- a **cookie**, which changes the auth model and brings a cross-site request forgery surface the bearer-token design deliberately avoids,
- or the `Sec-WebSocket-Protocol` header abused to smuggle a token.

A token in the query string is not an option: it lands in access logs, proxies, and browser history.

This is the cost that is easiest to overlook when comparing the two, and it is the same for both - so it is not a reason to prefer one over the other, but it is a real reason to prefer neither until something needs it. Whichever is eventually chosen inherits a new credential path, which is a meaningful thing to add to a system whose identity model was settled deliberately.

---

## What would make this decision wrong

Written down so the revisit is triggered by evidence rather than by taste. Any one of these supersedes this decision:

1. **A view needs freshness below the time-to-live.** If a console screen must reflect a change faster than `PEER_TTL` allows, polling is no longer the limiting factor and a push transport starts to earn its cost. Note this requires the TTL question to be settled first.
2. **An operator complaint that names latency.** Not "it feels sluggish" - a specific report that a stale reading led to a wrong action.
3. **Polling load becomes visible.** One console per baseline at one request per 10 seconds is nothing. A wall display per operator across many baselines is a different shape, and the gateway is the thing that would feel it.
4. **A view needs server-initiated events that are not status.** Anything genuinely push-shaped - an alert, a job completing - is a different problem from a status rollup and should not be forced through polling.

**If it is revisited, Server-Sent Events is the favourite** and the burden of proof sits with WebSocket. Status flows one way, SSE degrades to ordinary HTTP through proxies, and the bidirectionality WebSocket buys has no use here. That is a starting position, not a decision.

---

## What this is not

**Not "push is wrong".** It is "push is not yet worth a second protocol, a stateful fan-out point on the mesh-gateway, and a new credential path, to win a quarter of a latency budget for a screen that does not exist."

**Not a deferral.** P6 is settled: polling is the answer, the reasoning is recorded, and the conditions for changing it are explicit. A future decision supersedes this one by number rather than by discovering the question was never answered.

---

## Decisions settled here

- **The console's live-status transport is polling**, at a 10-second interval, against `/api/v1/baseline`.
- **No push transport** (Server-Sent Events or WebSocket) is added until one of the four triggers above is met.
- **`PEER_TTL`, not the transport, is the dominant term** in status staleness; arguments about freshness belong to the mesh design first.
- **If push is revisited, Server-Sent Events is the default** and WebSocket carries the burden of proof.

Promoted to [locked_decisions.md](../../reference/locked_decisions.md) #59.
