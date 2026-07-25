# Lattice - External Service Integrations

Developer runbook for setting up every external service Lattice depends on. Follow this in order when provisioning a new environment.

> Each external service gets one section, with a per-environment env-var matrix at the bottom. Concrete provider choices that the founder has not fixed yet are marked **TBD** - do not invent a specific value; set it when that decision lands (see the **Planned - design session** group in [locked_decisions.md](locked_decisions.md)).

---

## Services

| Service                  | Purpose                                                         | Environments               |
|--------------------------|-----------------------------------------------------------------|----------------------------|
| Elasticsearch            | The datastore for every cluster (one data model per cluster)    | local, dev, prod           |
| Apache Artemis broker    | The mesh transport - clusters discover + talk to peer clusters  | local, dev, prod           |
| Kubernetes cluster       | Orchestrates the baseline's service containers                  | dev, prod (local optional) |
| Container registry (TBD) | Where built Docker images are pushed for clusters to pull       | dev, prod                  |
| Observability (TBD)      | Metrics + tracing (+ log aggregation) for services and the mesh | dev, prod                  |
| Keycloak (per baseline)  | Authentication / authorization - each baseline its own realm (locked #38) | local, dev, prod           |
| CI (GitHub Actions)      | `./mvnw verify` on the dev->main PR (local hook gates pushes)   | all                        |

---

## Elasticsearch - Datastore

**Purpose:** The single data store for a cluster. Each cluster owns its own, possibly-divergent data model (indices + mappings). Services read/write through the Elasticsearch client in `lattice-common`; integration tests run against a real Elasticsearch via Testcontainers.

**Setup**

1. Local: Elasticsearch runs in `deploy/docker` docker-compose (single node, security relaxed for local only). **Version pinned to 8.19.x** (client `co.elastic.clients:elasticsearch-java`, the Testcontainers image, and the compose/deploy image all track one Maven property `elasticsearch.version`; locked #34).
2. Each service is the **single writer** of its own indices/mappings - a mapping change ships with the service that owns it.
3. Bootstrap indices/aliases from the service on startup (create-if-absent), or via a versioned bootstrap step - exact mechanism TBD when the per-service data model is designed (locked_decisions.md P4).

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

1. Local: Artemis runs in `deploy/docker` docker-compose alongside Elasticsearch (image `apache/activemq-artemis`, console on `:8161`, core protocol on `:61616`).
2. Services connect via the mesh discovery client in `lattice-common`.
3. The discovery/announcement protocol + envelope schema over Artemis are **settled** (Shape A): a cluster multicasts a `ClusterAnnouncement` (advertising `consoleUrl` + `apiBaseUrl`) and builds a peer registry; the mesh carries discovery only, no work (locked #37; `mesh_discovery.md` + `mesh_envelopes.md`).

**Environment variables (read by each service)**

| Variable           | Value                                     | Notes                                |
|--------------------|-------------------------------------------|--------------------------------------|
| `ARTEMIS_URL`      | broker URL (e.g. `tcp://localhost:61616`) | required for mesh-connected services |
| `ARTEMIS_USER`     | broker user                               | TBD per environment                  |
| `ARTEMIS_PASSWORD` | broker password                           | secret; TBD per environment          |

**Verification:** A service connects to the broker on startup (log line), and the mesh integration tests (Testcontainers Artemis) pass.

---

## Keycloak - Per-baseline Identity

**Purpose:** Authenticates operators for **one** baseline. Each baseline runs its own Keycloak with its own realm, including locally - a shared instance would make one baseline privileged and would let a decision made elsewhere lock this one out or let it in (locked #38, #48). Every service validates a bearer token on every `/api/v1` operation against its own realm's signing keys; `/health` and `/readiness` stay open.

**Setup**

1. Local: Keycloak runs in `deploy/docker` docker-compose (`start-dev --import-realm`, on `:8083`; the peer baseline's on `:8084`). Its realm - roles, groups, the console client, and two demo users - is the committed `deploy/docker/keycloak/lattice-realm.json`.
2. **Dev mode with no persistence is deliberate.** The import runs only when the realm is absent, so a persisted database would silently ignore later edits to that file - the same trap a persisted Artemis instance hits with `etc-override`. A deployed baseline needs a real database, which is a deploy concern.
3. Roles are `viewer` (every `GET`) and `operator` (reads plus writes), granted through the `viewers` / `operators` groups. Role and group **names** are standard across every baseline; **membership is not** - an operator working across N baselines holds N grants (locked #49).

**Environment variables (read by each service; the console reads the first three)**

| Variable                | Value                                                     | Notes                                                          |
|-------------------------|-----------------------------------------------------------|----------------------------------------------------------------|
| `KEYCLOAK_URL`          | Keycloak base URL as a token's issuer claims it            | required; a service refuses to start without it                |
| `KEYCLOAK_REALM`        | this baseline's realm name (e.g. `lattice`)                | required; a service refuses to start without it                |
| `KEYCLOAK_INTERNAL_URL` | where this service reaches Keycloak, if not the above      | optional; needed wherever in-network and published differ      |
| `KEYCLOAK_CLIENT_ID`    | the public client the console authenticates as             | console only; services are bearer-only and start no login flow |

**Verification:** Obtain a token for the demo operator and exercise all four cases - no token is 401, a `viewer` reads but a write is 403, an `operator` does both, and the probes answer without a token throughout. The one-line token call is in [deploy/docker/keycloak/README.md](../../deploy/docker/keycloak/README.md).

---

## Kubernetes - Orchestration

**Purpose:** Runs the baseline's service containers as a cluster. Manifests / Helm charts live in `deploy/k8s`. Generated manifest output is never hand-edited - regenerate it from source.

**Setup**

1. Provision a Kubernetes cluster per environment. **Local dev uses `kind`** (Kubernetes-in-Docker, reuses the local Docker daemon); dev/prod provider is TBD - locked_decisions.md P7. Local setup steps + versions are in [DEVELOPMENT.md](../../DEVELOPMENT.md).
2. Apply the manifests / Helm charts from `deploy/k8s`.
3. Each service exposes readiness + liveness probes (`/health`); the status console reads node status per cluster.

**Configuration / environment**

| Variable        | Value                                         | Notes                                           |
|-----------------|-----------------------------------------------|-------------------------------------------------|
| `KUBECONFIG`    | path to the kubeconfig for the target cluster | local tooling / CI deploy step; never committed |
| `K8S_NAMESPACE` | namespace the baseline runs in                | per environment                                 |

**Verification:** `kubectl get pods -n $K8S_NAMESPACE` shows the baseline's services `Running` with passing readiness probes.

---

## Container Registry (TBD)

**Purpose:** Stores the built Docker images that clusters pull. **Provider is TBD** - Docker + Kubernetes are locked, the registry + hosting are a deferred design question (locked_decisions.md P7).

**Setup (once the provider is chosen)**

1. Create the registry / repositories for Lattice images.
2. Give CI push credentials (a scoped token) and clusters pull credentials (an image-pull secret).

**Environment variables**

| Variable               | Value                                                | Notes                             |
|------------------------|------------------------------------------------------|-----------------------------------|
| `IMAGE_REGISTRY`       | registry host / prefix (e.g. `registry.tbd/lattice`) | TBD - set when the registry lands |
| `IMAGE_REGISTRY_USER`  | push/pull user                                       | secret (CI); TBD                  |
| `IMAGE_REGISTRY_TOKEN` | push/pull token                                      | secret (CI); TBD                  |

**Verification:** CI builds a service image and pushes it; a cluster pulls it successfully.

---

## Observability (TBD)

**Purpose:** Metrics + tracing (and log aggregation) for services and the mesh. **Provider/stack is TBD** (e.g. an OpenTelemetry collector to a metrics/tracing backend) - not yet fixed.

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

## Auth Provider (TBD)

**Purpose:** Authentication / authorization for REST + mesh traffic. **No mechanism is fixed yet** (locked_decisions.md P5) - this section is a placeholder for when it is designed.

**Environment variables (placeholder names)**

| Variable        | Value                        | Notes                                |
|-----------------|------------------------------|--------------------------------------|
| `AUTH_ISSUER`   | token issuer / provider URL  | TBD - set when the auth scheme lands |
| `AUTH_JWKS_URL` | key set URL for token verify | TBD                                  |
| `AUTH_AUDIENCE` | expected token audience      | TBD                                  |

**Verification:** TBD - defined with the auth design.

---

## CI - GitHub Actions

**Purpose:** A clean-room backstop that re-runs the canonical gate `./mvnw verify` (unit + integration tests via Testcontainers) on a pristine runner, and builds/pushes service images once the container registry lands. On this private repo (Free-plan Actions minutes) the **primary** gate is the local pre-push hook; GitHub CI is deliberately sparing - see [core_protocol.md](../protocol/core_protocol.md#ci-triggers--qa-iteration-discipline).

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

Every variable a service reads, grouped by concern, across **local / dev / prod**. `secret` = set via the environment's secret store (Kubernetes Secret / CI secret), never committed; `config` = non-sensitive, may live in a ConfigMap / `[env]`. Concrete provider values are **TBD** where the provider is not yet chosen.

| Variable                        | Kind   | local                     | dev                     | prod                     |
|---------------------------------|--------|---------------------------|-------------------------|--------------------------|
| `ELASTICSEARCH_URL`             | config | `http://localhost:9200`   | dev cluster ES endpoint | prod cluster ES endpoint |
| `ELASTICSEARCH_USERNAME`        | config | unset (security relaxed)  | TBD                     | TBD                      |
| `ELASTICSEARCH_PASSWORD`        | secret | unset                     | TBD (K8s Secret)        | TBD (K8s Secret)         |
| `ARTEMIS_URL`                   | config | `tcp://localhost:61616`   | dev broker URL          | prod broker URL          |
| `ARTEMIS_USER`                  | config | `artemis` (local default) | TBD                     | TBD                      |
| `ARTEMIS_PASSWORD`              | secret | `artemis` (local default) | TBD (K8s Secret)        | TBD (K8s Secret)         |
| `KUBECONFIG`                    | config | optional (local K8s)      | dev cluster kubeconfig  | prod cluster kubeconfig  |
| `K8S_NAMESPACE`                 | config | `lattice`                 | `lattice-dev`           | `lattice-prod`           |
| `IMAGE_REGISTRY`                | config | local build (no push)     | TBD                     | TBD                      |
| `IMAGE_REGISTRY_TOKEN`          | secret | unset                     | TBD (CI secret)         | TBD (CI secret)          |
| `OTEL_EXPORTER_OTLP_ENDPOINT`   | config | unset (off)               | TBD                     | TBD                      |
| `OTEL_SERVICE_NAME`             | config | per service               | per service             | per service              |
| `AUTH_ISSUER` / `AUTH_JWKS_URL` | config | unset (off)               | TBD                     | TBD                      |

**Deferred - do not finalize here yet:**
- Container registry + hosting provider (P7), observability stack (metrics/tracing), and the auth mechanism (P5) - fill these in when the design session settles them.
- Mesh discovery + envelope-related settings arrive with the discovery protocol design (P1, P3).
