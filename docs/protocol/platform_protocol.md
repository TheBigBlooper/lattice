# Lattice - Platform Protocol

The job description for platform work, owned by the **`platform`** agent. Platform owns how a
Lattice service becomes a running node in a cluster: the Docker images, the Kubernetes
manifests, the Artemis-backed mesh (broker + discovery), the local docker-compose stack, and
the per-environment operational config. It owns `deploy/*`.

This document holds platform-specific standards and rules. The build/deploy sequence and
environment map live in [deploy_protocol.md](deploy_protocol.md). The versioned REST + mesh
envelope contract lives in [contract_protocol.md](contract_protocol.md). Service-side rules
(thin verticles, the data layer, the health endpoints the platform probes) live in
[service_protocol.md](service_protocol.md). This document follows the same house style as the
other protocol docs.

---

## Docker image standards

Every service, and the status console, ships as its own image. The standards:

- **Base image (JVM choice TBD).** Services are Java 21 / Vert.x 5. The base image is **TBD** -
  the decision is between a **distroless** Java runtime (smallest surface, no shell) and an
  **eclipse-temurin** JRE (more debuggable). Decide in a design session and record the choice
  here; whichever wins is used by **every** service so images stay uniform. The console is a
  static bundle served from a minimal web image (base TBD).
- **Non-root.** Every image runs as a **non-root user** - no service process runs as root. This
  is a hard rule (it also matches how migrations/index jobs run under a non-root user).
- **Layered jars.** Package Vert.x services as **layered jars** (dependencies, then application
  layers) so an image rebuild for a code change reuses the cached dependency layer - faster
  builds, smaller pushes.
- **Healthcheck.** Each image declares a container `HEALTHCHECK` (or relies on the K8s probes
  below) hitting the service's health endpoint - a container that cannot answer health is not
  healthy.
- **Image tagging = `<version>-<git-sha>`.** Every image is tagged with the service's semantic
  version **and** the git sha of the commit it was built from. Tags are **immutable**; no
  floating `latest` is ever deployed. The tag is the provenance that traces a running node to a
  commit and to its place in the versioned **baseline** (see [deploy_protocol.md](deploy_protocol.md)
  service versioning).

---

## Kubernetes manifest conventions

A service in a cluster is a **Deployment + Service** with health probes and config. Conventions:

- **Deployment + Service per service.** Each service gets a Deployment (its pods, referencing an
  immutable `<version>-<sha>` image) and a Service (stable in-cluster address). The status
  console is deployed the same way (its own Deployment + Service).
- **Readiness + liveness probes hit the service health endpoints.** The **readiness** probe hits
  the service's readiness endpoint (is it ready to serve - dependencies up); the **liveness**
  probe hits its liveness/health endpoint (is the process alive). K8s must not route traffic to a
  not-ready pod, and must restart a dead one - both come straight from the health contract below.
- **ConfigMap for config, Secret for secrets.** Non-secret per-environment config (Elasticsearch
  URL, Artemis broker URL, baseline version, mesh identity/peer config) goes in a **ConfigMap**;
  credentials and TLS material go in a **Secret**. Config is read at startup and is **never**
  baked into the image - the same image runs in every environment, pointed by config. Secret
  values are never written to a tracked file.
- **Resource requests + limits.** Every container sets CPU/memory **requests and limits**, sized
  per environment (dev modest, prod for real load) - never copied blindly from dev to prod.
- **Labels/selectors keyed by service + baseline version.** Standard labels identify a pod by
  **service name** and **baseline version** (plus environment), and Service selectors match on
  them - so "which version of which service is running where" is queryable, and a rollout targets
  the right pods.

---

## Generated manifests are never hand-edited

If any manifests or charts are **generated** (from a template, a Helm render, or a scaffolding
tool), the generated output is **never hand-edited** - regenerate from the source of truth
instead. This is the same rule that applies to any generated artifact: a hand-edit to
generated output silently drifts from its source and is lost on the next regeneration. Hand-write
only the source templates/values; treat `deploy/k8s/`'s generated output as read-only (the one
documented exception, if any arises, is recorded here explicitly). Tooling (kustomize vs Helm) is
**TBD** - see [deploy_protocol.md](deploy_protocol.md) open items.

---

## Health / readiness contract (every service must expose)

