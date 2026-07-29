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
documented exception, if any arises, is recorded here explicitly). Tooling is **Helm** (locked #54);
the chart lives at `deploy/k8s/chart` - see its README.

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

Separate clusters discover each other over an **Artemis-backed mesh**. Under **Shape A federation**
(locked #37) the mesh is a **discovery phone book**, not a work bus: the only thing that crosses it
is a cluster announcing its presence + endpoints. Platform owns the broker and the discovery
mechanics; the announcement **envelope** is defined in the shared `lattice-contract` module
([contract_protocol.md](contract_protocol.md)).

**What the mesh does (settled - Shape A):**

- **Broker deployment.** Each cluster runs **its own** Artemis broker, deployed as part of the
  cluster (its own Deployment/Service + Secret for credentials), and the brokers are joined by
  Artemis **federation** so announcements cross between baselines (locked #44). No baseline
  depends on another's broker, so one going down never stops the rest discovering each other.
  A joining baseline configures its peers; existing baselines are never edited (the join
  sequence, the failure model, and the local stack are in
  [mesh_broker_topology.md](../design/architecture/mesh_broker_topology.md)).
- **The broker is not a readiness dependency.** A broker outage degrades discovery, not a
  service's ability to answer, so `mesh-gateway` stays **UP** without it and serves the peer
  registry from last-known state (locked #42); the mesh-link state is surfaced on its own API
  rather than announced (locked #46).
- **Announce / discover.** A cluster **announces itself** (a `ClusterAnnouncement` multicast on
  `lattice.mesh.announce`, advertising `consoleUrl` + `apiBaseUrl`) and **discovers peers** over
  Artemis - clusters do not hardcode each other's addresses; they learn peers, and where to reach
  them, through the mesh. The status console renders the discovered peer set and uses the
  advertised endpoints for its unified view + redirect (see
  [mesh_discovery.md](../design/architecture/mesh_discovery.md)).
- **Announcement is the only mesh traffic.** No work, orders, or directed request/reply crosses
  the mesh. Federation is a **UI redirect to the owning baseline** (act on the peer directly), not
  a mesh-mediated operation - so there is no fulfillment handoff and no per-cluster work inbox (see
  [cluster_interop.md](../design/architecture/cluster_interop.md)).
- **Announcement idempotency.** `ClusterAnnouncement` is unsolicited and idempotent by nature - a
  duplicate just refreshes the peer's `lastSeen`; there is no dedup/ack state to keep.
- **Envelope versioning references the contract.** The announcement is a versioned record from
  `lattice-contract`; evolution is additive and forward-compatible (unknown fields ignored). The
  former directed-envelope version negotiation was retired with the work-exchange layer (locked
  #37). Envelope definitions + versioning rules are owned by
  [contract_protocol.md](contract_protocol.md), not here.
- **Interoperability across divergent data models.** Each cluster owns its own, possibly divergent,
  Elasticsearch model; they stay interoperable because an operator **acts on the owning baseline**
  (redirect), so a local model never needs translating into another's.

The discovery/announce protocol, the announcement wire shape, and peer liveness/TTL are **settled**
in [mesh_discovery.md](../design/architecture/mesh_discovery.md) + [mesh_envelopes.md](../design/architecture/mesh_envelopes.md);
per-baseline auth (Keycloak) is locked #38 and builds under its own ticket.

---

## Local docker-compose (the whole stack)

The default local environment is a single docker-compose stack under `deploy/docker/`
(`docker-compose.yml` + [its README](../../deploy/docker/README.md)) that brings up the system:
Elasticsearch + the Artemis broker, and the services + status console as they are built. It is the
fast iteration and QA loop ([qa_protocol.md](qa_protocol.md)). It is currently **infra-first** - ES
+ Artemis come up healthy now; a Vert.x service drops into the documented **service slot** in the
compose file when the first service (#6) lands.

- **One command up.** A single `docker compose up` starts every piece; services address each other
  by compose service name, and the host reaches published ports (see qa_protocol networking).
- **Startup order.** Elasticsearch and the broker must be ready before the services - express this
  with compose dependency + healthcheck conditions so a service does not start against a
  not-ready dependency.
- **Two-cluster mesh locally.** For mesh work, two compose projects stand up two clusters, **each
  with its own broker**, federated to each other over a shared Docker network (which models
  routable sites). Neither project is privileged, so local QA exercises the real topology -
  including the join sequence and broker restart - rather than an approximation of it.
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
