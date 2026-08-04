# Lattice - External Service Integrations

Developer runbook for setting up every external service Lattice depends on. Follow this in order when provisioning a new environment.

> Each external service gets one section, with a per-environment env-var matrix at the bottom. A genuinely open choice says what is open and what it waits on, rather than carrying a bare TBD - do not invent a value for one, and do not leave a settled decision described as pending. A remaining **TBD** in a variable table means the value is per-environment, not that the mechanism is undecided.

---

## Services

| Service                  | Purpose                                                         | Environments               |
|--------------------------|-----------------------------------------------------------------|----------------------------|
| Elasticsearch            | The datastore for every cluster (one data model per cluster)    | local, dev, prod           |
| Apache Artemis broker    | The mesh transport - clusters discover + talk to peer clusters  | local, dev, prod           |
| Kubernetes cluster       | Orchestrates the baseline's service containers                  | dev, prod (local optional) |
| Image delivery           | Exported archives plus the chart; there is no registry (locked #55) | all                        |
| Observability            | Prometheus metrics on each service's management port (locked #78); collection stack still open | local, dev, prod           |
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

## Observability - Metrics

**Purpose:** Prometheus metrics for the services and the mesh. **The instrumentation half is settled and built** (locked #78, [observability.md](../design/architecture/observability.md)); the **collection stack is still open**, because it needs somewhere to run and hosting is deferred (locked #56).

**What exists.** Every service registers a Micrometer `PrometheusMeterRegistry` through `vertx-micrometer-metrics` and serves `/metrics` on a dedicated management port. Three layers are measured: the Java Virtual Machine, Hypertext Transfer Protocol server and pool families from the Vert.x binding; nine mesh and rollup metrics in mesh-gateway; and a timer plus an error counter in the shared repository base. Hypertext Transfer Protocol metrics are labelled by OpenAPI route template rather than raw path, so cardinality is bounded by the contract.

**What does not exist, by decision.** No tracing, no log aggregation, no business metrics, no dashboards or alert rules, and no exporters for Elasticsearch, Artemis, Keycloak or MySQL - those publish their own metrics and are the customer's to collect. Keycloak's `KC_METRICS_ENABLED` is unrelated: it exists to put that component's database check into its readiness group (locked #73), not to emit anything anyone here collects. The OpenTelemetry pin in the parent pom is likewise unrelated, constraining only what the Elasticsearch client pulls in transitively.

**Setup**

1. Nothing to stand up for the metrics themselves - they are on by default and served in-cluster.
2. Scrape `http://<service>:9090/metrics`. The management Service is **ClusterIP** and is deliberately never published to the host, which is what bounds an endpoint that carries no token.
3. The collector is not chosen. The recorded default is **one stack per baseline**, with an optional aggregation path for a mesh-wide view; neither is built.

**Environment variables (read by every service)**

| Variable          | Value                          | Notes                                                      |
|-------------------|--------------------------------|------------------------------------------------------------|
| `METRICS_ENABLED` | `true` / `false`               | Default `true`. False binds no port and creates no registry. |
| `METRICS_PORT`    | management port serving `/metrics` | Default `9090`; declared in the chart per locked #77.     |

**Verification:** `kubectl port-forward` to a service's management port and `curl :9090/metrics` returns Prometheus text format including `lattice_mesh_announcements_received_total`; the same path on the API port 404s.

---

## Auth provider - see Keycloak, above

This was a placeholder for an undecided auth mechanism, carrying invented variable names (`AUTH_ISSUER`, `AUTH_JWKS_URL`, `AUTH_AUDIENCE`) that were never read by anything. **The authentication mechanism is settled** and the mechanism is per-baseline Keycloak (locked #38, #48), documented in [its own section above](#keycloak---per-baseline-identity) with the variables the services actually read.

The heading is kept rather than deleted so anyone who bookmarked it, or who remembers a section by this name, lands on the answer instead of on nothing.

**Mesh traffic is authenticated separately and not by this**: brokers authenticate to each other by per-baseline X.509 certificate over mutual TLS, signed by an authority the customer runs (locked #50, #58). There is no bearer token on the mesh.

---

## CI - GitHub Actions

**Purpose:** The **authoritative** full-reactor run - `./mvnw verify` including the Testcontainers suites the pre-push hook skips - on a pristine runner that holds none of a developer's local state. The repository is public, so Actions is free on standard runners and there is no minutes budget; the local hook is the fast authoring gate rather than the primary one (locked #74) - see [core_protocol.md](../protocol/core_protocol.md#ci-triggers--qa-iteration-discipline). It builds no images: Lattice is delivered as exported archives and there is no registry to push to (locked #55).

**Setup**

1. Workflow (`.github/workflows/ci.yml`) triggers on **`push`** to `dev` and to any `lat-*` branch, plus manual `workflow_dispatch`. There is deliberately **no `pull_request` trigger and none on `main`** (locked #76): a push run's checks attach to the same head commit a pull request is evaluated against, and a promotion is a fast-forward onto a commit that already carries them.
2. A concurrency group keeps one run per branch, cancelling a superseded run - except on `dev`, whose run is what a promotion fast-forwards onto.
3. Seven jobs, all blocking: `verify` (the Maven reactor), `console` (the status console's own gate), `dependency-scan` (OSV-Scanner over the generated software bill of materials and the console's lockfile), `static-analysis` (Semgrep), `secret-scan` (gitleaks), `chart-check` (renders every baseline and lints the output), and `workflow-lint` (actionlint).
4. Testcontainers needs a Docker daemon on the runner (default GitHub-hosted runners provide one).

**Environment variables / secrets**

None. No registry credential is needed, because nothing is pushed.

**Verification:** the branch's own run is green before a pull request is opened, and the `dev` push run is green on the commit a promotion fast-forwards onto.

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
| `METRICS_ENABLED`               | config | `true` (default)          | `true` (default)        | `true` (default)         |
| `METRICS_PORT`                  | config | `9090`                    | `9090`                  | `9090`                   |

**Deferred - do not finalize here yet:**
- **The collection stack.** The instrumentation half is settled and built (locked #78), which is why `METRICS_ENABLED` and `METRICS_PORT` above carry real values in every column rather than a TBD. The two `OTEL_*` rows they replaced were placeholder names nothing read, and choosing Micrometer made them wrong rather than pending. What stays open is where the metrics are collected and stored, which waits on hosting.
- **Hosting.** Settled as far as it goes - Lattice is delivered rather than hosted (locked #55), so there is no vendor registry at all. `IMAGE_REGISTRY` and `IMAGE_REGISTRY_TOKEN` are deleted from the table above rather than carried as pending: nothing reads either one, and a name nothing reads is the exact shape of the variable list locked #77 removed, on the same reasoning that deleted the `OTEL_*` placeholders. If a customer pushes the delivered archives into their own registry, that is their cluster's business and their variable. What stays open is where a customer's dev and prod clusters run, which is what the two right-hand columns wait on.

Everything else this block once listed is settled and documented above: the auth mechanism is per-baseline Keycloak (locked #38 and #48), and mesh discovery and the envelope format are locked #29 and #31.
