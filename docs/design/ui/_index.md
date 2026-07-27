# Status-console UI design

The **status console** is the operator view of one baseline: a React (Vite + TypeScript) single-page app, one container per cluster. This folder is the canonical source for its **visual direction**, its **design tokens**, and the **proportion system** every screen honors.

What this folder does **not** own: the framework rules, component checklist, data layer, testing standard, and accessibility gates. Those live once in [ui_protocol.md](../../protocol/ui_protocol.md) and are not restated here. The REST shapes the console renders come from the OpenAPI contract ([contract_protocol.md](../../protocol/contract_protocol.md)).

Related: [interop_console.md](../features/interop_console.md) (the unified view + peer redirect), [per_baseline_identity.md](../features/per_baseline_identity.md) (the sign-in this console performs), [api_structure.md](../architecture/api_structure.md) (the response envelope it reads).

| Doc | Concern | Settles |
|-----|---------|---------|
| [live_status_transport.md](live_status_transport.md) | How node status reaches the console: polling, why neither Server-Sent Events nor WebSocket is worth it yet, and what would change that. | P6 |

---

## The direction: the cluster's verdict comes first

**The console answers "is this baseline healthy?" before it answers "what is each service doing?"** A single verdict - `ready`, `degraded`, or `down` - is the largest thing on the screen, with the per-service breakdown beneath it.

This is a deliberate choice against two alternatives that were considered and rejected:

| Considered | Why not |
|--------------|-----------|
| A dense service table (one row per service) | Scales furthest and is the least work, but it makes the operator compute the cluster's overall state themselves by scanning rows. The system already computes that state; refusing to show it is a step backwards. |
| A card grid (one card per service) | Matches the existing component vocabulary best and is the most scannable at three services, but it degrades badly once a cluster runs dozens, and it still leaves the cluster verdict implicit. |

**The verdict is not computed in the browser.** The mesh-gateway already rolls its services' readiness into one label and serves it on `getBaseline`; the console renders that value. Recomputing it client-side would fork the definition of "degraded" across two languages and let the console disagree with what the baseline announces to its peers.

The per-service breakdown stays on screen underneath, so the verdict is never a black box: an operator seeing `degraded` sees which service caused it in the same glance, without navigating.

### The screen set

| Screen | Shows | State it renders |
|----------|---------|--------------------|
| Cluster overview | The verdict, then each service with its state and baseline version | The signed-in default |
| Signed out | The verdict block replaced **in place** by a sign-in prompt | A first-class screen, not an error |
| Unified baselines | The verdict on the left, the discovered mesh on the right | The signed-in default once peers exist |

**The signed-out screen swaps in place.** It occupies the same block, in the same position, at the same size as the verdict. Signing in must not make the layout jump, because a layout that reflows on sign-in reads as a page that broke and then recovered.

Later screens (the unified multi-baseline view, the peer redirect, and the "you have no access on this peer" landing) are designed with their own features and inherit everything below.

---

## The unified baselines view (confirmed direction)

The overview extends sideways rather than downwards once this baseline has discovered peers: **the local verdict keeps the left column at its full weight, and the mesh occupies the right at the documented `1 : 1.618` split.** This is the first consumer of that ratio.

**The mesh gets its own verdict.** The right column opens with a rollup of the mesh itself at `21` - "1 of 2 peers reachable" - with the peers listed compactly beneath it. That mirrors the pattern the cluster verdict already establishes one level down (a rollup, then its breakdown), so the screen teaches its logic once and applies it twice. Without it, "is the mesh healthy?" is a question the operator answers by counting rows, and that question is the whole reason this view exists.

The two verdicts are separated by weight, not by decoration: the cluster's is `34`, the mesh's is `21`. The local baseline is never repeated as a peer.

**The mesh rollup is the one derived value on the screen.** Nothing serves it, so the console counts it. That is a deliberate and bounded exception to "the console renders verdicts, it does not compute them": counting how many peers are reachable is arithmetic over a field the registry already sets, not a second definition of `degraded`. Health itself is still never recomputed. If the gateway ever serves a mesh rollup, the console reads it and this note goes away.

**Peers render from the local registry alone.** The browser does not read a peer's API. Each peer shows its identity, region, baseline version, health as this baseline last heard it, and how long ago that was; a peer past its liveness window is retained with that last-known detail and marked unreachable rather than removed. The reasoning is identity, not effort - see [locked_decisions.md](../../reference/locked_decisions.md) #61.

### Directions considered and rejected

