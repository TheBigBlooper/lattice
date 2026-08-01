# Status-console UI design

> **The console runs on Material UI** (locked #62, detail in [material_ui.md](material_ui.md)). The proportion and colour sections below describe that system: Material's 8px grid and its default palette, with the status-colour contrast bar at 3:1 (locked #63). The **direction** - the cluster verdict first, the unified baselines layout, the failure states, and the rule that colour is never the sole indicator - predates the migration and is unaffected by it.

The **status console** is the operator view of one baseline: a React (Vite + TypeScript) single-page app, one container per cluster. This folder is the canonical source for its **visual direction**, its **design tokens**, and the **proportion system** every screen honors.

What this folder does **not** own: the framework rules, component checklist, data layer, testing standard, and accessibility gates. Those live once in [ui_protocol.md](../../protocol/ui_protocol.md) and are not restated here. The REST shapes the console renders come from the OpenAPI contract ([contract_protocol.md](../../protocol/contract_protocol.md)).

Related: [interop_console.md](../features/interop_console.md) (the unified view + peer redirect), [per_baseline_identity.md](../features/per_baseline_identity.md) (the sign-in this console performs), [api_structure.md](../architecture/api_structure.md) (the response envelope it reads).

| Doc | Concern | Settles |
|-----|---------|---------|
| [live_status_transport.md](live_status_transport.md) | How node status reaches the console: polling, why neither Server-Sent Events nor WebSocket is worth it yet, and what would change that. | P6 |
| [activity_vocabulary.md](activity_vocabulary.md) | What the activity log and its toasts can say: the eleven transition kinds, the local/mesh scope split, the glyph and tone rules, and what deliberately produces nothing. | - |

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
| Signed out | A centred sign-in card naming the baseline being entered | A first-class screen, not an error |
| Unified baselines | The verdict on the left, the discovered mesh on the right | The signed-in default once peers exist |

**The signed-out screen is a landing page, not a swap.** It is a centred card carrying the brand mark, the baseline being entered, the sign-in action, and one sentence stating that a session on another baseline does not carry here.

*This retires an earlier rule* that the signed-out screen occupy the same block, position and size as the verdict, so signing in could not make the layout jump. Two things aged that reasoning out. The peer redirect turned this screen into the **first thing** an operator arriving from another baseline sees, which is worth more than a transition. And the guarantee was already partial: signing in reveals the entire mesh panel regardless, so the layout was never going to hold still.

**It claims nothing it cannot know.** Region and baseline version come from an endpoint requiring a token, so before sign-in the console genuinely does not have them. A screen that displayed them would be confidently wrong on the one page an operator has no way to check.

Later screens (the unified multi-baseline view, the peer redirect, and the "you have no access on this peer" landing) are designed with their own features and inherit everything below.

---

## The unified baselines view (confirmed direction)

The overview extends sideways rather than downwards once this baseline has discovered peers: **the local verdict keeps the left column at its full weight, and the mesh takes the wider right column.** The split is a flex ratio - the verdict at `1 1 320px` against the mesh at `2 1 480px` - so the mesh gets roughly twice the width at any size and both wrap to full width on a narrow window, the verdict staying first in reading order. It was originally specified as the golden-section `1 : 1.618`; that scale was retired with the Material UI migration (see [The proportion system](#the-proportion-system) below), and this line described the retired version until #56.

**Both columns end on the same line.** The verdict card fills the row height rather than sitting short beside a tall mesh panel, and the space is spent on the per-service breakdown rather than on padding. Two cards of visibly different height read as one finished and one still loading, which is precisely the wrong thing to suggest on a status screen.

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

Every size in the console comes from **Material UI's 8px spacing grid**. A component asks for spacing in grid units - `p: 2` is 16px, `gap: 1` is 8px - and Material resolves them, so no size is chosen by hand.

**Why this replaced a golden-section scale.** The console previously derived every size from the ratio 1.618, rounded to an integer sequence. That argument was sound on its own terms, and it did not survive adopting a component library: overriding Material's spacing to return the golden sequence makes every component's built-in density assumptions subtly wrong, so each one needs correcting by hand. That is paying for a library while fighting it. The library's conventions came with the library.

**What went with it.** The `1 : 1.618` layout split between the verdict and the mesh, and the Fibonacci block height that sat on the same scale. Column proportion is now a flex ratio, and the reserved block height is a multiple of 8.

| Applied to | Rule |
|--------------|--------|
| Type scale | Material's own variants: `caption` 12 &middot; `body2` 14 &middot; `h6` 20 &middot; `h4` 34 |
| Spacing | Grid units, never pixels: `1` = 8px inside a group, `2` = 16px between elements, `3` = 24px page padding |
| Layout split | The verdict column against the mesh column, roughly 1 : 2 by flex basis, wrapping rather than shrinking |
| Radius | Material's default (4px). Chips keep their pill radius. |
| Surfaces | **Outlined, elevation 0.** Material's elevation is a shadow, and a shadow on a near-black background is close to invisible - an elevated card in dark mode floats with no edge. An outline is legible in both modes. |

**12px is still the floor.** Anything smaller stops being readable at the distance an operator sits from a wall-mounted or side-monitor dashboard, which is the case this console is for. Material's `caption` is 12px, so the floor holds without intervention.

---

## Colour

**Every colour comes from Material UI's default palette**, resolved per mode. `ready`, `degraded` and `down` map onto `success`, `warning` and `error`; surfaces, text, and dividers come from the same theme. Nothing is overridden, so there is no palette to maintain.

The theme lives in one file (`src/theme/theme.ts`), and the `check:tokens` gate fails the build on a colour literal anywhere else - including inside an `sx` prop, which accepts a raw colour just as readily as a palette key and is where this drift would now appear.

### The one measured cost

Material's light-mode `warning` (`#ed6c02`) measures **3.11:1** against the page, below the 4.5:1 WCAG AA requires for normal text. No colour in Material's orange ramp clears it: `orange[900]` reaches 3.79:1 and `orange[800]` 3.08:1. The bar was deliberately relaxed to 3:1 for status colours rather than overriding the palette (locked #63). Dark mode is unaffected at 9.64:1.

**Colour is never the only signal**, and that is what bounds the cost. Every state renders its colour, its glyph, **and its word**. The word is what carries the meaning for an operator who cannot resolve the hue, and it is untouched by this trade. Restoring the bar is a single palette override.

---
## Components this direction needs

The vocabulary is already fixed in [glossary.md](../../reference/glossary.md) and these are the same objects, not new ones:

| Component | Renders | Built from |
|-------------|-----------|--------------|
| App bar | The baseline's identity and the operator's session. Deliberately more structure than one screen needs: it is where navigation lands when the console gains operational views. | `AppBar` + `Toolbar` |
| Verdict block | The cluster's rolled-up state at `h4`, its icon, its word, and a one-line count of services ready | `Paper` |
| Status pill | One service's state: colour, icon, and word | `Chip`, as a list item |
| Signed-out block | Occupies the verdict block's position and size; a line of copy and one sign-in action | the same `Paper` |
| Mesh rollup | How many discovered peers are reachable, at `h6`, above the peer table | `Typography` |
| Peer table | One row per discovered baseline: identity, region, health as last heard, version, and that age | `Table` |

**Reuse over rebuild applies to all of them.** The status pill in the verdict's breakdown is one component, configured; a second pill implementation is a defect, not a variant. The verdict block and the signed-out screen are literally the same component, which is what makes "signing in does not reflow the page" structural rather than coincidental.

**Peers are a table, not a list.** The data is genuinely tabular, and a table gives a screen-reader user the column name alongside each value. It is also the only shape here that survives a mesh of a dozen baselines unchanged.

---

## Open

- **Live-status transport.** The overview renders from `getBaseline`, which already carries the per-service health and the rollup, so the console needs no streaming transport to be correct. When one lands it sits behind the single data hook `ui_protocol.md` requires, and no panel changes.
- **Density at scale.** The detail strip is designed for the handful of services a baseline runs today. A cluster of dozens needs the virtualized list the protocol already calls for; the verdict block itself does not change.
