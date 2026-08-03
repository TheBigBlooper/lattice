# REST API Structure

The versioned REST contract's shape: the response/error envelope every endpoint returns, the error taxonomy, pagination, how `/api/v<n>` versioning manifests, the operational health surface, request-body hardening, and the interactive docs. This is the schema-first source both the service (Vert.x OpenAPI router) and the status-console client derive from. Grounds ticket #17.

Related: [contract_protocol.md](../../protocol/contract_protocol.md) (the seam, the typed client, mock-first), [service_protocol.md](../../protocol/service_protocol.md) (thin routes, the data layer), [locked_decisions.md](../../reference/locked_decisions.md) (#17 OpenAPI, #19 strict bodies, #20 path versioning). The concrete OpenAPI file lives at `platform/lattice-contract/src/main/resources/openapi/v1.yaml`.

---

## Response envelope

Every REST response is a JSON envelope with a `meta` block and exactly one of `data` (success) or `error` (failure). `data` and `error` are **mutually exclusive**; `meta` is always present.

```json
// success
{ "data": { "id": "abc", "...": "..." },
  "meta": { "requestId": "9f1c...", "apiVersion": "v1" } }

// error
{ "error": { "code": "NOT_FOUND", "message": "Order abc was not found." },
  "meta": { "requestId": "9f1c...", "apiVersion": "v1" } }
```

**Responses stay lenient** - response schemas do **not** set `additionalProperties: false`, so an added response field never breaks an older client (forward-compatibility). Only request bodies are strict (below).

### `meta`

| Field        | Always | Meaning                                                                        |
|--------------|--------|--------------------------------------------------------------------------------|
| `requestId`  | yes    | A UUID for tracing / log correlation, generated per request (or echoed from an inbound correlation header). |
| `apiVersion` | yes    | The API major version serving the response, e.g. `v1`.                          |
| `pagination` | lists  | Present only on list responses (see [Pagination](#pagination)).                 |

---

## Errors

The `error` object is machine-branchable:

```json
"error": {
  "code": "VALIDATION_ERROR",
  "message": "Request body failed validation.",
  "details": [ { "field": "quantity", "issue": "must be >= 1" } ]
}
```

| Field     | Required | Meaning                                                                          |
|-----------|----------|----------------------------------------------------------------------------------|
| `code`    | yes      | A machine-readable value from the fixed taxonomy below. Clients branch on this.   |
| `message` | yes      | A human-readable summary (for logs / display). Never the branch key.             |
| `details` | no       | Field-level problems `[{field, issue}]`, populated for validation failures; omitted otherwise. |

### Error-code taxonomy

One code per HTTP status family; services reuse these rather than inventing per-endpoint codes. The set is extended additively.

| `code`             | HTTP | When                                                        |
|--------------------|------|-------------------------------------------------------------|
| `VALIDATION_ERROR` | 400  | Request failed contract validation (body/query/path).       |
| `UNAUTHORIZED`     | 401  | No bearer token, or one that failed validation against this baseline's realm. |
| `FORBIDDEN`        | 403  | The token is valid but its role does not permit the operation (a `viewer` writing). |
| `NOT_FOUND`        | 404  | The addressed resource does not exist.                      |
| `CONFLICT`         | 409  | The request conflicts with current state (e.g. a uniqueness or state-transition violation). |
| `RATE_LIMITED`     | 429  | Too many requests.                                          |
| `INTERNAL`         | 500  | Unhandled server error (never leaks internals in `message`). |
| `UNAVAILABLE`      | 503  | A dependency is down / the service is not ready.            |

A **validation failure** raised by the Vert.x OpenAPI router (unknown key, missing required field, a bound exceeded) maps to `VALIDATION_ERROR` (400) with `details` describing the offending fields.

---

## Pagination

List endpoints paginate with bounded offset params; `data` is an array of **skinny card DTOs** (never a list of full detail objects - see the service_protocol N+1 rule), and `meta.pagination` carries the counts.

```
GET /api/v1/things?page=0&size=20
```

| Query  | Default | Bound        |
|--------|---------|--------------|
| `page` | `0`     | `>= 0`       |
| `size` | `20`    | `1..100`     |

```json
"meta": { "pagination": { "page": 0, "size": 20, "total": 137, "totalPages": 7 } }
```

---

## Versioning

Path-based per locked #20: the version is in the path (`/api/v1/...`). One OpenAPI document per major version:

```
platform/lattice-contract/src/main/resources/openapi/
  v1.yaml        -> paths: /api/v1/baseline, /api/v1/...
  (future) v2.yaml -> /api/v2/...
```

A future `v2` is a **new spec file + new path prefix**, served alongside `v1` during migration - never an in-place rewrite of `v1`. Each version is independently browsable at `/docs`.

---

## Operational health surface (reconciled with #4)

`/health` and `/readiness` are **operational probe endpoints**, not business API: they are **unversioned** (served at the root, not under `/api/v1`) and **not** wrapped in the `{data,error,meta}` envelope - Kubernetes probes, the deploy smoke, and the status console read them directly.

| Endpoint     | Checks                                              | 200            | 503            |
|--------------|----------------------------------------------------|----------------|----------------|
| `/health`    | Liveness - the process is up (no dependency checks). | always once up | (process dead -> no response) |
| `/readiness` | Readiness - every registered dependency check passes. | all UP         | any check DOWN |

Fixed shape (both endpoints):

```json
{ "status": "UP",
  "checks": [ { "name": "elasticsearch", "status": "UP" } ] }
```

`status` is `UP` | `DOWN`; `checks` is one entry per registered check with a `name` and its `UP`/`DOWN` `status`. `/readiness` returns HTTP **200** when `status` is `UP` and **503** when `DOWN`, so an orchestrator pulls a not-ready pod out of rotation. `BaseVerticle` (`lattice-common`) emits exactly this shape (it maps the vertx-health-check result to it); this supersedes the provisional native shape #4 shipped.

The health paths are documented in the OpenAPI spec (an operational section) for discoverability, but their responses are the operational shape above, not the business envelope.

**They survive the per-service narrowing deliberately.** A service publishes only the operations it owns, so a path no service declares is filtered out of the served document - which silently removed both probes from every docs page, leaving the specification, this document and the page disagreeing. `BaseVerticle` mounts them for **every** service, so they are owned by all of them and are added back for the published document. They are **not** added to the router contract: they are mounted directly rather than through the OpenAPI router, so declaring them there would ask it for handlers that do not exist.

---

## Request-body hardening

Every request body is strict (locked #19), enforced at the edge by the Vert.x OpenAPI router:

- **`additionalProperties: false`** on every request body and every nested object - unknown keys are rejected (a client/contract drift or a mass-assignment-shaped payload fails validation instead of passing quietly).
- **Bounded fields** - reusable named string types cap length, and every array caps `maxItems`. Bounds are declared once in `components.schemas` and referenced, so they are consistent and tunable in one place.

```yaml
components:
  schemas:
    ShortString:  { type: string, maxLength: 100 }    # names, ids, short labels
    MediumString: { type: string, maxLength: 512 }    # descriptions, summaries
    LongText:     { type: string, maxLength: 4000 }   # free-text bodies
# every requestBody: additionalProperties: false; every array: maxItems <= 100
```

**Response** schemas stay lenient (no `additionalProperties: false`) for forward-compatibility.

---

## Sample operation (baseline)

The baseline spec ships one real, read-only operation that proves the router mounts and the success envelope validates:

```
GET /api/v1/baseline  -> 200
{ "data": { "clusterId": "hub-west", "region": "us-west",
            "baselineVersion": "0.1.0", "apiVersions": ["v1"] },
  "meta": { "requestId": "...", "apiVersion": "v1" } }
```

It returns this cluster's baseline identity (useful to the console and to peers), needs no service/Elasticsearch, and exercises the success path. The **request-body rejection path** (a 400 `VALIDATION_ERROR` from a strict body) is first exercised by the first `POST` endpoint a service adds; the baseline has no request body.

---

## Interactive docs

The interactive API docs are served **from the same OpenAPI spec** that drives router validation (one source, no second hand-written spec):

- **`/docs/json`** - the OpenAPI document. **Built** (#79), served by `BaseVerticle` so every service inherits it.
- **`/docs`** - Swagger UI. **Built** (#79), from assets **bundled in the image** and never fetched from a content delivery network: a baseline may run air-gapped (locked #55), where a page reaching out for its own scripts would render blank with nothing in the logs to explain it. It costs about **1.1 MB against a ~517 MB service image** (0.2%), and is not served at all when the docs are gated off.

  Two rewrites make the bundled page work here, both of which fail *quietly* if forgotten. The asset ships wired to Swagger's public demo API, so the initializer is repointed at this service's own document - left alone, `/docs` renders a perfectly working page for somebody else's service, which looks like success. And its relative asset links (`./swagger-ui.css`) only resolve from a path ending in a slash, which Vert.x normalises away, so they are rewritten to absolute paths rather than fixed with a redirect that would loop.

**Exposure:** enabled on **local + dev** (a testing surface); **gated OFF in prod** by `API_DOCS_ENABLED=false` (the chart's `apiDocs.enabled`). Unset means on, so a value nobody set never silently withdraws the contract in dev; prod turns it off explicitly, which a deploy can be checked for.

When gated off the route is **not mounted at all**, so the path 404s like any other address the service does not serve - a 403 would confirm the endpoint exists and invite someone to look for a way past it. The spec resource stays on the classpath either way, so router validation is unaffected.

The document sits **outside `/api/v1`** and needs no token: it describes the API rather than exposing it, every operation it lists stays guarded, and requiring a token to read the contract a client generator needs *before* it can authenticate would be circular.

---

## Decisions settled here

- Envelope: `data` XOR `error`, `meta` always; responses lenient.
- `meta`: `requestId` + `apiVersion`, `pagination` on lists.
- Error object `{code, message, details?}`; the fixed code -> HTTP taxonomy above.
- Offset pagination (`page`/`size`, bounded) with `meta.pagination`; lists return skinny DTOs.
- One OpenAPI doc per major version, `/api/v1` in the path.
- `/health` + `/readiness`: unversioned root, non-enveloped `{status, checks:[{name,status}]}`, 200/503.
- Strict request bodies: `additionalProperties:false` + reusable bounded types + `maxItems`.
- Sample op `GET /api/v1/baseline`; `/docs` Swagger on local+dev, gated off in prod.

The envelope + error taxonomy is promoted to a locked decision - see [locked_decisions.md](../../reference/locked_decisions.md). Auth is no longer deferred: every `/api/v1` operation carries the spec's `bearerAuth` requirement and the 401/403 codes are live, per [per_baseline_identity.md](../features/per_baseline_identity.md). Only the docs prod-gate signal is still open (deploy env naming).
