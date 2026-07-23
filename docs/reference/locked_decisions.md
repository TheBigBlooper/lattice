# Lattice - Locked Decisions

An **append-only, numbered** registry of decisions the founder has fixed. Grouped by area under `###` headings. Each entry states the locked value plus a one-line rationale. **Append only** - never renumber or delete an entry; a superseded decision is struck by a new, higher-numbered entry that references it. The `Lock` column is `Hard` (needs an explicit unlock to change) or `Soft` (a default, changeable with less ceremony).

> Only the stack + architecture facts the founder has already fixed are seeded here; deep design questions that are explicitly deferred to a future design session live in the **Planned - design session** group at the bottom as placeholders, NOT as locked decisions.

---

## Locked Decisions

### Technical Stack

| #  | Decision                                                                                                              | Rationale (one line)                                                                                          | Lock |
|----|---------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|------|
| 1  | **Language: Java 21** (JDK 21) for all services.                                                                     | One long-term-support runtime across the whole service tree; modern language features (records, virtual threads). | Hard |
| 2  | **Application framework: Vert.x 5.**                                                                                 | Reactive, non-blocking toolkit that fits a mesh of many small services on the Java Virtual Machine.          | Hard |
| 3  | **Build: Maven multi-module**, wrapper `./mvnw`; canonical gate `./mvnw verify`.                                     | One reproducible build over every service + shared module; the wrapper pins the Maven version per checkout.  | Hard |
| 4  | **Tests: JUnit 5 + vertx-junit5**, Testcontainers for Elasticsearch + Artemis in integration tests.                 | Standard Java test stack; Testcontainers exercises the real datastore + broker instead of mocks.             | Hard |
| 5  | **Packaging + runtime: Docker containers.**                                                                          | Each service ships as one immutable image; identical artifact from local to production.                       | Hard |
| 6  | **Orchestration: Kubernetes.**                                                                                       | A cluster of services is a Kubernetes cluster; scheduling, health, and rollout are the platform's job.        | Hard |
| 7  | **Datastore: Elasticsearch.**                                                                                        | The single data store per cluster; search + document model fit the mesh's query needs.                        | Hard |
| 8  | **Mesh broker: Apache Artemis.**                                                                                     | The message broker over which clusters discover and talk to peer clusters.                                     | Hard |
| 9  | **Status console: React (Vite + TypeScript).**                                                                       | A typed single-page app for node-status viewing; Vite for fast builds; ships as its own container.            | Hard |
| 10 | **Java package namespace / groupId: `io.lattice`.**                                                                  | One stable namespace for every module and published artifact.                                                 | Hard |

> **Stack rationale (why these):** Java 21 + Vert.x 5 give a reactive Java Virtual Machine service tier without a heavier framework; Maven multi-module keeps the contract, common library, and services building as one tree. Elasticsearch is the only datastore (no separate relational database in the baseline). Artemis is the mesh transport; Kubernetes is the only orchestrator. The React status console is a thin operator view, not a product surface.

---

### Architecture

| #  | Decision                                                                                                              | Rationale (one line)                                                                                          | Lock |
|----|---------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|------|
| 11 | **A service = one Docker container** (a thin main verticle wired to `lattice-common` + `lattice-contract`).          | The container is the unit of deploy, scale, and version.                                                       | Hard |
| 12 | **A cluster = all services in one Kubernetes cluster = the versioned baseline** (versioned services + versioned REST endpoints). | "Baseline" names a known-good, versioned set of services and endpoints that ship together.                    | Hard |
| 13 | **Clusters discover + communicate with peer clusters over an Artemis-backed mesh.**                                  | Independent clusters federate at runtime over the broker rather than sharing a datastore.                     | Hard |
| 14 | **Each cluster owns its own, possibly-divergent Elasticsearch data model.**                                          | A cluster evolves its indices independently; no shared-schema coupling between clusters.                       | Hard |
| 15 | **Clusters must be interoperable** across those divergent data models.                                               | The mesh's value is peers exchanging work despite independent local models.                                   | Hard |
| 16 | **A React status console runs as its own container, one per cluster,** showing the status of all nodes.              | Operators see node/cluster health without coupling the view into a service.                                   | Hard |

