# Service design

This folder holds **one spec per microservice** (the Service Spec Lifecycle): each service's responsibility, its REST endpoints (the OpenAPI contract), its Elasticsearch index/mappings, and the mesh envelopes it publishes or consumes. A spec is written before the service is built and kept current as it changes.

## Services

| Service | Owns | REST (`/api/v1`) | ES index | Spec |
|---------|------|-------------------|----------|------|
| **orders** | Customer orders + their lines (this cluster's own; Shape A) | `createOrder`, `getOrder` | `orders` (nested lines) | [orders.md](orders.md) |
| **inventory** | Stock + reservations (planned, #7) | TBD | TBD | planned |

Each baseline owns its own data (locked #37); no service hands work to a peer over the mesh. New services are added per the [Service Spec Lifecycle](../../protocol/session_protocol.md#service-spec-lifecycle).
