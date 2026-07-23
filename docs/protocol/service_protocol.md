# lattice - Service Protocol

The job description for API and data work: the **Vert.x 5 microservices** (`services/*`) and the **Elasticsearch data layer** (the Elasticsearch client + repositories in `platform/lattice-common`).

Cross-cutting rules (folder structure, naming, Java conventions, env/config, commits, TDD loop, branching, Javadoc) live in the shared core [core_protocol.md](core_protocol.md). The service<->status-console contract seam (the OpenAPI specs + the `lattice-contract` envelope module) lives in [contract_protocol.md](contract_protocol.md). The full response/error/envelope spec is [api_structure.md](../design/architecture/api_structure.md).

---

## Routes and verticles (Vert.x 5, `services/<name>/`)

- Each service is a **Vert.x 5 microservice** in `services/<name>/`: a **thin main verticle** that builds a **`Router`** and starts an HTTP server. This is **not** a servlet stack. Handlers are **thin shells**; cross-cutting concerns (auth, Elasticsearch access) live in `platform/lattice-common` (a `BaseVerticle` and shared clients wired once, available to every service).
- **No business logic in the handler** - extract to a **service class** in the module (e.g. `io.lattice.<name>.service`). The handler validates, calls the service class, and shapes the response.
- Routes validate request **and response** against the **OpenAPI 3.1 contract** via the **Vert.x OpenAPI router** (the router is built from the spec resource in `lattice-contract`, so the spec drives validation). Response shape, error codes, the `{data,error,meta}` envelope, rate limiting, and request logging are defined in [api_structure.md](../design/architecture/api_structure.md). This document does not redefine them - follow that spec exactly.
- All endpoints are versioned under `/api/v1/` and require auth. **No public endpoints.**

> **What not to do:** do not put a query, a branch on business rules, or response assembly inside a route handler. If a handler grows past validate -> call service class -> return, the logic belongs in the service class.

### Auth guard

- **Auth mechanism is TBD - placeholder, set when auth lands.** The guard itself stays: a shared auth handler (in `lattice-common`) protects `/api/v1/*`, verifies the incoming token, and **never trusts a client-sent user id** - identity is derived from the verified token, not the request body.
- Elevated/admin routes sit behind a role guard (the role claim source is **TBD** - set when auth lands).

### API docs (`/docs`) exposure + testing by role

- The interactive **OpenAPI docs** are at `/docs` (Swagger UI) + `/docs/json` (spec), **served from the OpenAPI 3.1 spec in `lattice-contract`** (the same spec that drives router validation - one source, no second hand-written spec).
- **Exposure policy:** the docs UI is served on **local + dev** (a testing surface) and **gated OFF on a prod deployment** - it reveals the full API surface, so it is not public in prod. Gate: a prod environment signal (**TBD - set when the deploy env naming lands**; see the [deploy_protocol env map](deploy_protocol.md)). The spec resource stays available to the service so validation still works even where the UI route is gated off.
- **The docs page has no login of its own** - the **Authorize** button takes a session token (`Authorization: Bearer <token>`); endpoints stay auth-gated regardless of the docs. (Token format is **TBD** - set when auth lands.)
- **Test as a role (today, manual):** obtain a session token for the identity you want and paste it into Authorize. A **regular user** vs an **elevated/admin** identity is distinguished by the role claim (**TBD - set when auth lands**).
- **Ergonomic + scripted role testing** - a token helper, seeded test identities, and a headless simulation harness - is **TBD** (track in a dedicated issue when auth lands). Until it lands, the manual token path above is the way.

---

## Elasticsearch access (`platform/lattice-common`)

- All Elasticsearch access goes through the **Elasticsearch client + repositories** exported from `platform/lattice-common`. No direct connections or backdoor client imports from service code.
- **No ad-hoc query DSL scattered across service code**, with one narrow exception: complex query bodies the repository API cannot express cleanly are kept **inside the repository in `lattice-common`**, never inlined in a handler or service class.
- When the data model changes: update the **mapping definition** in `lattice-common` (the single-writer of mappings), evolve it via the reindex/alias strategy below, and **commit the mapping change alongside the code that depends on it in the same PR**.
- **`platform/lattice-common` is the single-writer of Elasticsearch mappings and indices.** Do not define or mutate a mapping from a service module.

