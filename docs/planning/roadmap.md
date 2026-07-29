# Lattice - Build Roadmap

The phased delivery plan. The **<- you are here** marker shows the current phase. This is the **build/delivery** roadmap, not the governance build phases in [governance.md](../governance/governance.md); this doc holds the phases and sequence, the GitHub issues hold the granular work.

---

## Near-term

- **Design the mesh + the first service.** *Done.* Nail down the Artemis-backed discovery + cross-cluster message envelopes (`lattice-contract`) and the spec for the first Vert.x microservice, before building data.
- **Stand up the build.** *Done.* Maven multi-module skeleton, `platform/lattice-common` (BaseVerticle, config loader, health/readiness, Elasticsearch client + repositories, the mesh discovery client) and `platform/lattice-contract` (OpenAPI specs + envelope records).
- **First service on a local cluster.** *Done.* One service running against local docker-compose (Elasticsearch + Artemis), packaged as a Docker image, described by K8s/Helm manifests. Delivered well past the minimum: three services plus the console, three federating baselines locally, and a Helm chart proven on a `kind` cluster.
- **Interop.** *Done.* Cluster-to-cluster discovery + communication over the mesh; interoperability across divergent Elasticsearch data models proven. Discovery, federation, loop prevention, and per-baseline broker identity are proven, and the Shape A redirect plus the unified view and mesh-link state (#12, #28, #56) closed the remaining half.

## Later

- **Status console.** *Largely delivered ahead of sequence.* The Vite + React console ships per cluster with sign-in and the cluster verdict; the unified multi-baseline view is what is outstanding, and it belongs to Interop above.
- **Multi-cluster.** <- you are here. Multiple peered clusters, environments (dev / prod parity), deploy maturity, monitoring.

(This doc holds the phase narrative; the granular work is tracked as GitHub issues under the roadmap epic #13.)
