# Lattice - Deploy Protocol

The build / deploy / parity protocol for shipping a Lattice cluster to a real
environment. Where [qa_protocol.md](qa_protocol.md) covers bringing a branch's stack up
**locally** (docker-compose or a dev namespace), this doc covers the **deployed** path:
what a service image bakes in, how a cluster is deployed to Kubernetes, and exactly what
differs local vs dev vs prod so the **prod caveats** are known in advance.

> Lattice is **delivered, not hosted** (locked #55). A baseline delivery is a set of
> **exported Docker image archives** plus the Helm chart, and the **customer runs the
> clusters** - there is no Lattice-hosted registry, no Lattice-hosted cluster, and no
> Fly.io, EAS, Vercel, Clerk, or over-the-air-update path. Hosting is deferred (#56), so
> the deployed-environment sections below describe the shape a customer deployment takes,
> not an environment we currently operate.

It is a protocol the agents follow: before an agent touches an image build, a manifest,
a broker/Elasticsearch config, or a migration/mapping that ships, it reconciles the
change against the relevant checklist below.

> **Scope note.** Image tags, service names, and hostnames in this doc are non-secret.
> Secret material (Elasticsearch credentials, Artemis broker passwords, TLS keys, any
> API token) lives only in **Kubernetes Secrets** / an untracked `.env` and is **never**
> written here or to any tracked file.

---

## The three stages (kept separate)

Industry-standard split; we keep them distinct so a failure is localized to one stage.

| Stage                  | Tool                                          | Does                                                                                                   | Our trigger                                    |
|------------------------|-----------------------------------------------|--------------------------------------------------------------------------------------------------------|------------------------------------------------|
| **CI**                 | GitHub Actions                                | Tests + gates a merge (`./mvnw verify`: compile, unit + integration tests, the console's Vitest, lint) | every push / PR                                |
| **Image build + export** | Docker build -> exported archives (locked #55) | Builds + tags each service/console image (version + git sha) and **exports it as a `.tar`** for delivery | merge to `dev` (auto) / a release tag (prod)   |
| **K8s deploy**         | `kubectl` / Helm                              | Applies the manifests, rolls the Deployments, runs any index/migration job                             | merge to `dev` (auto) / `main` (prod, founder) |

**Key rule:** a deployed cluster runs images **referenced by an immutable tag**
(`<version>-<sha>`) - never a `latest` tag and never a hand-built image. The tag is the
provenance: it traces to a SHA so you know exactly what is running.

The tag rule stands; the *registry* half of it does not (locked #55). Images reach a
customer as **exported archives**, and their cluster pulls from wherever they loaded them -
their own registry, their nodes' image stores, or an air-gapped mirror. That is their
environment's business. Provenance matters **more** under this model, not less: when the
customer holds the artifacts, the tag is the only thread back to a commit, so a floating
tag would make "what is running" unanswerable rather than merely inconvenient. Image standards (base image, non-root, layered jars, healthcheck,
tagging) are owned by the `platform` agent in [platform_protocol.md](platform_protocol.md).

---

## Environment map (local -> dev -> prod)

The single source for what differs per environment. **dev and prod are the customer's clusters** (locked #55/#56), so those columns describe the shape a customer deployment takes rather than an environment we operate. Host values are theirs.

|                                    | **local**                                | **dev**                        | **prod**                                |
|------------------------------------|------------------------------------------|--------------------------------|-----------------------------------------|
| Runs on                            | docker-compose (`deploy/docker/`)        | customer dev cluster           | customer prod cluster (separate)        |
| Image source                       | locally built                            | delivered archive, `dev` build | delivered archive, from a release       |
| Image tag                          | working-tree build                       | `<version>-<sha>` (dev sha)    | `<version>` (release) + `<sha>`         |
| Elasticsearch                      | compose container, local volume          | dev cluster's Elasticsearch    | prod cluster's Elasticsearch            |
| Artemis broker                     | compose container                        | dev cluster's broker           | prod cluster's broker                   |
| Mesh                               | single cluster (or two compose projects) | dev mesh (peers TBD)           | prod mesh (peers TBD)                   |
| Config / secrets                   | untracked `.env` / compose env           | dev ConfigMap + Secret         | prod ConfigMap + Secret (separate)      |
| API docs (`/docs` + `/docs/json`)  | on                                       | on                             | **off** - `API_DOCS_ENABLED=false`      |
| Data                               | manual seed / reindex                    | seeded / steward-gated (TBD)   | real data only, no seed                 |
| Deploy                             | n/a (compose up)                         | auto on merge to `dev`         | founder `dev -> main` promotion         |

**Prod caveats to settle before any prod deploy (fill in as they land):**
- **Prod Elasticsearch + Artemis are separate instances** with their own credentials - a
  dev Secret must never be reused for prod. Confirm prod Secrets exist and are distinct.
- **API docs must be gated off in prod** on every service: set `API_DOCS_ENABLED=false`
  (the chart's `apiDocs.enabled`). It is inherited from `BaseVerticle`, so every service is
  covered including any added later. Verify before promotion: `/docs` and `/docs/json` must both return 404.
- **Resource requests/limits** sized for prod load, not copied from dev (owned by
  `platform`, [platform_protocol.md](platform_protocol.md)).

---

## Per-environment cluster config (namespace + Elasticsearch + Artemis)

This is the sharpest parity trap - the per-environment consistency rule: **every service in a
cluster must point at that cluster's own Elasticsearch
and its own Artemis broker, and the mesh config must match its peers, or the cluster comes
up broken or cross-talks the wrong environment.**

**Why.** Each cluster has its own, possibly divergent, Elasticsearch data model and its own
Artemis broker, and the brokers are joined by **federation** so announcements still cross
between baselines (locked #44 - per-baseline brokers that never federate would mean no
baseline ever hears another, and discovery would silently never work). A service that is
handed the wrong Elasticsearch URL indexes/reads against the wrong data model; a service
pointed at the wrong broker either fails to join the mesh or joins the **wrong** mesh (dev
announcing onto a prod broker is a serious cross-environment leak). Config is per-cluster and
lives in that cluster's ConfigMap/Secret, never baked into the image.

**Federation is broker config, not service config.** `ARTEMIS_URL` is always **this** cluster's
own broker and is never a peer list; the peer connectors live in the broker's own configuration.
An environment's mesh is scoped by which brokers are federated together, so a dev broker must
never federate to a prod one.

**The map (keep this true):**

| Environment | Elasticsearch                | Artemis broker                   | Mesh                                |
|-------------|------------------------------|----------------------------------|-------------------------------------|
| local       | compose ES (local volume)    | one broker per compose project   | two local projects, brokers federated |
| dev         | dev cluster's ES             | dev cluster's own broker         | dev brokers federated to each other   |
| prod        | prod cluster's ES (separate) | prod cluster's own broker        | prod brokers federated to each other  |

**Parity check (run when a service comes up unhealthy or the mesh misbehaves):**
1. Confirm each service's resolved Elasticsearch URL points at **this** cluster's ES
   (from its ConfigMap), not a shared or another environment's index.
2. Confirm each service's Artemis broker URL points at **this** cluster's broker, and that
   the broker credentials come from **this** environment's Secret.
3. Confirm the mesh identity is the environment's own, and that the broker's **federation
   peers** are all in this environment - a dev broker must never federate to a prod one. If any
   differ, align the ConfigMap/Secret and roll the Deployment, then re-verify against the console.
4. If peers are missing rather than misrouted, check the broker's federation links before the
   services: the gateway stays UP with an unreachable broker by design, so a federation problem
   shows up as silent peers, not as a failing service.

---

## Build-parity checklist (per integration)

A deployed image is a configured, running service, not a test run - anything a service needs
must be present in its ConfigMap/Secret (read at startup) or the feature silently breaks in
the cluster. Run this before shipping a cluster for QA or promotion.

| Integration                | Needs in the deploy                                                                          | Breaks as, if missing                                          |
|----------------------------|----------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| **Elasticsearch reach**    | ES URL + credentials in ConfigMap/Secret                                                     | service fails readiness; all data reads/writes error           |
| **Elasticsearch mappings** | the index/mapping applied (index job ran) - single-writer is the `service` agent             | queries 404 / return nothing; indexing fails                   |
| **Artemis broker**         | broker URL + credentials in ConfigMap/Secret                                                 | service cannot join the mesh; messaging dead                   |
| **Mesh join**              | correct mesh identity + peer/discovery config for the environment                            | cluster isolated, or announces onto the wrong mesh             |
| **REST contract**          | the deployed services + console share the same versioned OpenAPI contract                    | 404 / contract-validation errors between console and service   |
| **Health/readiness**       | probes point at each service's health endpoints ([service_protocol.md](service_protocol.md)) | K8s routes traffic to a not-ready pod, or never marks it ready |
| **Resource limits**        | requests/limits set per environment                                                          | eviction / OOM under load, or wasted scheduling                |
| **API docs gating**        | the docs-off flag set in prod                                                                | internal API surface exposed in prod                           |

> **Gotcha:** config comes from the environment's **ConfigMap/Secret at runtime**, not from
> your local `.env`. A value that works in compose can be absent in the cluster - it must be
> added to the manifest's config for that environment.

---

## Reset + reindex dev (wipe to clean, keep the mappings)

When dev Elasticsearch data drifts into a bad state (mis-seeded docs, orphaned fixtures) the
clean fix is a **full wipe + reindex**, not hand-deleting documents. The wipe keeps the
index **mappings** and the cluster config; only the data is rebuilt.

General order (exact commands TBD - land the guarded reset/reindex jobs and reference them):

1. **Confirm the target** - print the Elasticsearch URL + index names first; a reset job must
   **refuse** unless a `CONFIRM_RESET_DEV=yes`-style guard is set and **abort** if the target
   host/index looks like prod. (A guarded reset - it refuses to run against a prod-looking target.)
2. **Wipe** the dev indices' documents (delete-by-query or drop + recreate from the mapping),
   leaving the mappings intact.
3. **Reindex/seed** the intended dev dataset (the seed job - TBD).
4. **Verify:** query the index (`_cat/indices`, a sample search) - expected doc counts, no
   duplicates.
5. **Broker state (separate from the index):** if the reset involves mesh/messaging, drain or
   reset the relevant Artemis queues too - broker state is not in Elasticsearch.

This **wipes the shared dev data** - coordinate with the other founder before running it.

---

## Service versioning (image tags + the baseline)

A cluster is the versioned **baseline**: a set of versioned services with versioned REST
endpoints. Versioning is expressed in **image tags**, not a single marketing string.

- **Each service image is tagged `<version>-<git-sha>`.** The version is the service's own
  semantic version; the git sha makes every image trace to an exact commit. This pair is the
  immutable identity a Deployment references - never a floating `latest`. (Tagging is owned by
  `platform`, [platform_protocol.md](platform_protocol.md).)
- **The baseline is the set of service versions + the REST contract version that ship
  together.** Bump the baseline at a release point (pair it with the release-notes flow and a
  git tag), not on every image build. A cluster records which baseline it is running so
  "what is deployed" is never a guess.
- **REST changes are backward-compatible within a contract major version** (`/api/v<n>`). A
  breaking change is a new versioned endpoint, not a mutation of the deployed one - peer
  clusters and the console may lag a rollout, so both versions must serve during a transition
  (see expand-contract in the runbook).
- **Mesh envelope versions travel with the baseline** and are defined in
  [contract_protocol.md](contract_protocol.md); a cluster must not announce an envelope version
  a peer cannot read.

---

## Where checks live (defense-in-depth ladder)

No single stage catches every failure. Each class is caught at the **earliest rung that can
see it** - a headless CI job never runs a real broker mesh, so a mesh-join failure can only be
caught once a cluster is actually up. For every bug we fix, answer: **which rung catches this
class next time?**

| Rung                     | Runs                                | Gates               | Catches (class)                                           | Example                                      |
|--------------------------|-------------------------------------|---------------------|-----------------------------------------------------------|----------------------------------------------|
| **1. Static + tests**    | local `./mvnw verify` (pre-push hook) every push; GitHub Actions on the `dev` -> `main` PR | the push / the promotion | compile, contract drift, unit/integration failures, lint | a route that violates the OpenAPI contract   |
| **2. Image build**       | the Docker build, per image         | the image           | build failure, missing layered artifact, bad base image   | a service jar that will not assemble         |
| **3. Deploy-time**       | K8s apply / rollout                 | the deploy          | bad manifest, failing readiness, missing ConfigMap/Secret | a service pointed at the wrong Elasticsearch |
| **4. Post-deploy smoke** | a script/human after rollout        | the deploy          | live reachability, mesh join, index presence              | health curl + two-cluster discovery          |
| **5. Runtime capture**   | in the field (logs/metrics/tracing) | nothing (last line) | whatever slips 1-4                                        | a broker reconnect storm under load          |

**Rung-by-failure map (extend this as bugs are fixed):**
- **Missing/wrong cluster config** (ES URL, broker URL) -> rung 3 (readiness fails on apply) +
  rung 4 (health smoke).
- **Wrong-mesh announce** (dev onto prod broker) -> rung 3 config review + rung 4 (peer list on
  the console) - not statically catchable.
- **Contract drift between console and service** -> rung 1 (contract tests) + rung 4 (browser
  smoke).
- **Index/mapping not applied** -> rung 4 (query returns nothing); prevent with the index job as
  part of deploy (rung 3).

---

## Cluster bring-up runbook

Standing up a Lattice cluster (dev or a fresh environment) in order. The tooling is **Helm**
(locked #54) and the chart is `deploy/k8s/chart`; the registry and host values are still **TBD**
and owned by the `platform` agent ([platform_protocol.md](platform_protocol.md)). This is the
order of operations.

1. **Namespace.** Create the cluster's namespace (e.g. `lattice-dev`). One namespace per
   environment keeps config and mesh identity isolated.
2. **Secrets + config.** Apply the environment's **Secret** (Elasticsearch credentials,
   Artemis broker password, TLS) and **ConfigMap** (ES URL, broker URL, baseline version,
   mesh identity/peer config). Never echo secret values into a tracked file - create them from
   an untracked source (`kubectl create secret ...`).
3. **Artemis broker.** Deploy the broker and confirm it is ready before the services - a
   service that starts before the broker fails readiness and stays out of rotation. (Broker
   deployment + mesh registration are owned by `platform`.)
4. **Elasticsearch.** Deploy/reach Elasticsearch and **apply the index mappings** (the index
   job; single-writer is the `service` agent). Confirm the indices exist before the services
   read them.
5. **Apply the service + console manifests.** Roll out the Deployments (each referencing an
   immutable `<version>-<sha>` image), their Services, and readiness/liveness probes. Wait for
   every pod to pass readiness.
6. **Mesh join.** Confirm each service announces onto the mesh and the cluster discovers its
   peers - verify on the status console's mesh/peer view. For a two-cluster environment, bring
   the peer up and confirm mutual discovery.
7. **Smoke.** Run the deployed smoke below.

**Standard deploy (code / config change to an existing cluster):**
1. Merge to `dev` -> auto build + push the image(s), then roll the Deployment(s).
2. A ConfigMap/Secret change is applied and the affected Deployments rolled - config is
   separate from the image and persists across image rolls.

**Mapping change (a mapping/index ships):**
1. Change the Elasticsearch mapping + its spec-driven integration test in the same change
   (the documented test-first exception - a mapping cannot be queried until it exists; owned
   by the `service` agent, [service_protocol.md](service_protocol.md)).
2. Merge to `dev` -> the index job applies the mapping on the dev cluster.
3. Reindex only if the change needs it.

**Expand-contract (zero-downtime, and peer-safe):** split a breaking REST or mapping change
into **expand** (add the new field/index/endpoint, deploy, backfill) then **contract** (remove
the old, deploy) across two releases, so old and new service images - and lagging peer
clusters - all interoperate against the in-between state.

---

## Deployed smoke (rung 4)

The checklist a static stage cannot run - verify against a deployed cluster, per deploy. Seed
these into the PR / issue QA checklist ([qa_protocol.md](qa_protocol.md)):

- [ ] **All services ready** - every service's readiness endpoint returns healthy; no pod in a
  crash loop (`kubectl get pods`).
- [ ] **Console shows the cluster green** - open the status console; every node/service renders
  healthy, versions/baseline as expected.
- [ ] **Elasticsearch reads** - a known query returns the expected documents (confirms the ES
  read path + mappings are healthy).
- [ ] **Mesh discovery** - the cluster announced onto the mesh and lists its peers; a two-cluster
  environment shows mutual discovery.
- [ ] **Contract parity** - the console's OpenAPI client and the deployed services agree (no
  404 / validation errors in the console).
- [ ] **API docs gated** - `/docs` is off in prod, on in dev.

---

## Open items (unverified - do not treat as settled)

Resolve and update this doc as each lands.

- ~~**Container registry** - name/host TBD.~~ **Settled by #55**: no vendor registry; images are delivered as exported archives.
- **K8s tooling** - **settled: Helm** (locked #54); the chart is `deploy/k8s/chart`. Registry + host values still owned by `platform`.
- **Mesh peer discovery over Artemis** - the `ClusterAnnouncement` shape + announce/discovery
  protocol are **settled** (Shape A: `mesh_discovery.md` + `mesh_envelopes.md`); the runtime
  implementation is pending (#9, owned by `platform`, envelopes in [contract_protocol.md](contract_protocol.md)).
- **Seed / reindex jobs** - the guarded dev reset + seed are not yet built; commands are TBD.
- **API-docs gating flag** - the per-environment mechanism to turn `/docs` off in prod is TBD.
- **Prod cluster** - no prod environment stood up yet; the prod column is planned, not built.
