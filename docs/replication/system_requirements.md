# Lattice - System Requirements Document (SRD)

Part of the [replication pack](replication_prompt.md), alongside the [Software Design Document](software_design.md) and the [External API document](external_api.md). This document states, as numbered requirements, what a system must do and be to count as a replication of Lattice. It is the binding part of the pack: the replication prompt sequences the build, the design document explains the shape, the External API document pins the wire, and this document is the checklist all of it is verified against.

**This document is deliberately self-contained.** It is written to be handed to a builder (human or LLM) who does not hold this repository. Inside the repository, the canonical sources remain [locked_decisions.md](../reference/locked_decisions.md), the protocols under `docs/protocol/`, and the design docs under `docs/design/`; if this document ever disagrees with them, they win and this document is the defect.

## Conventions

- **Shall** marks a binding requirement; a replica missing one is not a replication. **Should** marks a strong default a builder may vary with a recorded reason. **May** marks an allowed option.
- Requirements are numbered `<AREA>-<nnn>` and are stable: a withdrawn requirement keeps its number and is marked withdrawn, never reused.
- Each requirement carries a verification method: **T** (test - an automated test proves it), **D** (demonstration - observed on a running system), **I** (inspection - read the artifact).

## Scope exclusion

The repository's `orders` and `inventory` services exist purely to demonstrate the platform on an example domain (a regional fulfillment network). **They are not required.** Requirements below are stated for the platform and for "a domain service" generically; a replica satisfies them with domain services of its own, provided each follows the service-shaped requirements (SVC, DAT, CON). At least one data-owning domain service is required to prove the vertical slice (VER-002).

---

## 1. ARC - System architecture

