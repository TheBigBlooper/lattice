# Lattice - External API Document

Part of the [replication pack](replication_prompt.md), alongside the [System Requirements Document](system_requirements.md). This document defines every interface a Lattice baseline exposes to the outside world: the versioned REST API, the operational probe endpoints, the documentation endpoints, the metrics scrape endpoint, the mesh wire protocol between clusters, and the broker federation link. A replica that serves these interfaces exactly is interoperable with an original.

**This document is deliberately self-contained.** It is written to be handed to a builder (human or LLM) who does not hold this repository. Inside the repository, the canonical sources remain [`v1.yaml`](../../platform/lattice-contract/src/main/resources/openapi/v1.yaml), the architecture docs under `docs/design/architecture/`, and [locked_decisions.md](../reference/locked_decisions.md); if this document ever disagrees with them, they win and this document is the defect.

**Scope exclusion.** The repository's `orders` and `inventory` services (and their operations `listOrders`, `createOrder`, `getOrder`, `listInventory`, `getInventory`, `setStock`, `createReservation`) are a demonstration domain and are **not** part of the platform's external API. They are excluded here. A rebuilt system defines its own domain operations following the conventions in section 2.

---

## 1. Interface inventory

| # | Interface                | Transport            | Consumers                                     | Section |
|---|--------------------------|----------------------|-----------------------------------------------|---------|
| 1 | Versioned REST API       | HTTP + JSON          | The baseline's own status console, operators  | 2, 3    |
| 2 | Operational probes       | HTTP + JSON          | Kubernetes probes, the mesh-gateway, console  | 4       |
| 3 | API documentation pages  | HTTP                 | Engineers integrating against the baseline    | 5       |
| 4 | Metrics scrape endpoint  | HTTP (Prometheus)    | A metrics collector inside the cluster        | 6       |
| 5 | Mesh announce protocol   | AMQP 1.0 over Artemis| Peer clusters                                 | 7       |
| 6 | Broker federation link   | TCP + mutual TLS     | Peer clusters' brokers                        | 8       |

---

## 2. REST API conventions

Every rule in this section applies to every REST operation, including domain operations a rebuilder adds.

### 2.1 Base path and versioning

- Every business operation lives under a versioned path prefix: `/api/v1/...`. The version is in the path from the first endpoint.
- One OpenAPI 3.1 document per major version (`v1.yaml`). A future `v2` is a new document and a new `/api/v2` prefix served alongside `v1` during migration, never an in-place rewrite of `v1`.
- The OpenAPI document is schema-first: it drives server-side request validation (the Vert.x OpenAPI router) and generates the console's TypeScript client. Neither side hand-declares a parallel shape.
- Each service serves only the operations it owns. The published document is narrowed per service; an operation no service claims is not served.

### 2.2 Authentication and authorization

- Every `/api/v1` operation requires a bearer token: `Authorization: Bearer <JWT>`, issued by **this baseline's own** Keycloak realm. Declared once at the OpenAPI document level so a new operation is protected by default.
- Services are **bearer-only**: the token signature is verified against the realm's JSON Web Key Set (JWKS), cached locally, so no per-request call to the identity provider occurs. A Keycloak outage does not invalidate already-issued tokens.
- A token issued by a peer baseline's realm is refused (401). Identity belongs to the baseline that owns the data; an operator working across N baselines holds N grants.
- Two realm roles govern access on every baseline: `viewer` (every GET) and `operator` (everything `viewer` has, plus writes). Role **names** are standard across baselines; role **membership** is deliberately unsynchronized.
- A missing, expired, or foreign token is 401 `UNAUTHORIZED`. A valid token whose role does not permit the operation (a `viewer` attempting a write) is 403 `FORBIDDEN`.
- The operational probes (`/health`, `/readiness`) and the documentation endpoints (`/docs`, `/docs/json`) are unauthenticated by design (sections 4 and 5).
- The OpenAPI document also declares an `oauth2` authorization-code + PKCE scheme against the same realm, so the interactive docs page can obtain a token itself. On the wire both schemes are the same bearer header.

### 2.3 Response envelope

Every REST response is a JSON envelope with a `meta` block and **exactly one of** `data` (success) or `error` (failure).

```json
{ "data": { "clusterId": "hub-west" },
  "meta": { "requestId": "9f1c...", "apiVersion": "v1" } }
```

