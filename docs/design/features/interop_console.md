# Interop Console - Unified View + Peer Redirect

The federation layer of the status console: beyond a baseline's own read-only status ([#11] skeleton), the console shows a **unified view of all discovered baselines** and lets an operator **jump to a peer's own console** to act on it. Grounds ticket #28. Cross-cutting: it spans the status console (UI), the mesh discovery data (`ClusterAnnouncement`), and each baseline's own services.

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

The unified view + redirect **extend the console skeleton ([#11])** into a single surface; they do not add a parallel console. Reusing the shared components:

- **Unified baselines panel:** the local baseline plus every discovered peer, each a `NodeCard` with health, nodes, reachability, and a **"go to this baseline"** redirect action. Live-pulled per peer.
- **Layout direction (to confirm on [#11]'s tokens):** the local baseline foregrounded, discovered peers listed alongside; the golden-section proportion + design tokens apply once [#11]'s foundation lands. This is a UI ticket, so it still needs a **confirmed mockup** (Enforcement Rule 16) before build - it carries `needs-mockup`.

---

## Demonstrating the failure behavior (requirement, mechanism unsettled)

The interesting behavior of this federation is what happens when things go **wrong**, and it is invisible in a healthy screenshot. A peer aging out to `UNREACHABLE` but staying on screen with its last-known detail, a baseline dropping to `degraded` or `down`, the mesh going quiet when the broker is lost - these are the states that show an engineer how the architecture actually behaves, and they are the whole reason the design chose retention over deletion.

**Requirement:** the console's mockups and stories must cover these states, and it must be possible to **put the console into them on demand** for architecture demonstrations to other engineers, rather than hoping to catch one live.

**Unsettled - two readings, one of which conflicts with an existing rule:**

- **Presentation-only.** The console renders the states from fixture or story data (a demo mode, or component stories per state). Nothing is triggered in a running service, so this sits entirely within the UI and conflicts with nothing.
- **Genuinely triggered.** The console causes the real condition (dropping a mesh connection, forcing a service to report down). This collides directly with [qa_protocol.md](../../protocol/qa_protocol.md): *"A steward-only runtime 'data mode' toggle is **not** used (it would put a dev affordance into the running services)."* Honouring both would mean either amending that rule or keeping the trigger outside the services (for example the QA stack simply stopping a container, which is exactly how these states were reproduced during the mesh-gateway QA).

The requirement is recorded here so the console tickets carry it; **which mechanism applies is a founder decision and is not settled by this doc.**

---

## Dependencies + gates

- **P6 (live-status transport, Server-Sent Events vs WebSocket)** - still a deferred design question; it governs how the local + per-peer status refreshes live. Settle before the console build.
- **[#11]** (console skeleton + token/proportion foundation) - the base this unifies into.
- **[#9]** (mesh announce + discovery) - must advertise `consoleUrl` + `apiBaseUrl` in `ClusterAnnouncement` and surface them in the peer registry; this feature reads them.
- **[#6]/[#7]** (the orders + inventory services) - each baseline's own services that the unified view reads and that an operator acts on after a redirect.
- **Keycloak (locked #38)** - per-baseline auth; the redirect target authenticates the operator. Its own ticket.
- **Mockup gate (Enforcement Rule 16):** carries `needs-mockup` until a founder confirms the unified-view visual direction on [#11]'s tokens. The mockups must also cover the **failure states** above (a peer `UNREACHABLE` with last-known detail, a baseline `degraded` / `down`), not just the healthy view - those states are the point of the architecture, and a mockup that only shows everything green leaves the most important screen ungated.
- **CORS:** baseline consoles/APIs must allow cross-origin reads on the shared operator network (the live-pull requirement).

---

## Decisions settled here (Shape A)

- Federation is **UI redirect to the owning baseline** + a **unified read-only view**; never mesh-mediated cross-cluster operations from one console.
- **Order ownership:** each baseline always owns its own orders; no handoff, no cross-cluster order-of-record.
- The unified view is **live-pull** (browser reads each peer's `apiBaseUrl`); CORS is a requirement.
- **No new mesh envelopes**; `AvailabilityQuery`/`Response` and handoff-based triggers are removed; `ClusterAnnouncement` (with `consoleUrl` + `apiBaseUrl`) is the only envelope.
- Redirect lands on the peer console root; per-baseline Keycloak auth; deep-link + cross-baseline single-sign-on deferred.

Promoted to a locked decision - see [locked_decisions.md](../../reference/locked_decisions.md) #37. The `[#N]` references above are the tracking issues.