| ID      | Requirement                                                                                                                                                                                                    | Verify |
|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| ARC-001 | Every service shall be a Java 21 application built on Vert.x 5.                                                                                                                                                | I      |
| ARC-002 | Every service shall ship as one immutable Docker image; the container shall be the unit of deploy, scale, and version.                                                                                         | I      |
| ARC-003 | All services of one deployment shall run in one Kubernetes cluster, and that collection shall constitute the versioned baseline: versioned services plus versioned REST endpoints that ship together.          | I      |
| ARC-004 | Each cluster shall own its own Elasticsearch data model, and data models may diverge between clusters without breaking interoperability.                                                                       | D      |
| ARC-005 | Elasticsearch shall be the single datastore for all platform-owned data. A third-party infrastructure component may bring the store it requires (e.g. the identity provider's relational database), and no platform service shall ever connect to that store. | I      |
| ARC-006 | Separate clusters shall discover and communicate with peer clusters exclusively over an Apache Artemis backed mesh.                                                                                            | D      |
| ARC-007 | The mesh shall carry discovery and endpoint advertisement only. No domain data and no work shall cross the mesh (Shape A federation).                                                                          | I, T   |
| ARC-008 | Acting on a peer baseline shall be a browser redirect to that peer's own console (`consoleUrl`); no cluster shall read or write another cluster's data.                                                        | D      |
| ARC-009 | A React (Vite + TypeScript) status console shall ship as its own container, one per cluster, showing the status of that cluster and its discovered peers.                                                      | I      |
| ARC-010 | Exactly one service per cluster (the mesh-gateway) shall participate in the mesh: one announcement stream and one peer registry per cluster.                                                                    | I, T   |
| ARC-011 | The build shall be a single Maven multi-module reactor: a parent aggregator, a contract module, a shared runtime module, and one module per service, with shared versions managed centrally in the parent.     | I      |
| ARC-012 | The Java package root and Maven group shall be a single stable namespace (`io.lattice` in the original; a replica shall choose one and use it everywhere).                                                     | I      |
| ARC-013 | Shared components shall be reused, never forked: one base verticle, one repository base, one console component per concept. A second parallel implementation of an existing shared component is a defect.      | I      |

## 2. CON - Contract

| ID      | Requirement                                                                                                                                                                       | Verify |
|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| CON-001 | The REST contract shall be an OpenAPI 3.1 document, schema-first: it shall drive server-side router validation and generate the console's typed client, and neither side shall hand-declare a parallel shape. | I, T   |
| CON-002 | One contract module shall be the single writer of every REST data transfer object and every mesh envelope record; no service shall inline a wire shape.                            | I      |
| CON-003 | REST paths shall be versioned in the path (`/api/v1/...`) from the first endpoint; a new major version shall be a new document and path prefix served alongside the old, never an in-place rewrite. | I      |
| CON-004 | Every REST response shall be the envelope `{data XOR error, meta}` defined in the External API document section 2.3, with the fixed error taxonomy of section 2.4.                 | T      |
| CON-005 | Every request body shall be strict: `additionalProperties: false`, bounded string types, bounded arrays. Unknown keys shall be rejected with `VALIDATION_ERROR`.                   | T      |
| CON-006 | Response schemas shall remain lenient (no `additionalProperties: false`) so additive response fields never break an older client.                                                  | I      |
| CON-007 | List operations shall page by the shared bounded `page`/`size` parameters with counts in `meta.pagination`, per External API document section 2.5.                                | T      |
| CON-008 | Servers shall mint every identifier and timestamp; no request body shall carry a client-supplied id; all timestamps shall be UTC ISO-8601.                                        | T      |
| CON-009 | Mesh envelopes shall be immutable record types serialized as JSON with the common header of External API document section 7.2, and shall follow its compatibility rules: additive changes keep `schemaVersion`, breaking changes bump it, and an unknown higher version is skipped at the payload after reading the header. | T      |
| CON-010 | Each service shall publish only the operations it owns: the served OpenAPI document shall be narrowed per service, and no unmounted operation shall be advertised.                | T      |
| CON-011 | The platform operations `getBaseline`, `getPeers`, and `getMetrics`, and the probe endpoints `/health` and `/readiness`, shall conform to External API document sections 3 and 4. | T      |

## 3. SVC - Service runtime

| ID      | Requirement                                                                                                                                                                          | Verify |
|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| SVC-001 | Every service shall extend one shared base verticle providing configuration loading, `/health` and `/readiness`, the `/api/v1` authentication guard, graceful shutdown, and the management port. A service shall not re-implement any of these. | I      |
| SVC-002 | Route handlers shall be thin: parse and validate against the contract, call a service method, map the result to the envelope. Business logic shall live in a service layer that no web type (e.g. `RoutingContext`) leaks into. | I      |
| SVC-003 | `/health` shall report liveness with no dependency checks; `/readiness` shall return 200 only when every registered dependency check passes and 503 otherwise, in the operational shape of External API document section 4. | T      |
| SVC-004 | A data-owning service's readiness shall reflect true datastore health, not a transport ping: a datastore that cannot serve (e.g. Elasticsearch red) shall fail readiness.            | T      |
| SVC-005 | Services shall not block the event loop: blocking work shall be offloaded to worker execution, and no unmanaged thread pools or sleeps shall appear in handler code.                  | I      |
| SVC-006 | All internal time handling shall be UTC with an explicit locale; no code shall rely on host-default timezone or locale.                                                              | I, T   |
| SVC-007 | One logging pipeline shall serve the whole system: an SLF4J facade with a Logback binding, toolkit logging routed through it, third-party commons-logging bridged into it. No `System.out`, no `printStackTrace`, no second facade. | I      |
| SVC-008 | Logs shall be parameterized, levelled deliberately (ERROR with exception for failed operations, WARN concise for handled anomalies, INFO for lifecycle, DEBUG for detail), and shall never contain secrets, credentials, or unbounded user input. | I      |
| SVC-009 | Configuration shall be read through one shared loader from SCREAMING_SNAKE_CASE environment variables; no scattered environment reads and no hardcoded secrets anywhere.             | I      |
| SVC-010 | Data transfer objects, envelopes, configuration and value objects shall be immutable records; closed variant sets shall be sealed types; no method shall return null for a collection. | I      |
| SVC-011 | Startup shall be resilient: a service started before its dependencies (broker, datastore) shall join or bootstrap on its own once they appear, without restart, and a failed bootstrap shall retry rather than memoize the failure. | T      |

## 4. DAT - Data layer

| ID      | Requirement                                                                                                                                                       | Verify |
|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| DAT-001 | All Elasticsearch access shall go through one shared repository base in the shared runtime module; no service shall hand-roll a client.                           | I      |
| DAT-002 | The data model shall be index-per-entity with read and write aliases, so a mapping can evolve by reindex-behind-alias without breaking readers.                   | I, T   |
| DAT-003 | Elasticsearch mappings shall live in the shared runtime module and be single-writer: one in-flight change at a time.                                              | I      |
| DAT-004 | Each service shall bootstrap its own indices from committed mappings on startup, applying additive mapping updates in place to an existing index.                 | T      |
| DAT-005 | Automatic index creation on write to an unknown target shall be disabled, so a premature write cannot invent an index that blocks a later bootstrap.              | T      |
| DAT-006 | Indices shall set `auto_expand_replicas: "0-1"`, so a single-node cluster is genuinely green and a multi-node cluster regains replica redundancy.                 | T      |
| DAT-007 | Cluster status shall map green to `UP`, yellow to `DEGRADED`, red to `DOWN` wherever datastore health is reported.                                                | T      |
| DAT-008 | The Elasticsearch client library version, the Testcontainers image tag, and the deployed server image tag shall derive from one declared version property.        | I      |

## 5. MSH - Mesh discovery

| ID      | Requirement                                                                                                                                                                             | Verify |
|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| MSH-001 | Discovery shall be decentralized: no central registry. Each cluster shall announce itself on the shared multicast address and maintain its own peer registry from announcements heard.  | D, T   |
| MSH-002 | The announcement shall be the `ClusterAnnouncement` envelope of External API document section 7.3: the fixed six-field payload, so the wire is O(1) in service count.                    | T      |
| MSH-003 | A cluster shall announce on startup, on a periodic heartbeat (default 10 seconds), and immediately on a material change (health flip or baseline version change). The heartbeat shall be the liveness signal; there shall be no separate ping. | T      |
| MSH-004 | The announce address shall be addressed as a topic (multicast), never a queue, so every peer sees every announcement.                                                                   | T      |
| MSH-005 | A peer unheard past the time-to-live (default 30 seconds) shall flip to `UNREACHABLE` and be retained with its last-known snapshot, never deleted; any later announcement shall restore it. | T      |
| MSH-006 | Peer liveness shall be derived on read from the receiver's own clock (`lastSeen`), so a peer's clock drift cannot mask or fake liveness and an idle registry needs no bookkeeping.       | T      |
| MSH-007 | Heartbeat interval and peer time-to-live shall be configuration-driven with the defaults above.                                                                                          | I      |
| MSH-008 | The announced health shall be the rollup label only. The per-service breakdown, infrastructure state, and mesh-link state shall be served on the owning baseline's own API and never announced. | T      |
| MSH-009 | The gateway shall compute the rollup by polling the readiness of the services named in its configured service list (`ready` all up, `degraded` some, `down` none).                       | T      |
| MSH-010 | The gateway shall report its own broker connection as the mesh-link state on its own API, and its readiness shall deliberately stay UP when the broker is unreachable, so the console keeps an honest degraded view. | T      |
| MSH-011 | The mesh transport client shall be the non-blocking AMQP 1.0 client native to the toolkit; the mesh shall never require blocking offload.                                               | I      |
| MSH-012 | The peer registry shall be in-memory only: it is derived state that peers re-announce every heartbeat.                                                                                  | I      |

## 6. FED - Broker federation and trust

| ID      | Requirement                                                                                                                                                                       | Verify |
|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| FED-001 | Every baseline shall run its own Artemis broker; brokers shall join by address federation, not clustering.                                                                        | I, D   |
| FED-002 | Federation shall be limited to the announce address with `max-hops="1"`, so a federated message is never re-federated (loop prevention), and this shall be measured, not assumed. | T      |
| FED-003 | A joining baseline shall declare both upstream and downstream connections to each peer, so existing baselines federate with it with no edit, restart, or redeploy anywhere.       | D      |
| FED-004 | Brokers shall authenticate each other by mutual TLS with per-baseline X.509 certificates signed by a shared certificate authority; trust shall attach to the authority, never to named peers. | T, D   |
| FED-005 | A certificate from an untrusted authority, and a revoked certificate, shall each be refused, with only the enforcing baseline's configuration involved.                           | D      |
| FED-006 | Federation authorization shall be ordinary broker security: a shared federation role granted the same permissions as the baseline's own services, presented by the joiner as credentials on its federation element. | I      |
| FED-007 | There shall be one certificate authority per deployment and per environment; a development certificate shall structurally fail a production handshake. The issuing tool shall be deliverable to the customer, and no key material load-bearing for a customer's mesh shall be held by the vendor. | I      |
| FED-008 | The federation link shall be raw TCP with end-to-end mutual TLS; the deployment shall not route it through any TLS-terminating middlebox.                                         | I      |
| FED-009 | Each baseline shall measure its broker's federation link state per peer at the broker (up/down/refused per External API document section 3.7) rather than inferring it from peer silence, and derive its broker infrastructure row from those states so the two readings cannot disagree. | T      |
| FED-010 | Services shall address only their own baseline's broker; the broker address configuration shall be single-valued.                                                                 | I      |

## 7. IDN - Identity and access

| ID      | Requirement                                                                                                                                                                     | Verify |
|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| IDN-001 | Each baseline shall run its own Keycloak with its own realm; a redirect to a peer authenticates against that peer's realm.                                                      | D      |
| IDN-002 | Every baseline shall define exactly two realm roles with matching groups: `viewer` (every read) and `operator` (reads plus writes). Role names shall be standard across baselines; membership shall be per-baseline and deliberately unsynchronized. | I, T   |
| IDN-003 | Every `/api/v1` operation on every service shall require a bearer token from that baseline's own realm; `/health`, `/readiness`, and the documentation endpoints shall remain open. The guard shall live in the shared base verticle so a new service cannot forget it. | T      |
| IDN-004 | Token validation shall be bearer-only against the realm's cached JSON Web Key Set: no per-request identity-provider call, so an identity-provider outage does not invalidate issued tokens. | T      |
| IDN-005 | A token issued by a peer baseline's realm shall be refused with 401; a valid token lacking the required role shall be refused with 403.                                         | T      |
| IDN-006 | The console shall be a public client using authorization-code with Proof Key for Code Exchange; services shall never handle passwords.                                          | I      |
| IDN-007 | The realm shall be provisioned from a committed import file whose redirect addresses derive from the configured console address and service ports; a console served at any other address shall be refused. | T, D   |
| IDN-008 | Locally, Keycloak shall run dev-mode and unpersisted (so realm-file edits always apply); deployed, it shall persist in its own relational database that no platform service connects to, with a startup probe guarding schema migration. | I      |
| IDN-009 | The identity database shall be reported through Keycloak's own readiness (with its database check enabled in the readiness group), probed on a management endpoint that stays reachable when not ready, rather than by a platform database client. | T      |
| IDN-010 | The first authenticated write after a cold start shall not fail: a request arriving before signing keys are cached shall be paused, not drained.                                | T      |

## 8. OBS - Observability

| ID      | Requirement                                                                                                                                                        | Verify |
|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| OBS-001 | Every service shall serve Prometheus exposition at `/metrics` on a dedicated management port per External API document section 6: unauthenticated, ClusterIP only, never published to the host, never the API port. | I, T   |
| OBS-002 | Instrumentation shall cover the runtime families from the toolkit binding plus platform meters for the mesh (announcements, expiries, link state), the health rollup, and the shared repository (a timer and an error counter). | T      |
| OBS-003 | HTTP metrics shall be labelled by route template, never raw path; the fallback for an unresolvable template shall be no path label, never the raw path.            | T      |
| OBS-004 | Gauge registration shall be re-registration-safe: replacing a gauge's owning object shall never leave a meter bound to a dead owner reporting stale values.        | T      |
| OBS-005 | The console shall read metrics only through the guarded `getMetrics` contract operation (External API document section 3.3), which shall carry the platform's own meters only. | T      |
| OBS-006 | Metrics shall be enabled by default and switchable per deployment (`METRICS_ENABLED`, `METRICS_PORT` with default 9090).                                           | I      |
| OBS-007 | The collection stack (what scrapes and stores) shall default to per-baseline and is otherwise out of scope; tracing, log aggregation, business metrics, dashboards and alerting are deliberately excluded. | I      |

## 9. UI - Status console

| ID     | Requirement                                                                                                                                                            | Verify |
|--------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| UI-001 | The console shall be a React single-page application (Vite + TypeScript) shipped as its own container per baseline, with its API addresses baked in at build time.     | I      |
| UI-002 | The console's API client shall be generated from the OpenAPI document, never hand-declared, and regenerated whenever the contract changes.                             | I, T   |
| UI-003 | The console shall render verdicts, never compute them: the cluster rollup, the federation conclusion, and every other judgement shall be read from the API, so the console cannot disagree with what the baseline announces. The sole exception is arithmetic over fields the registry already sets (the mesh rollup count). | I      |
| UI-004 | The console shall poll for live status (default every 10 seconds) rather than holding a push connection.                                                               | I      |
| UI-005 | The unified mesh view shall render every discovered peer from this baseline's own registry (`getPeers`) and shall never fan out to a peer's API from the browser.      | I, T   |
| UI-006 | Acting on a peer shall be a redirect action to that peer's `consoleUrl`, on the peer's row, requiring sign-in against the peer's own realm.                            | D      |
| UI-007 | The console shall distinguish, on screen, this baseline's own broker connection (Broker) from the broker's per-peer links (Federation), and shall not call either one "mesh". | I      |
| UI-008 | An unreachable peer shall stay visible with its last-known snapshot and how long since it was seen; a failed read shall say whether it is still retrying and annotate rather than block views that carry no form. | D      |
| UI-009 | A viewer lacking a grant shall see controls visible but disabled with the missing grant named on this baseline, never hidden, because they may hold the grant on a peer. | T      |
| UI-010 | Every state indication shall carry colour, glyph, and word together; colour shall never be the sole indicator.                                                         | I      |
| UI-011 | Styling shall use one component library (Material UI) and one styling engine (Emotion) through a single theme file; no colour literal shall exist outside the theme, enforced by an automated check. | T      |
| UI-012 | The console shall have its own quality gate set (lint plus format, type check, unit tests with coverage, dead-code check, security lint) runnable as one command, since the reactor's gates do not cover it. | I      |
| UI-013 | An ended session shall say why (expired versus never signed in), and the sign-in flow shall survive a strict-mode double mount without consuming its own authorization code twice. | T      |

## 10. DEP - Packaging, deployment, configuration

| ID      | Requirement                                                                                                                                                          | Verify |
|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| DEP-001 | All service images shall build on one shared, pinned JDK 21 runtime base image declaring a numeric user (so `runAsNonRoot` holds); no service shall drift to another base. | I      |
| DEP-002 | One Helm release shall install one baseline. The chart shall be an umbrella with a subchart per component over a mandatory shared library chart, each component with an `enabled` flag so a customer can point at infrastructure the environment already runs. | I      |
| DEP-003 | Baseline configuration shall have exactly one authored source (the chart); every environment variable shall be declared, with a one-line description, in the values of the component that reads it. Derived values (image tags from the baseline version, realm redirect addresses from the console address) shall be derivations, never second declarations. | I      |
| DEP-004 | Every container shall declare a measured security context; all shall run non-root except where a component genuinely requires otherwise, with the reason recorded in its values. | T      |
| DEP-005 | Rendered chart output shall be linted in continuous integration: every baseline rendered, no duplicate environment key, every container carrying a security context, and the lint shall self-test against committed fixtures so a lint that stops matching fails loudly. | T      |
| DEP-006 | The local stack shall be the same chart a customer receives, installed on three local kind clusters (one baseline each) by one script offering create, image build, deploy, seed, per-service redeploy, and the failure scenarios. | D      |
| DEP-007 | The image build path shall compile before it packages, so a built image can never silently carry a stale jar.                                                        | I      |
| DEP-008 | Delivery shall be exported image archives plus the chart; no vendor-hosted registry and no vendor-hosted cluster. Images shall be referenced by an immutable `<version>-<sha>` tag. | I      |
| DEP-009 | The baseline version shall be declared once; every consumer (chart, scripts, labels) shall read it from that declaration.                                            | I      |
| DEP-010 | The system shall pass the scripted failure-scenario suite defined in the failure scenario specification (the pack's `failure_scenarios.md`) against the running three-baseline stack: seven scenarios, each opening with a control asserting the healthy pre-state, restoring what it broke, and turning a failed assertion into a non-zero exit status. | D      |
| DEP-011 | A cluster's announced verdict shall never be affected by infrastructure state: a datastore that has merely lost a replica shall not make a peer believe the baseline cannot serve. | T      |

## 11. QUA - Build quality and verification

| ID      | Requirement                                                                                                                                                              | Verify |
|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| QUA-001 | One command (`./mvnw verify`) shall run every Java gate: compile, unit and integration tests, formatting, style, coverage floors (line 90 percent, branch 80 percent per module), dependency convergence, static analysis over main and test sources, and unused-private-member detection. Every gate shall fail the build, not warn. | T      |
| QUA-002 | Integration tests shall exercise real dependencies (Elasticsearch, Artemis) via Testcontainers with pinned image tags matching the deployed versions; unit tests shall mock at the repository or typed-client seam and never touch uncontrolled input/output. | I      |
| QUA-003 | A test emitting an unexpected error or warning log shall fail, mechanically; expected logs shall be declared and asserted by the test.                                   | T      |
| QUA-004 | Test suites shall be deterministic and leak-free: async work awaited to settle, every client and verticle closed in teardown, no assertions on single snapshots of timing-dependent intermediate states. | I      |
| QUA-005 | New behavior shall be built test-first (a failing test shown before implementation), with one documented exception: datastore mapping work carries its specification-driven integration tests in the same change. | I      |
| QUA-006 | The supply chain shall be scanned in continuous integration: the console lockfile plus a build-generated software bill of materials (not the build files directly, which remote resolution cannot see); suppressions shall each carry a reason and a removal condition. | I      |
| QUA-007 | Continuous integration shall run the full gate set on a clean runner for every push to the integration branch and every feature branch, and a red run shall block the merge as a required check. | I      |
| QUA-008 | Dependencies shall follow one-engine-per-job: one JSON codec, one HTTP client, one logging facade, one styling engine; versions managed centrally; a duplicate engine is converged, not excluded around. The sole recorded exception is a second static-analysis engine scoped to exactly the checks the first is structurally blind to. | I      |

## 12. VER - Replication acceptance

| ID      | Requirement                                                                                                                                     | Verify |
|---------|--------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| VER-001 | The staged acceptance checks of the replication prompt shall each pass in order, each stage closing on its check rather than on judgement.       | T, D   |
| VER-002 | At least one data-owning domain service (of the builder's own domain) shall exist end to end: strict contract operations, thin routes, a repository over the shared base, both test layers, an image, and a subchart. | T      |
| VER-003 | Three baselines shall run locally on separate kind clusters, discover each other across cluster boundaries, and survive the full failure-scenario set of DEP-010. | D      |
| VER-004 | The external interfaces shall conform to the External API document in full, verified by the contract test suites and by inspection of the served OpenAPI document. | T, I   |