```json
{ "error": { "code": "NOT_FOUND", "message": "No such resource." },
  "meta": { "requestId": "9f1c...", "apiVersion": "v1" } }
```

`meta` fields:

| Field        | Presence            | Meaning                                                            |
|--------------|---------------------|--------------------------------------------------------------------|
| `requestId`  | always              | A UUID generated per request, for tracing and log correlation.     |
| `apiVersion` | always              | The API major version serving the response, e.g. `v1`.             |
| `pagination` | list responses only | Offset-paging counts (section 2.5).                                |

**Response schemas are lenient**: they do not set `additionalProperties: false`, so an added response field never breaks an older client. Only request bodies are strict (section 2.6).

### 2.4 Error taxonomy

The `error` object is machine-branchable: clients branch on `code`, never on `message`.

| Field     | Required | Meaning                                                                     |
|-----------|----------|------------------------------------------------------------------------------|
| `code`    | yes      | One value from the fixed taxonomy below.                                    |
| `message` | yes      | A human-readable summary. Never leaks internals. Never the branch key.      |
| `details` | no       | Field-level problems `[{field, issue}]`, populated for validation failures. |

The fixed taxonomy, one code per HTTP status family, extended only additively:

| `code`             | HTTP | When                                                                        |
|--------------------|------|------------------------------------------------------------------------------|
| `VALIDATION_ERROR` | 400  | Request failed contract validation (body, query, or path).                  |
| `UNAUTHORIZED`     | 401  | No bearer token, or one that failed validation against this realm.          |
| `FORBIDDEN`        | 403  | Token valid, role insufficient for the operation.                           |
| `NOT_FOUND`        | 404  | The addressed resource does not exist.                                      |
| `CONFLICT`         | 409  | The request conflicts with current state.                                   |
| `RATE_LIMITED`     | 429  | Too many requests.                                                          |
| `INTERNAL`         | 500  | Unhandled server error.                                                     |
| `UNAVAILABLE`      | 503  | A dependency is down or the service is not ready.                           |

### 2.5 Pagination

List operations page with two bounded query parameters, declared once in the contract and shared by every list operation:

| Query  | Default | Bound    | Meaning                  |
|--------|---------|----------|--------------------------|
| `page` | `0`     | `>= 0`   | Zero-based page index.   |
| `size` | `20`    | `1..100` | Items per page.          |

The `size` ceiling is the contract refusing an unbounded page. Counts ride only in `meta.pagination`:

```json
"meta": { "pagination": { "page": 0, "size": 20, "total": 137, "totalPages": 7 } }
```

### 2.6 Request-body hardening

Every request body is strict, enforced at the edge by contract validation:

- `additionalProperties: false` on every request body and every nested request object. Unknown keys are rejected as `VALIDATION_ERROR` rather than ignored.
- Bounded fields via three reusable named string types, declared once and referenced everywhere: `ShortString` (max 100 characters - labels, names, ids), `MediumString` (max 512 - descriptions), `LongText` (max 4000 - free text). Every array caps `maxItems` (100 unless stated).
- Servers mint identifiers and timestamps. A request body never carries a client-supplied id, and timestamps are always UTC ISO-8601.

---

## 3. Platform REST operations

The three operations every baseline serves regardless of domain. All are GET, all require `viewer`, all return the envelope of section 2.3 and the error responses of section 2.4.

### 3.1 `getBaseline` - `GET /api/v1/baseline`

This cluster's identity and health, served by the mesh-gateway. Health values are the ones last computed on the announce heartbeat, so the read is cheap and never blocks on a slow service.

`data` is a `Baseline` object:

| Field             | Required | Type                       | Meaning                                                                                                        |
|-------------------|----------|----------------------------|----------------------------------------------------------------------------------------------------------------|
| `clusterId`       | yes      | ShortString                | This cluster's id, e.g. `hub-west`.                                                                            |
| `region`          | yes      | ShortString                | The region label, e.g. `us-west`.                                                                              |
| `baselineVersion` | yes      | string                     | The versioned baseline this cluster runs, e.g. `1.0.0`.                                                        |
| `apiVersions`     | yes      | array of ShortString       | The API major versions served, e.g. `["v1"]`.                                                                  |
| `health`          | no       | `ready\|degraded\|down`    | The cluster's rolled-up health (section 3.4).                                                                  |
| `services`        | no       | array of ServiceHealth     | Per-service readiness behind the rollup. Served only here; never announced to peers.                           |
| `infrastructure`  | no       | array of ComponentHealth   | Infrastructure state (section 3.5). Served only here; never announced. Absent when nothing is configured.      |
| `meshLink`        | no       | `up\|down`                 | Whether this cluster can reach its own broker (section 3.6). Optional so older generated clients still parse.  |

