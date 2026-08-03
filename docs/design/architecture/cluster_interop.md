# Cluster Interop - Discovery + UI-Redirect Federation

How independent baselines federate: each owns its own data, and an operator reaches a peer by being **redirected to that peer's own console**, not by work being translated across the mesh. Settles how peers interoperate across divergent local data models (Shape A).

Related: [mesh_envelopes.md](mesh_envelopes.md) (the announcement wire shape), [mesh_discovery.md](mesh_discovery.md) (announce + peer registry + endpoint advertisement), [data_model.md](data_model.md) (each baseline's own divergent model), [interop_console.md](../features/interop_console.md) (the console UX), [locked_decisions.md](../../reference/locked_decisions.md) (#14 divergent models, #15 interoperable, #37 Shape A federation, #38 per-baseline auth).

---

## Principle - each baseline owns its own data; you go to the owner

Baselines own **divergent** Elasticsearch models (locked #14) yet must **interoperate** (locked #15). Under Shape A, interoperability is achieved by **going to the cluster that owns the thing**, not by reconciling divergent models on the wire:

- **Each baseline always owns its own orders** (and all its data). There is no cross-cluster order-of-record split, and no "A owns an order B fulfills."
- **To act on a peer, the console redirects the operator to that peer's own console.** You then act natively on the peer, against the peer's own services and data model. The peer owns the result.
- **Nothing local ever crosses the mesh as work.** Because you always act on the owning baseline, a divergent local model never needs translating into another's. The canonical-envelope translation layer is therefore unnecessary and is not part of Shape A.

```
operator on West console
   -> picks a discovered peer (hub-central) from the unified view
   -> console redirects the browser to hub-central's own console
   -> operator acts on hub-central natively; hub-central owns the result
```

This keeps each baseline's internals free to diverge while federation stays a thin, contract-light hop: discover the peer, jump to it, act there.

---

## What the mesh carries (discovery only)

The Artemis mesh is a **phone book**, not a work bus. It carries exactly one thing: each baseline's **`ClusterAnnouncement`**, multicast to all peers, from which every baseline builds its own **peer registry** (see [mesh_discovery.md](mesh_discovery.md)). The announcement advertises, alongside identity + health + liveness, the peer's reachable endpoints:

- **`consoleUrl`** - where to redirect an operator to act on that peer.
- **`apiBaseUrl`** - where the unified view reads that peer's status/details live.

No directed mesh traffic exists under Shape A: there is no per-cluster work inbox, no request/response envelope pair, no handoff. Announcements are unsolicited and multicast only.

---

## The unified view (read-only, from the local registry)

Every baseline's console shows both its own local status and a **unified, read-only view of all discovered baselines**:

1. The console reads the **peer list** (identity, region, baseline version, health, `consoleUrl`, `apiBaseUrl`, `lastSeen`, reachability) from **its own cluster's registry**, which the mesh populated.
2. That registry is the **only** source for a peer. The browser does **not** fan out to a peer's `apiBaseUrl` (locked #61, correcting locked #37): every `/api/v1` operation accepts only a token from its own baseline's realm, so a cross-baseline read is refused however the network is configured. `apiBaseUrl` remains in the announcement, unused by the console today.
3. An `UNREACHABLE` peer (silent past its TTL, see [mesh_discovery.md](mesh_discovery.md)) shows its last-known snapshot, honestly marked as gone silent.

**No CORS requirement follows from this view**, because it makes no cross-origin call. The redirect (a browser navigation to a peer's `consoleUrl`) is unaffected - a navigation is not a cross-origin read.

---

## Reaching a peer (redirect)

"Act on peer B" is a **browser redirect to B's own console root** (`consoleUrl` from the registry). There is no shared cross-baseline route contract: each baseline owns its own UI routes, and the redirect lands on the peer's console home, from which the operator proceeds. Deep-linking into a specific peer screen is deferred (it would require a shared route contract).

---

## Auth across the redirect (per-baseline)

Each baseline runs its **own Keycloak** (its own realm, roles + groups; locked #38). The redirect lands the operator on peer B's own origin, so **B authenticates the operator against B's Keycloak** and owns that session. For the MVP the operator **re-authenticates per baseline** (a fresh session on B if not already signed in there). Cross-baseline single-sign-on via Keycloak realm-to-realm identity brokering is a future refinement, not built now. The Keycloak integration itself (containers, realm config, protecting service APIs + consoles) is its own ticket.

---

## Cross-baseline identity

A baseline still has its own local ids. When a baseline is *named* in another's unified view or as a redirect target, it is referenced by its stable **`clusterId`** (e.g. `hub-west`), the same id it announces. There is no cross-cluster subject reference (`<clusterId>:<localId>`) under Shape A, because no subject (order/line) is ever copied or handed to a peer - each subject lives only on the baseline that owns it.

---

## Unreachable peer

Because the mesh carries only discovery, "unreachable" is a **liveness + reachability** concern, not a work-delivery one:

- A peer unheard past `PEER_TTL` flips to `UNREACHABLE` in the registry and is retained with its last-known snapshot (see [mesh_discovery.md](mesh_discovery.md)).
- A redirect to an `UNREACHABLE` (or momentarily down) peer simply fails at the browser like any unreachable site; the unified view shows the peer as unreachable rather than silently pretending it is live.
- There is no store-and-forward, ack-timeout, or retry to manage - those belonged to the deleted directed-handoff layer.

---

## Decisions settled here (Shape A)

- Each baseline owns its own data (orders included); no cross-cluster order-of-record split or fulfillment handoff.
- Interoperability is achieved by **UI redirect to the owning baseline** + a unified read-only view, not by canonical-envelope translation of divergent models.
- The mesh carries **discovery only** (`ClusterAnnouncement` advertising `consoleUrl` + `apiBaseUrl`); no directed work traffic, no per-cluster inbox.
- The unified view reads the **local peer registry only**; the browser makes no cross-origin read, so no CORS requirement follows (locked #61, correcting #37).
- Auth is **per-baseline** (each baseline's own Keycloak); redirect authenticates against the peer; re-auth per baseline for MVP, realm brokering deferred.

Promoted to locked decisions - see [locked_decisions.md](../../reference/locked_decisions.md) #37, #38.