The platform's probes are only as good as the endpoints they hit, so every service exposes a
uniform health surface - this is a contract the `service` agent implements and the `platform`
agent depends on:

- **Liveness** - "the process is alive." Cheap, no dependency checks; a failure means restart.
- **Readiness** - "ready to serve requests." Checks that this service's **dependencies are
  reachable** (its Elasticsearch, the Artemis broker it needs) and that startup completed; a
  failure means pull the pod out of rotation until it recovers. A service that starts before
  Elasticsearch or the broker is up correctly reports **not ready** rather than serving errors.
- **Uniform shape.** The endpoints live at agreed paths and return an agreed shape across every
  service (exact paths/shape are part of the REST contract - see
  [contract_protocol.md](contract_protocol.md)) so the K8s probes, the deploy smoke, and the
  status console can all read them the same way. The console renders exactly this signal
  ([ui_protocol.md](ui_protocol.md) "what this console shows").

---

## The Artemis mesh

Separate clusters discover and communicate with peer clusters over an **Artemis-backed mesh**.
Platform owns the broker and the discovery mechanics; the **message envelopes** exchanged are
defined in the shared `lattice-contract` module and versioned in
[contract_protocol.md](contract_protocol.md).

**What is settled (principles):**

- **Broker deployment.** Each cluster runs (or reaches) an Artemis broker, deployed as part of
  the cluster (its own Deployment/Service + Secret for credentials). The broker is a startup
  dependency: services gate readiness on reaching it.
- **Register / announce / discover.** A cluster **announces itself** onto the mesh and
  **discovers peers** over Artemis - clusters do not hardcode each other's addresses; they learn
  peers through the mesh. The status console renders the discovered peer set.
- **Envelope versioning references the contract.** Every mesh message is a versioned envelope
  record from `lattice-contract`; a cluster must not announce an envelope version a peer cannot
  read. Envelope definitions + their versioning rules are owned by
  [contract_protocol.md](contract_protocol.md), not here.
- **At-least-once + idempotency.** Mesh delivery is treated as **at-least-once**: a consumer may
  see the same message more than once, so every mesh message handler must be **idempotent**
  (processing a duplicate is a no-op, keyed on a message/envelope id). Do not assume
  exactly-once.
- **Interoperability across divergent data models.** Each cluster has its own, possibly
  divergent, Elasticsearch data model; the mesh envelopes are the interoperability seam - clusters
  agree on envelopes, not on each other's internal schemas.

**What is not settled (planned - design session):** the concrete discovery/announce **protocol**
(addresses/queues/topics used, announce cadence, peer liveness/timeout, how a peer is marked
gone), the envelope wire format specifics, and the exact idempotency-key strategy are **planned -
design session**. Keep the principles above; do not invent the protocol details before that
session - record them here and in [contract_protocol.md](contract_protocol.md) when they land.

---

## Local docker-compose (the whole stack)

The default local environment is a single docker-compose stack under `deploy/docker/` that brings
up the **whole system**: Elasticsearch + the Artemis broker + the services + the status console.
It is the fast iteration and QA loop ([qa_protocol.md](qa_protocol.md)).

- **One command up.** A single `docker compose up` starts every piece; services address each other
  by compose service name, and the host reaches published ports (see qa_protocol networking).
- **Startup order.** Elasticsearch and the broker must be ready before the services - express this
  with compose dependency + healthcheck conditions so a service does not start against a
  not-ready dependency.
- **Two-cluster mesh locally.** For mesh work, two compose projects (or a compose profile) stand up
  two clusters sharing a reachable broker network so discovery can be exercised locally.
- **Parity with deployed config.** Compose reads the same **config keys** (ES URL, broker URL,
  baseline, mesh identity) as the K8s ConfigMap, from compose env - so "works in compose" and
  "works in the cluster" diverge only where a value differs, not where a key is missing.

---

## Cross-references

- **Build + deploy sequence, environments, cluster bring-up runbook, deployed smoke:**
  [deploy_protocol.md](deploy_protocol.md).
- **REST + mesh envelope contract (versioning, the health-endpoint shape, envelope records):**
  [contract_protocol.md](contract_protocol.md).
- **Service internals platform depends on (thin verticles, the data layer, health endpoints,
  single-writer of Elasticsearch mappings):** [service_protocol.md](service_protocol.md).
