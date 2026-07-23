# Feature design

This folder holds **cross-cutting feature design** - behavior that spans more than one service or the mesh (for example cluster interoperability rules, discovery + peering, status aggregation across nodes). One canonical doc per feature.

## Docs

| Doc | Feature | Spans | Ticket |
|-----|---------|-------|--------|
| [interop_console.md](interop_console.md) | Interactive interop console - operator triggers (check peer inventory, place cross-cluster order) mesh-mediated from one cluster, visualized live. | UI + contract + orders/inventory + mesh | #26 |