> **What not to do:** never ship a service change that assumes a new field without the mapping change that adds it; never open an Elasticsearch connection from a service module outside the shared client; never scatter raw query DSL through handlers (complex queries live in a `lattice-common` repository only).

> **Gotcha (mappings are not free-form):** Elasticsearch will dynamically map any unseen field, which silently mistypes data and can explode the mapping. Every index sets an explicit mapping with dynamic mapping constrained (see [Model the mapping deliberately](#model-the-mapping-deliberately-analyzers-keyword-vs-text)). Local Elasticsearch runs from the pinned Elasticsearch Docker image (**TBD - pin the exact image tag when the compose stack lands**).

### Reindex and alias strategy (keep the index model clean)

Elasticsearch mappings are largely **immutable once created** - you cannot change a field's type or its analyzer in place. Evolving the model means **creating a new index with the new mapping and reindexing into it**, then atomically swapping a read/write **alias** so services keep pointing at a stable name while the physical index changes underneath. The alias is the indirection that makes a model change non-breaking.

- **Pre-1.0.0, while no persistent production cluster exists:** reindex or drop-and-recreate freely when the model shifts, and at each pre-release checkpoint. Every cluster is local-dev (droppable) or ephemeral, so nothing depends on the intermediate states.
- **Once a persistent production cluster exists** (at launch): the model is **frozen for in-place edits** - a model change is a deliberate, coordinated, **zero-downtime alias swap** (reindex into the new index, then flip the alias, optionally dual-write during the cutover), never a routine drop-and-recreate.

**How to reindex / re-baseline** (inside `platform/lattice-common`; the documented mapping-change test exception applies):

1. Confirm **no mapping branch is in flight** - the mapping is the single-writer seam ([Concurrent branches](core_protocol.md#concurrent-branches-multi-agent)); serialize behind any open branch that touches the mapping definitions.
2. Define the **new index + mapping version** next to the current one.
3. Reindex from the old index into the new one (Elasticsearch `_reindex`).
4. **Re-apply any custom analyzer / keyword settings** a naive copy drops - a reindex does not carry analysis settings across a mapping change.
5. Confirm the **mapping-drift check is green** (the committed mapping matches what the integration suite builds) and the integration suite builds a **fresh index from the single mapping definition**.
6. **Atomically swap the alias** to the new index; drop the old index only once the new one is verified. Never mutate a live index in place across a model change.

### Model the mapping deliberately (analyzers, keyword vs text)

Elasticsearch **auto-maps unseen fields by default**, which is a trap: an **unbounded dynamic mapping** mistypes fields and can explode the index. When you add an index or a field, **model the mapping deliberately** - hot read paths first:

- **Constrain dynamic mapping** (`strict`, or explicit with a reviewed default) - **no unbounded dynamic fields.** A rejected-on-write strict mapping surfaces drift instead of silently mistyping it.
- **Choose `keyword` vs `text` per field to match the query:** `keyword` for exact-match filters, aggregations, and sorting; `text` (with an analyzer) for full-text search. A field queried **both** ways gets a `text` field with a `keyword` sub-field (`fields.keyword`).
- **Pick analyzers deliberately** for `text` fields - match the analyzer to how the field is actually queried, do not leave it on the default when the query needs otherwise.
- A genuinely open sub-document (a rarely-queried metadata blob) may stay dynamic - but **say so in a comment**, do not leave it silent.

### Batch collection reads (no N+1)

When you have a set of ids and need their documents or related data, load it in **one bulk request keyed by the id set** - Elasticsearch **multi-get (`_mget`)** for by-id fetches, or **`_msearch`** to bundle several queries into a single round-trip - then join in memory. Never fan out a request per item - whether that is an `await`/blocking call inside a `.map()` / `for`, a `CompositeFuture` over per-id requests, or a per-row helper (like a card builder) that queries. The cost is the round-trip **count** (hundreds of requests for ~80 documents), not the payload, and it is not fixed by tuning each query. A per-row producer should collapse to a handful of requests total. Shape corollary: a list returns the **skinny card DTO**, a single record the **full detail DTO** - never assemble a list of full objects.

### Enforce locked invariants at the data layer

Many locked decisions are data invariants. Elasticsearch has **no declarative constraint** (no `CHECK`), so every invariant is enforced in code - classify and enforce each, **never leave one silently unenforced**:

- **Single-document data rule** (a bound, a range, a not-self reference - e.g. `amount > 0`, `radius BETWEEN 1 AND 50`, `attestedById != personId`) -> a **guard in the repository** (`lattice-common`), before the write, and a spec test asserting the violating write is rejected (the mapping-change exception: the test ships in the same change).
- **Cross-document rule** (a count cap, an even split across backers, a uniqueness across documents) -> an **app-layer guard in the service class, test-first** (the rejected write returns the expected error envelope).
- **Write path not built yet** -> do **not** add a speculative guard for it; record the classification (in the PR / a comment) so it is explicit, and enforce it when the feature ticket builds that path.

---

## Testing (service)

Service work is the part of the stack that **leans on integration tests** rather than manual QA (a real Elasticsearch, every PR), so the bar here is concrete:

- **Integration tests required for every route**, against a **real Elasticsearch** (**Testcontainers**), not mocks. At minimum each route covers the happy path.
- **Contract tests** assert responses validate against the **OpenAPI 3.1 spec** (prevent drift) - this is the service side of the contract seam ([contract_protocol.md](contract_protocol.md)).
- **Unit tests (JUnit 5)** for service classes with non-trivial logic.
- Test files live under `src/test/java/` mirroring the `src/main/java/` package structure (the Maven layout).
- **Test hygiene is enforced** ([core_protocol.md - Test hygiene](core_protocol.md#test-hygiene)): an unexpected error/warning log fails the test. When product code logs on a tested path (an expected error branch, a best-effort cleanup, a safety valve), **assert the log fired** - never leave it to print into the build log.
- **Javadoc is gated:** public exported types, classes, and methods in `platform/*` and `services/*` must have a Javadoc docstring (enforced in the `./mvnw verify` gate).

**Contract test pattern (JUnit 5 + vertx-junit5 + `WebClient`).** Deploy the verticle on a Vert.x test instance, send a request with the Vert.x `WebClient`, assert the body validates against the OpenAPI schema - a real in-process HTTP call, real Elasticsearch via Testcontainers.

```java
@Testcontainers
@ExtendWith(VertxExtension.class)
class HealthRouteTest {

  @Container
  static final ElasticsearchContainer ES =
      new ElasticsearchContainer(/* pinned image - TBD when compose stack lands */);

  int port;

  @BeforeEach
  void deploy(Vertx vertx, VertxTestContext ctx) {
    vertx.deployVerticle(new MainVerticle(/* wired to ES.getHttpHostAddress() */),
        ctx.succeeding(id -> ctx.completeNow()));
    // capture the bound port for the WebClient below
  }

  @Test
  void returnsBodyMatchingContract(Vertx vertx, VertxTestContext ctx) {
    WebClient.create(vertx)
        .get(port, "localhost", "/api/v1/health")
        .send(ctx.succeeding(res -> ctx.verify(() -> {
          assertEquals(200, res.statusCode());
          assertTrue(ContractValidator.matchesSpec("Health", res.bodyAsJsonObject()));
          ctx.completeNow();
        })));
  }
}
```

For routes behind the auth guard, stub the shared auth handler (a no-op that injects a known identity) to drive authenticated / unauthenticated cases - the auth mechanism itself is **TBD**, so keep the stub behind a seam that swaps for the real verifier when it lands. Integration tests that exercise Elasticsearch run against the **Testcontainers** Elasticsearch, not mocks.

---

## Folder shape

The canonical `services/<name>/` Maven module tree (the module POM, `src/main/java/io/lattice/<name>/` with the main verticle + `Router` wiring + service classes, and `src/test/java/` mirroring it) is in the shared core: [core_protocol.md - Folder Structure](core_protocol.md#folder-structure). The rule that matters here: handlers are thin shells, business logic is a service class, the Elasticsearch client + repositories live in `platform/lattice-common`, and all request/response DTOs live in `platform/lattice-contract` ([contract_protocol.md](contract_protocol.md)), never inlined in handlers.
