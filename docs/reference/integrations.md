# Lattice - External Service Integrations

Developer runbook for setting up every external service Lattice depends on. Follow this in order when provisioning a new environment.

> Each external service gets one section, with a per-environment env-var matrix at the bottom. A genuinely open choice is marked with the planned question it belongs to (currently **P8**, observability) rather than a bare TBD - do not invent a value for one, and do not leave a settled decision described as pending. A remaining **TBD** in a variable table means the value is per-environment, not that the mechanism is undecided.

---

## Services

| Service                  | Purpose                                                         | Environments               |
|--------------------------|-----------------------------------------------------------------|----------------------------|
| Elasticsearch            | The datastore for every cluster (one data model per cluster)    | local, dev, prod           |
| Apache Artemis broker    | The mesh transport - clusters discover + talk to peer clusters  | local, dev, prod           |
| Kubernetes cluster       | Orchestrates the baseline's service containers                  | dev, prod (local optional) |
| Image delivery           | Exported archives plus the chart; there is no registry (locked #55) | all                        |
| Observability (P8, open) | Metrics + tracing (+ log aggregation) for services and the mesh | dev, prod                  |
| Keycloak (per baseline)  | Authentication / authorization - each baseline its own realm (locked #38) | local, dev, prod           |
| CI (GitHub Actions)      | `./mvnw verify` on the dev->main PR (local hook gates pushes)   | all                        |

---

## Elasticsearch - Datastore

**Purpose:** The single data store for a cluster. Each cluster owns its own, possibly-divergent data model (indices + mappings). Services read/write through the Elasticsearch client in `lattice-common`; integration tests run against a real Elasticsearch via Testcontainers.

**Setup**

1. Local: one Elasticsearch per baseline, deployed by the Helm chart into that baseline's kind cluster (single node, security relaxed for local only). **Version pinned to 8.19.x** (client `co.elastic.clients:elasticsearch-java`, the Testcontainers image, and the chart's image all track one Maven property `elasticsearch.version`; locked #34).
2. Each service is the **single writer** of its own indices/mappings - a mapping change ships with the service that owns it.
3. **Indices and aliases are bootstrapped by the owning service on startup**, create-if-absent, with additive mapping updates applied in place so an existing index gains new fields on boot (locked #32). A failed bootstrap is retried rather than memoized, and Elasticsearch is configured to refuse inventing an index for an unknown target - a premature write to a write alias once created an index carrying that alias's name and left the service unable to bootstrap ever again.

**Environment variables (read by each service)**

| Variable                 | Value                                                 | Notes                                         |
|--------------------------|-------------------------------------------------------|-----------------------------------------------|
| `ELASTICSEARCH_URL`      | Elasticsearch endpoint (e.g. `http://localhost:9200`) | required                                      |
| `ELASTICSEARCH_USERNAME` | user for basic auth                                   | TBD - may be unset locally (security relaxed) |
| `ELASTICSEARCH_PASSWORD` | password for basic auth                               | secret; TBD per environment                   |

**Verification:** A service starts and its integration tests (Testcontainers Elasticsearch) pass; `curl $ELASTICSEARCH_URL/_cluster/health` returns `green`/`yellow`.

---

## Apache Artemis - Mesh Broker

**Purpose:** The message broker over which clusters discover and communicate with peer clusters (the mesh). The shared `lattice-contract` module defines the envelope records exchanged over it. Integration tests run against a real Artemis via Testcontainers.

**Setup**

1. Local: one Artemis broker per baseline (locked #44), deployed by the chart into that baseline's kind cluster (image `apache/activemq-artemis`). The federation acceptor is exposed as a NodePort so peers in other clusters can dial it; it is not published to the host, because nothing on the host needs it.
2. Services connect via the mesh discovery client in `lattice-common`.
3. The discovery/announcement protocol + envelope schema over Artemis are **settled** (Shape A): a cluster multicasts a `ClusterAnnouncement` (advertising `consoleUrl` + `apiBaseUrl`) and builds a peer registry; the mesh carries discovery only, no work (locked #37; `mesh_discovery.md` + `mesh_envelopes.md`).

**Environment variables (read by each service)**

| Variable           | Value                                     | Notes                                |
|--------------------|-------------------------------------------|--------------------------------------|
| `ARTEMIS_URL`      | broker URL - always this baseline's own broker, in-cluster (`tcp://<release>-lattice-artemis:61616`). Single-valued by design (locked #44) | required for mesh-connected services |
| `ARTEMIS_USER`     | broker user                               | TBD per environment                  |
| `ARTEMIS_PASSWORD` | broker password                           | secret; TBD per environment          |

**Verification:** A service connects to the broker on startup (log line), and the mesh integration tests (Testcontainers Artemis) pass.

---

## Keycloak - Per-baseline Identity

**Purpose:** Authenticates operators for **one** baseline. Each baseline runs its own Keycloak with its own realm, including locally - a shared instance would make one baseline privileged and would let a decision made elsewhere lock this one out or let it in (locked #38, #48). Every service validates a bearer token on every `/api/v1` operation against its own realm's signing keys; `/health` and `/readiness` stay open.

**Setup**

1. Local: one Keycloak per baseline, deployed by the chart and reached on the host at `:8083` (hub-central), `:8093` (hub-east) and `:8103` (hub-west). Its realm - roles, groups, the console client, and two demo users - is the committed `deploy/k8s/chart/charts/keycloak/files/lattice-realm.json`, from which the cluster ConfigMap is generated (locked #54).
2. **The local stack runs Keycloak persisted** (`keycloak.devMode=false`, against its own MySQL - locked #72), because it is the same chart a customer installs and running the local stack in a mode nothing ships in would leave the persisted path unexercised. The consequence is the one locked #72 names: the realm import runs **only when the realm is absent**, so an edit to `lattice-realm.json` does not reach an existing Keycloak. Recreate the cluster, or make the change as an admin operation. Compose was the unpersisted local mode and is retired (locked #77).
3. Roles are `viewer` (every `GET`) and `operator` (reads plus writes), granted through the `viewers` / `operators` groups. Role and group **names** are standard across every baseline; **membership is not** - an operator working across N baselines holds N grants (locked #49).

**Environment variables (read by each service; the console reads the first three)**

| Variable                | Value                                                     | Notes                                                          |
|-------------------------|-----------------------------------------------------------|----------------------------------------------------------------|
| `KEYCLOAK_URL`          | Keycloak base URL as a token's issuer claims it            | required; a service refuses to start without it                |
| `KEYCLOAK_REALM`        | this baseline's realm name (e.g. `lattice`)                | required; a service refuses to start without it                |
| `KEYCLOAK_INTERNAL_URL` | where this service reaches Keycloak, if not the above      | optional; needed wherever in-network and published differ      |
| `KEYCLOAK_CLIENT_ID`    | the public client the console authenticates as             | console only; services are bearer-only and start no login flow |

**Verification:** Obtain a token for the demo operator and exercise all four cases - no token is 401, a `viewer` reads but a write is 403, an `operator` does both, and the probes answer without a token throughout. The one-line token call is `kc_token` in [mesh-clusters.sh](../../deploy/k8s/mesh-clusters.sh).

---

## Kubernetes - Orchestration

**Purpose:** Runs the baseline's service containers as a cluster. Manifests / Helm charts live in `deploy/k8s`. Generated manifest output is never hand-edited - regenerate it from source.

**Setup**

1. Provision a Kubernetes cluster per environment. **Local dev uses `kind`** (Kubernetes-in-Docker, reuses the local Docker daemon), three clusters with one baseline each. **A hosting provider is deliberately not chosen** (locked #56): nothing yet needs to be reachable from outside a developer machine, and the mesh has been proven across cluster boundaries without one. Local setup steps + versions are in [DEVELOPMENT.md](../../DEVELOPMENT.md).
2. Apply the manifests / Helm charts from `deploy/k8s`.
3. Each service exposes readiness + liveness probes (`/health`); the status console reads node status per cluster.

**Configuration / environment**

| Variable        | Value                                         | Notes                                           |
|-----------------|-----------------------------------------------|-------------------------------------------------|
| `KUBECONFIG`    | path to the kubeconfig for the target cluster | local tooling / CI deploy step; never committed |
| `K8S_NAMESPACE` | namespace the baseline runs in                | per environment                                 |

**Verification:** `kubectl get pods -n $K8S_NAMESPACE` shows the baseline's services `Running` with passing readiness probes.

---

## Image delivery - there is no registry

**Purpose:** getting built images onto the clusters that run them.

**There is no Lattice registry, and this is settled rather than pending** (locked #55). A baseline is *delivered, not hosted*: it ships as exported `docker save` archives plus the Helm chart, and the customer loads them before installing. That is the only option that works air-gapped, needs no account shared with the customer, and leaves them holding something they can archive and re-install without us being reachable.

**Consequences worth knowing**

1. **The image tag is the only thread back to a commit.** Images stay on an immutable `<version>-<sha>` tag, because when the customer holds the artifacts a floating tag makes "what is running" unanswerable rather than merely inconvenient.
2. **Third-party images stay the customer's to obtain.** Elasticsearch, Artemis, Keycloak and MySQL come from their own publishers; redistributing other people's images is a licensing question with no upside.
3. **Locally there is no push or pull at all.** `mesh-clusters.sh images` builds and side-loads straight into each `kind` cluster, and the chart runs `imagePullPolicy: Never`, so nothing reaches out.

**Environment variables:** none. `global.image.registry` in the chart is an optional prefix for a customer who does run their own registry; unset, images are referenced by bare name.

**Verification:** `mesh-clusters.sh images` then `deploy`, and every pod starts without an image pull.

---

## Observability (P8 - open)

**Purpose:** Metrics + tracing (and log aggregation) for services and the mesh. **Open as P8**, and genuinely greenfield: there is no metrics registry, no endpoint and no instrumentation in the services today. The only OpenTelemetry in the tree is a version pin in the parent pom constraining what the Elasticsearch client pulls in transitively, and Keycloak's `KC_METRICS_ENABLED` exists to put its database check into the readiness group rather than to emit anything anyone collects.

**This is an open design question, not an unfilled form.** Two halves with different dependencies: instrumentation depends on no hosting decision and is buildable today, while the collection stack is what hosting defers. Whether that stack is per baseline or shared is the tension worth designing. Revisit after v1.0.0.

**Setup (once chosen)**

1. Stand up the collector/backend for the environment.
2. Point services at it; Vert.x exposes metrics that the collector scrapes/receives.

**Environment variables**

| Variable                      | Value              | Notes                  |
|-------------------------------|--------------------|------------------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | collector endpoint | TBD - placeholder name |
| `OTEL_SERVICE_NAME`           | the service's name | set per service        |

**Verification:** A service's traces/metrics appear in the chosen backend after a request.

---

## Auth provider - see Keycloak, above

This was a placeholder for an undecided auth mechanism, carrying invented variable names (`AUTH_ISSUER`, `AUTH_JWKS_URL`, `AUTH_AUDIENCE`) that were never read by anything. **P5 is settled** and the mechanism is per-baseline Keycloak (locked #38, #48), documented in [its own section above](#keycloak---per-baseline-identity) with the variables the services actually read.

The heading is kept rather than deleted so anyone who bookmarked it, or who remembers a section by this name, lands on the answer instead of on nothing.

**Mesh traffic is authenticated separately and not by this**: brokers authenticate to each other by per-baseline X.509 certificate over mutual TLS, signed by an authority the customer runs (locked #50, #58). There is no bearer token on the mesh.

---

## CI - GitHub Actions

**Purpose:** A clean-room backstop that re-runs the canonical gate `./mvnw verify` (unit + integration tests via Testcontainers) on a pristine runner, and builds/pushes service images once the container registry lands. The repository is public, so Actions is free on standard runners: CI is the **authoritative** run and triggers on feature-branch pushes as well as both merge points, while the local pre-push hook is the fast authoring gate that skips the container suites (locked #74) - see [core_protocol.md](../protocol/core_protocol.md#ci-triggers--qa-iteration-discipline).

**Setup**

1. Workflow (`.github/workflows/ci.yml`) runs `./mvnw -B -ntp verify` **only** on `pull_request` into `main` (the `dev` -> `main` promotion) and on manual `workflow_dispatch` - not on feature pushes or `dev` PRs.
2. Testcontainers needs a Docker daemon on the runner (default GitHub-hosted runners provide one).
3. Image build/push step is added when `IMAGE_REGISTRY` is chosen; registry credentials live in GitHub Actions secrets.

**Environment variables / secrets**

| Variable               | Value               | Notes                              |
|------------------------|---------------------|------------------------------------|
| `IMAGE_REGISTRY_TOKEN` | registry push token | GitHub Actions secret; TBD with P7 |

**Verification:** Every push runs `./mvnw verify` locally (pre-push hook); the `dev` -> `main` promotion PR shows the `verify` job green on a clean runner before promotion.

---

## Per-environment variable matrix

Every variable a service reads, grouped by concern, across **local / dev / prod**, plus any still-reserved name marked as such in the local column. `secret` = set via the environment's secret store (Kubernetes Secret / CI secret), never committed; `config` = non-sensitive, may live in a ConfigMap / `[env]`.

**The dev and prod columns are `TBD` by decision, not by omission.** Hosting is deliberately deferred (locked #56) and Lattice is delivered rather than hosted (locked #55), so there is no provider to name yet. Local is the only environment that exists, and it is real rather than a stand-in.

| Variable                        | Kind   | local                     | dev                     | prod                     |
|---------------------------------|--------|---------------------------|-------------------------|--------------------------|
| `ELASTICSEARCH_URL`             | config | in-cluster ES Service     | dev cluster ES endpoint | prod cluster ES endpoint |
| `ELASTICSEARCH_USERNAME`        | config | unset (security relaxed)  | TBD                     | TBD                      |
| `ELASTICSEARCH_PASSWORD`        | secret | unset                     | TBD (K8s Secret)        | TBD (K8s Secret)         |
| `ARTEMIS_URL`                   | config | in-cluster broker Service | dev broker URL          | prod broker URL          |
| `ARTEMIS_USER`                  | config | `artemis` (local default) | TBD                     | TBD                      |
| `ARTEMIS_PASSWORD`              | secret | `artemis` (local default) | TBD (K8s Secret)        | TBD (K8s Secret)         |
| `KUBECONFIG`                    | config | optional (local K8s)      | dev cluster kubeconfig  | prod cluster kubeconfig  |
| `K8S_NAMESPACE`                 | config | `lattice`                 | `lattice-dev`           | `lattice-prod`           |
| `IMAGE_REGISTRY`                | config | local build (no push)     | TBD                     | TBD                      |
| `IMAGE_REGISTRY_TOKEN`          | secret | unset                     | TBD (CI secret)         | TBD (CI secret)          |
| `OTEL_EXPORTER_OTLP_ENDPOINT`   | config | P8 - nothing reads it yet | TBD                     | TBD                      |
| `OTEL_SERVICE_NAME`             | config | P8 - nothing reads it yet | TBD                     | TBD                      |

**Deferred - do not finalize here yet:**
- **P8, observability.** The two `OTEL_*` rows above are placeholder names carrying no reader; the real ones arrive with the design session, along with whether the collection stack is per baseline or shared.
- **P7, hosting.** Settled as far as it goes - Lattice is delivered rather than hosted (locked #55), so there is no vendor registry and `IMAGE_REGISTRY` may stay unset forever. What stays open is where a customer's dev and prod clusters run, which is what the two right-hand columns wait on.

Everything else this block once listed is settled and documented above: the auth mechanism is per-baseline Keycloak (P5, locked #38 and #48), and mesh discovery and the envelope format are locked #29 and #31.