| Considered | Why not |
|--------------|-----------|
| Peers in a section **beneath** the verdict | Smallest change and the shipped screen never moves, but the local baseline and its peers end up drawn in two different visual languages, so comparing them means switching how you read. Peers read as an appendix to one cluster rather than as a mesh. |
| **One grid of equal cards**, local included | The most genuinely unified answer, and the best of the three if the console's job is to operate a federation. Rejected here because it demotes the cluster verdict from the largest thing on screen to one card among many, which contradicts the direction settled above. That is a change worth making deliberately, not as a side effect of adding peers. |
| A **ledger** of peers (aligned columns, hairline rules) | Scales furthest and is the right answer at a dozen baselines. Held in reserve rather than rejected: the mesh rollup sits above a table exactly as it sits above the compact rows, so the row list is the swappable part when density demands it. |

---

## The proportion system

Every size in the console comes from **one golden-section scale**. The ratio is 1.618; rounded to whole pixels its powers give an integer sequence, which is the scale used everywhere:

```
5   8   13   21   34   55
```

**Why a single scale.** An operator console is dense and mostly text. Without one ratio governing type, spacing, and layout, a dozen ad-hoc values accumulate and every panel drifts a little from the last. One sequence means any two sizes on screen are already in proportion, and "which padding do I use here" has an answer rather than an opinion.

| Applied to | Rule |
|--------------|--------|
| Type scale | `12` metadata · `13` body · `21` section heading · `34` the cluster verdict |
| Spacing | `5` inside a pill · `8` between related items · `13` inside a card · `21` between sections · `34` page gutter |
| Line height | `1.618` on body copy; `1.2` on the verdict, where the ratio would loosen a single large word |
| Layout split | The verdict block to the detail region reads `1 : 1.618` at the console's default width |
| Radius | `5` on pills and controls, `8` on cards. Never a third value. |

**12px is the floor.** Anything smaller stops being readable at the distance an operator actually sits from a wall-mounted or side-monitor dashboard, which is the case this console is for.

**One documented escape.** A value that must be dynamic (a computed bar width, a virtualized row height) is exempt and carries a comment saying so. Everything else references a token.

---

## Tokens

The console reads these through its theme module and the theme hook. **No component hardcodes a color, a spacing value, or a radius** - that rule and its lint gate are owned by [ui_protocol.md](../../protocol/ui_protocol.md#styling-and-theming).

### Status colors

Three states, each defined in both modes. Every value below was checked against the surface it sits on and meets the WCAG AA 4.5:1 minimum for normal text:

| Token | Light | Contrast on light surface | Dark | Contrast on dark surface |
|---------|---------|-----------------------------|--------|----------------------------|
| `statusReady` | `#1a7f37` | 5.08:1 | `#3fb950` | 7.45:1 |
| `statusDegraded` | `#9a6700` | 4.87:1 | `#d29922` | 7.50:1 |
| `statusDown` | `#cf222e` | 5.36:1 | `#f85149` | 5.65:1 |

**Color is never the only signal.** Every state renders its color *and* an icon *and* the word. A console that distinguishes healthy from failed by hue alone is unreadable to a color-blind operator, which on a status dashboard is a correctness failure, not a polish item. This is why `degraded` uses an amber that is legible rather than the brightest available: the word and icon carry the meaning, and the color only reinforces it.

`statusDegraded` deliberately reads as a warning rather than a second failure state. A degraded cluster is still serving; rendering it as alarming as `down` trains operators to ignore it.

### Surface, text, and border

| Token | Light | Dark |
|---------|---------|--------|
| `surfacePage` | `#ffffff` | `#0d1117` |
| `surfaceRaised` | `#f6f8fa` | `#161b22` |
| `textPrimary` | `#1f2328` | `#e6edf3` |
| `textSecondary` | `#59636e` | `#8d96a0` |
| `border` | `#d1d9e0` | `#30363d` |

Light and dark are **reactive**, not a build flag: the console follows the operator's system preference and re-themes live.

---

## Components this direction needs

The vocabulary is already fixed in [glossary.md](../../reference/glossary.md) and these are the same objects, not new ones:

| Component | Renders |
|-------------|-----------|
| Verdict block | The cluster's rolled-up state at `34`, its icon, its word, and a one-line count of services ready |
| Status pill | One service's state: color, icon, and word, at `13` in a `5`-radius pill |
| Node card | One service: its name, its status pill, its baseline version |
| Signed-out block | Occupies the verdict block's position and size; a line of copy and one sign-in action |
| Mesh rollup | How many discovered peers are reachable, at `21`, above the peer list |
| Peer row | One discovered baseline: glyph, cluster id, region, health as last heard, and that age |

**Reuse over rebuild applies to all of them.** The status pill on the verdict's detail strip is the same component the node card uses, configured differently. A second pill implementation is a defect, not a variant.

---

## Open

- **Live-status transport.** The overview renders from `getBaseline`, which already carries the per-service health and the rollup, so the console needs no streaming transport to be correct. When one lands it sits behind the single data hook `ui_protocol.md` requires, and no panel changes.
- **Density at scale.** The detail strip is designed for the handful of services a baseline runs today. A cluster of dozens needs the virtualized list the protocol already calls for; the verdict block itself does not change.
