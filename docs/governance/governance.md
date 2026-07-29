# Lattice - Governance

A lean statement of what Lattice is for, what it deliberately is not, and the phases we build it in. This is internal protocol context; it will be expanded as the project matures.

---

## Philosophy

- **Interoperable by contract, not by coupling.** Clusters may run divergent Elasticsearch data models, but they stay interoperable through versioned REST endpoints and the shared `lattice-contract` mesh envelopes. The contract is the guarantee; the internals are free to differ.
- **A cluster is a versioned baseline.** A collection of versioned services + versioned REST endpoints is one reproducible unit. Reproducible packaging (Docker + Kubernetes) over hand-tuned hosts.
- **Thin edges, clear seams.** Routers/handlers are thin; business logic sits in services; the boundary shapes live in the contract module. One engine per job, reuse over rebuild.
- **Observable clusters.** Every cluster ships a status console so node health is visible, not inferred.

---

## Non-goals

- **Not a monolith.** No single all-in-one service; capabilities are separate Vert.x microservices.
- **Not a shared global database.** No single central data store every cluster reads/writes; each cluster owns its Elasticsearch model.
- **Not a hidden mesh.** No undocumented cross-cluster message shapes - anything crossing the mesh is a versioned `lattice-contract` envelope.
- **Not tied to one cloud.** Kubernetes-native; no dependence on a single proprietary managed service.

---

## Build phases

- **Phase 1 - Foundation.** *Complete.* Design the mesh + the first service; stand up the Maven multi-module build, `lattice-common` (BaseVerticle, config, health, Elasticsearch client) and `lattice-contract`.
- **Phase 2 - First cluster.** *Complete.* A running single cluster: one or more services on Kubernetes, local docker-compose (Elasticsearch + Artemis + services), the status console viewing node status.
- **Phase 3 - Interop.** *Complete.* Cluster-to-cluster discovery + communication over the Artemis mesh; interoperability across divergent Elasticsearch models proven. Discovery, federation and loop prevention are proven across three federating baselines, and the Shape A redirect plus the unified view make divergent data models interoperable in practice rather than only on paper.
- **Phase 4 - Multi-cluster + hardening.** **<- you are here.** Multiple peered clusters, operational maturity (deploy, environments, monitoring).

(Phases are the narrative; the granular work is tracked as GitHub issues - see the [roadmap](../planning/roadmap.md) and the roadmap epic #13. This stub grows as the phases do.)
