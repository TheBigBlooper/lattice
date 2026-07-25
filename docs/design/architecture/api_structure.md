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

- **`/docs`** - Swagger UI.
- **`/docs/json`** - the raw `v1.yaml` spec.

**Exposure:** enabled on **local + dev** (a testing surface); **gated OFF in prod** via a prod-environment signal (the exact env name is **TBD** - set when the deploy env naming lands). The spec resource stays available to the router even where the UI is gated, so validation still works. The docs page has no login of its own; endpoints stay auth-gated regardless (the **Authorize** button carries a bearer JSON Web Token from this baseline's realm).

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
