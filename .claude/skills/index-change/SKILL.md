---
name: index-change
description: Change an Elasticsearch mapping/index + the spec-driven integration test in the same change. The documented TDD exception (a mapping cannot be queried until it exists).
---

Use to change the Elasticsearch data model (a new field, an analyzer, an alias). Canonical rules: [service_protocol.md](../../../docs/protocol/service_protocol.md). This is the documented **TDD exception** (a mapping cannot be queried until it exists): write the spec-driven integration test in the **same** change and drive to green - the same class of exception as a database schema/migration change.

**Single-writer:** Elasticsearch mappings are owned by the **service agent**. Only one in-flight branch touches a given index mapping at a time; claim the ticket before editing.

## Steps (in order)

1. **Edit the mapping/index** in `platform/lattice-common` (the mapping definitions + the index/alias setup the client applies). New field, analyzer, or alias - keep field names consistent with the existing model.
2. **Version the change forward.** Prefer an **additive** mapping update or a **new index + alias swap** (reindex behind the alias) over an in-place breaking change; Elasticsearch mappings are largely immutable per field. Never rewrite history of a live index - roll the alias forward. Do **not** hand-edit any generated manifest/output under `deploy/k8s` - regenerate it from source instead.
3. **Spec-driven integration test** in the same change: assert the mapping's invariants (the field is indexed/searchable, the analyzer tokenizes as intended, the alias resolves) against a **live Elasticsearch via Testcontainers** (vertx-junit5). Run it red first if the mapping does not yet exist.
4. **Apply + verify:** bring the mapping up (the client's index/alias bootstrap, or a reindex) and run the integration test to green. Reseed with **synthetic** data if needed - never copy real production data.

## Checks
- [ ] mapping/index change + its spec-driven integration test committed together
- [ ] integration test against real Elasticsearch (Testcontainers), green
- [ ] change is additive or alias-swapped (no destructive in-place mapping rewrite); generated `deploy/k8s` output not hand-edited
- [ ] single-writer respected (one branch per index mapping); `./mvnw verify` green
