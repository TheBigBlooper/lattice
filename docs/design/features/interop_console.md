# Interop Console - Unified View + Peer Redirect

The federation layer of the status console: beyond a baseline's own read-only status (the console skeleton), the console shows a **unified view of all discovered baselines** and lets an operator **jump to a peer's own console** to act on it. Cross-cutting: it spans the status console (UI), the mesh discovery data (`ClusterAnnouncement`), and each baseline's own services.

Related: [cluster_interop.md](../architecture/cluster_interop.md) (the federation mechanism + order ownership), [mesh_discovery.md](../architecture/mesh_discovery.md) (the peer registry + advertised endpoints), [api_structure.md](../architecture/api_structure.md) (the REST envelope the unified view reads), [ui_protocol.md](../../protocol/ui_protocol.md).

---

## Principle - each baseline owns its own data; federate by redirect

Under Shape A federation (locked #37), each baseline owns its own data (orders included), and cross-cluster interaction is a **UI redirect to the owning baseline**, not a mesh-mediated operation driven from one console. Two capabilities:

- **A unified, read-only view** of every discovered baseline (health, nodes, and each baseline's own detail), pulled **live from each owner**.
- **A redirect** that navigates the operator to a chosen peer's own console, where they act natively on that peer.

"Do X on B" means "**go to B and do X there**," never "A drives X toward B over the mesh." The mesh is only how the console learns **which** baselines exist and **where** they are (see [mesh_discovery.md](../architecture/mesh_discovery.md)).

---

## Order ownership (declared explicitly)

**Each baseline always owns its own orders.** An operator who wants to place or change an order on baseline B is redirected to B's own console and creates it on B; B owns it end to end, in B's own orders service and data model. There is no cross-cluster order-of-record split and no fulfillment handoff - a baseline never creates or owns an order on another baseline's behalf. The same semantic is stated in [cluster_interop.md](../architecture/cluster_interop.md).

---

## The unified view (read-only, live-pull)

The console reads its **peer list** from its own cluster's registry (populated by the mesh), then the **browser pulls each peer's status/details live** from that peer's advertised `apiBaseUrl`:

- Each discovered baseline renders with the shared console components (one `NodeCard`, one `StatusPill`, one health indicator) - the same primitives the local view uses, never a parallel set.
- A peer past its liveness TTL shows as `UNREACHABLE` with its last-known snapshot (honest "was here, gone silent"), not a vanished row.
- No baseline holds or replicates a peer's data; each serves its own truth. This needs **CORS allowed** between baseline consoles/APIs on the shared operator network (a recorded requirement).

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

- **Unified baselines panel:** the local baseline plus every discovered peer, each a `NodeCard` with health, nodes, reachability, and a **"go to this baseline"** redirect action. Live-pulled per peer.
- **Layout direction (to confirm on the console skeleton's tokens):** the local baseline foregrounded, discovered peers listed alongside; the golden-section proportion + design tokens apply once that foundation lands. This is a UI ticket, so it still needs a **confirmed mockup** (Enforcement Rule 16) before build - it carries `needs-mockup`.

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

- **P6 (live-status transport, Server-Sent Events vs WebSocket)** - still a deferred design question; it governs how the local + per-peer status refreshes live. Settle before the console build.
- **The console skeleton** (token + proportion foundation) - the base this unifies into.
- **Mesh announce + discovery** - must advertise `consoleUrl` + `apiBaseUrl` in `ClusterAnnouncement` and surface them in the peer registry; this feature reads them.
- **The orders + inventory services** - each baseline's own services that the unified view reads and that an operator acts on after a redirect.
- **Keycloak (locked #38)** - per-baseline auth; the redirect target authenticates the operator. Its own ticket.
- **Mockup gate (Enforcement Rule 16):** carries `needs-mockup` until a founder confirms the unified-view visual direction on the console skeleton's tokens. The mockups must also cover the **failure states** above (a peer `UNREACHABLE` with last-known detail, a baseline `degraded` / `down`), not just the healthy view - those states are the point of the architecture, and a mockup that only shows everything green leaves the most important screen ungated.
- **CORS:** baseline consoles/APIs must allow cross-origin reads on the shared operator network (the live-pull requirement).

---

## Decisions settled here (Shape A)

- Federation is **UI redirect to the owning baseline** + a **unified read-only view**; never mesh-mediated cross-cluster operations from one console.
- **Order ownership:** each baseline always owns its own orders; no handoff, no cross-cluster order-of-record.
- The unified view is **live-pull** (browser reads each peer's `apiBaseUrl`); CORS is a requirement.
- **No new mesh envelopes**; `AvailabilityQuery`/`Response` and handoff-based triggers are removed; `ClusterAnnouncement` (with `consoleUrl` + `apiBaseUrl`) is the only envelope.
- Redirect lands on the peer console root; per-baseline Keycloak auth; deep-link + cross-baseline single-sign-on deferred.

Promoted to a locked decision - see [locked_decisions.md](../../reference/locked_decisions.md) #37. The `[#N]` references above are the tracking issues.
