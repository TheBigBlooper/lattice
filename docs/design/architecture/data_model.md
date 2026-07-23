# Per-Service Data Model - Indices, Aliases, Bootstrap

The Elasticsearch data-model **approach** every service follows: how indices are shaped, how mappings evolve, and how they are provisioned. Settles deferred question P4 (the approach; concrete per-service field mappings live in each service spec).

Related: [cluster_interop.md](cluster_interop.md) (divergent local models stay interoperable via envelopes), [locked_decisions.md](../../reference/locked_decisions.md) (#7 Elasticsearch, #14 divergent per-cluster models), [core_protocol.md](../../protocol/core_protocol.md) (naming, the ES-mapping TDD exception).

---

## Principle - divergent local models, single-writer indices

Each cluster owns its **own** Elasticsearch model and clusters may **diverge** (locked #14); interoperability is preserved by the mesh envelopes, not a shared schema (see [cluster_interop.md](cluster_interop.md)). Within a cluster, **one service owns each index and is its single writer** (the mapping seam - core_protocol concurrent-branches rule). This doc fixes the *shape and lifecycle* of those indices; the *fields* are per-service (below).

---

## Index shape + naming

- **Index-per-entity**, owned by the service that writes it (e.g. `orders`, `inventory`).
- **Naming (locked convention):** kebab-case index names, snake_case fields (e.g. index `node-status`, field `last_seen`).
- **Explicit mappings** - fields are declared, not left to Elasticsearch dynamic guessing (a strict-ish dynamic policy catches typo fields rather than silently indexing them).

---

## Mapping evolution - reindex behind an alias

Reads and writes go through **aliases**, never the concrete index directly, so a mapping change is invisible to callers:

| Alias         | Role  | Points at            |
|---------------|-------|----------------------|
| `orders`      | read  | current concrete index |
| `orders-write`| write | current concrete index |

```
index:  orders-000001            (concrete)
alias:  orders        (read)  -> orders-000001
alias:  orders-write  (write) -> orders-000001

mapping change:
  create orders-000002 (new mapping) -> reindex 000001 into 000002
  -> repoint orders + orders-write to 000002   (readers never see downtime)
```

A non-additive mapping change (retype/rename) is a new concrete index + reindex + alias repoint. An additive change (a new field) can be applied in place, but the alias indirection is kept from day one so the harder case never requires a retrofit.

---

## Bootstrap - create-if-absent on startup

Each service provisions its own indices through the shared repository base in `platform/lattice-common`:

- On startup the service **ensures its indices + aliases exist** (create the concrete index and the read/write aliases if missing; no-op if present).
- Versioned mapping definitions live **with the owning service**.
- The local `docker-compose` cluster is therefore **self-provisioning** - no external migration step for local dev / QA.

```
service boot -> ensureIndex("orders", mapping-v1)
  missing -> create orders-000001 + aliases (orders, orders-write)
  present -> no-op
```

**Testing (documented ES-mapping TDD exception).** A mapping cannot be queried until the index exists, so index/mapping work ships with **spec-driven integration tests in the same change** - assert the mapping + aliases against a real Elasticsearch (Testcontainers), driven to green (core_protocol TDD exception; the [index-change skill](../../../.claude/skills/index-change/SKILL.md)).

---

## Cross-cluster note

Because models diverge, an index's fields on one hub need not match another's. Cross-hub work never reads a peer's index directly - it goes through the canonical envelope, and each hub's `mesh-gateway` maps envelope <-> local index (see [cluster_interop.md](cluster_interop.md)). A received handoff's `originRef` (the `<clusterId>:<localId>`) is stored as an ordinary field on the local document.

---

## Concrete mappings are per-service (scope boundary)

This doc settles the **approach**. The actual field-level mappings for each service (`orders`, `inventory`, and the mesh-gateway's peer-registry + processed-message-id + handoff-state indices) are defined in that service's spec under `docs/design/services/<name>.md` when the service is designed/built, so field decisions are not guessed ahead of their tickets.

---

## Decisions settled here (P4)

- Index-per-entity, single-writer per index; kebab-case indices, snake_case fields; explicit mappings.
- Read/write aliases from day one; mapping change = reindex-behind-alias + repoint.
- Create-if-absent bootstrap on service startup via the shared `lattice-common` repository base (self-provisioning local cluster).
- Divergent per-cluster models stay interoperable via envelopes, not a shared schema.
- Concrete per-service field mappings deferred to `docs/design/services/*`.

Promoted to locked decisions - see [locked_decisions.md](../../reference/locked_decisions.md).
