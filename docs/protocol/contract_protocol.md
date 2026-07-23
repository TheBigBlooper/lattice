# lattice - Contract Protocol

The job description for work that spans the services and the status console. The seam between them is the **OpenAPI 3.1 specs plus the `lattice-contract` envelope module** in **`platform/lattice-contract`**. This document owns the contract, the both-sides-test-first rule, the typed client, and the mock -> live cutover.

Status-console specifics live in [ui_protocol.md](ui_protocol.md); service specifics in [service_protocol.md](service_protocol.md); the TDD loop and shared conventions in [core_protocol.md](core_protocol.md).

---

## The contract is the seam

`platform/lattice-contract` holds two seams that every side depends on:

- **OpenAPI 3.1 specs** for the versioned REST surface (`/api/v1/*`): every request and response shape, plus the shared envelope (`{data,error,meta}`), error codes, and pagination shapes.
- **Java records** for the **Artemis mesh message envelopes** - the message contract every cluster agrees on for cross-cluster (mesh) interop.

Both sides import from it:

- The **service** validates request and response against the OpenAPI spec (the Vert.x OpenAPI router, built from the spec resource), and cross-cluster messages against the envelope records.
- The **status console** types its responses from the **generated OpenAPI TypeScript client**, and fixtures/mocks are typed from the same generated types.

Because both sides depend on the one contract, a drift on either side fails a test. The REST seam is the OpenAPI spec (not hand-written types on each side); the mesh seam is the shared record module.

> **What not to do:** never inline an API request/response type in a handler or a console component. If a shape crosses the service<->console boundary, it is defined in the OpenAPI spec first (or, for mesh messages, as a record in `lattice-contract`), and both sides derive from it.

### Request-body schema convention

Every **request-body** schema (the `requestBody` an operation validates) is hardened two ways in the OpenAPI spec, and the Vert.x OpenAPI router enforces both at the edge:

- **`additionalProperties: false`** (including each nested object schema): reject unknown keys instead of silently accepting them, so a client/contract drift or a mass-assignment-shaped payload fails validation rather than passing quietly.
- **A `maxLength` on every user-facing free-text string, and `maxItems` on every array** (the body-size limit only caps the whole body, not a single field). The design docs lock no character limits, so the bounds are conservative defaults declared in the schema (named/reused where possible).

**Response** schemas stay lenient (no `additionalProperties: false`) for forward-compatibility - an added response field must not break an older client.

The same discipline applies to the **mesh envelope records**: a versioned envelope adds fields compatibly, and a consumer ignores unknown fields rather than rejecting the message (see [mesh envelope versioning](#when-you-are-doing-contract-work-you-own)).

---

## Both sides are test-first

A feature that spans the service and the status console writes failing tests **first, on each side of the contract**, before implementation:

1. **Contract (`platform/lattice-contract`):** the spec/envelope change lands first (if the contract is new or changing), so both sides have something to fail against.
2. **Service (`services/<name>`):** the route contract/integration test (Vert.x `WebClient`, real Elasticsearch via Testcontainers) asserting the response validates against the OpenAPI spec and the error envelope is correct - **the service side is the contract test that the route matches the spec.**
3. **Status console (`ui/status-console`):** the data-hook/component test against a mock typed from the **generated OpenAPI client** - **the console side is typed from the generated OpenAPI types.**

The contract is the integration seam, so neither side ships without its test. The full end-to-end across the running stack is the service integration suite. Per-layer test detail: [core_protocol.md](core_protocol.md#test-first-development-tdd).

> **Order matters:** author or change the **spec/envelope first**, watch both sides' tests fail against it, then implement service and console to green. Implementing one side before the contract exists is how drift sneaks in.

---

## The typed client

The status console's data layer is typed from the **generated OpenAPI TypeScript client** (generated from the spec in `lattice-contract`), and its single crossing point:

- attaches the session token to every request (auth mechanism **TBD - set when auth lands**),
- unwraps the `{data,error,meta}` envelope,
- validates the payload against the generated types and throws a typed error on failure.

**Resilience guarantees (keep them - they are tested).** The client enforces a per-request **timeout** (via `AbortController`), surfaced as a typed `NETWORK_ERROR` so a flaky connection cannot hang a request forever; and it **guards the body parse** so a non-JSON response (a proxy 502 HTML page, an empty body) becomes a typed error carrying the HTTP status, never a raw parse exception. **Retry stays owned by the data-fetching layer** (e.g. TanStack Query), not the client - do not add retry loops here.

Base URL and the mock toggle come from the console's config (`config.apiBaseUrl`, `config.useMocks`), sourced per environment from `VITE_*` vars. **No hardcoded endpoints or keys** anywhere in console code.

> **Gotcha:** the status console runs as its own container per cluster and talks to the services over the cluster network, not `localhost`. Point the base URL at the in-cluster service address per environment; only a same-host local dev run uses `localhost`.

---

## Mock-first cutover

Status-console work does not block on a live service:

- The console iterates against a **mock (MSW or a stub) that satisfies the OpenAPI contract** - typed from the generated client.
- `config.useMocks` (the `VITE_USE_MOCKS` flag) serves the contract stubs instead of the live service.
- **The mock -> live cutover must not touch the console** - same contract on both sides, so flipping the flag is the only change. Mock the endpoint from the spec until the service lands.

Because mocks are typed from the generated OpenAPI client, a contract change breaks the mock at compile time, not at runtime in front of a user.

> **What not to do:** do not hand-write an untyped JSON fixture. Every mock/fixture is typed from the generated OpenAPI client so it cannot drift from the contract.

---

## When you are doing contract work, you own

- **Single-writer of `platform/lattice-contract`** - the OpenAPI specs and the mesh envelope records. Defining or amending the spec/envelope **first**.
- **Both sides of any contract change** - the failing test on the service side (route matches spec) **and** the status-console side (client typed from the generated OpenAPI types) before implementation.
- Keeping the generated client and the mock layer contract-faithful (no untyped escapes).
- **Mesh envelope versioning for cross-cluster interop** - a peer cluster may run an older or newer envelope, so envelope changes stay backward/forward compatible (additive fields, ignore-unknown consumers); a breaking envelope change is a new envelope version, coordinated across clusters, never an in-place rewrite.
- Confirming the service response envelope matches [api_structure.md](../design/architecture/api_structure.md) (planned - written in the architecture design session, #2).
