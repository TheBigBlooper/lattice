---
name: new-contract
description: Add or change an OpenAPI operation or a lattice-contract envelope; wire across the service + status console; test-first on both sides.
---

Use when a shape crosses a boundary - a versioned REST request/response, or an Artemis mesh message between clusters. Canonical rules: [contract_protocol.md](../../../docs/protocol/contract_protocol.md). Do not inline a boundary type in a route, a consumer, or a console component - it goes in the contract first. The `platform/lattice-contract` module is **single-writer** (the contract role).

Pick the seam:
- **Versioned REST:** add or change an **OpenAPI 3.1** operation/schema in the service's spec (the resources under `platform/lattice-contract`) - this drives Vert.x router validation and the status-console generated client.
- **Mesh interop:** add or change a **`lattice-contract` envelope record** (a Java record every cluster agrees on) for cluster-to-cluster Artemis messages.

## Steps (in order)

1. **Define the contract first.** For REST, edit the OpenAPI operation + schema and regenerate the DTOs. For the mesh, add/change the envelope record in `platform/lattice-contract` (versioned, with its Javadoc). Reuse the shared error/pagination/envelope shapes.
2. **Write the failing contract test** in `platform/lattice-contract` pinning the new/changed shape (a schema/serialization round-trip for the envelope; the operation shape for REST). Run it red.
3. **Service side, test-first:** write the route/consumer integration test (vertx-junit5 + `WebClient` against real Elasticsearch via Testcontainers, or a Testcontainers Artemis broker for the mesh) asserting the payload validates against the OpenAPI operation / envelope. Run it red. (Hand the route to the service role / [`/new-endpoint`](../new-endpoint/SKILL.md).)
4. **Status-console side, test-first:** write the React data-hook/component test against the **generated, contract-typed** client (no untyped JSON). Run it red. (Hand the panel to the ui role / [`/new-panel`](../new-panel/SKILL.md).)
5. **Implement both sides to green.** Confirm a drift on either side now fails a test.

## Checks
- [ ] OpenAPI operation/schema **or** `lattice-contract` envelope record added/changed, with Javadoc, in `platform/lattice-contract`
- [ ] failing tests shown on all three layers before implementation
- [ ] the status-console client stays generated + contract-typed; mock->live cutover does not touch the console
- [ ] `./mvnw verify` green (service + contract); status-console tests green
