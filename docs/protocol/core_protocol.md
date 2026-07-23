# Lattice - Core Protocol

---

## Overview

This is the **shared core**: development standards that apply across the whole Lattice codebase - the TDD loop, the branching model, folder structure, naming, Java conventions, env vars, commits, the PR checklist, and docstrings.

Role-specific rules, examples, and gotchas live in their own protocols:

| Protocol                                          | Owns                                                                    |
|---------------------------------------------------|-------------------------------------------------------------------------|
| [ui_protocol.md](ui_protocol.md)                  | UI - the React status console (`ui/status-console`)                    |
| [service_protocol.md](service_protocol.md)        | Vert.x services (`services/*`) + the Elasticsearch data layer (`platform/lattice-common`) |
| [contract_protocol.md](contract_protocol.md)      | the versioned seam - OpenAPI REST specs + the `platform/lattice-contract` mesh envelopes |
| [platform_protocol.md](platform_protocol.md)      | Docker, K8s/Helm, the Artemis mesh, docker-compose, deploy (`deploy/*`) |

Issue mechanics, ticket selection, and how priority labels work live in [session_protocol.md](session_protocol.md#github-issues--priority-labels).

---

## Deliverable-first (know the outcome before you start)

Before any work on a ticket begins, its **deliverable must be known** - the concrete artifact or observable outcome that means "done". If the deliverable is unclear or ambiguous, **stop and ask a founder, or run a design session** (`/new-design`); never assume or guess the intent. (Enforcement: [session_protocol.md](session_protocol.md#enforcement-rules) Rule 14.)

Default deliverable by ticket type:

| Type          | Deliverable                                        | Close gate                                                                 |
|---------------|----------------------------------------------------|----------------------------------------------------------------------------|
| Research      | a short written report (a doc)                     | founder reads and accepts                                                  |
| Docs          | requirement and definition brought into alignment  | founder accepts                                                            |
| Refactor      | green tests, no behavior change (prefer a CI gate) | CI green                                                                   |
| Feature / Bug | the change running in the local cluster            | founder builds + runs it locally, confirms, opens PR; founder merges if CI green |

The deliverable sets the test-first target below and the QA close gate in the [Branching Model](#branching-model-dev-integration) / [qa_protocol.md](qa_protocol.md).

---

## Test-First Development (TDD)

**All new behavior is written test-first.** This is a hard requirement (see [CLAUDE.md](../../CLAUDE.md) and the [Enforcement Rules](session_protocol.md#enforcement-rules)).

### The loop

1. **Red** - write the test(s) that capture the expected behavior and run them; confirm they fail for the right reason. Show the failing run before writing implementation.
2. **Green** - write the minimum code to make them pass.
3. **Refactor** - clean up with the tests staying green.

### What "the test first" means per layer

| Layer                                              | The test written first                                                                                                                                                                                                                                                                                                                                    |
|----------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Vert.x route / service (`services/*`)              | A route/contract test (`vertx-junit5` + `WebClient` against the deployed verticle, asserting the response and error envelope) and/or a service unit test asserting the behavior                                                                                                                                                                             |
| Shared contract (`platform/lattice-contract`)      | A unit test pinning the OpenAPI operation shape / mesh-envelope record (serialization + invariants)                                                                                                                                                                                                                                                        |
| Status-console component / hook (`ui/status-console`) | A Vitest + React Testing Library render/interaction test, with the data layer mocked at the query/client seam against the OpenAPI-typed client                                                                                                                                                                                                          |
| Integration (service + UI together)                | **Both sides, test-first**: the service route contract/integration test (`WebClient`, real Elasticsearch + Artemis via Testcontainers) *and* the status-console data-hook/component test against the contract-typed mock. The shared OpenAPI spec + envelope records are the integration seam (contract testing), so a drift on either side fails a test. Full end-to-end across the running cluster is the Docker-compose integration suite. |

### Documented exception

**Elasticsearch mapping / index work** (`platform/lattice-common`) cannot be queried until the index exists with its mapping, so a pure red-first test is impossible.

There, write the **spec-driven integration tests in the same change** (assert the spec's invariants against a live Elasticsearch, provisioned via Testcontainers) and drive to green. State explicitly whenever this exception applies. No other exceptions without a stated reason.

### Timing-dependent / nondeterministic behavior

Do **not** write a unit test that asserts on a single snapshot of a race-y, timing-dependent intermediate state - a one-frame UI flash, an in-flight async write before its settle, a mesh message mid-propagation, an animation or debounce midpoint. Such a test is flaky by construction and a poor use of effort. Instead:

- Assert a **timing-independent invariant** - the final settled state, or the union of states observed across the whole transition - not a single mid-transition frame. For eventual-consistency surfaces (Elasticsearch refresh, mesh propagation), poll to the settled state (`await`/`awaitility`) rather than snapshotting a single instant.
- Or fix it at the render/logic level and verify by **local QA** ([qa_protocol.md](qa_protocol.md)), not a flaky unit test.

If a test cannot be made deterministic in a couple of attempts, **stop and report it as a design problem** rather than thrashing (spy tests, isolation runs, temp logging). An ill-posed test is a signal to rescope, not to grind.

---

## Test hygiene

Green is necessary but not sufficient: a passing suite must also be **clean and durable**. These rules keep a regression failing locally instead of accumulating and surfacing weeks later on `dev`.

### Log output on an unexpected path fails the test

A test that emits an **unexpected error/warn log fails the run**. Post-teardown leaks, unmocked network / container calls, and swallowed exceptions all surface as noisy log output; left unchecked they pile up and can mask a real failure.

- **Never let an unexpected log print.** When product code logs on a path a test exercises (an expected error branch, a best-effort cleanup, a safety valve), the test must **assert that it logged** (capture the appender / verify the mocked logger) rather than merely tolerating it. Assert it, do not silently swallow it.

### Await async settle - no leaked verticles or clients

An async operation (a deployed verticle, an Elasticsearch write + refresh, a mesh publish) must be awaited so it settles inside the test. Use `vertx-junit5`'s `VertxTestContext` (`testContext.completeNow()` only after all assertions), and `awaitility` for eventual-consistency polling. Never trigger an async operation and let it settle after the test body returns.

### Mock at the seam, never reach uncontrolled I/O

A unit test must never hit an uncontrolled network or a shared live cluster. Mock at the **data-layer seam** (the repository / the typed client) against the contract; provision real infrastructure only in **integration** tests, via **Testcontainers** (Elasticsearch + Artemis), so each run gets a clean, isolated container. A test that lets a real connection fail is a masked bug, not coverage.

### Keep the suites leak-free and deterministic

Reuse Testcontainers instances across a suite where safe (singleton container pattern) but reset state per test so the parallel run is deterministic - a shared index document or a residual mesh subscription bleeding across tests makes the suite order-dependent. Close every `Vertx`, `WebClient`, and Elasticsearch client in teardown; a leaked client is an open handle that eventually flakes the run.

---

## Branching Model (`dev` integration)

The cluster (the "real system") is built on a long-lived **`dev`** integration branch; **`main`** stays the stable trunk. Local build-and-run QA notes live in [qa_protocol.md](qa_protocol.md). (Enforcement: [session_protocol.md](session_protocol.md#enforcement-rules) Rule 11.)

- **`main`** - the stable, **founder-only** trunk. Claude never commits or merges here (Rule 11).
- **`dev`** - the long-lived integration branch. **All work lands here** - services *and* docs, protocol, Elasticsearch mappings, and the REST/mesh contract - via `<type>-<issue>-<slug>` feature branches off `dev`.
- **`dev -> main`** is a separate, deliberate, founder-only promotion once `dev` is stable (below). There is no `main -> dev` sync - nothing is authored directly on `main`.
- **Feature branch, named for feature + ticket.** Claude works on a `<git_issue_tag>-<issue>-<slug>` branch off `dev` (tag + ticket number + short feature slug, e.g. `lat-12-mesh-discovery`).
- **Order of operations (critical):**
  1. Claude writes the code **test-first** - the TDD red/green loop runs **locally** (a CI round-trip is far too slow to author against) - then runs the **fast local gate (`./mvnw verify`)** on the affected module(s), and **pushes the branch and stops**. **When the change touches container/deploy surface (a Dockerfile, a K8s manifest, the base image, the mesh broker wiring) and the local Docker toolchain is available, Claude also brings the affected service(s) up under docker-compose as verification** - a unit run cannot see an image build / wiring / runtime failure (see [platform_protocol.md](platform_protocol.md)). CI does **not** run on a feature-branch push; the heavier / CI-only gates run on the PR (step 3, after local QA). See [CI triggers + QA-iteration discipline](#ci-triggers--qa-iteration-discipline) below.
  2. **Founder builds the feature branch locally and runs it** (build the module + `docker compose up` the affected service/cluster). Claude opens **no PR of any kind - not even a draft** - before this gate; it pushes and hands off.
  3. **Founder says it passes** -> Claude opens the PR into `dev` (label `qa-passed`, QA results in the PR body); opening the PR runs CI.
  4. **PR CI green** -> the **founder merges** into `dev`. Claude prepares the PR and **stops there** - it does not merge unless the founder explicitly tells it to merge *that* PR. CI red -> Claude fixes and repeats.
- **QA labels:** `needs-qa` while a change still needs local sign-off; `qa-passed` once a founder approves.
- **`dev -> main` is founder-only.** Only the founder (Nick) performs the promotion, later, once accumulated `dev` work is stable. **Claude never merges to `main`** and never commits app work straight to `main`; Claude prepares the change on `dev`, and the founder promotes it.
- A feature replacing an old one removes the old code on `dev` in the same change (Rule 10); the dead code leaves `main` when the founder promotes `dev`.

### CI triggers + QA-iteration discipline

CI (`.github/workflows/ci.yml`) is the **pre-merge gate**, not a per-push gate on feature branches. It triggers on `push` to `main`/`dev` only and on `pull_request` into `main`/`dev` - so a feature branch runs CI **through its PR** (once per push to the open PR), never as a duplicate push+PR pair.

**Division of labor - what runs where (Policy B).** Three loops, three jobs; do not collapse them:

- **TDD red/green - local, always.** Writing behavior test-first means running the test locally to watch it fail, then pass. This is authorship, not a checkpoint, and it *cannot* run through CI (a CI round-trip is minutes; the loop needs seconds).
- **Fast local gate before pushing - `./mvnw verify` on the affected module(s).** This catches the large majority of red PRs for near-zero cost. Heavier / CI-only gates - whole-tree coverage, dependency/security scans, the container-image scan (as they land - TBD) - are **not** re-run locally; CI owns them.
- **CI on the PR - the comprehensive merge gate.** The full `./mvnw verify` across every module, run on the integrated merge commit. This is what makes a PR mergeable.
- **Local QA - the human, system-facing gate** ([qa_protocol.md](qa_protocol.md)). Covers what automation can't (does the cluster actually come up, discover peers, serve the console); it does **not** replace the automated gates - they test different surfaces (a compile error or contract drift is invisible to a running-cluster smoke, and vice versa).

**No PR before local QA - not even a draft.** A bare feature-branch push runs no CI, and that is intended: the comprehensive CI gate runs when the **founder-approved** PR opens (step 3, *after* local QA), never before. Claude must **not** open a draft PR to get CI early - that jumps the founder's QA gate. Push the branch, hand off for local QA, and open the PR only once the founder approves. If a CI-only gate (coverage, a security/container scan) is a genuine mid-flight risk, run that specific check **locally** rather than opening a PR.

**QA-iteration discipline.** Local QA happens **on the pushed branch, with no PR open** - so those fix pushes trigger no CI; just push the fixes (batch them for tidiness). The skip-ci escape hatch applies only **after** the PR is open (post-`qa-passed`): if the founder requests a further change on the open PR, put the literal skip-ci token (bracketed, in the commit message) on the iteration commits to avoid CI churn, batch the findings, then do a final push **without** that token as the pre-merge gate and merge on green.

**Iterating while a PR is open - do not push per edit (any iteration, not just QA).** Every push to a branch with an **open PR** re-runs the full CI suite. So once a PR exists, the same batching rule covers **all** in-flight iteration - founder-requested design changes and in-chat review tweaks included, not only local-QA fixes. **Make the edits, commit locally, and hold** until the founder confirms the iteration is final; then push **once**. If a push mid-iteration is genuinely needed, skip-ci-tag those commits (above). A CI run is a deliberate pre-merge act, not a side effect of saving an iteration.

**Chunk large work as checkpoint commits on one branch (not many PRs).** A big or multi-part change lands as a sequence of **QA-checkpoint commits on a single feature branch** - push at each checkpoint so the founder can pull and QA in progress - culminating in **one** PR. Do **not** open a PR per chunk. As always the PR is founder-gated: **Claude never opens it until the founder explicitly says so** (an open PR burns CI minutes the founder did not authorize). "Get started" / "proceed" authorizes building and **pushing checkpoint commits**, never a PR.

(Cross-ref: [qa_protocol.md](qa_protocol.md#per-pr-qa-checklist-convention).)

### Concurrent branches (multi-agent)

Both founders develop in agent mode and often work at the same time - two `claude` instances, each possibly delegating to `service` / `ui` / `contract` / `platform` subagents. Parallel branches are safe **only when they never touch the same files.** (Narrative onboarding: [team_workflow.md](team_workflow.md).)

- **One worktree per branch.** Each concurrent `claude` runs in its own `git worktree` so two sessions never share a checkout.
- **Partition by disjoint file sets**, not just by surface - two service tickets can still collide on one shared verticle in `lattice-common`. Agree on file ownership before starting parallel work.
- **The seam is single-writer.** At most one in-flight branch may touch any of these at a time; land the change first, then fan out dependents:
  - `platform/lattice-contract` (the OpenAPI specs + mesh envelope records) - two `contract` agents must never run against it at once.
  - Elasticsearch mappings in `platform/lattice-common` - serialize mapping changes so the index schema does not fork across branches.
  - `docs/changelog.md` - the top-prepend collides on every concurrent edit; it is written once, at end of day (see [session_protocol.md](session_protocol.md#enforcement-rules)).
- **Claim the ticket (the `in-progress` label + assignee) before touching code** so the other founder can see who owns which files ([session_protocol.md](session_protocol.md#github-issues--priority-labels)).
- **Safe pairing:** one founder on a service ticket (`services/<a>`, its ES mappings) + the other on a disjoint status-console panel = zero overlap.
- **Supervise background agents; abort runaway loops.** When you launch a background or subagent, watch its runtime. An agent that repeatedly tries-fails with no convergence (or whose child test/build/container processes run far past normal) is looping - **stop it early** rather than let it burn tens of minutes. And do not hand an agent an ill-posed task (e.g. a deterministic test for a nondeterministic state, see [Timing-dependent behavior](#timing-dependent--nondeterministic-behavior)); scope its work to what is deterministically verifiable.

### Cross-platform / container gotchas

Day-to-day local runs happen on **`dev`** via docker-compose. Cross-environment constraints to plan around:

| Concern            | Gotcha                                                                                                                                                                                                                                                                             |
|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| JVM base image     | Every service image builds on **one shared base image** (a pinned JDK 21 runtime); do not let a service drift to a different base or JDK. A mismatched base is a size + security regression and can shift default TLS/locale behavior. Pin the digest, bump it in one place.        |
| Timezone / locale  | Container defaults differ from the dev host. Do all internal time in **UTC** and set an explicit locale; never rely on the host's default `TimeZone`/`Locale`. A test that passes on the dev machine and fails in CI/container is usually an implicit-locale or implicit-timezone assumption. |
| Elasticsearch version skew | The **client library version must match the ES server** the cluster runs, and Testcontainers must pin the **same** ES image tag the deploy manifests use. A client/server skew fails at query time, not compile time - keep the version in one property and reference it from both the POM and the compose/Testcontainers config. |
| Mesh / Artemis     | The Artemis broker version + the envelope wire format are a **cross-cluster contract** (peer clusters may run a different service version); keep envelope changes backward-compatible so an old peer can still parse a new cluster's messages. Serialize envelope changes (single-writer of `lattice-contract`). |

Consolidated platform/mesh operational notes: [platform_protocol.md](platform_protocol.md); external-service setup + env vars: [integrations.md](../reference/integrations.md).

---

## Common Conventions (all surfaces)

### Dependencies (one engine per job)

Before adding a dependency, check whether the stack already solves the problem, and prefer **one library per job across the whole system** (services + common + UI) over a second library that does the same thing. Being the popular or "standard" pick for a layer is **not** a reason to add it; cohesion is the goal. The most elegant solution that keeps the stack working nicely together wins over the locally-standard one-off - this is the dependency face of the Ethos = simplicity tiebreaker.

- **Look first.** Search the POMs and run `./mvnw dependency:tree` for a library that already does the job, even in another module. Vert.x ships a broad toolbox (web, web-client, config, health) - reach for the Vert.x-native option before a third-party one that overlaps it.
- **Extend over add.** Reuse or adapt the in-stack library (config, a thin wrapper) before introducing a parallel one. A new dependency must do something **no** in-stack library can, not merely be the popular choice for this layer.
- **A duplicate engine is a smell.** Two libraries doing one job (two JSON codecs, two HTTP clients, two logging facades) fragment the build, multiply the transitive tree, and breed the version conflicts that cost a session. When you find one, converge on a single engine - do not patch around the conflict with an exclusion pile.
- **Manage versions centrally.** Shared versions live in the parent `pom.xml` (`<dependencyManagement>` / properties); a module declares the dependency without a version. Do not pin a version in one module that diverges from the parent.
- **When genuinely unsure** which library fits (nothing in-stack covers it), stop and ask the founder (Enforcement Rule 5) rather than guessing.

### Folder Structure

The repo is a **Maven multi-module** project. The parent `pom.xml` is a packaging-`pom` aggregator; each service and platform module is a child module.

```
lattice/
├── pom.xml                         ← parent aggregator POM (packaging pom); central dependency + plugin management
├── services/                       ← one Maven module per Vert.x microservice
│   └── <name>/
│       ├── pom.xml                 ← depends on lattice-common + lattice-contract
│       ├── src/main/java/io/lattice/<name>/
│       │   ├── MainVerticle.java   ← thin bootstrap: config + deploy the service verticle(s)
│       │   ├── <Name>Verticle.java ← the service verticle; mounts the router
│       │   ├── routes/             ← route handlers (thin shells; validated against the OpenAPI spec)
│       │   └── service/            ← business logic (no Vert.x web types leak in here)
│       ├── src/test/java/io/lattice/<name>/   ← vertx-junit5 route/service tests + Testcontainers integration
│       └── Dockerfile              ← builds on the shared JDK 21 base image
├── platform/
│   ├── lattice-contract/           ← the versioned contract. Single-writer.
│   │   ├── src/main/resources/openapi/   ← OpenAPI 3.1 specs (drive router validation + the console client)
│   │   └── src/main/java/io/lattice/contract/   ← REST DTOs + Artemis mesh envelope records (sealed/records)
│   └── lattice-common/             ← shared runtime
│       └── src/main/java/io/lattice/common/
│           ├── BaseVerticle.java   ← config load, health/readiness, graceful shutdown
│           ├── config/             ← config loader
│           ├── es/                 ← Elasticsearch client + repositories (single-writer of mappings)
│           └── mesh/               ← Artemis mesh discovery + publish/subscribe client
├── ui/
│   └── status-console/             ← React (Vite + TypeScript); its own Dockerfile + container
├── deploy/
│   ├── docker/                     ← base image(s) + local docker-compose (ES + Artemis + services)
│   └── k8s/                        ← K8s manifests / Helm charts (never hand-edit generated output)
└── docs/ · .claude/                ← this scaffolding port
```

- **Java package root is `io.lattice`.** A service lives under `io.lattice.<name>`; shared runtime under `io.lattice.common`; the contract under `io.lattice.contract`. Do not create packages outside `io.lattice`.
- **Routes are thin.** A route handler parses/validates (against the OpenAPI operation), calls a service method, and maps the result to the response envelope. Business logic lives in `service/`, never in the handler. Vert.x web types (`RoutingContext`) do not leak into the service layer.
- **All REST request/response DTOs and all mesh envelopes live in `platform/lattice-contract`** - not inlined in a service or a route handler. That module is the single writer of the wire format both sides agree on.
- **Elasticsearch mappings live in `platform/lattice-common`** (`es/`) and are single-writer - one branch changes a mapping at a time.

### Naming Conventions

Full naming conventions for the project:

| Item                     | Convention                    | Example                                |
|--------------------------|-------------------------------|----------------------------------------|
| Classes / records / enums| PascalCase                    | `MeshEnvelope`, `NodeStatusVerticle`   |
| Verticles                | PascalCase, `Verticle` suffix | `DiscoveryVerticle`                    |
| Methods / fields / vars  | camelCase                     | `registerPeer`, `nodeId`               |
| Constants                | SCREAMING_SNAKE_CASE          | `MAX_MESH_PEERS`, `DEFAULT_API_PORT`   |
| Java packages            | lowercase, under `io.lattice` | `io.lattice.common.mesh`               |
| Maven modules / artifactId| kebab-case                   | `lattice-common`, `lattice-contract`   |
| REST routes              | kebab-case, versioned         | `/api/v1/node-status`                  |
| OpenAPI operationId      | camelCase                     | `getNodeStatus`, `listPeers`           |
| Elasticsearch index      | kebab-case, snake_case fields | index `node-status`; field `last_seen` |
| Test classes             | `*Test` (unit) / `*IT` (integration) | `MeshEnvelopeTest`, `DiscoveryIT` |
| Status-console components | PascalCase                   | `NodeStatusPanel.tsx`                   |
| Status-console hooks     | camelCase, `use` prefix       | `useNodeStatus.ts`                     |
| Environment variables    | SCREAMING_SNAKE_CASE          | `ELASTICSEARCH_URL`, `ARTEMIS_URL`     |

### Java (21)

- **Prefer immutability.** Use **records** for DTOs, mesh envelopes, config, and value objects. A closed set of variants is a **sealed interface/class** with record implementations (pattern-match with `switch`), not an enum-plus-instanceof ladder.
- **Null-safety.** No method returns `null` for a collection - return an empty collection. For an optional single value, return `Optional<T>`; do not use `Optional` as a field or a parameter. Guard external/JSON input at the boundary and fail fast with a clear message.
- **`var` for local variables** where the right-hand side makes the type obvious; keep explicit types on method signatures and fields.
- **Explicit types on public API.** Public/exported method return types and parameters are explicit - no leaking an inferred anonymous type across a module boundary.
- **No raw generics, no unchecked suppressions without a comment.** A `@SuppressWarnings("unchecked")` carries an inline comment explaining why it is safe (the wire/deserialization case).
- **Concurrency stays on the Vert.x model.** Do not block the event loop; offload blocking work (Elasticsearch/Artemis clients that are not async) to `executeBlocking` or a worker verticle. No `Thread.sleep`, no unmanaged thread pools in handler code.
- **Text blocks** for multi-line strings (JSON fixtures, queries) instead of concatenation.
- All REST DTOs and mesh envelopes are defined in `platform/lattice-contract` - not inlined in a service (mirrors the type-placement rule).

### Environment Variables

- Every new environment variable must be added to `.env.example` with a one-line description before the PR is opened.
- No hardcoded secrets anywhere in the codebase - no exceptions.
- Variable names must follow SCREAMING_SNAKE_CASE.
- Config is read through the shared config loader (`lattice-common`), not by scattering `System.getenv` calls through the code.
- `.env` is never committed.

### Commit Messages - Conventional Commits

Format: `<type>(<scope>): <short description>`

| Type       | When to use                              |
|------------|------------------------------------------|
| `feat`     | New feature or behaviour                 |
| `fix`      | Bug fix                                  |
| `chore`    | Tooling, config, dependency updates      |
| `docs`     | Documentation only                       |
| `refactor` | Code change with no behaviour change     |
| `test`     | Adding or updating tests                 |
| `style`    | Formatting, whitespace - no logic change |

Scope is optional but recommended when the change is isolated (e.g., `feat(discovery): add peer-registration endpoint`).

Breaking changes: append `!` after the type/scope and include a `BREAKING CHANGE:` footer.

**No trailer or footer.** Do not append any authorship trailer or footer to a commit message - no `Co-authored-by`, no "Generated with Claude Code", none. The commit message is the Conventional Commits subject + body and nothing more.

### PR Checklist

Before opening a PR, all of the following must be completed.

- [ ] **Self-audit:** re-read the role protocol(s) for the work you did (service / ui / contract / platform) and audit the diff against them - confirm the change conforms before opening the PR. This is the moment to catch a deviation, not in review.
- [ ] A GitHub Issue exists and is referenced in the PR body via `Closes #N`
- [ ] **Tests were written first** (failing test before implementation) per [Test-First Development](#test-first-development-tdd) - or the documented Elasticsearch-mapping exception is stated
- [ ] `./mvnw verify` passes with zero failures
- [ ] No raw generics or unexplained `@SuppressWarnings`; no method returning `null` for a collection
- [ ] All new env vars added to `.env.example` with descriptions
- [ ] Commit messages follow conventional commits format with no authorship trailer or footer
- [ ] If an Elasticsearch mapping changed: the spec-driven integration test is in the same change and green
- [ ] If a REST operation or mesh envelope changed: the OpenAPI spec / envelope record in `platform/lattice-contract` is updated and both sides test green
- [ ] At least one test covers the happy path for any new feature or endpoint
- [ ] **Coverage is whole-tree:** a new source file ships with a test, or a justified exclusion is added (coverage floors are CI-enforced as they land - TBD)
- [ ] **No unjustified CI-runtime regression:** if the change materially increases total CI wall-clock (a new heavy step, a per-run download replacing a cached build), it is flagged and justified in the PR (see [CI runtime is a cost budget](#ci-runtime-is-a-cost-budget))

#### CI gates - all blocking

CI runs `./mvnw verify` across every module, and **every gate blocks the merge**. A PR is not green until all pass. Per the [division of labor](#ci-triggers--qa-iteration-discipline) you run `./mvnw verify` on the affected module(s) locally before pushing and let CI own the full multi-module run plus the CI-only gates on the PR; the set the PR must clear:

- **Compile + unit + integration** (`./mvnw verify`) - all modules; Testcontainers integration suites run here.
- **Coverage** floors per module, whole-tree (as the coverage plugin lands - TBD).
- **Lint / static analysis** (Spotless / Checkstyle / an ES-mapping-drift check as they land - TBD).
- **Secrets / supply-chain / container** - secret scan, dependency audit, and a container-image scan on the built service images (as they land - TBD).

(Several gates are marked TBD: they are wired up as the corresponding tooling is added; `./mvnw verify` is the one that exists from day one.)

#### CI runtime is a cost budget

CI wall-clock is billed, so keep it lean. A change that **materially increases total CI runtime** - a step that doubles or triples a job, a dependency or tooling setup that forces a per-run download instead of a cached build, a new heavy gate - is a **cost regression**: flag and justify it in the PR, do not land it silently. If CI time suddenly balloons (doubles or triples), treat it as a defect - find the cause and revert or fix it, rather than quietly accepting the new baseline. Keep the Maven cache warm (cache `~/.m2`), pin the Testcontainers images so they are pulled from a warm layer cache, and prefer the option that keeps build/setup cached and fast.

---

## Code Commenting and Docstrings

- All public classes, records, and methods should have detailed **Javadoc** comments with appropriate tags.
- Generated comments should include elements like those found in the example provided below.
- When writing single line and multiline comments, do NOT reference GitHub issues or numbers or link them in source code files.

**Example Javadoc**
```java
/**
 * Registers a peer cluster discovered over the Artemis mesh, so this cluster can
 * route interop messages to it.
 *
 * <p>Called by the {@link DiscoveryVerticle} when a peer announces itself. If the peer
 * is already known, its last-seen timestamp is refreshed rather than a duplicate created.
 *
 * @param peer the announcing peer's identity + endpoint, parsed from a {@link MeshEnvelope}.
 * @return the registered {@link Peer}, whether newly added or refreshed.
 * @throws MeshException if the peer's envelope fails validation against the contract.
 * @see DiscoveryVerticle
 * @see MeshEnvelope
 */
public Peer registerPeer(PeerAnnouncement peer) {
    // ...
}
```
