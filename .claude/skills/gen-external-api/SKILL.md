---
name: gen-external-api
description: Survey a repository - this one or any other local checkout, down to a single microservice - and generate a full External API document for it - every interface the system exposes to the outside world, each shape traced to source evidence, unknowns flagged rather than invented. Modeled on docs/replication/external_api.md.
---

# Generate an External API document

Survey a repository and produce the document that answers one question completely: **what does this system expose to the outside world, exactly?** Every transport, every endpoint, every message shape, every port - as served, not as remembered or assumed.

**Why this exists.** A system's real external surface is spread across specs, route code, config, and deploy manifests, and any one of them can disagree with the others. This skill reads all of them and writes the reconciled result as one document, in the structure proven by [docs/replication/external_api.md](../../../docs/replication/external_api.md) - the worked example this skill's output should resemble.

---

## Inputs

- **Target repo** - a path. Default: this repository. Any other local checkout works, including a repo that is a single microservice (the document is then that one service's surface, and says so). If given only a remote URL, clone it to a scratch location first and say where.
- **Output path** - confirm with the founder before writing. Defaults: for this repository, regenerate `docs/replication/external_api.md` in place; for an external repo, `<target>/docs/external_api.md` if writing into the target is wanted, otherwise a founder-named location.
- **Exclusions** - ask whether any part of the repo is demonstration or example content to exclude (this repository excludes its `orders`/`inventory` demo domain). Excluded surface is named in the document's scope section, not silently dropped.

---

## Step 1 - Inventory the interfaces before describing any

Sweep the repo for every externally reachable surface, breadth first. Look in this order, because each layer catches what the previous one hides:

1. **Interface definitions** - OpenAPI/Swagger, GraphQL schemas, protobuf/gRPC, AsyncAPI, WSDL. These are the richest source when present, but they are a claim, not proof of serving.
2. **Route registration in code** - router mounts, controller annotations, handler tables. This is what is actually served; diff it against the spec and record any drift as a finding.
3. **Message-broker surface** - topics, queues, exchanges, subscriptions; the envelope or record types published and consumed.
4. **Operational surface** - health and readiness probes, metrics endpoints, admin or docs pages, and which are authenticated.
5. **Deploy config** - exposed ports, Services and Ingresses, published ports in compose or charts, TLS termination. A port nothing publishes is not external; a listener the chart exposes is, even if no doc mentions it.
6. **Auth configuration** - what guards which paths, token types, roles or scopes, what is deliberately open.

Produce the interface inventory table first (the document's section 1) and confirm scope with the founder before writing the detail sections. A document that describes three interfaces when the deploy config exposes five is worse than none.

## Step 2 - Extract conventions once, then operations

Mirror the exemplar's shape: a **conventions** section stating what applies to every operation (base paths, versioning scheme, auth, response envelope, error taxonomy, pagination, request strictness), then per-operation sections that state only what is specific to each. Repeating a convention per operation is how the copies drift.

For each operation or message type record: method and path (or address and direction), auth requirement, request shape, response shape with every field's name, requiredness, type, and meaning (a field table, not prose), and error behaviour. For message wires record the envelope header, payload types, timing (heartbeats, time-to-live), and the compatibility rules for version skew.

## Step 3 - Evidence discipline

- **Every asserted shape traces to a file and line** (or a spec anchor). Cite it while surveying; keep citations in working notes, and carry them into the document itself only when the document stays inside the repo it describes (a self-contained export drops them, as the exemplar does).
- **Never document from memory or framework defaults.** If the code does not show the behaviour, run it or read the framework source pin, or flag it.
- **Flag, never fill.** An interface whose shape cannot be determined from source gets an explicit `UNKNOWN - <what is missing>` marker in the document, listed in a "gaps" subsection. A confabulated field is the one defect this document type cannot afford.
- **Record contradictions as findings.** Spec-versus-code drift, a documented endpoint that is not served, an undocumented port that is: these go in the document under a "findings" note and in the summary back to the founder.

## Step 4 - Write the document

Follow the exemplar's skeleton, adapted to what the target actually has (omit sections with no counterpart; do not pad):

1. Title + scope statement (what system, what is excluded and why, self-containment note if the doc is meant to travel).
2. Interface inventory table.
3. Conventions (one subsection per cross-cutting rule).
4. Per-interface detail sections with field tables.
5. Operational endpoints.
6. Message wire protocol(s).
7. An interoperability or conformance summary: the shortest list of checks that mean "a client or peer built against this document works".

Style: field tables over prose, exact enum values, exact defaults and bounds, and plain-English expansion of any acronym at first use. In this repository the em dash is banned; honor the target repo's own style rules when writing into it.

## Step 5 - Verify and hand off

- Cross-check the finished document against the inventory of Step 1: every inventoried interface appears or is listed as excluded.
- If the target has a runnable stack cheaply available, spot-check one or two shapes against a live response rather than trusting the read.
- Summarize for the founder: interface count, findings (drift, undocumented surface), and the flagged unknowns. The founder accepts the document; that is the close gate.

---

## What this is not

- **Not an internal design doc.** Internal module boundaries, private helpers, and database schemas stay out unless they cross the process boundary.
- **Not a requirements document.** What the system *shall* do is [/gen-srd](../gen-srd/SKILL.md)'s job; this documents what it *serves*. Run both for a full replication-grade pack.
- **Not a copy of the spec file.** Where an OpenAPI document exists, this reconciles it with what is actually served and states the conventions the spec leaves implicit; it never just restates it.
