# Architecture design

System-level technical design: the mesh (Artemis discovery + cross-cluster interop + the `lattice-contract` envelopes), the per-service data-model approach, and the REST API structure. One doc per concern.

The mesh + interop core (deferred questions P1-P4) is designed; auth (P5), the status-console live-status transport (P6), and delivery + hosting (P7) are all settled - **every planned design question is now promoted**. Deployment architecture (Docker images, the Kubernetes cluster, Helm, environments) is documented under [platform_protocol.md](../../protocol/platform_protocol.md) / [deploy_protocol.md](../../protocol/deploy_protocol.md) until it grows its own design doc.

---

## Docs

| Doc                                    | Concern                                                                                          | Settles |
|----------------------------------------|-------------------------------------------------------------------------------------------------|---------|
| [mesh_envelopes.md](mesh_envelopes.md) | The shared wire shapes: envelope header + typed payload, the MVP types, JSON + records, versioning + compatibility. | P3      |
| [mesh_discovery.md](mesh_discovery.md) | How a cluster announces itself, the Artemis addressing (multicast announce + per-cluster inbox), peer liveness + TTL. | P1      |
| [mesh_broker_topology.md](mesh_broker_topology.md) | Where the broker lives: a broker per baseline, joined by Artemis federation; the join sequence, the failure model, and the local two-baseline stack. | #44     |
| [cluster_interop.md](cluster_interop.md)| Shape A federation: each baseline owns its data; UI redirect to the owning baseline; the unified read-only view, rendered from the local registry (locked #61); per-baseline auth. | P2      |
| [data_model.md](data_model.md)         | Per-service Elasticsearch approach: index-per-entity, read/write aliases, reindex-behind-alias, create-if-absent bootstrap. | P4      |
| [delivery_model.md](delivery_model.md) | How a baseline reaches a customer and who runs it: exported image archives, hosting deferred, separate authorities per environment and per customer. | P7      |
| [api_structure.md](api_structure.md)   | The REST contract shape: `{data,error,meta}` envelope, error taxonomy, pagination, `/api/v1` versioning, the health surface, request hardening, `/docs`. | #17     |

---

## How they fit together

```
                 lattice.mesh.announce (multicast)   [mesh_discovery]
   hub-west   -- ClusterAnnouncement (consoleUrl, apiBaseUrl) --> all peers
   hub-central -- ClusterAnnouncement (consoleUrl, apiBaseUrl) --> all peers
        |                                        |
   peer registry (who + where)              peer registry (who + where)
        |                                        |
   console: unified view + redirect         console: unified view + redirect   [cluster_interop]
        |  (each console reads its OWN registry; redirects to a peer consoleUrl)  |
   local ES (divergent model, aliases)      local ES (divergent model, aliases)   [data_model]
```

The mesh is a discovery phone book: a versioned `ClusterAnnouncement` establishes who exists and where they are ([mesh_envelopes.md](mesh_envelopes.md), [mesh_discovery.md](mesh_discovery.md)); federation is a UI redirect to the owning baseline plus a unified read-only view rendered from this baseline's own registry, never pulled from a peer (locked #61; [cluster_interop.md](cluster_interop.md)); and each baseline keeps its own divergent, single-writer indices ([data_model.md](data_model.md)) that it alone owns and serves.