---

### Contract

| #  | Decision                                                                                                              | Rationale (one line)                                                                                          | Lock |
|----|---------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|------|
| 17 | **OpenAPI 3.1 is the versioned REST contract** (drives Vert.x router validation + the status-console client).        | One schema-first source of truth for every REST endpoint, versioned with the baseline.                        | Hard |
| 18 | **A shared `lattice-contract` Java module defines the Artemis mesh envelopes** (records) every cluster agrees on.    | The interop wire format lives in one single-writer module both sides depend on.                               | Hard |
| 19 | **Request bodies use `additionalProperties: false` + bounded fields.**                                               | Reject unknown/oversized input at the edge; a strict contract is the first line of defense.                   | Hard |
| 20 | **`/api/v<n>/...` REST versioning from the first endpoint.**                                                         | Endpoints are versioned with the baseline so peers can target a known contract version.                        | Hard |

---

### Workflow

| #  | Decision                                                                                                              | Rationale (one line)                                                                                          | Lock |
|----|---------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|------|
| 21 | **Branch model: long-lived `dev` integration branch; `main` is founder-only.**                                      | App work never lands straight on `main`; `dev -> main` is a deliberate founder promotion.                     | Hard |
| 22 | **Git-issue tag `lat`; feature branches are `lat-<issue-number>-<slug>`.**                                           | One naming convention ties every branch to its issue.                                                          | Hard |
| 23 | **Conventional Commits; no authorship trailer or footer on commit messages or PR bodies.**                          | Machine-readable history; keep messages clean - no `Co-authored-by`, no generated footer.                     | Hard |
| 24 | **Issue tracking: GitHub issues with `P0` / `P1` / `P2` priority labels; no project board.**                        | Priority lives on a label, ranked at session start; no board to maintain.                                     | Hard |
| 25 | **One session = one branch = one PR.**                                                                               | A "do the work" instruction never authorizes a second branch/ticket/PR without an explicit go.                | Hard |
| 26 | **Git Safety: fast-forward `git push` only; never force-push or rewrite shared history.**                           | A rewrite of pushed history diverges every other clone and can lose committed work.                           | Hard |

---

### Planned - design session (NOT yet locked)

> These are **open design questions**, deliberately deferred to a future design session. They are recorded here as placeholders so no one treats them as settled; each becomes a numbered locked decision **only** after that session. Do **not** invent details for any of these ahead of the session.

| #  | Open question (placeholder)                                                                                          | Note                                                                                                          | State |
|----|---------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|-------|
| P1 | **Mesh discovery / announcement protocol over Artemis** - how a cluster announces itself and finds peers.           | Transport is Artemis (locked #8, #13); the announce/discover protocol on top of it is undesigned.            | planned - design session |
| P2 | **Cross-cluster interop model across divergent Elasticsearch data models** - how peers exchange work despite local schema differences. | Interoperability is a requirement (#15); the mechanism (mapping, translation, canonical form) is undesigned. | planned - design session |
| P3 | **Mesh message envelope schema + versioning** - the concrete records in `lattice-contract` and how they version.    | The module exists (#18); the envelope fields + version strategy are undesigned.                              | planned - design session |
| P4 | **Per-service Elasticsearch data model** - indices, mappings, aliases per service.                                  | Elasticsearch is the datastore (#7); concrete mappings are per-service design work.                          | planned - design session |
| P5 | **Auth mechanism** - how REST + mesh traffic is authenticated / authorized.                                         | No auth provider or scheme is fixed yet.                                                                       | planned - design session |
| P6 | **Status-console live-status transport** - Server-Sent Events (SSE) vs WebSocket for node status.                   | The console is locked (#16); the push transport is an open choice.                                            | planned - design session |
| P7 | **Container registry + hosting** - where images are pushed and clusters run.                                        | Docker + Kubernetes are locked (#5, #6); the concrete registry + hosting provider are TBD.                    | planned - design session |

---

_Append new locked decisions below with the next number in sequence; when a session settles one of the planned questions above, add it as a new numbered Hard/Soft decision and mark the matching `P#` row as promoted._
