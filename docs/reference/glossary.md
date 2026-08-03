# Lattice - Glossary

**Rule:** This file is a true index - term + one-line definition + a pointer (spec doc or code equivalent). No inline spec content.
**Rule:** Definitions must remain stable unless a term is renamed or expanded.

> Grouped into six buckets. Seeded with the terms in use today; deep design terms land as their design sessions run.

---

## Domain Terms

| Term              | Definition                                                                                                                                                 | Reference                                             |
|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| Guiding documents | The markdown files defining project rules, decisions, vocabulary, and design/architecture. They govern all sessions and are updated when decisions change. | session_protocol.md, locked_decisions.md, glossary.md |
| Baseline          | A known-good, versioned set of services + REST endpoints that ship together as one cluster. The unit of "what version this cluster is."                    | locked_decisions.md (Architecture)                    |
| Cluster           | All Lattice services running in one Kubernetes cluster - one deployment of the baseline, with its own Elasticsearch data model.                            | locked_decisions.md (Architecture)                    |
| Node              | A single running instance (pod/container) of a service within a cluster; what the status console reports health for.                                       | ui/status-console                                     |
| Service           | One Vert.x microservice - a Maven module under `services/`, packaged as a Docker image, exposing versioned REST endpoints.                                 | services/*                                            |
| Mesh              | The Artemis-backed network over which clusters discover and communicate with peer clusters.                                                                | platform_protocol.md                                  |
| Peer cluster      | Another Lattice cluster this cluster discovers and exchanges work with over the mesh.                                                                      | platform_protocol.md                                  |
| Interoperability  | The requirement that baselines federate despite each owning its own, possibly-divergent Elasticsearch data model. Under Shape A, achieved by redirecting an operator to the owning baseline + a unified view, not a shared schema. | locked_decisions.md #37; cluster_interop.md           |
| Order ownership   | Under Shape A each baseline always owns its own orders (and all its data); there is no cross-cluster order-of-record split, fulfillment-only role, or handoff.                            | cluster_interop.md / interop_console.md               |
| Order             | A customer order owned by a baseline: `{ orderId, customerId, status, lines }`, persisted to that cluster's own Elasticsearch. Created as `RECEIVED`.                                      | docs/design/services/orders.md                        |
| Order line        | One item within an order: `{ sku, quantity }`. Embedded in the order (no separate line id).                                                                                                | docs/design/services/orders.md                        |
| Stock item        | An inventory record for one sku at a cluster: `{ sku, onHand, reserved }`; `available = onHand - reserved` (computed). The source of truth for "can this hub fill it".                       | docs/design/services/inventory.md                     |
| Reservation       | A hold of `quantity` of a sku against an order line `(orderId, sku)`; oversell-safe + idempotent. Keyed by `(orderId, sku)`.                                                                | docs/design/services/inventory.md                     |
| Discovery         | How a cluster announces itself on the mesh and finds peers: startup + 10s heartbeat + on-change, with a peer-liveness TTL.                                 | locked_decisions.md #29; mesh_discovery.md            |
| mesh-gateway      | A cluster's door to the mesh, and its only mesh participant: announces this cluster (with its `consoleUrl` + `apiBaseUrl`), discovers peers, maintains the peer registry + liveness, and serves both over `/api/v1`. No document translation (Shape A: nothing local crosses the mesh).                                | docs/design/services/mesh-gateway.md                   |
| Health rollup     | A cluster's single health label (`ready` / `degraded` / `down`), computed by the mesh-gateway polling its configured services' readiness. This is what rides the mesh; the per-service breakdown, and infrastructure, stay on the owning baseline's `getBaseline`. | docs/design/services/mesh-gateway.md                   |
| Infrastructure component | A non-Lattice dependency a baseline runs (Elasticsearch, Artemis, Keycloak), reported on that baseline's own API only and never announced. Reports a coarse `UP` / `DEGRADED` / `DOWN` state plus an optional detail line in its own system's vocabulary. | locked_decisions.md #66; baseline_component_reporting.md |
| Mesh link         | A baseline's own connection to its own broker. Its state is served on that baseline's API only and never announced, so an operator can tell "we are cut off" from "the peers are gone". | locked_decisions.md #46; mesh_broker_topology.md        |

---

## Technical Terms

| Term              | Definition                                                                                                             | Code equivalent                         |
|-------------------|------------------------------------------------------------------------------------------------------------------------|-----------------------------------------|
| Vert.x            | The reactive, non-blocking toolkit every Java service is built on.                                                     | `io.vertx` (Vert.x 5)                   |
| Verticle          | Vert.x's unit of deployment - an actor-like component; a service's thin main verticle wires up its routers.            | `AbstractVerticle` / `BaseVerticle`     |
| Event bus         | Vert.x's in-process (and clusterable) message bus between verticles.                                                   | `vertx.eventBus()`                      |
| OpenAPI operation | One versioned REST endpoint defined in the OpenAPI 3.1 spec; drives Vert.x router validation + the console client.     | `lattice-contract` OpenAPI resources    |
| Envelope          | A shared mesh message record (in `lattice-contract`) that every cluster agrees on for interop over Artemis.            | `platform/lattice-contract` records     |
| Response envelope | The REST response wrapper: `data` (success) XOR `error` (failure), plus `meta`. Distinct from the mesh Envelope.       | api_structure.md                        |
| Envelope header   | The common fields on every mesh message (messageId, type, schemaVersion, sourceClusterId, occurredAt, correlationId).  | mesh_envelopes.md                       |
| Envelope type     | The MVP mesh envelope: ClusterAnnouncement (discovery only under Shape A); further types added additively.             | mesh_envelopes.md                       |
| Peer registry     | A cluster's live view of discovered peers, their advertised endpoints (`consoleUrl`, `apiBaseUrl`) + last-seen liveness (reachable / unreachable).                              | mesh_discovery.md                       |
| Contract          | The versioned seam: the OpenAPI REST specs + the `lattice-contract` mesh envelope module. Schema-first, single-writer. | `platform/lattice-contract`             |
| Monorepo          | A single repository holding every service, the shared modules, the status console, and deploy config.                  | Maven multi-module                      |
| CI                | Continuous Integration - the canonical gate `./mvnw verify`, run locally per push; GitHub Actions on the dev->main PR. | GitHub Actions                          |
| Unit test         | A test that validates a single class or function.                                                                      | JUnit 5                                 |
| Integration test  | A test exercising a service against a real Elasticsearch + Artemis via Testcontainers.                                 | JUnit 5 + vertx-junit5 + Testcontainers |

---

## UX / UI Terms

| Term             | Definition                                                                                                 | Code equivalent     |
|------------------|------------------------------------------------------------------------------------------------------------|---------------------|
| Status console   | The React (Vite + TypeScript) single-page app, one container per cluster, showing the status of all nodes. | `ui/status-console` |
| Node card        | The status-console unit that renders one node's identity + current health at a glance.                     | React component     |
| Status pill      | A small labeled indicator on a node/service showing its state (e.g. healthy / degraded / down).            | React component     |
| Health indicator | A visual signal (color + label) reflecting a service's readiness/liveness on a node card.                  | React component     |
| Design token     | A named, reusable styling value (color, spacing, typography); no hardcoded colors in the console.          | `theme` tokens      |
| Operator / Viewer | The two realm roles every baseline defines: `viewer` reads, `operator` also writes. Same names everywhere; membership is per-baseline. | locked_decisions.md #48, #49 |
| Component        | A reusable UI element in the status console.                                                               | React component     |
| Material UI      | The status console's component library, adopted as a full replacement for its hand-rolled primitives: it supplies the components, the 8px spacing grid, the semantic palette, and the status icons. | locked_decisions.md #62; material_ui.md |
| Emotion          | The styling engine Material UI renders through, and the console's only one: styles are written as `sx` or `styled`, never as inline style props. | locked_decisions.md #62; material_ui.md |
| MUI theme        | The single file holding every colour, spacing step, and type variant. It replaces the token module as the one place a colour may be written, and the `check:tokens` gate points at it. | material_ui.md |
| Unified view     | The status-console view showing this baseline's verdict alongside every discovered peer, read-only and read from this baseline's own peer registry rather than pulled from each owner (locked #61). Each peer carries a redirect to its own console (Shape A). | ui/status-console   |
| Operational view | A status-console screen that acts on this baseline rather than only reporting it: Orders and Inventory. Reached by a tab in the app bar; a viewer sees it with write controls disabled. | locked_decisions.md #64; operational_views.md |
| Listing          | A paged, newest-first read of a resource on this baseline (`listOrders`, `listInventory`). Added because the contract could otherwise be submitted to and looked up in, but not browsed. | locked_decisions.md #65 |

---

## Data Model Terms

| Term     | Definition                                                                                                        | Code equivalent        |
|----------|-------------------------------------------------------------------------------------------------------------------|------------------------|
| Index    | An Elasticsearch collection of documents - the closest analogue to a table.                                       | Elasticsearch index    |
| Mapping  | The schema of an index - field names, types, and how they are indexed. Single-writer is the service that owns it. | Elasticsearch mapping  |
| Document | A single JSON record stored in an index.                                                                          | Elasticsearch document |
| Alias    | A named pointer to one or more indices, letting a mapping change without breaking readers (reindex-behind-alias). | Elasticsearch alias    |
| Analyzer | The pipeline that tokenizes + normalizes text fields at index and query time.                                     | Elasticsearch analyzer |

---

## Operational Terms

| Term                 | Definition                                                                                        | Code equivalent                        |
|----------------------|---------------------------------------------------------------------------------------------------|----------------------------------------|
| Tracked session      | A session that produces commits, a changelog entry, and a PR.                                     | `lat-<n>-<slug>` branch                |
| Sandbox session      | A non-committing, exploratory session.                                                            | no branch                              |
| Locked decision      | A decision that cannot be changed without an unlock process.                                      | `locked_decisions.md`                  |
| Changelog            | A chronological, newest-first record of work.                                                     | `changelog.md`                         |
| Health check         | A probe that reports whether a service is running correctly.                                      | `/health` endpoint                     |
| Readiness / liveness | Kubernetes probes: readiness = ready to receive traffic; liveness = still alive (restart if not). | K8s `readinessProbe` / `livenessProbe` |
| Baseline version     | The version stamp of a cluster's shipped set of services + REST endpoints.                        | locked_decisions.md (#12)              |
| Rolling deploy       | Replacing pods gradually so the service stays available during a version change.                  | K8s rolling update                     |
| Instrumentation      | The metrics a service emits about itself, in Prometheus format: the Java Virtual Machine and Vert.x families from the binding, plus the mesh, rollup and data-layer metrics Lattice writes. The half of observability that needs no hosting decision. | locked_decisions.md #78; observability.md |
| Management port      | A service's second HTTP port, serving `/metrics` only. ClusterIP, never published to the host, and separate from the API port so an unauthenticated scrape surface never rides a port the browser reaches. | locked_decisions.md #78; observability.md |
| Collection stack     | Whatever scrapes and stores the metrics. **Per baseline by default**, with an optional aggregation path named but not built - the open half of P8, waiting on the deferred hosting decision. | locked_decisions.md #78; observability.md |

---

## Infrastructure Terms

| Term                  | Definition                                                                                                      | Code equivalent                |
|-----------------------|-----------------------------------------------------------------------------------------------------------------|--------------------------------|
| Docker image          | The immutable packaged artifact for one service; identical from local to production.                            | `Dockerfile` per service       |
| Kubernetes            | The orchestrator; a Lattice cluster is a Kubernetes cluster running the baseline's services.                    | `deploy/k8s`                   |
| Pod                   | Kubernetes' smallest deployable unit - runs one (or more) containers; a node instance is a pod.                 | K8s pod                        |
| Helm                  | The templating/packaging tool for the Kubernetes manifests.                                                     | `deploy/k8s` Helm charts       |
| Umbrella chart        | The chart a baseline installs as ONE release. It holds the baseline-level values and the service list, and installs a subchart per component. Amends locked #54's one-chart clause; "one release installs one baseline" is unchanged. | locked_decisions.md #77; baseline_configuration.md |
| Subchart              | One component's own chart - its `deployment.yaml` and `values.yaml` - installed by the umbrella. Every component running in its own pod has one, including third-party infrastructure, each with an `enabled` flag so a customer can point at a managed component instead. | locked_decisions.md #77; baseline_configuration.md |
| Library chart         | The shared Helm helpers (`fullname`, `labels`, `commonEnv`) every subchart uses. Mandatory rather than optional: copied per subchart they would drift, which is the defect the single-source design exists to remove. | baseline_configuration.md |
| Artemis broker        | The Apache Artemis message broker carrying mesh traffic. One per baseline, deployed with that baseline.         | `deploy/k8s` (local), mesh     |
| Broker federation     | The Artemis mechanism joining independent per-baseline brokers so announcements cross between them, without clustering them. A joining baseline configures its peers; existing ones are never edited. | locked_decisions.md #44, #47; mesh_broker_topology.md |
| Downstream link       | The federation connection a JOINING baseline opens to command a peer's broker to federate back to it. It is what makes onboarding cost linear and paid by the joiner, so no existing baseline is edited on a join. | locked_decisions.md #44; mesh_broker_topology.md |
| Image archive         | How a baseline is delivered: exported `docker save` archives plus the Helm chart, loaded by the customer before install. **There is no Lattice registry and no Lattice-hosted cluster** - the customer runs them. | locked_decisions.md #55; delivery_model.md |
| Elasticsearch cluster | The Elasticsearch deployment backing one Lattice cluster's data model; the sole datastore.                      | `deploy/k8s` (local + deployed)|
| Local stack           | The local development environment: three `kind` clusters, one baseline each, installed with the same Helm chart a customer receives. The only local stack - compose is retired (locked #77). | `deploy/k8s/mesh-clusters.sh`  |
| Keycloak              | The per-baseline identity provider (its own realm, roles + groups) authenticating operators on that baseline; a redirect to a peer authenticates against that peer's Keycloak. One per baseline, including locally. | locked_decisions.md #38, #48; per_baseline_identity.md |
| Realm                 | One baseline's Keycloak tenant - its own users, roles, groups + client. Realms never share membership: role NAMES are standard across baselines, who holds them is not.                                          | locked_decisions.md #49; per_baseline_identity.md |
| Lattice CA            | The shared certificate authority signing every baseline's broker certificate. Brokers trust the authority, not individual peers, which is what preserves no-edit-on-join while giving each baseline its own identity. | locked_decisions.md #50; per_baseline_identity.md |
