# Mesh-gateway service

This cluster's door to the mesh. It announces the cluster to its peers, hears their announcements, maintains the peer registry, and serves both that registry and this cluster's own identity over `/api/v1`. It is the **only** service that talks to the Artemis broker; every other service stays unaware the mesh exists.

Under Shape A federation (locked #37) that is the whole job: the mesh is a **discovery phone book**. No work, no local document, and no operator trigger ever crosses it - an operator acts on a baseline by being redirected to that baseline's own console, and a peer's detail is read live from its own API.

Related: [mesh_discovery.md](../architecture/mesh_discovery.md) (announce cadence, peer liveness, TTL), [mesh_envelopes.md](../architecture/mesh_envelopes.md) (the `ClusterAnnouncement` shape + versioning), [cluster_interop.md](../architecture/cluster_interop.md) + [interop_console.md](../features/interop_console.md) (what the console does with the registry), [api_structure.md](../architecture/api_structure.md) (envelope + error taxonomy + the operational health surface), [orders.md](orders.md) / [inventory.md](inventory.md) (sibling services + shared patterns), [locked_decisions.md](../../reference/locked_decisions.md) (#8 Artemis, #13 mesh, #17 OpenAPI, #29 discovery, #31 envelopes, #35 response envelope, #37 Shape A, #41 AMQP client).

---

## Scope

- **Announce** this cluster on startup, on a heartbeat, and immediately when its health rollup changes.
- **Discover** peers by consuming announcements, maintaining the peer registry with liveness.
- **Serve** the registry (`getPeers`) and this cluster's identity + health detail (`getBaseline`).
- **Aggregate** this cluster's health by polling its services' readiness.

Everything else is out of scope ([Deferred](#deferred-post-mvp)). Notably it does **not** own an Elasticsearch index, translate documents, or route work.

---

## REST contract (`/api/v1`)

Standard `{data|error, meta}` envelope (locked #35); strict request bodies do not apply (both operations are reads).

| Operation | operationId | Success | Errors |
|-----------|-------------|---------|--------|
| `GET /api/v1/peers` | `getPeers` | **200** `{ data: Peer[], meta }` | 500 |
| `GET /api/v1/baseline` | `getBaseline` | **200** `{ data: Baseline, meta }` | 500 |

`getBaseline` already exists in the v1 spec but has never been served by any service, so the router logs an unimplemented-operation warning on every startup. Mesh-gateway is its natural owner: it already knows exactly this information, because it is what the cluster announces.

### `getPeers` -> `Peer[]`

The registry the status console reads to render the unified view and to offer a redirect.

```json
{ "data": [
    { "clusterId": "hub-east", "region": "us-east", "baselineVersion": "1.0.0",
      "health": "ready", "consoleUrl": "https://east.console:3000",
      "apiBaseUrl": "https://east.svc:8080/api/v1",
      "lastSeen": "2026-07-25T00:00:00Z", "reachability": "REACHABLE" } ],
  "meta": { "requestId": "...", "apiVersion": "v1" } }
```

- `consoleUrl` is the redirect target ("go to this baseline"); `apiBaseUrl` is what the console's browser live-pulls for that peer's detail.
- `reachability` is `REACHABLE` | `UNREACHABLE`, computed from `lastSeen` against the peer TTL at read time.
- An `UNREACHABLE` peer is **included**, carrying its last-known snapshot - an operator must see "this baseline was here and has gone silent" rather than a row vanishing.
- A cluster never lists itself, though it hears its own multicast announcements.

### `getBaseline` -> `Baseline`

This cluster's own identity, plus the health detail its own operators need.

```json
{ "data": { "clusterId": "hub-west", "region": "us-west", "baselineVersion": "1.0.0",
            "apiVersions": ["v1"], "health": "degraded",
            "services": [ { "name": "orders", "status": "UP" },
                          { "name": "inventory", "status": "DOWN" } ] },
  "meta": { "requestId": "...", "apiVersion": "v1" } }
```

The `Baseline` schema gains `health` and `services` **additively** - the existing required fields are unchanged, so this does not break the generated console client.

**Peers get the rollup; the owning baseline gets the detail.** Keeping the per-service breakdown off the mesh keeps the envelope thin and stops it growing with every service added, and it stops the mesh drifting from discovery into status replication, which Shape A deliberately avoids. It costs nothing operationally: under Shape A you go to the owning baseline for detail anyway.

The endpoint serves the **last polled** rollup, cached from the heartbeat, so it stays cheap and never blocks on a slow service. (The operation's current spec description says it "needs no service or Elasticsearch"; that is corrected when health lands.)

---

## Announcing

Per [mesh_discovery.md](../architecture/mesh_discovery.md):

```
startup            -> announce
every 10s          -> announce (heartbeat / liveness)
health rollup flips -> announce now
```

The heartbeat **is** the liveness signal; there is no separate ping. `HEARTBEAT_INTERVAL` (10s) and `PEER_TTL` (30s) are config-driven.

**On-change detection.** The health rollup is recomputed on each heartbeat tick, and an announce fires immediately when it differs from the last announced value rather than waiting out the remaining interval. This needs no callback seam into the health subsystem, and the flip still propagates within one tick. A baseline-version change only happens across a deploy, which the startup announce already covers.

Publishing is deliberately **fire-and-forget**: an announcement is a heartbeat, so a lost one is corrected by the next tick rather than being worth retrying or blocking on.

---

## Cluster health (the rollup)

`health` on the wire is a genuine statement about **this cluster's services**, not about the gateway process.

Mesh-gateway polls each service's unversioned `/readiness` (per [api_structure.md](../architecture/api_structure.md)), from a configured list:

```
CLUSTER_SERVICES=orders=http://orders-central:8080,inventory=http://inventory-central:8080
```

| Poll result | Rollup |
|-------------|--------|
| every service UP | `ready` |
| at least one UP, not all | `degraded` |
| no service UP | `down` |

- A service counts as **UP only if `/readiness` returns 200**. A non-200, a timeout, and a connection error all count as not-UP, so "reachable but broken" and "unreachable" both land correctly.
- Polls **fan out in parallel with a short timeout**, well under the heartbeat interval, so one hanging service can never delay or block an announce.
- The gateway **excludes itself** from the aggregate - it is alive by definition if it is announcing at all.
- An **empty or unset `CLUSTER_SERVICES`** yields `ready` plus a loud startup WARN naming the misconfiguration. Startup is not failed, so a deliberately minimal deployment still runs.

**Why not the gateway's own readiness.** It has no Elasticsearch dependency and a broker outage deliberately does not fail its readiness (below), so its own readiness can never be anything but UP - announcing it would put a constant, meaningless `ready` on the wire while a visibly broken baseline rendered as healthy in every peer's unified view.

**Why health can never describe the broker.** A signal about the mesh link cannot propagate *over* the mesh link. If the broker drops, peers never see a "degraded" announcement; they simply watch this cluster cross its TTL and go `UNREACHABLE`. That is the correct and only possible signal.

---

## Peer registry

In-memory only. The registry is **derived state**: every peer re-announces on its own heartbeat, so a restarted gateway is fully repopulated within one interval.

- **No Elasticsearch index**, and therefore no Elasticsearch dependency for this service at all. This retires the peer-registry index that [data_model.md](../architecture/data_model.md) previously anticipated.
- Persisting it would buy visibility in the seconds before the first heartbeat, at the cost of a dependency this service otherwise does not need and the risk of serving stale peers after a long outage.
- Liveness uses **this cluster's own receive time**, never the announcement's `occurredAt`: peers keep independent clocks, so trusting a peer's timestamp would let its drift mask or fake its liveness.
- Reachability is **computed at read, not stored**, so expiry needs no timer and there is no background task to leak.

---

## Operational surface

- **`/readiness` stays UP when the broker is unreachable.** Broker loss is a mesh degradation, not a service failure: the gateway can still serve `/peers` with last-known state, and TTL expiry naturally surfaces peers as `UNREACHABLE`. Reporting DOWN would pull the pod from rotation and give the console *nothing* during exactly the incident an operator most needs visibility into.
- This differs deliberately from orders and inventory, where a dead Elasticsearch does mean DOWN - there the service genuinely cannot answer, whereas here it still can.
- `/health` and `/readiness` keep the shared unversioned, non-enveloped operational shape from `BaseVerticle`.

---

## Deployment

- **Single replica.** The registry is per-pod and in-memory, so two replicas would each hold their own view and the console could observe different peer lists across requests; both would also announce independently under the same cluster id. The gateway is not on a request-serving hot path and a restart repopulates within one heartbeat, so one replica is an acceptable availability trade. The manifest pins it with that reasoning recorded.
- One container, on the shared JDK 21 base, like every other service (locked #11).

---

## CORS

The console's browser live-pulls **each peer's** `apiBaseUrl` cross-origin, which [interop_console.md](../features/interop_console.md) records as a hard requirement of the unified view.

Cross-origin allowance is therefore handled **once in `BaseVerticle`** behind a config-driven allowed-origins list, not per service: every service a peer console reads (orders, inventory, mesh-gateway) needs the identical behavior, and implementing it separately would fork a shared concern. Vert.x ships a CORS handler, so this adds no dependency.

This is the one part of this design that reaches beyond mesh-gateway into shared runtime.

---

## Code shape

Mirrors the sibling services; reuse over rebuild.

```
services/mesh-gateway/
  pom.xml                                  depends on lattice-common + lattice-contract
  src/main/java/io/lattice/meshgateway/
    MainVerticle.java                      thin bootstrap: deploy MeshGatewayVerticle
    MeshGatewayVerticle.java               extends BaseVerticle; routes + heartbeat timer
    routes/MeshGatewayRoutes.java          thin handlers: service -> envelope
    service/AnnouncerService.java          startup + heartbeat + on-change announce
    service/ClusterHealthService.java      polls the configured services, computes the rollup
  src/test/java/io/lattice/meshgateway/    route + integration tests (Testcontainers Artemis)
  Dockerfile                               shared JDK 21 base
```

- Reuses `AmqpMeshClient` and `PeerRegistry` from `lattice-common` (already built and integration-tested); this service wires them to a lifecycle, a timer, and an API rather than reimplementing them.
- **No repository and no `EsRepository`** - the only service with no Elasticsearch layer.
- Logging per locked #39: INFO on announce lifecycle and rollup transitions, DEBUG per heartbeat (a 10s INFO would drown the log).

---

## Testing (test-first)

- **Announce cadence** - announces on startup; re-announces on the heartbeat; announces immediately when the rollup flips, without waiting out the interval.
- **Health rollup** - all UP -> `ready`; one DOWN -> `degraded`; none reachable -> `down`; a non-200, a timeout, and a connection error each count as not-UP; an unset service list yields `ready` plus the warning.
- **`getPeers`** - returns discovered peers with both endpoints and reachability; includes an expired peer as `UNREACHABLE` with its last-known snapshot; never lists this cluster itself.
- **`getBaseline`** - returns identity plus the rollup and the per-service breakdown; serves the cached rollup without blocking on a live poll.
- **Readiness** - stays UP while the broker is unreachable, and `/peers` still serves.
- **Two-cluster discovery** - two gateways on one broker each end up holding the other (extends the existing `MeshDiscoveryIT` coverage to the deployed service).
- Contract tests assert responses validate against the v1 OpenAPI schema.
- Suites use the shared fail-on-unexpected-log harness, asserting the expected startup warnings rather than tolerating them.

---

## Deferred (post-MVP)

- **Kubernetes-derived health** (querying pod readiness via the API) - more authoritative, but couples to K8s, needs RBAC, and does not work under docker-compose, which is the primary local QA loop.
- **Registry persistence + high availability** (multi-replica with a shared view).
- **Cross-baseline single-sign-on** and **deep-linking** into a specific peer screen (both deferred in `interop_console.md`).
- **Live-status transport** (Server-Sent Events vs WebSocket) governing how the console refreshes - still an open design question, settled separately.
- **Richer health** than a three-state rollup (per-service detail on the mesh, degradation reasons).

---

## Decisions settled here

- Mesh-gateway is the **sole** mesh participant: one announcement and one registry per cluster. Announce logic does **not** live in `BaseVerticle`, which would make every service announce independently under the same cluster id and hold its own divergent registry.
- REST: `getPeers` (the registry) and `getBaseline` (identity + health detail, closing a previously unserved operation); `Baseline` gains `health` + `services` additively.
- Peer registry is **in-memory only**; the previously anticipated peer-registry Elasticsearch index is retired.
- `health` is a **polled rollup of the cluster's services** (`ready` / `degraded` / `down`), not the gateway's own readiness; the rollup travels on the mesh, the per-service breakdown only on `getBaseline`.
- `/readiness` stays **UP** when the broker is unreachable; peers surface as `UNREACHABLE` instead.
- **Single replica** for MVP.
- **CORS** is handled once in `BaseVerticle`, config-driven, for every service.

This spec is an instance of locked **#29** (discovery + liveness), **#31** (envelopes), **#37** (Shape A) and **#41** (AMQP client); the new decisions above are promoted to [locked_decisions.md](../../reference/locked_decisions.md).
