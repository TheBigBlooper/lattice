# Cluster Interop - Canonical Envelope + Handoff

How two hubs with divergent Elasticsearch data models exchange work while staying interoperable. Settles deferred question P2.

Related: [mesh_envelopes.md](mesh_envelopes.md) (the wire shapes), [mesh_discovery.md](mesh_discovery.md) (addressing + peer registry), [data_model.md](data_model.md) (local models that diverge), [locked_decisions.md](../../reference/locked_decisions.md) (#14 divergent models, #15 interoperable, #18 envelopes).

---

## Principle - canonical envelope, per-cluster translation

Clusters own **divergent** Elasticsearch models (locked #14) yet must **interoperate** (locked #15). The mechanism: **nothing local ever crosses the mesh.** Clusters agree only on the versioned `lattice-contract` envelopes as the **canonical form**, and each cluster's `mesh-gateway` service translates between its local documents and the envelope:

```
West orders (local ES doc)
   -> mesh-gateway maps to FulfillmentHandoff envelope (canonical)
   -> Artemis mesh
   -> Central mesh-gateway maps envelope -> its own local ES doc
```

- A hub with **extra local fields** simply does not put them in the envelope.
- A hub that receives an envelope with **a field it does not use** ignores it (forward-compat, see [mesh_envelopes.md](mesh_envelopes.md)).

This keeps the mesh a stable contract while each hub's internals evolve freely.

---

## Cross-cluster identity

Each hub has its own local ids, so a shared subject is named by a **cluster-qualified id**: `<clusterId>:<localId>`.

- The originating hub sets the envelope `subjectId` to its own qualified id, e.g. `hub-west:order-123`.
- The receiving hub **mints its own local id** for its copy (e.g. `order-88`) and stores the `subjectId` as an **`originRef`** foreign reference.
- The qualified id is globally unique, human-readable (provenance at a glance), and a natural idempotency key.

```
West local id:      order-123
envelope subjectId: "hub-west:order-123"
Central local id:   order-88   (its own)   + originRef = "hub-west:order-123"
```

---

## Handoff flow (FulfillmentHandoff + HandoffAck)

West cannot fill an order line and hands it to Central:

1. **Emit.** West's mesh-gateway maps the local order line to a `FulfillmentHandoff` and sends it to Central's inbox `lattice.mesh.cluster.hub-central`. West starts an **ack-timeout** and marks the line `PENDING_HANDOFF`.
2. **Receive + dedup.** Central checks the `messageId` against its processed-id store. A repeat is a no-op that **re-sends the prior Ack** (idempotent). A new message proceeds.
3. **Act.** Central reserves stock, creates its local copy (its own id + `originRef`), and records the `messageId` as processed.
4. **Acknowledge.** Central sends a `HandoffAck` (`accepted` | `rejected` + `reason`, `correlationId` = the handoff's `messageId`) to West's inbox `lattice.mesh.cluster.hub-west`.
5. **Close the loop.** West matches the Ack by `correlationId` and marks the line `accepted` (peer fulfilling) or `rejected` (e.g. peer also out of stock - an explicit outcome, not a silent drop).

**Order ownership (explicit).** The **order-of-record stays with the originator** - West owns `order-123` throughout; Central provides **fulfillment only**. Central's local copy (its own id + `originRef`) is a fulfillment record, **not** ownership of the order. West remains the order's home and shows it as `fulfilled by <peer>`. A handoff routes *fulfillment* to a peer; it is **not** remote order-creation (the order is never created or owned on the peer).

```
recv handoff msgId=M
  seen(M)? -> resend prior Ack (no re-processing)
  new      -> reserve stock, create local copy + originRef, record M
           -> HandoffAck{outcome, correlationId=M} -> sender inbox
sender: match Ack.correlationId=M -> close the outstanding handoff
```

---

## Idempotency

Artemis delivers **at-least-once**, so a handoff can arrive more than once.

- The receiver keeps a **processed-`messageId` store** (in Elasticsearch). A duplicate `messageId` never re-runs the side effect; it just re-sends the Ack it already produced.
- Dedup is on **`messageId`** (the transport identity), *not* `subjectId` (the business key) - so a genuine second handoff of the same subject later is still processed, while a transport retry is absorbed.

---

## Undeliverable / unreachable peer

- The per-cluster inbox is **durable**, so a handoff to a **temporarily** down peer is held by the broker and delivered when the peer reconnects (store-and-forward). No sender action needed for a brief outage.
- The sender starts an **ack-timeout** (default `ACK_TIMEOUT`, ~60s) when it emits a handoff. If no `HandoffAck` arrives in time, the handoff is marked **`PENDING_UNACKED`** in the sender's Elasticsearch and surfaced on the status console (an operator sees it waiting).
- **No auto-cancel at MVP.** Automatic retry, or rerouting to an alternate capable hub, is **deferred** (it needs availability signals - the deferred `StockSignal` - and conflict rules).

```
emit handoff -> start ack-timeout (~60s)
  peer down     -> broker holds message, delivers on reconnect
  Ack in time   -> accepted / rejected
  no Ack        -> PENDING_UNACKED (surfaced on console)
deferred: auto-retry, reroute to an alternate hub
```

---

## Status-console surface (out of scope here)

Peer reachability and handoff state (`PENDING_HANDOFF`, `PENDING_UNACKED`, accepted, rejected) are **consumed** by the status console (peers panel #11, handoffs view #12), but their **visual design is out of scope for this doc** and is owned by those UI tickets (which carry `needs-mockup`, Enforcement Rule 16). This doc defines the data those panels render, not the look.

---

## Decisions settled here (P2)

- Canonical envelope + per-cluster mesh-gateway translation; no local document crosses the mesh.
- Cross-cluster identity `<clusterId>:<localId>`; receiver stores it as `originRef` beside its own local id.
- Idempotency by processed-`messageId` store; duplicate re-sends the prior Ack.
- Every `FulfillmentHandoff` is answered by a `HandoffAck` (accepted|rejected, `correlationId`-linked).
- Durable inbox (store-and-forward) + sender ack-timeout -> `PENDING_UNACKED`; auto-retry/reroute deferred.

Promoted to locked decisions - see [locked_decisions.md](../../reference/locked_decisions.md).
