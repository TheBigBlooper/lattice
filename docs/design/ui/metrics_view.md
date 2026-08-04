# The Metrics view - reading a baseline's own instrumentation

The status console gains a fourth destination showing this baseline's metrics. This document settles how the browser reads them, what the view shows, which existing components it reuses, and where it deliberately diverges from one of them.

Related: [observability.md](../architecture/observability.md) (the instrumentation this surfaces), [operational_views.md](operational_views.md) (the tabs, the role model, and the panel conventions this follows), [material_ui.md](material_ui.md) (the component library and the 8px grid), [api_structure.md](../architecture/api_structure.md) (the response envelope), [ui_protocol.md](../../protocol/ui_protocol.md) (the style rules this is built to), [locked_decisions.md](../../reference/locked_decisions.md) (#48 roles, #59 polling, #61 local reads only, #78 the instrumentation).

---

## Why this needed settling before a build

Every service serves Prometheus exposition at `/metrics`, and **the console's browser cannot reach any of it**. The endpoint is on a separate management port, that port's Service is ClusterIP and never published, and the endpoint carries no bearer token. Locked #78 chose all three deliberately: reachability is the entire protection on a surface that has no authentication.

So the view is not a matter of rendering something that already arrives. Something has to carry the numbers to a browser first, and that is a contract decision rather than a console one.

**This also reverses locked #78's "no console surface for v1.0.0" clause**, by founder decision. That clause argued a metrics view built before a collection stack exists would be replaced by a real dashboard. The counter-argument accepted here: the numbers are useful now, the console is where an operator already looks, and waiting on a hosting decision to see them is a poor trade. That amendment has landed: #78's clause is struck and this design is recorded as locked **#79**.

---

## The decisions

| # | Question                      | Decision                                                                      |
|---|-------------------------------|-------------------------------------------------------------------------------|
| 1 | How the browser reads metrics | **A new bearer-protected contract operation**, `getMetrics`, on the API port. |
| 2 | Where that operation lives    | **On every service**, not aggregated by the gateway.                          |
| 3 | What the view shows           | **Six sparkline cards**, with every series behind a disclosure.               |
| 4 | Where history comes from      | **The console's own poll**, a session-only ring buffer.                       |
| 5 | Failure behaviour             | **Annotate, never block** - a deliberate divergence from `ListPanel`.         |
| 6 | Role handling                 | **None.** No writes, so `viewer` and `operator` see the same screen.          |

---

## 1. A contract operation, not a scrape

`GET /api/v1/metrics` returns a selected set of this service's meters in the standard `{data, meta}` envelope, bearer-protected like every other `/api/v1` operation.

Three alternatives were rejected:

- **Publishing the management port.** It would put an unauthenticated surface on a host-reachable address, reversing the one property locked #78 built the separate port to hold. The console being convenient is not worth that.
- **Proxying the scrape through the API port.** The same disclosure with an extra hop, and it would mean the API port serves an endpoint whose shape is Prometheus text rather than the response envelope every other operation returns.
- **Waiting for a collection stack.** That is the hosting decision, and it is deferred.

**The scrape endpoint is unchanged and stays the collector's path.** This adds a second, narrower reader for a browser; it does not replace the first. A future Prometheus scrapes `/metrics` exactly as it would today.

**Selected, not everything: Lattice's own meters and nothing else.** The operation returns the `lattice.*` family. Everything the runtime and the toolkit register about themselves - the Java Virtual Machine, the pools, the Hypertext Transfer Protocol series - stays on the scrape endpoint, which remains complete.

**The boundary was drawn on a measurement rather than a principle.** The first cut excluded only the Java Virtual Machine families, and a running gateway then served **52 samples, 40 of them Vert.x pool and HTTP series that no card reads**. That is 6.8 kB every ten seconds, per service, to transfer data the client discards. Narrowing to `lattice.*` takes the payload to roughly a fifth of that.

The cost is real and is accepted: per-route HTTP latency and error counts are genuinely useful when debugging, and they are now reachable only by port-forwarding to the scrape endpoint. That is the same access a collector has, and it is one command. A family filter as a query parameter was considered and declined as speculative - it should wait until someone has used the screen and found something missing.

---

## 2. On every service, not aggregated

Each service serves its own `getMetrics`. The console reads all three, exactly as it already reads three services for its other data.

Aggregating in the mesh-gateway was considered - it already polls every service's readiness, so the shape exists. It was rejected because it makes the gateway a metrics proxy for components it otherwise only probes, and a proxy is precisely the job a collection stack does. Building a small one now means building something a real collector replaces, and coupling the gateway to a second thing about every service in the meantime.

The cost is accepted and is small: three reads instead of one, on a console that already issues several per tick, against services on the same network.

---

## 3. Six cards, and everything else behind a disclosure

**The cards**: mesh link, peers reachable, announces per minute, expiries, datastore latency, failed reads.

Each carries a label, a current value, a small trend line, and the window that trend covers. **The number stays dominant and the trend stays supporting** - the sparkline is pinned to a fixed height so a card never becomes a chart with a caption.

**Why trend at all, rather than a plain summary card.** Most of these are counters, and a counter's instantaneous value is close to meaningless: `announcements_received_total = 46` says nothing on its own, while the same counter having stopped advancing four polls ago says everything. A summary card shows a number that only becomes information if the reader remembers what it was last time. This was the deciding argument between the three directions offered.

**The disclosure** holds every series the operation returns, as a filterable table of metric, label and value. It is collapsed by default and carries a count. It exists for the case the six cards cannot serve - a question nobody anticipated - and it is the honest alternative to guessing which seventh metric matters.

---

## 4. History is the console's own, and says so

There is no storage. The view keeps a **ring buffer of the values its own poll has seen**, on the existing 10-second cadence (locked #59) rather than a second one, holding 60 points - a 10-minute window.

**The window is stated on the card**, and the buffer is session-only: a reload starts it empty. That limitation is written on screen rather than left to be discovered, because a trend line that silently covers "since you opened the tab" is worse than no trend line at all.

Reusing the existing poll matters beyond tidiness: a second cadence would put two different ideas of "now" on one screen.

---

## 5. Failure annotates rather than blocks

`ListPanel` frames a failed read with a dialog that **blocks** the panel, and that is right where it is used today: behind it sit a form and a list that has become a memory, and acting on either is what the dialog prevents.

**This view has nothing to act on.** Blocking would hide the last known numbers at exactly the moment an operator wants them - a mesh incident is when the metrics matter most and also when a read is most likely to fail. So each card keeps its last value, visibly stale, alongside the retry state and the age of the last good read.

This is a deliberate divergence from a shared component's default rather than a new component. The series table still uses `ListPanel` unchanged, because a table of stale rows genuinely is a memory.

---

## 6. No role variant

Locked #48 gives `viewer` every `GET`. There are no writes here, so `viewer` and `operator` see an identical screen and `ViewerNotice` does not appear - the only operational view where it does not.

---

## What it reuses

Reuse over rebuild is the rule, and this view is mostly assembly:

| Piece                  | Component                                                                     |
|------------------------|-------------------------------------------------------------------------------|
| Navigation             | A fourth `Tab` in the existing `Tabs`; Status stays the default route.        |
| Card header            | `PanelHeader`, so the eye does not re-learn a shape per card.                 |
| Card explanation       | A `PANEL_HELP.metrics` entry in the existing shows / source / omits shape.    |
| The series table       | `ListPanel`, which already handles empty, error, retrying and last-good-read. |
| Table styling          | `FLUSH` for edge alignment, `FIGURE` for tabular numerals.                    |
| Status colour and word | `StatusIcon` and the semantic palette, so colour is never the sole indicator. |

The one genuinely new component is the sparkline card. Nothing in the console draws a trend today.

**Proportions**, per the mockup requirement: cards on a 12-column grid at `xs={12} sm={6}` with `spacing={2}`; the value at the type scale's large step over a caption-sized unit; the trend at a fixed height that keeps it subordinate to the number. The series table is full width at `xs={12}`, fixed layout, weighted toward the metric name.

**Style rules this must not break** (from [ui_protocol.md](../../protocol/ui_protocol.md)): no colour literal anywhere including inside `sx` - the sparkline strokes read palette keys, not hex; no spacing literal; emphasis through the type scale rather than an inline weight; `FIGURE` on every figure compared vertically; the table's configuration in a sibling map rather than inline; `React.memo` with stable handlers, because this re-renders on every tick; and a scroll container on the table, which grows with the peer count.

---

## Build order

1. **Contract** - `getMetrics` in the OpenAPI spec, with the selected meter set and its response shape. Single-writer, so it serialises against other contract work.
2. **Services** - the operation in `BaseVerticle`, reading the registry it already holds, so every service inherits it rather than implementing it three times.
3. **Console** - the tab, the cards, the ring buffer, and the disclosure.

---

## Deferred

- **A real collection stack.** Unchanged: still the open half of observability, still waiting on hosting.
- **Persisted history.** Follows the collection stack; a ring buffer is not a step toward it.
- **Peer metrics.** A peer refuses a token from another realm (locked #38, #49), which is the same constraint that made the unified view read the local registry rather than fan out (locked #61). This view is about this baseline, like every other operational view.
- **Alerting.** Needs storage and a rules engine, neither of which exists.
