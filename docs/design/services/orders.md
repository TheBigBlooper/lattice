# Orders service

The first Lattice Vert.x microservice. Owns customer **orders** and their lines for its own cluster: create an order and retrieve it by id, persisted to Elasticsearch, described by the versioned `/api/v1` REST contract. Grounds ticket #6.

Under Shape A federation (locked #37) **each baseline owns its own orders** end to end - there is no cross-cluster order-of-record or fulfillment handoff. This service is single-cluster and local: no mesh participation, no auth wiring yet (both deferred).

Related: [api_structure.md](../architecture/api_structure.md) (the `{data|error, meta}` envelope + error taxonomy), [data_model.md](../architecture/data_model.md) (index-per-entity + aliases + bootstrap), [service_protocol.md](../../protocol/service_protocol.md) (thin routes, the data layer), [example_domain.md](../../reference/example_domain.md) (the illustrative fulfillment domain), [locked_decisions.md](../../reference/locked_decisions.md) (#17 OpenAPI, #19 strict bodies, #20 path versioning, #32 per-service ES model, #35 response envelope, #37 Shape A).

---

## Scope (MVP, #6)

A thin, local slice - the minimum that proves a real service runs end to end on the shared foundation:

- **Create** an order with its lines, persisted to Elasticsearch.
- **Get** an order by id.
- Reuse the shared runtime (`BaseVerticle`, `EsRepository`) rather than rebuilding config/health/data plumbing.

Everything else about orders (below, [Deferred](#deferred-post-mvp)) is out of scope for #6.

---

## REST contract (`/api/v1`)

Two operations, added to the single OpenAPI v1 spec (`platform/lattice-contract/src/main/resources/openapi/v1.yaml`). Both return the standard `{data|error, meta}` envelope ([api_structure.md](../architecture/api_structure.md)); request bodies are strict (locked #19), responses lenient.

| Operation                      | operationId   | Success                         | Errors                               |
|--------------------------------|---------------|---------------------------------|--------------------------------------|
| `POST /api/v1/orders`          | `createOrder` | **201** `{ data: Order, meta }` | 400 `VALIDATION_ERROR` (strict body) |
| `GET /api/v1/orders/{orderId}` | `getOrder`    | **200** `{ data: Order, meta }` | 404 `NOT_FOUND`                      |

Either operation returns **503 `UNAVAILABLE`** when Elasticsearch is unreachable (a down dependency, per the taxonomy) rather than a 500; `/readiness` independently reports `DOWN` so an orchestrator pulls the pod from rotation.

### Request - `CreateOrderRequest` (strict)

`additionalProperties: false` on the body and every nested object; bounds referenced from the shared `components.schemas` bounded types.

```json
{
  "customerId": "cust-1024",
  "lines": [
    { "sku": "sku-42", "quantity": 3 },
    { "sku": "sku-77", "quantity": 1 }
  ]
}
```

| Field              | Type          | Rule                                     |
|--------------------|---------------|------------------------------------------|
| `customerId`       | `ShortString` | required                                 |
| `lines`            | array         | required, `minItems: 1`, `maxItems: 100` |
| `lines[].sku`      | `ShortString` | required                                 |
| `lines[].quantity` | integer       | required, `minimum: 1`                   |

A missing `customerId`, an empty `lines`, a blank `sku`, or a `quantity < 1` is rejected at the contract edge by the Vert.x OpenAPI router as a **400 `VALIDATION_ERROR`** with `details` naming the offending field(s). No cross-line rule (duplicate skus within one order are allowed).

### Response - `Order`

```json
{
  "data": {
    "orderId": "b3f1c2a8-...-uuid",
    "customerId": "cust-1024",
    "status": "RECEIVED",
    "lines": [ { "sku": "sku-42", "quantity": 3 }, { "sku": "sku-77", "quantity": 1 } ],
    "createdAt": "2026-07-24T00:00:00Z"
  },
  "meta": { "requestId": "9f1c...", "apiVersion": "v1" }
}
```

| Field        | Type                                     | Notes                                                          |
|--------------|------------------------------------------|----------------------------------------------------------------|
| `orderId`    | string                                   | **server-generated** UUID; never client-supplied               |
| `customerId` | string                                   | echoed from the request                                        |
| `status`     | `OrderStatus`                            | always `RECEIVED` on create (see [Status](#status--lifecycle)) |
| `lines`      | array of `OrderLine` `{ sku, quantity }` | as submitted                                                   |
| `createdAt`  | string (date-time)                       | **server-set**, always UTC                                     |

`OrderResponse = { data: Order, meta }`. New spec schemas: `CreateOrderRequest`, `CreateOrderLine`, `Order`, `OrderLine`, `OrderStatus`, `OrderResponse` - all reusing the existing `ShortString` bound and the shared error responses.

---

## Status / lifecycle

`OrderStatus` is a declared enum so the contract + mapping are stable across later work:

```
RECEIVED -> ALLOCATED -> PACKED -> SHIPPED -> DELIVERED
```

**MVP:** create sets `RECEIVED`. There are **no transition endpoints** and no transition logic yet - the fuller lifecycle (allocate/pack/ship/deliver) is a later ticket. The enum is declared now so adding transitions does not reshape the `status` field or the mapping.

---

## Data model (Elasticsearch)

Per locked #32 (index-per-entity, single-writer, aliases, create-if-absent bootstrap) and [data_model.md](../architecture/data_model.md). The `orders` mapping is **single-writer in `platform/lattice-common`**; the service never defines or mutates it.

- **Index:** logical `orders` - concrete `orders-000001`, read alias `orders`, write alias `orders-write` (provisioned by `EsRepository.ensureIndex`).
- **Bootstrap:** create-if-absent on service startup (idempotent), so a fresh local/ephemeral cluster self-provisions. Pre-1.0.0 the model may be dropped/recreated freely ([service_protocol.md](../../protocol/service_protocol.md) reindex/alias strategy).
- **Mapping** (`dynamic: strict` - no unbounded dynamic fields):

```json
{
  "dynamic": "strict",
  "properties": {
    "orderId":    { "type": "keyword" },
    "customerId": { "type": "keyword" },
    "status":     { "type": "keyword" },
    "createdAt":  { "type": "date" },
    "lines": {
      "type": "nested",
      "properties": {
        "sku":      { "type": "keyword" },
        "quantity": { "type": "integer" }
      }
    }
  }
}
```

`lines` is **`nested`** so each line's `sku` + `quantity` stay associated per-line (a later "orders containing sku X with quantity > N" query matches within one line, not across lines). All identifier/status/sku fields are `keyword` (exact-match / filter); `quantity` is `integer`; `createdAt` is `date`.

---

## Code shape

Reuse over rebuild - the shared runtime already provides config, health/readiness, and the data-layer mechanics.

```
services/orders/
  pom.xml                         depends on lattice-common + lattice-contract
  src/main/java/io/lattice/orders/
    MainVerticle.java             thin bootstrap: deploy OrdersVerticle
    OrdersVerticle.java           extends BaseVerticle; configureRoutes + an ES readiness check
    routes/OrderRoutes.java       thin handlers: validate -> OrderService -> envelope
    service/OrderService.java     business logic (mint id + timestamp + status, build Order)
    repository/OrdersRepository.java   extends EsRepository; ensureIndex(orders) + index/get
  src/test/java/io/lattice/orders/   contract + integration tests (vertx-junit5 + Testcontainers ES)
  Dockerfile                      provisional eclipse-temurin:21-jre base
```

- **`OrdersVerticle extends BaseVerticle`** - contributes only its routes (`configureRoutes`) and an **Elasticsearch readiness check** (`registerReadinessChecks`), so `/readiness` reports `DOWN` until Elasticsearch is reachable. Health/config/shutdown come from the base.
- **Thin routes** - a handler validates against the OpenAPI operation, calls `OrderService`, and shapes the `{data|error, meta}` envelope. No business logic or query DSL in a handler ([service_protocol.md](../../protocol/service_protocol.md)).
- **`OrderService`** - mints the `orderId` (UUID) + `createdAt` (UTC) + initial `RECEIVED` status, assembles the `Order`, and persists via the repository. Never trusts a client-supplied id.
- **`OrdersRepository extends EsRepository`** - orders-specific wiring in the service module: bootstraps the index from the `lattice-common` mapping and does `index`(write alias) / `get`(read alias) by id. All Elasticsearch access goes through the shared client.
- **DTOs** (`Order`, `OrderLine`, `CreateOrderRequest`) are records in `platform/lattice-contract`, never inlined in the service.

---

## Testing (test-first)

Per [service_protocol.md](../../protocol/service_protocol.md) - integration against a **real Elasticsearch (Testcontainers)**, contract-validated, written before the implementation:

- **`createOrder`** - a valid body returns 201 with a server-minted `orderId`, `status: RECEIVED`, and the submitted lines; the document is retrievable from Elasticsearch.
- **`getOrder`** - an existing id returns 200 with the persisted order; an **unknown id returns 404 `NOT_FOUND`**.
- **Strict-body rejection** - an empty `lines`, a `quantity: 0`, or an unknown key returns **400 `VALIDATION_ERROR`** with `details`.
- **Readiness** - `/readiness` is `DOWN` (503) until Elasticsearch is reachable, `UP` (200) once it is.
- Contract tests assert every response validates against the OpenAPI v1 schema (drift guard).

---

## Deferred (post-MVP)

Explicitly out of scope for #6, recorded so nothing is assumed built:

- **Search**, and filters on the listing. The paged **list** is no longer deferred: `listOrders` returns a page sorted newest-first, taking a page size and a position and nothing else (locked #65). Filters were declined until the screen has been used, and free-text search on a stronger ground - a shared query contract would commit every baseline to the same query semantics, which locked #14 declines.
- **Status transitions** (allocate / pack / ship / deliver) and their endpoints.
- **Cancel / delete** an order.
- **Inventory reservation** and the orders ↔ inventory interaction (that is #7 inventory).
- **Create idempotency key** (a repeated POST currently creates a new order).
- ~~**Real auth** - the `/api/v1` guard is a stub; real authentication is per-baseline Keycloak (#30, locked #38).~~ **Closed by locked #48.** Every `/api/v1` operation is bearer-protected against this baseline's own realm; only `/health` and `/readiness` are open, because a probe cannot carry a token.
- **Mesh participation** - none; discovery/announce is the mesh-gateway's job (#9), and Shape A means orders never hands off to a peer.

---

## Decisions settled here

- MVP endpoints: `createOrder` + `getOrder` only; local REST + Elasticsearch, no mesh, no real auth.
- `Order = { orderId (server UUID), customerId, status, lines[], createdAt (UTC) }`; line `{ sku, quantity }` embedded, no line id.
- `OrderStatus` enum declared (RECEIVED..DELIVERED); create sets `RECEIVED`; no transitions yet.
- Strict create body (`customerId` required, `lines` ≥ 1, `sku` required, `quantity` ≥ 1); no sku-uniqueness.
- `orders` index, `dynamic: strict`, `lines` **nested**; mapping single-writer in `lattice-common`; repository in `services/orders`.
- Reuse `BaseVerticle` + `EsRepository`; DTOs in `lattice-contract`; Dockerfile on provisional `eclipse-temurin:21-jre`.

This spec is an instance of locked **#32** (per-service Elasticsearch data model) and the REST contract (#17/#35); it introduces no new locked decision.