`ServiceHealth`: `{ name: ShortString, status: "UP"|"DOWN" }` - `UP` when the service's readiness probe returned 200.

### 3.2 `getPeers` - `GET /api/v1/peers`

Every peer this cluster has heard announce itself, served by the mesh-gateway from its in-memory registry. A cluster never lists itself. Empty before any peer announces. A peer silent past the liveness time-to-live is **included** as `UNREACHABLE` with its last-known snapshot, never removed.

`data` is an array of `Peer` objects:

| Field             | Required | Type                     | Meaning                                                                                                           |
|-------------------|----------|--------------------------|--------------------------------------------------------------------------------------------------------------------|
| `clusterId`       | yes      | ShortString              | The peer's cluster id.                                                                                            |
| `region`          | yes      | ShortString              | The peer's region.                                                                                                |
| `baselineVersion` | yes      | string                   | The baseline the peer last reported running.                                                                      |
| `health`          | yes      | `ready\|degraded\|down`  | The peer's announced rollup.                                                                                      |
| `consoleUrl`      | yes      | string                   | The peer's console root; where a "go to this baseline" redirect lands.                                            |
| `apiBaseUrl`      | yes      | string                   | The peer's REST API base. Recorded, never read from a browser: a peer refuses tokens this realm issued.           |
| `lastSeen`        | yes      | date-time                | When this cluster last heard the peer, by **this cluster's own clock**, so a peer's clock drift cannot fake liveness. |
| `reachability`    | yes      | `REACHABLE\|UNREACHABLE` | `REACHABLE` while announced within the time-to-live; `UNREACHABLE` once silent longer.                            |
| `federation`      | no       | `up\|down\|refused`      | This broker's federation link to that peer's broker (section 3.7). Absent when it could not be measured.          |

### 3.3 `getMetrics` - `GET /api/v1/metrics`

A selected set of this service's meters, for the console's Metrics view. **Every service serves its own**; the console reads each and merges. This operation exists because a browser cannot reach the Prometheus scrape endpoint at all (separate unpublished management port, no token). It carries the platform's own meters only; the runtime and toolkit families stay on the scrape endpoint.

`data` is a `MetricsSnapshot`: `{ service: ShortString, samples: [MetricSample] }`. `samples` may be empty for a service that just started, which is a real state and not a failure.

`MetricSample`:

| Field    | Required | Type                     | Meaning                                                                          |
|----------|----------|--------------------------|-----------------------------------------------------------------------------------|
| `name`   | yes      | ShortString              | The meter name.                                                                  |
| `kind`   | yes      | `COUNTER\|GAUGE\|TIMER`  | How to read the value: a counter is read as a rate, a gauge stands on its own.   |
| `labels` | no       | map of string to string  | The label set identifying this series; absent or empty when it carries none.     |
| `value`  | yes      | number                   | The value at the moment the snapshot was taken.                                  |

### 3.4 Cluster health vocabulary

`ClusterHealth` is the rolled-up label computed by the mesh-gateway from its configured services' readiness: `ready` (every service up), `degraded` (some up), `down` (none reachable, though the cluster still announces). The rollup is the **only** health that travels the mesh; the per-service breakdown and infrastructure stay on `getBaseline`.

### 3.5 Infrastructure component vocabulary

`ComponentHealth` reports one non-platform dependency (the datastore, the broker, the identity provider) as seen by the mesh-gateway:

| Field    | Required | Type                               | Meaning                                                                    |
|----------|----------|------------------------------------|-----------------------------------------------------------------------------|
| `name`   | yes      | ShortString                        | The deployment's label for the component.                                  |
| `kind`   | yes      | `elasticsearch\|artemis\|keycloak` | Which probe produced the status; explicit rather than inferred from name.  |
| `status` | yes      | `UP\|DEGRADED\|DOWN`               | Coarse shared state; `DEGRADED` means serving with something genuinely lost. |
| `detail` | no       | MediumString                       | An optional line in the component's own vocabulary (e.g. `yellow`).        |

