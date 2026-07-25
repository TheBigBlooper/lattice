# Mesh Discovery - Announce + Peer Liveness

How a cluster announces itself on the Artemis mesh, how peers find each other, and how a silent peer is detected. Under Shape A federation (locked #37) this is the mesh's **only** job: a discovery phone book that also advertises where to reach each peer. Settles deferred question P1.

Related: [mesh_envelopes.md](mesh_envelopes.md) (the `ClusterAnnouncement` shape), [cluster_interop.md](cluster_interop.md) (how the registry drives redirect + the unified view), [locked_decisions.md](../../reference/locked_decisions.md) (#8 Artemis, #13 mesh, #37 Shape A).

---

## Principle

Discovery is **decentralized** - there is no central registry. Each cluster announces its own presence on a shared address, and every cluster independently maintains its own view of the peers (a **peer registry**) from the announcements it hears. A cluster's `mesh-gateway` service (see [example_domain.md](../../reference/example_domain.md)) owns both sides: publishing this cluster's announcements and consuming peers'. The registry is what the console reads to render the unified view and to redirect an operator to a peer (see [cluster_interop.md](cluster_interop.md)).

---

## Announcement mechanism

A cluster publishes a `ClusterAnnouncement` (see [mesh_envelopes.md](mesh_envelopes.md)):

- **On startup** - as soon as the mesh-gateway is ready.
- **On a periodic heartbeat** - every `HEARTBEAT_INTERVAL` (default **10s**). The heartbeat *is* the liveness signal - there is no separate ping.
- **Immediately on a material change** - health flips (ready <-> degraded), or the baseline version changes. So status changes propagate without waiting for the next tick.

```
startup                   -> announce
every 10s                 -> announce (heartbeat / liveness)
health or baseline change -> announce now
```

`ClusterAnnouncement` payload carries: `clusterId`, `region`, `baselineVersion`, `health`, `consoleUrl`, and `apiBaseUrl`.

### Advertising reachable endpoints

`consoleUrl` and `apiBaseUrl` are what make Shape A federation work - a peer that hears the announcement learns not just *that* the cluster exists, but *where* to reach it:

- **`consoleUrl`** - the peer's own status console root. The redirect action ("go to this baseline") navigates the operator's browser here.
- **`apiBaseUrl`** - the peer's REST API base. The unified view's browser fans out here to read that peer's status/details live.

Both are the cluster's reachable addresses on the shared operator network (the reachability assumption in [cluster_interop.md](cluster_interop.md)). They are ordinary fields on the registry entry beside identity + health + liveness.

---

## Artemis addressing

One address shape - broadcast presence. Under Shape A there is no directed mesh traffic, so there is no per-cluster work inbox.

| Address                 | Routing   | Who consumes             | Carries                          |
|-------------------------|-----------|--------------------------|----------------------------------|
| `lattice.mesh.announce` | multicast | every cluster subscribes | `ClusterAnnouncement` (fan-out)  |

Bootstrapping onto the mesh is just the broker connection (`ARTEMIS_URL`, see [integrations.md](../../reference/integrations.md)); once connected, a cluster subscribes to `lattice.mesh.announce` and starts publishing its own announcements.

Each baseline connects to **its own** broker, and the brokers are federated so an announcement published on any one of them reaches every baseline ([mesh_broker_topology.md](mesh_broker_topology.md), locked #44). That is purely a transport concern: **discovery itself stays fully dynamic.** Who exists, where to reach them, their health, and their liveness are all still learned at runtime from announcements alone, and none of it is configured.

---

## Peer liveness + expiry

Each cluster's peer registry records, per peer, the last-heard announcement (`lastSeen`) and last-known fields (region, baseline, health, `consoleUrl`, `apiBaseUrl`).

- A peer unheard for `PEER_TTL` (default **30s** = 3 missed heartbeats) flips to **`UNREACHABLE`**.
- An `UNREACHABLE` peer is **retained, not deleted** - the last-known snapshot stays so an operator sees "this baseline was here and has gone silent" rather than a peer vanishing.
- The next announcement from that peer flips it back to **`REACHABLE`** and refreshes `lastSeen`.

```
now - lastSeen <= 30s -> REACHABLE
now - lastSeen  > 30s -> UNREACHABLE (retained, last-known shown)
new announcement      -> REACHABLE, lastSeen = now
```

Timing is config-driven (defaults above): `HEARTBEAT_INTERVAL`, `PEER_TTL`.

---

## Edge cases

- **Broker briefly unavailable:** announcements pause; peers may cross the TTL and show `UNREACHABLE`, then self-heal on reconnect. No manual intervention. Note this cuts **both** ways: while its own broker is unreachable a cluster hears nothing, so its registry ages *every* peer out at once. That is why the mesh-link state is surfaced separately (locked #46) - otherwise "we are cut off" and "the peers are gone" render identically.
- **Clock skew:** liveness uses each cluster's *own* receive time for `lastSeen`, not the announcement's `occurredAt`, so a peer's clock drift cannot mask its liveness.
- **Duplicate announcement:** harmless - it just refreshes `lastSeen` (announcements are idempotent by nature).
- **Stale endpoint:** because `consoleUrl` / `apiBaseUrl` ride every announcement, a peer that moves re-advertises its new address on the next heartbeat; the registry self-corrects within a heartbeat interval.

---

## Decisions settled here (P1, Shape A)

- Decentralized discovery; per-cluster peer registry from announcements.
- Announce on startup + 10s heartbeat + on-change; heartbeat is the liveness signal.
- Multicast `lattice.mesh.announce` only; no directed per-cluster inbox (no directed mesh traffic under Shape A).
- 30s TTL (3 missed beats) -> `UNREACHABLE` but retained; config-driven `HEARTBEAT_INTERVAL` / `PEER_TTL`.
- `ClusterAnnouncement` advertises `consoleUrl` + `apiBaseUrl`; the registry surfaces them for the console's redirect + live-pull unified view.

Promoted to locked decisions - see [locked_decisions.md](../../reference/locked_decisions.md).
