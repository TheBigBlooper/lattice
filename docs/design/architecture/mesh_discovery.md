# Mesh Discovery - Announce + Peer Liveness

How a cluster announces itself on the Artemis mesh, how peers find each other, and how a silent peer is detected. Settles deferred question P1.

Related: [mesh_envelopes.md](mesh_envelopes.md) (the `ClusterAnnouncement` shape), [cluster_interop.md](cluster_interop.md) (directed handoffs use the addresses established here), [locked_decisions.md](../../reference/locked_decisions.md) (#8 Artemis, #13 mesh).

---

## Principle

Discovery is **decentralized** - there is no central registry. Each cluster announces its own presence on a shared address, and every cluster independently maintains its own view of the peers (a **peer registry**) from the announcements it hears. A cluster's `mesh-gateway` service (see [example_domain.md](../../reference/example_domain.md)) owns both sides: publishing this cluster's announcements and consuming peers'.

---

## Announcement mechanism

A cluster publishes a `ClusterAnnouncement` (see [mesh_envelopes.md](mesh_envelopes.md)):

- **On startup** - as soon as the mesh-gateway is ready.
- **On a periodic heartbeat** - every `HEARTBEAT_INTERVAL` (default **10s**). The heartbeat *is* the liveness signal - there is no separate ping.
- **Immediately on a material change** - health flips (ready <-> degraded), or the baseline version changes. So status changes propagate without waiting for the next tick.

```
startup                 -> announce
every 10s               -> announce (heartbeat / liveness)
health or baseline change -> announce now
```

`ClusterAnnouncement` payload carries: `clusterId`, `region`, `baselineVersion`, `health`, `endpoint`, and `supportedEnvelopeVersions`.

### Advertising supported envelope versions

`supportedEnvelopeVersions` tells peers **which envelope `schemaVersion`s this cluster can read**, per envelope type - a small capability map (a `min`/`max` range per type):

```json
"supportedEnvelopeVersions": {
  "FulfillmentHandoff": { "min": 1, "max": 2 },
  "HandoffAck":         { "min": 1, "max": 1 }
}
```

This is what lets a **newer** cluster hand off to an **older** peer across a breaking change: the sender reads the peer's advertised range and emits the highest version the peer supports, rather than sending a too-new version and getting NACKed (the negotiation rule in [mesh_envelopes.md](mesh_envelopes.md)). A cluster that advertises no entry for a type is assumed to support `min=max=1`. The field is optional and additive - a peer that does not send it (an older cluster that predates negotiation) is treated as supporting version 1 only, so negotiation degrades safely.

---

## Artemis addressing

Two address shapes split broadcast presence from point-to-point work:

| Address                          | Routing   | Who consumes                         | Carries                          |
|----------------------------------|-----------|--------------------------------------|----------------------------------|
| `lattice.mesh.announce`          | multicast | every cluster subscribes             | `ClusterAnnouncement` (fan-out)  |
| `lattice.mesh.cluster.<clusterId>` | anycast (inbox) | only that cluster consumes     | directed `FulfillmentHandoff` / `HandoffAck` |

A cluster learns a peer's inbox address from the peer's `sourceClusterId` (the inbox is `lattice.mesh.cluster.<sourceClusterId>`), so directed handoffs need no configuration beyond having heard the peer announce. The per-cluster inbox is **durable** (store-and-forward across a brief peer outage - see [cluster_interop.md](cluster_interop.md)).

Bootstrapping onto the mesh is just the broker connection (`ARTEMIS_URL`, see [integrations.md](../../reference/integrations.md)); once connected, a cluster subscribes to `lattice.mesh.announce` and its own inbox.

---

## Peer liveness + expiry

Each cluster's peer registry records, per peer, the last-heard announcement (`lastSeen`) and last-known fields (region, baseline, health, endpoint).

- A peer unheard for `PEER_TTL` (default **30s** = 3 missed heartbeats) flips to **`UNREACHABLE`**.
- An `UNREACHABLE` peer is **retained, not deleted** - the last-known snapshot stays so an operator sees "this hub was here and has gone silent" rather than a peer vanishing.
- The next announcement from that peer flips it back to **`REACHABLE`** and refreshes `lastSeen`.

```
now - lastSeen <= 30s -> REACHABLE
now - lastSeen  > 30s -> UNREACHABLE (retained, last-known shown)
new announcement      -> REACHABLE, lastSeen = now
```

Timing is config-driven (defaults above): `HEARTBEAT_INTERVAL`, `PEER_TTL`.

---

## Edge cases

- **Broker briefly unavailable:** announcements pause; peers may cross the TTL and show `UNREACHABLE`, then self-heal on reconnect. No manual intervention.
- **Clock skew:** liveness uses each cluster's *own* receive time for `lastSeen`, not the announcement's `occurredAt`, so a peer's clock drift cannot mask its liveness.
- **Duplicate announcement:** harmless - it just refreshes `lastSeen` (announcements are not deduped like handoffs; they are idempotent by nature).

---

## Decisions settled here (P1)

- Decentralized discovery; per-cluster peer registry from announcements.
- Announce on startup + 10s heartbeat + on-change; heartbeat is the liveness signal.
- Multicast `lattice.mesh.announce` + per-cluster durable anycast inbox `lattice.mesh.cluster.<id>`.
- 30s TTL (3 missed beats) -> `UNREACHABLE` but retained; config-driven `HEARTBEAT_INTERVAL` / `PEER_TTL`.
- `ClusterAnnouncement` advertises `supportedEnvelopeVersions` (a per-type `min`/`max`) so a sender can negotiate the common envelope version (see [mesh_envelopes.md](mesh_envelopes.md)); absent = version 1 only.

Promoted to locked decisions - see [locked_decisions.md](../../reference/locked_decisions.md).