### 3.6 Mesh link state

`meshLink` (`up`/`down`) is whether this cluster can reach the mesh at all - its own connection to its own broker. Served here only and never announced: a report about a broken link cannot travel over that link. It exists so "we are cut off" (every peer ages out at once) reads differently from "the peers are gone".

### 3.7 Federation state

`federation` (`up`/`down`/`refused`) is this broker's link to **one peer broker**, measured per peer at the broker - a different thing from `meshLink`, and the thing a revoked or expired certificate actually breaks. `refused` means the link is down **and** this baseline's own certificate has expired, so the fault is local and the fix is a re-issue; that conclusion is computed in the gateway and only rendered by clients, never re-derived. A revoked but unexpired certificate reads `down` (revocation lives in the authority's list, which a baseline does not hold). Absent, rather than defaulted, when it could not be measured.

---

## 4. Operational probe endpoints

Unversioned, served at the root, **not** wrapped in the business envelope, and unauthenticated by design: a Kubernetes probe cannot carry a token, and gating a probe trades real availability risk for no secrecy.

| Endpoint     | Meaning                                                            | 200            | 503            |
|--------------|--------------------------------------------------------------------|----------------|----------------|
| `/health`    | Liveness: the process is up. No dependency checks.                 | always once up | process not live |
| `/readiness` | Readiness: every registered dependency check passes.               | all checks UP  | any check DOWN |

Both return the fixed operational shape:

```json
{ "status": "UP",
  "checks": [ { "name": "elasticsearch", "status": "UP" } ] }
```

`status` is `UP` or `DOWN`; `checks` is one entry per registered check. Every service mounts both probes from the shared base verticle; they are mounted directly rather than through the OpenAPI router.

---

## 5. Documentation endpoints

| Endpoint     | Serves                                                 |
|--------------|--------------------------------------------------------|
| `/docs/json` | The OpenAPI document, narrowed to this service's owned operations plus the probes. |
| `/docs`      | An interactive Swagger UI page over that document.     |

- Assets are **bundled in the image**, never fetched from a content delivery network, so the page works air-gapped.
- Both sit outside `/api/v1` and need no token: they describe the API rather than exposing it, and a client generator needs the contract before it can authenticate.
- Gated by `API_DOCS_ENABLED`. Unset means **on**; production turns it off explicitly. When off, the routes are not mounted at all and the paths 404 like any other unserved address (a 403 would confirm the endpoint exists).
- The page can obtain its own token via the authorization-code + PKCE flow against this baseline's realm; each service rewrites the contract's placeholder realm addresses to its own as it serves the document.

---

## 6. Metrics scrape endpoint

Prometheus exposition served at `/metrics` on a **dedicated management port** (`METRICS_PORT`, default 9090), never on the API port and never published outside the cluster (a ClusterIP Service per service, deliberately never a NodePort). It carries no token: reachability is the whole of its protection, which is why it must never ride a port a browser reaches.

It exposes the runtime families (Java Virtual Machine, HTTP server, pools) plus the platform's own meters (mesh, rollup, and data-layer families). HTTP metrics are labelled by **OpenAPI route template, never raw path**, so identifiers never enter the metric surface. `METRICS_ENABLED` defaults on.

---

## 7. Mesh wire protocol

The interface between clusters. Under Shape A federation the mesh is a discovery phone book: the **only** thing that crosses it is a cluster announcing its presence and endpoints. No domain data and no work ever crosses.

### 7.1 Transport and addressing

- Protocol: **AMQP 1.0** against the cluster's own Apache Artemis broker (`ARTEMIS_URL`, single-valued: a service only ever addresses its own broker).
- One shared address: `lattice.mesh.announce`, addressed as a **topic** (multicast). Topic addressing is load-bearing: treated as a queue, the broker load-balances and each peer sees only a fraction of the mesh.
- Every cluster subscribes to the address and publishes its own announcements to it. Broker federation (section 8) carries a message published on any broker to every baseline.

### 7.2 Envelope

Every mesh message is a self-describing JSON envelope: a common header wrapping a typed payload.

```json
{
  "messageId": "e4f1...-uuid",
  "type": "ClusterAnnouncement",
  "schemaVersion": 1,
  "sourceClusterId": "hub-west",
  "occurredAt": "2026-07-22T20:00:00Z",
  "correlationId": null,
  "payload": { }
}
```

| Field             | Type               | Meaning                                                                      |
|-------------------|--------------------|-------------------------------------------------------------------------------|
| `messageId`       | UUID string        | Unique per message.                                                          |
| `type`            | string             | Selects the payload shape.                                                   |
| `schemaVersion`   | integer            | Per-type payload version, starting at 1.                                     |
| `sourceClusterId` | string             | The announcing cluster's id.                                                 |
| `occurredAt`      | UTC instant        | Event time, always UTC.                                                      |
| `correlationId`   | UUID string / null | Reserved for a future directed request/reply pairing; null for all messages. |
| `payload`         | object             | The typed body.                                                              |

### 7.3 `ClusterAnnouncement`

The only envelope type. A fixed six-field payload, so the wire is O(1) in service count by design:

| Payload field     | Meaning                                                                       |
|-------------------|--------------------------------------------------------------------------------|
| `clusterId`       | The announcing cluster's id.                                                  |
| `region`          | Its region label.                                                             |
| `baselineVersion` | The baseline it runs.                                                         |
| `health`          | Its rolled-up health (`ready`/`degraded`/`down`). The rollup only, never a breakdown. |
| `consoleUrl`      | Its console root, where a peer redirects an operator to act on it.            |
| `apiBaseUrl`      | Its REST API base. Recorded by peers; never read from a browser.              |

### 7.4 Timing

| Trigger                                            | Behaviour                                    |
|----------------------------------------------------|----------------------------------------------|
| Startup                                            | Announce as soon as the gateway is ready.    |
| Every `HEARTBEAT_INTERVAL` (default 10 s)          | Announce. The heartbeat is the liveness signal; there is no separate ping. |
| Health flip or baseline version change             | Announce immediately.                        |

Consumers derive liveness on read: a peer unheard for `PEER_TTL` (default 30 s, three missed heartbeats) is `UNREACHABLE` but retained with its last-known snapshot. The next announcement flips it back. `lastSeen` uses the receiver's own clock, never the announcement's `occurredAt`. Duplicate announcements are harmless (idempotent refresh).

### 7.5 Compatibility rules

Peers may run different baselines, so the wire must tolerate skew:

- **Additive change, same `schemaVersion`.** Adding an optional field keeps the version. Consumers ignore unknown fields and tolerate a missing optional field.
- **Breaking change, new `schemaVersion`.** Removing, renaming, or retyping a required field is a new version, coordinated across clusters. Never an in-place rewrite.
- **Unknown higher version received:** read the stable header, skip the payload. For an unsolicited announcement this is a silent skip; the peer is simply not refreshed from that message.

---

## 8. Broker federation link

The transport that joins independent per-baseline brokers, so an announcement published on one reaches all.

- **Federation, not clustering.** Artemis address federation on `lattice.mesh.announce` with `max-hops="1"` (loop prevention: a federated message is never re-federated).
- **The joiner pays the cost.** A joining baseline declares both `upstream` and `downstream` connections to each peer, so existing brokers discover it with no edit, restart, or redeploy. No broker's configuration ever names a specific peer as trusted.
- **Mutual TLS, authority-based trust.** Each broker presents a per-baseline X.509 certificate signed by a shared certificate authority; brokers trust the **authority**, never individual peers. A certificate from an untrusted authority, or a revoked one, is refused in the handshake with only the enforcing baseline touched. One authority per deployment and per environment, so a development broker structurally cannot federate onto production.
- **Authorization** is ordinary broker security: every broker grants a shared `lattice_federation` role the same permissions as its own services, and the joiner presents that role's credential via `user`/`password` on its `<federation>` element. (There is no `downstream-authorization` attribute in the Artemis 2.44 schema; a broker configured with one fails validation and does not start.)
- The federation link is **raw TCP with mutual TLS end to end**: it cannot pass through an HTTP ingress or any middlebox that terminates TLS.

---

## 9. Interoperability summary

A replica is externally interoperable with an original when:

1. Its REST responses validate against the conventions of section 2 and the shapes of sections 3 and 4.
2. It publishes and consumes `ClusterAnnouncement` envelopes per section 7, on the shared multicast address, tolerating unknown fields and higher schema versions.
3. Its broker federates per section 8 with a certificate signed by the shared authority.
4. It never puts a per-service breakdown, infrastructure state, or mesh-link state on the wire: those are local API surface only.
