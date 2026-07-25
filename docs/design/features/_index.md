# Feature design

This folder holds **cross-cutting feature design** - behavior that spans more than one service or the mesh (for example cluster interoperability rules, discovery + peering, status aggregation across nodes). One canonical doc per feature.

## Docs

| Doc | Feature | Spans | Ticket |
|-----|---------|-------|--------|
| [interop_console.md](interop_console.md) | Interop console - a unified read-only view of all discovered baselines (live-pulled) + a redirect to a peer's own console to act on it (Shape A federation). | UI + mesh discovery + each baseline's own services | #28 |
| [per_baseline_identity.md](per_baseline_identity.md) | Per-baseline identity - each baseline's own Keycloak realm protecting its `/api/v1` + console, what happens when an operator crosses a baseline boundary, and per-baseline broker certificates. | services + UI + platform (Keycloak, Artemis mutual TLS) | #30 |
