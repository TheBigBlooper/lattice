---
name: new-endpoint
description: Scaffold a Vert.x route under /api/v1 with its OpenAPI operation + a failing contract/integration test first.
---

Use to add a REST endpoint to a service. Canonical rules: [service_protocol.md](../../../docs/protocol/service_protocol.md); response/error/envelope spec: [architecture](../../../docs/design/architecture/_index.md) *(planned - written in the architecture design session, #2)*. If the request/response shape is new, do [new-contract](../new-contract/SKILL.md) first.

## Steps (in order)

1. **Contract:** ensure the request + response schemas exist as an **OpenAPI 3.1 operation** in the service's `platform/lattice-contract` spec (run [`/new-contract`](../new-contract/SKILL.md) if not).
2. **Failing test first:** write the contract/integration test with **vertx-junit5 + `WebClient`** against a real Elasticsearch (Testcontainers) - happy path plus the failure/validation case. Run it red.
3. **Route as a thin shell** in the target service under `/api/v1/`: register it on the **OpenAPI-driven Vert.x router** so request + response are validated against the operation, and delegate to a **service class** for the logic. Never trust a client-sent identity - derive it from the verified request context.
4. **Implement the service class** to green. Add Javadoc to exported methods.
5. If the Elasticsearch mapping/index changed, run [`/index-change`](../index-change/SKILL.md).

## Checks
- [ ] failing contract/integration test (vertx-junit5 + `WebClient`, Testcontainers Elasticsearch) shown before implementation
- [ ] route is a thin handler; logic in a service class; request + response validated by the OpenAPI router
- [ ] `/api/v1`, versioned, validated against the `lattice-contract` operation
- [ ] Javadoc present; `./mvnw verify` green
