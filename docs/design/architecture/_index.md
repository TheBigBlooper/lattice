# Architecture design

System-level technical design: the mesh (Artemis discovery + cross-cluster interop + the `lattice-contract` envelopes) and the per-service data-model approach. One doc per concern.

The mesh + interop core (deferred questions P1-P4) is designed; auth (P5), the status-console live-status transport (P6), and container registry + hosting (P7) remain deferred to their own sessions. Deployment architecture (Docker images, the Kubernetes cluster, Helm, environments) is documented under [platform_protocol.md](../../protocol/platform_protocol.md) / [deploy_protocol.md](../../protocol/deploy_protocol.md) until it grows its own design doc.

---

## Docs

| Doc                                    | Concern                                                                                          | Settles |
|----------------------------------------|-------------------------------------------------------------------------------------------------|---------|
| [mesh_envelopes.md](mesh_envelopes.md) | The shared wire shapes: envelope header + typed payload, the MVP types, JSON + records, versioning + compatibility. | P3      |
| [mesh_discovery.md](mesh_discovery.md) | How a cluster announces itself, the Artemis addressing (multicast announce + per-cluster inbox), peer liveness + TTL. | P1      |
| [cluster_interop.md](cluster_interop.md)| Canonical-envelope interop, cross-cluster identity, the handoff + ack flow, idempotency, undeliverable handling. | P2      |
| [data_model.md](data_model.md)         | Per-service Elasticsearch approach: index-per-entity, read/write aliases, reindex-behind-alias, create-if-absent bootstrap. | P4      |

---

## How they fit together

```
                 lattice.mesh.announce (multicast)
   hub-west  --------- ClusterAnnouncement ---------> all peers   [mesh_discovery]
   hub-west  --- FulfillmentHandoff --> lattice.mesh.cluster.hub-central   [mesh_envelopes + cluster_interop]
   hub-central --- HandoffAck -------> lattice.mesh.cluster.hub-west
        |                                        |
   mesh-gateway maps local <-> envelope     mesh-gateway maps envelope <-> local
        |                                        |
   local ES (divergent model, aliases)      local ES (divergent model, aliases)   [data_model]
```

Every cross-cluster message is a versioned envelope ([mesh_envelopes.md](mesh_envelopes.md)); discovery establishes who exists and where to send ([mesh_discovery.md](mesh_discovery.md)); interop defines how work is handed off and acknowledged ([cluster_interop.md](cluster_interop.md)); and each hub keeps its own divergent, single-writer indices ([data_model.md](data_model.md)).
