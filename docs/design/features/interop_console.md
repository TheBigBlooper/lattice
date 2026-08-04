# Interop Console - Unified View + Peer Redirect

The federation layer of the status console: beyond a baseline's own read-only status (the console skeleton), the console shows a **unified view of all discovered baselines** and lets an operator **jump to a peer's own console** to act on it. Cross-cutting: it spans the status console (UI), the mesh discovery data (`ClusterAnnouncement`), and each baseline's own services.

Related: [cluster_interop.md](../architecture/cluster_interop.md) (the federation mechanism + order ownership), [mesh_discovery.md](../architecture/mesh_discovery.md) (the peer registry + advertised endpoints), [api_structure.md](../architecture/api_structure.md) (the REST envelope the unified view reads), [ui_protocol.md](../../protocol/ui_protocol.md).

---

## Principle - each baseline owns its own data; federate by redirect

Under Shape A federation (locked #37), each baseline owns its own data (orders included), and cross-cluster interaction is a **UI redirect to the owning baseline**, not a mesh-mediated operation driven from one console. Two capabilities:

- **A unified, read-only view** of every discovered baseline (health and reachability as this baseline last heard them), read from **this baseline's own peer registry**.
- **A redirect** that navigates the operator to a chosen peer's own console, where they act natively on that peer.

"Do X on B" means "**go to B and do X there**," never "A drives X toward B over the mesh." The mesh is only how the console learns **which** baselines exist and **where** they are (see [mesh_discovery.md](../architecture/mesh_discovery.md)).

---

## Order ownership (declared explicitly)

**Each baseline always owns its own orders.** An operator who wants to place or change an order on baseline B is redirected to B's own console and creates it on B; B owns it end to end, in B's own orders service and data model. There is no cross-cluster order-of-record split and no fulfillment handoff - a baseline never creates or owns an order on another baseline's behalf. The same semantic is stated in [cluster_interop.md](../architecture/cluster_interop.md).

---

## The unified view (read-only, from the local registry)

The console reads its **peer list** from its own cluster's registry (populated by the mesh) and renders each peer from what that registry holds. **The browser does not read a peer's API** (locked #61, correcting the live-pull clause of #37):

- Each discovered baseline renders with the shared console primitives - the same status glyph and vocabulary the local view uses, never a parallel set.
- A peer past its liveness TTL shows as `UNREACHABLE` with its last-known snapshot (honest "was here, gone silent"), not a vanished row.
- No baseline holds or replicates a peer's data; the registry holds only what that peer announced about itself.

**Why the live-pull was dropped.** It cannot authenticate. Every `/api/v1` operation is bearer-protected against its own baseline's realm (locked #48), a peer refuses a token minted by another realm (locked #38), and realm membership is deliberately unsynchronized (locked #49), so the operator may hold no grant on that peer at all. A browser fan-out returns 401 from every peer, and CORS does not change that - only `/health` and `/readiness` are open, and neither carries a rollup. The clause predates the identity model and did not survive it. The cost is small: locked #59 measured staleness as dominated by `PEER_TTL`, not by the read.

**CORS is therefore no longer a requirement of this feature.** It returns only if cross-baseline read access is ever settled.

---

## Reaching a peer (redirect)

Each discovered baseline in the unified view carries a **"go to this baseline"** action that redirects the browser to the peer's own `consoleUrl` (advertised in the registry):

- The redirect lands on the peer's **console root**; the operator proceeds from there. No shared cross-baseline route contract; deep-linking into a specific peer screen is deferred.
- The peer's own **Keycloak** authenticates the operator on arrival (locked #38); for MVP the operator re-authenticates per baseline (no cross-baseline single-sign-on yet).
- A redirect to an unreachable peer fails at the browser like any unreachable site; the view already shows that peer as unreachable.

---

## No new mesh envelopes

Shape A adds **no** directed mesh envelopes. The previously-designed `AvailabilityQuery` / `AvailabilityResponse` and the reuse of `FulfillmentHandoff` / `HandoffAck` for operator triggers are **removed** - there are no cross-cluster operator triggers, because operators act on the owning baseline directly. `ClusterAnnouncement` (extended to advertise `consoleUrl` + `apiBaseUrl`) is the only mesh envelope, and it is discovery, not a console trigger (see [mesh_envelopes.md](../architecture/mesh_envelopes.md)).

---

## UI - one unified surface

The unified view + redirect **extend the console skeleton** into a single surface; they do not add a parallel console. Reusing the shared components:

- **Unified baselines panel:** the local baseline's verdict keeps the left column at full weight; the discovered mesh occupies the right at the documented `1 : 1.618` split, opening with its own rollup ("1 of 2 peers reachable") above a compact peer list. The **"go to this baseline"** redirect action attaches to a peer row and is its own ticket.
- **Layout direction: confirmed.** The full direction, the two alternatives rejected, and the one derived value it introduces are recorded in [ui/_index.md](../ui/_index.md#the-unified-baselines-view-confirmed-direction).

---

## Demonstrating the failure behavior (requirement, mechanism unsettled)

The interesting behavior of this federation is what happens when things go **wrong**, and it is invisible in a healthy screenshot. A peer aging out to `UNREACHABLE` but staying on screen with its last-known detail, a baseline dropping to `degraded` or `down`, the mesh going quiet when the broker is lost - these are the states that show an engineer how the architecture actually behaves, and they are the whole reason the design chose retention over deletion.

**Requirement:** the console's mockups and stories must cover these states, and it must be possible to **put the console into them on demand** for architecture demonstrations to other engineers, rather than hoping to catch one live.

**Settled: genuinely triggered, from a separate tool - never from the console or the services.**

The states are induced for real rather than simulated, because a faked `UNREACHABLE` proves nothing to an engineer being shown how the architecture behaves. But the trigger lives **outside** the product: a separate demonstration harness acts on the infrastructure (stopping the broker, stopping a peer baseline, stopping one service to force `degraded`), exactly as these states were reproduced by hand during the mesh-gateway QA.

That placement is what keeps two rules intact at once:

- [qa_protocol.md](../../protocol/qa_protocol.md) forbids a dev affordance **in the running services** (*"a steward-only runtime 'data mode' toggle is **not** used"*). A tool that stops a container adds nothing to any service.
- The console stays product code. It renders whatever the mesh and the registry report, with no demo mode, no toggles, and no branch that exists only for a demonstration.

So the console's only obligation here is to **render these states correctly and legibly** - which is a mockup and component-story requirement, not a feature. Inducing them is the harness's job.

---

## Dependencies + gates

- **Live-status transport - settled: polling** (locked #59). The peer list refreshes on the same 10-second poll as the baseline, which is well inside the `PEER_TTL` that dominates staleness anyway.
- **The console skeleton** (token + proportion foundation) - the base this unifies into.
- **Mesh announce + discovery** - must advertise `consoleUrl` + `apiBaseUrl` in `ClusterAnnouncement` and surface them in the peer registry; this feature reads them.
- **The orders + inventory services** - each baseline's own services, which an operator acts on after a redirect. The unified view does not read them, on a peer or locally; it renders from the peer registry.
- **Keycloak (locked #38)** - per-baseline auth; the redirect target authenticates the operator. Its own ticket.
- **Mockup gate (Enforcement Rule 16): satisfied.** The founder confirmed the direction recorded in [ui/_index.md](../ui/_index.md#the-unified-baselines-view-confirmed-direction). The options put to them rendered the **failure states** rather than a healthy screen - a peer `UNREACHABLE` retained with its last-known detail, and the local baseline `degraded` - because those states are the point of the architecture, and a mockup showing everything green leaves the most important screen ungated.
- **CORS:** no longer required. The unified view reads only this baseline own registry (locked #61); a redirect is a navigation, not a cross-origin read.

---

## Decisions settled here (Shape A)

- Federation is **UI redirect to the owning baseline** + a **unified read-only view**; never mesh-mediated cross-cluster operations from one console.
- **Order ownership:** each baseline always owns its own orders; no handoff, no cross-cluster order-of-record.
- The unified view reads the **local peer registry only** (locked #61, correcting #37); no cross-origin read, so no CORS requirement.
- **No new mesh envelopes**; `AvailabilityQuery`/`Response` and handoff-based triggers are removed; `ClusterAnnouncement` (with `consoleUrl` + `apiBaseUrl`) is the only envelope.
- Redirect lands on the peer console root; per-baseline Keycloak auth; deep-link + cross-baseline single-sign-on deferred.

Promoted to a locked decision - see [locked_decisions.md](../../reference/locked_decisions.md) #37. The `[#N]` references above are the tracking issues.
