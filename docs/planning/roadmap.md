# Lattice - Build Roadmap

The phased delivery plan. The **<- you are here** marker shows the current phase. This is the **build/delivery** roadmap, not the governance build phases in [governance.md](../governance/governance.md); this doc holds the phases and sequence, the GitHub issues hold the granular work.

---

## Near-term

- **Design the mesh + the first service.** *Done.* Nail down the Artemis-backed discovery + cross-cluster message envelopes (`lattice-contract`) and the spec for the first Vert.x microservice, before building data.
- **Stand up the build.** *Done.* Maven multi-module skeleton, `platform/lattice-common` (BaseVerticle, config loader, health/readiness, Elasticsearch client + repositories, the mesh discovery client) and `platform/lattice-contract` (OpenAPI specs + envelope records).
- **First service on a local cluster.** <- you are here. One service running against local docker-compose (Elasticsearch + Artemis), packaged as a Docker image, described by K8s/Helm manifests.

## Later

- **Interop.** Cluster-to-cluster discovery + communication over the mesh; interoperability across divergent Elasticsearch data models proven.
- **Status console.** The Vite + React console shipping per cluster, viewing the status of every node.
- **Multi-cluster.** Multiple peered clusters, environments (dev / prod parity), deploy maturity, monitoring.

(This doc holds the phase narrative; the granular work is tracked as GitHub issues under the roadmap epic #13.)
