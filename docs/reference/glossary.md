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
| Interoperability  | The requirement that clusters exchange work despite each owning its own, possibly-divergent Elasticsearch data model.                                      | locked_decisions.md (Architecture) - P2 planned       |
| Discovery         | How a cluster announces itself on the mesh and finds peer clusters. Transport is Artemis; the protocol itself is a deferred design question.               | locked_decisions.md - P1 planned                      |

---

## Technical Terms

| Term              | Definition                                                                                                             | Code equivalent                         |
|-------------------|------------------------------------------------------------------------------------------------------------------------|-----------------------------------------|
| Vert.x            | The reactive, non-blocking toolkit every Java service is built on.                                                     | `io.vertx` (Vert.x 5)                   |
| Verticle          | Vert.x's unit of deployment - an actor-like component; a service's thin main verticle wires up its routers.            | `AbstractVerticle` / `BaseVerticle`     |
| Event bus         | Vert.x's in-process (and clusterable) message bus between verticles.                                                   | `vertx.eventBus()`                      |
| OpenAPI operation | One versioned REST endpoint defined in the OpenAPI 3.1 spec; drives Vert.x router validation + the console client.     | `lattice-contract` OpenAPI resources    |
| Envelope          | A shared mesh message record (in `lattice-contract`) that every cluster agrees on for interop over Artemis.            | `platform/lattice-contract` records     |
| Contract          | The versioned seam: the OpenAPI REST specs + the `lattice-contract` mesh envelope module. Schema-first, single-writer. | `platform/lattice-contract`             |
| Monorepo          | A single repository holding every service, the shared modules, the status console, and deploy config.                  | Maven multi-module                      |
| CI                | Continuous Integration - automated checks on each PR (the canonical gate is `./mvnw verify`).                          | GitHub Actions                          |
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
| Component        | A reusable UI element in the status console.                                                               | React component     |

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

---

## Infrastructure Terms

| Term                  | Definition                                                                                                      | Code equivalent                |
|-----------------------|-----------------------------------------------------------------------------------------------------------------|--------------------------------|
| Docker image          | The immutable packaged artifact for one service; identical from local to production.                            | `Dockerfile` per service       |
| Kubernetes            | The orchestrator; a Lattice cluster is a Kubernetes cluster running the baseline's services.                    | `deploy/k8s`                   |
| Pod                   | Kubernetes' smallest deployable unit - runs one (or more) containers; a node instance is a pod.                 | K8s pod                        |
| Helm                  | The templating/packaging tool for the Kubernetes manifests.                                                     | `deploy/k8s` Helm charts       |
| Artemis broker        | The Apache Artemis message broker carrying mesh traffic between clusters.                                       | `deploy/docker` (local), mesh  |
| Container registry    | Where built Docker images are pushed for clusters to pull. Concrete provider is TBD (deferred design question). | TBD - locked_decisions.md P7   |
| Elasticsearch cluster | The Elasticsearch deployment backing one Lattice cluster's data model; the sole datastore.                      | `deploy/docker` (local), K8s   |
| Local server          | The local development environment - docker-compose runs Elasticsearch, Artemis, and services.                   | `deploy/docker` docker-compose |
