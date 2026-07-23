# Interactive Interop Console

The interactive layer of the status console: beyond read-only status ([#11] skeleton, [#12] peers/handoffs), an operator on one cluster can **trigger cross-cluster operations** and watch interoperability happen live. Grounds ticket #26. Cross-cutting: it spans the status console (UI), the `lattice-contract` envelopes, the orders + inventory services, and the mesh.

Related: [cluster_interop.md](../architecture/cluster_interop.md) (the handoff flow + order ownership), [mesh_envelopes.md](../architecture/mesh_envelopes.md) (envelope shape/versioning), [mesh_discovery.md](../architecture/mesh_discovery.md) (the peer list), [api_structure.md](../architecture/api_structure.md) (the REST envelope), [ui_protocol.md](../../protocol/ui_protocol.md).

---

## Principle - mesh-mediated, never cross-cluster REST

The console on cluster **A** talks only to **A's own services** (per locked #13, all cross-cluster interaction is over the Artemis mesh; the console never calls a peer's REST API). A manual trigger causes **A** to initiate a mesh operation toward a peer, and the UI **visualizes the envelopes flowing** in real time. "Do X on B" always means "A initiates X toward B over the mesh," not "the console calls B directly."

---

## Order ownership (declared explicitly)

**The order-of-record stays on the originating cluster (A); a peer (B) provides fulfillment, not ownership.** When an operator on A places an order A cannot fill locally and force-routes it to B:

- **A creates and owns the order** in its own orders service (`order-501`), and tracks its state (`PENDING_HANDOFF -> accepted/rejected`, then `fulfilled by <peer>`).
- **B provides fulfillment only** - it reserves stock and acknowledges via `HandoffAck`; it mints its own local record with an `originRef` back to A's order, but it does **not** become the order's owner.
- This mirrors real regional fulfillment: a customer's order lives at their hub (A); a peer hub (B) just fills it.

```
A.orders: create order-501 {sku-42 x3, cannot fill locally}   [A owns the order-of-record]
  -> operator force-routes fulfillment to hub-east
  -> FulfillmentHandoff -> hub-east reserves stock -> HandoffAck ACCEPTED
A.orders: order-501 -> ACCEPTED (fulfilled by hub-east)         [A still owns it]
```

This is **not** remote order-creation (the order is never created/owned on B). The same semantic is stated in [cluster_interop.md](../architecture/cluster_interop.md).

---

## Triggers (MVP)

Two operator actions, each targeting an **explicitly chosen** peer from the discovered-peers list:

| Trigger | Effect | Envelope(s) | Guard |
|---------|--------|-------------|-------|
| **Check peer inventory** | Query a chosen peer's availability for an item (read-only). | `AvailabilityQuery` -> peer; `AvailabilityResponse` back | fires directly |
| **Place cross-cluster order** | Create a real local order on A (A owns it) that force-routes fulfillment to the chosen peer. | `FulfillmentHandoff` -> peer; `HandoffAck` back | **confirm-on-write** |

Targeting is always an **explicit operator choice** of the target peer (no auto-routing at MVP). The write trigger (place order) shows a confirmation naming the item, quantity, and target peer before firing; the read trigger fires directly.

---

## New mesh envelopes (touch `lattice-contract`)

A directed request/response pair, mirroring `FulfillmentHandoff`/`HandoffAck` (correlationId-linked, versioned like every envelope):

| Envelope | Direction | Payload (illustrative) |
|----------|-----------|------------------------|
| `AvailabilityQuery` | to the peer's inbox | `itemSku`, `quantity` |
| `AvailabilityResponse` | back to the sender's inbox | `itemSku`, `availableQuantity`, `canFill`; `correlationId` (in header) = the query's `messageId` |

`FulfillmentHandoff` / `HandoffAck` are **reused** for order placement (from the mesh design + #10). The availability pair is the deferred `StockSignal` need, resolved as an **on-demand directed query** rather than a broadcast.

---

## Trigger flow (async, mesh-mediated)

Each trigger is a new `/api/v1/interop/*` operation on A's service that publishes the mesh envelope and returns **immediately** - it does not block on the mesh round-trip (which can take up to the ack-timeout). The result streams back to the UI over the **live-status transport (P6)**:

```
POST /api/v1/interop/orders { itemSku, quantity, targetCluster } -> 202 { operationId }
   ... A creates order-501, publishes FulfillmentHandoff -> hub-east ...
   ... hub-east acks ...
live transport pushes: operation <operationId> -> ACCEPTED        (UI updates live)
```

The same shape backs `check inventory` (`POST /api/v1/interop/availability -> 202 { operationId }`, `AvailabilityResponse` streamed back). Exact REST operations are defined in the OpenAPI spec ([api_structure.md](../architecture/api_structure.md)) when built.

---

## UI - one unified mesh/interop surface

The interactive capability **extends [#12]'s peers/handoffs view into a single mesh/interop surface** (it does not add a parallel console): discovered peers, all cross-cluster activity (both observed peer-initiated handoffs and operator-triggered operations), and the trigger controls, all reusing the shared console components (one `NodeCard`, one `StatusPill`).

- **Live operation feed:** each triggered operation is a card with a **per-step timeline** (`envelope sent -> delivered -> peer responded/acked`), its state transitioning live (`PENDING -> ACCEPTED/REJECTED`), the target peer, and the payload. Observed handoffs appear in the same feed.
- **Layout direction (confirmed, conceptual):** **two columns** - the discovered-peers list + trigger controls on the left, the live operation feed on the right (selection/action left, consequences streaming right). The golden-section cut between columns and the design tokens are applied when [#11]'s proportion/token foundation lands.

---

## Dependencies + gates

- **P6 (live-status transport, SSE vs WebSocket)** - a **prerequisite**, still an open deferred design question (it also gates [#11]). Settle it before the console build.
- **[#11]** (console skeleton + token/proportion foundation) and **[#12]** (this unifies into it).
- **[#10]** (`FulfillmentHandoff` handoff, reused for order placement); **[#6]/[#7]** (the orders + inventory services the triggers exercise - inventory answers `AvailabilityQuery`, orders owns the order + emits the handoff).
- **Mockup gate (Enforcement Rule 16):** the two-column *layout direction* is confirmed; the UI build ticket still needs a **pixel mockup on [#11]'s tokens** and carries `needs-mockup` until a founder confirms it.
- **Auth (P5):** the write triggers should eventually sit behind an elevated role; recorded as a deferred gate, not built now.

---

## Decisions settled here

- Interactive interop is **mesh-mediated** (A drives A's services -> mesh -> peer; never cross-cluster REST).
- **Order ownership:** the originator (A) owns the order-of-record; a peer (B) provides fulfillment only.
- MVP triggers: check peer inventory + place cross-cluster order; explicit operator-chosen target; confirm-on-write.
- New envelopes `AvailabilityQuery` / `AvailabilityResponse` (directed request/response); `FulfillmentHandoff`/`HandoffAck` reused.
- Async trigger flow (202 + `operationId`, result via P6); one unified mesh/interop surface extending #12; two-column layout.

Promoted to a locked decision - see [locked_decisions.md](../../reference/locked_decisions.md). The `[#N]` references above are the tracking issues.
