# Lattice - Core Protocol

---

## Overview

This is the **shared core**: development standards that apply across the whole Lattice codebase - the TDD loop, the branching model, folder structure, naming, Java conventions, env vars, commits, the PR checklist, and docstrings.

Role-specific rules, examples, and gotchas live in their own protocols:

| Protocol                                     | Owns                                                                                      |
|----------------------------------------------|-------------------------------------------------------------------------------------------|
| [ui_protocol.md](ui_protocol.md)             | UI - the React status console (`ui/status-console`)                                       |
| [service_protocol.md](service_protocol.md)   | Vert.x services (`services/*`) + the Elasticsearch data layer (`platform/lattice-common`) |
| [contract_protocol.md](contract_protocol.md) | the versioned seam - OpenAPI REST specs + the `platform/lattice-contract` mesh envelopes  |
| [platform_protocol.md](platform_protocol.md) | Docker, K8s/Helm, the Artemis mesh, the local kind stack, deploy (`deploy/*`)             |

Issue mechanics, ticket selection, and how priority labels work live in [session_protocol.md](session_protocol.md#github-issues--priority-labels).

---

## Deliverable-first (know the outcome before you start)

Before any work on a ticket begins, its **deliverable must be known** - the concrete artifact or observable outcome that means "done". If the deliverable is unclear or ambiguous, **stop and ask a founder, or run a design session** (`/new-design`); never assume or guess the intent. (Enforcement: [session_protocol.md](session_protocol.md#enforcement-rules) Rule 14.)

Default deliverable by ticket type:

| Type          | Deliverable                                        | Close gate                                                                       |
|---------------|----------------------------------------------------|----------------------------------------------------------------------------------|
| Research      | a short written report (a doc)                     | founder reads and accepts                                                        |
| Docs          | requirement and definition brought into alignment  | founder accepts                                                                  |
| Refactor      | green tests, no behavior change (prefer a CI gate) | `./mvnw verify` green (local pre-push hook)                                      |
| Feature / Bug | the change running in the local cluster            | founder builds + runs it locally, confirms, opens PR; founder merges (verify green) |

The deliverable sets the test-first target below and the QA close gate in the [Branching Model](#branching-model-dev-integration) / [qa_protocol.md](qa_protocol.md).

---

## Test-First Development (TDD)

**All new behavior is written test-first.** This is a hard requirement (see [CLAUDE.md](../../CLAUDE.md) and the [Enforcement Rules](session_protocol.md#enforcement-rules)).

### The loop

1. **Red** - write the test(s) that capture the expected behavior and run them; confirm they fail for the right reason. Show the failing run before writing implementation.
2. **Green** - write the minimum code to make them pass.
3. **Refactor** - clean up with the tests staying green.

### What "the test first" means per layer

| Layer                                                 | The test written first                                                                                                                                                                                                                                                                                                                                                                                                                        |
|-------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Vert.x route / service (`services/*`)                 | A route/contract test (`vertx-junit5` + `WebClient` against the deployed verticle, asserting the response and error envelope) and/or a service unit test asserting the behavior                                                                                                                                                                                                                                                               |
| Shared contract (`platform/lattice-contract`)         | A unit test pinning the OpenAPI operation shape / mesh-envelope record (serialization + invariants)                                                                                                                                                                                                                                                                                                                                           |
| Status-console component / hook (`ui/status-console`) | A Vitest + React Testing Library render/interaction test, with the data layer mocked at the query/client seam against the OpenAPI-typed client                                                                                                                                                                                                                                                                                                |
| Integration (service + UI together)                   | **Both sides, test-first**: the service route contract/integration test (`WebClient`, real Elasticsearch + Artemis via Testcontainers) *and* the status-console data-hook/component test against the contract-typed mock. The shared OpenAPI spec + envelope records are the integration seam (contract testing), so a drift on either side fails a test. Full end-to-end across the running cluster is the Docker-compose integration suite. |

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

This is **mechanically enforced**, not a convention: add `@ExtendWith(FailOnUnexpectedLogExtension.class)` (from `lattice-common`'s test-jar, package `io.lattice.common.testing`) to a suite and any unaccounted ERROR/WARN fails that test.

- **Never let an unexpected log print.** When product code logs on a path a test exercises (an expected error branch, a best-effort cleanup, a safety valve), the test **declares it** via the injected `ExpectedLogs` parameter (`logs.expectWarn("dependency unavailable")`), which both pins the log line as behavior and accounts for it. Assert it, do not silently swallow it.
- **Expectations settle after the test body, not at the call.** Services log from Vert.x event-loop and worker threads, so a log can arrive after the line expecting it (a startup bootstrap failure is reported asynchronously). The extension therefore evaluates every expectation once the test has finished, briefly waiting for a late arrival - so these assertions do not become the timing-dependent flakes the [rule above](#timing-dependent--nondeterministic-behavior) warns about.
- **Quiet framework noise, never product levels.** Third-party chatter (the OpenAPI router warning about operations a single service does not implement) is silenced by raising that logger's level in `logback-test.xml`, so the events are never emitted. Do not lower a product log level to get a suite green.
- A test that has **already failed** is not log-checked - a failure usually logs errors on the way down, and reporting those on top would bury the real cause.

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
- **`dev -> main`** is a separate, deliberate, founder-only promotion once `dev` is stable, performed as a **fast-forward** (`git merge --ff-only dev`) rather than a pull request - so every commit on `main` is literally a commit on `dev` and the two can never diverge (locked #71). There is no `main -> dev` sync - nothing is authored directly on `main`, which is the precondition fast-forward needs.
- **Feature branch, named for feature + ticket.** Claude works on a `<git_issue_tag>-<issue>-<slug>` branch off `dev` (tag + ticket number + short feature slug, e.g. `lat-12-mesh-discovery`).
- **Order of operations (critical):**
  1. Claude writes the code **test-first** - the TDD red/green loop runs **locally** (a CI round-trip is far too slow to author against) - then runs the **local gate (`./mvnw verify -DskipITs`, whole reactor bar the container suites)**, enforced by the committed pre-push hook (`.githooks/pre-push`), and **pushes the branch and stops**. **When the change touches container/deploy surface (a Dockerfile, a K8s manifest, the base image, the mesh broker wiring) and the local Docker toolchain is available, Claude also brings the affected service(s) up on the local kind stack as verification** - a unit run cannot see an image build / wiring / runtime failure (see [platform_protocol.md](platform_protocol.md)). GitHub CI runs on every feature-branch push as well as on the `dev` PR and the `dev` -> `main` promotion PR, and is a required check on both merge points - so a red run blocks the merge rather than merely reporting (below). See [CI triggers + QA-iteration discipline](#ci-triggers--qa-iteration-discipline) below.
  2. **Founder builds the feature branch locally and runs it** (build the module + `deploy/k8s/mesh-clusters.sh` the affected service/cluster). Claude opens **no PR of any kind - not even a draft** - before this gate; it pushes and hands off.
  3. **Founder says it passes** -> Claude opens the PR into `dev` (label `qa-passed`, QA results in the PR body). Opening it starts CI, which is a **required check**: the merge is blocked until it is green.
  4. **Founder merges** into `dev`. Claude prepares the PR and **stops there** - it does not merge unless the founder explicitly tells it to merge *that* PR.
- **QA labels:** `needs-qa` while a change still needs local sign-off; `qa-passed` once a founder approves.
- **`dev -> main` is founder-only.** Only the founder (Nick) performs the promotion, later, once accumulated `dev` work is stable. **Claude never merges to `main`** and never commits app work straight to `main`; Claude prepares the change on `dev`, and the founder promotes it.
- A feature replacing an old one removes the old code on `dev` in the same change (Rule 10); the dead code leaves `main` when the founder promotes `dev`.

### CI triggers + QA-iteration discipline

**This repository is public, so GitHub Actions runs free on standard runners and there is no minutes budget to ration** (locked #74, correcting the cost premise of #28/#53/#68). CI is therefore the **authoritative full-reactor run**, and the local hook is the **fast authoring gate** rather than the primary one. GitHub Actions (`.github/workflows/ci.yml`) triggers on **`push`** to **`dev`** and to any **`lat-*`** feature branch, plus manual `workflow_dispatch`. There is deliberately **no `pull_request` trigger at all** (locked #76), and **none on `main`**. That covers the two moments that need a run: the final commit before a merge into `dev`, and the squashed commit that lands on `dev`.

`main` needs none, because promotion is a **fast-forward** (locked #71): main's new tip *is* the dev commit, same SHA, already carrying that commit's passing checks. Branch protection on `main` is satisfied by them, and a `main` trigger would re-run the identical reactor on the identical commit to learn nothing.

Both events firing on a branch with an open pull request ran the reactor twice for nearly the same thing. Required checks are evaluated against the pull request's head SHA and a push run produces its checks on exactly that SHA, so the gate is unchanged - a red run still blocks the merge. What is given up is the merge-preview run, so a semantic conflict with work that landed on `dev` meanwhile is caught by the `dev` push run just after the merge rather than just before it. A concurrency group keeps one run per branch.

The committed scope-aware pre-push hook (`.githooks/pre-push`; enable once per clone with `git config core.hooksPath .githooks`, documented in [DEVELOPMENT.md](../../DEVELOPMENT.md)) runs `./mvnw verify -DskipITs` rather than the full reactor. Measured warm: `test` 27s, `verify -DskipITs` 55s, `verify` 222s - the Testcontainers suites are 167s of that, three quarters of the run, while every static gate together costs 28s. So the containers are the only thing cut, and dropping further to `test` was declined: it would save 28 seconds and give up the em-dash ban, FindSecBugs and coverage. What the hook skips, CI runs in full on the same commits.

**A red run blocks the merge**, because the check is required in branch protection on both branches. That is the point of the change (locked #68, superseding the CI clause of #28/#53): the pre-push hook is enforcement by *discipline* - it is bypassable with `--no-verify`, it never runs on anyone else's machine, and with CI only at promotion the first forgotten run lands breakage on `dev` and is found late, after other work has been built on top of it.

**Division of labor - what runs where.** Four loops; do not collapse them:

- **TDD red/green - local, always.** Writing behavior test-first means running the test locally to watch it fail, then pass. This is authorship, not a checkpoint, and it *cannot* run through CI (a CI round-trip is minutes; the loop needs seconds).
- **Local verify before every push, via the pre-push hook - and the hook is scope-aware.** This is the fast authoring gate, not the last word - CI is: the push aborts if any gate it runs fails. It reads the paths in the commits being pushed and runs only what they can affect:

  | Changed | Runs |
  |-----------|--------|
  | Java, `pom.xml`, `.mvn/`, `config/` (the quality-gate configs Maven reads) | `./mvnw verify -DskipITs`, **whole reactor** - because a gate that guesses which module is affected is a gate that guesses wrong. The container suites are skipped here (167s of a 222s run) and re-run in full by CI on the same commits. |
  | `ui/status-console/`, `.semgrep/` | The console's own `verify` (Biome, types, Vitest, Semgrep, knip) - a separate command, because the console is not a Maven module. See [ui_protocol.md](ui_protocol.md#quality-gates-the-consoles-half-of-the-pre-push-run). |
  | Only docs, deploy config, or workflows | Nothing. These cannot break a build, and taxing the repo's most common commit is how `--no-verify` becomes a habit. |
  | Anything it cannot work out (no upstream, unreadable range) | **Everything.** A gate that guesses wrong should cost time, never a skipped check. |

  Two consequences worth stating plainly: `./mvnw verify` on its own does **not** cover the console, and a console change whose dependencies are not installed **fails** the hook rather than silently skipping it.
- **GitHub CI on every `dev` PR and on the promotion PR - the clean-room gate.** The same `./mvnw verify` on a runner that has none of your local state, and a required check on both branches, so a red run blocks the merge. It catches what the local hook structurally cannot: a push made with `--no-verify`, a machine configured differently, and a dependency that resolves locally because it was installed by hand. (CI-only gates - whole-tree coverage, dependency/security scans, the container-image scan - land here as they are added - TBD.)
- **Local QA - the human, system-facing gate** ([qa_protocol.md](qa_protocol.md)). Covers what automation can't (does the cluster actually come up, discover peers, serve the console); it does **not** replace the local verify - they test different surfaces (a compile error or contract drift is invisible to a running-cluster smoke, and vice versa).

**No PR before local QA - not even a draft.** Push the branch (the pre-push hook verifies it), hand off for local QA, and open the `dev` PR only once the founder approves. Now that a `dev` PR *does* run CI there is a new temptation - opening one early to let the runner check the work - and it is still wrong: it jumps the founder's QA gate and spends minutes re-running what the pre-push hook just ran. Never open a draft PR to "get CI".

**Iterating on an open PR.** Push freely. The batching habit and the bracketed skip-ci token existed to ration Actions minutes, and locked #74 removed that reason: runs are free, and the workflow's concurrency group cancels a superseded run automatically, so a rapid series of pushes settles into one run on the latest commit rather than a queue of them. Skipping CI on an intermediate commit is now the worse option - it buys nothing and leaves that commit unverified on a branch CI would otherwise have covered.

**Chunk large work as checkpoint commits on one branch (not many PRs).** A big or multi-part change lands as a sequence of **QA-checkpoint commits on a single feature branch** - push at each checkpoint (each push runs the local verify) so the founder can pull and QA in progress - culminating in **one** PR. Do **not** open a PR per chunk. As always the PR is founder-gated: **Claude never opens it until the founder explicitly says so.** "Get started" / "proceed" authorizes building and **pushing checkpoint commits**, never a PR.

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

Day-to-day local runs happen on **`dev`** via the three-cluster kind stack (`deploy/k8s/mesh-clusters.sh`).

#### Redeploy granularity - reach for the smallest one that works

Retiring docker-compose made the local loop slower, and the costs are **not uniform**. Reaching for a full rebuild when a `helm upgrade` would do is the single easiest way to waste ten minutes, and it is a reflex worth naming rather than trusting yourself to avoid:

| What changed                        | Path                                                    | Cost              |
|-------------------------------------|---------------------------------------------------------|-------------------|
| Chart or values only                | `mesh-clusters.sh deploy`                               | seconds           |
| One service's code                  | `mesh-clusters.sh redeploy <baseline> <service>`        | minutes           |
| Console code                        | `mesh-clusters.sh redeploy <baseline> status-console` - **per baseline**, because its API addresses are inlined at build time | minutes each |
| A host port mapping                 | `mesh-clusters.sh down` then `up` - the mapping is fixed when the kind cluster is created | ~10 min per baseline |

**A rebuilt image does not reach a running pod on its own.** Images are side-loaded with `imagePullPolicy: Never` and the tag does not change, so nothing tells Kubernetes anything is different. `redeploy` does the rollout restart for you; doing it by hand and forgetting that step is the modern form of the stale-image trap, where everything reports healthy and you are looking at the old build.

Cross-environment constraints to plan around:

| Concern                    | Gotcha                                                                                                                                                                                                                                                                                                                            |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| JVM base image             | Every service image builds on **one shared base image** (a pinned JDK 21 runtime); do not let a service drift to a different base or JDK. A mismatched base is a size + security regression and can shift default TLS/locale behavior. Pin the digest, bump it in one place.                                                      |
| Timezone / locale          | Container defaults differ from the dev host. Do all internal time in **UTC** and set an explicit locale; never rely on the host's default `TimeZone`/`Locale`. A test that passes on the dev machine and fails in CI/container is usually an implicit-locale or implicit-timezone assumption.                                     |
| Elasticsearch version skew | The **client library version must match the ES server** the cluster runs, and Testcontainers must pin the **same** ES image tag the deploy manifests use. A client/server skew fails at query time, not compile time - keep the version in one property and reference it from both the POM and the compose/Testcontainers config. |
| Mesh / Artemis             | The Artemis broker version + the envelope wire format are a **cross-cluster contract** (peer clusters may run a different service version); keep envelope changes backward-compatible so an old peer can still parse a new cluster's messages. Serialize envelope changes (single-writer of `lattice-contract`).                  |

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
│   ├── certs/                      ← issue-certs.sh: the certificate authority a customer runs
│   └── k8s/                        ← the Helm chart + mesh-clusters.sh, the only local stack
└── docs/ · .claude/                ← this scaffolding port
```

- **Java package root is `io.lattice`.** A service lives under `io.lattice.<name>`; shared runtime under `io.lattice.common`; the contract under `io.lattice.contract`. Do not create packages outside `io.lattice`.
- **Routes are thin.** A route handler parses/validates (against the OpenAPI operation), calls a service method, and maps the result to the response envelope. Business logic lives in `service/`, never in the handler. Vert.x web types (`RoutingContext`) do not leak into the service layer.
- **All REST request/response DTOs and all mesh envelopes live in `platform/lattice-contract`** - not inlined in a service or a route handler. That module is the single writer of the wire format both sides agree on.
- **Elasticsearch mappings live in `platform/lattice-common`** (`es/`) and are single-writer - one branch changes a mapping at a time.

### Naming Conventions

Full naming conventions for the project:

| Item                       | Convention                           | Example                                |
|----------------------------|--------------------------------------|----------------------------------------|
| Classes / records / enums  | PascalCase                           | `MeshEnvelope`, `NodeStatusVerticle`   |
| Verticles                  | PascalCase, `Verticle` suffix        | `DiscoveryVerticle`                    |
| Methods / fields / vars    | camelCase                            | `registerPeer`, `nodeId`               |
| Constants                  | SCREAMING_SNAKE_CASE                 | `MAX_MESH_PEERS`, `DEFAULT_API_PORT`   |
| Java packages              | lowercase, under `io.lattice`        | `io.lattice.common.mesh`               |
| Maven modules / artifactId | kebab-case                           | `lattice-common`, `lattice-contract`   |
| REST routes                | kebab-case, versioned                | `/api/v1/node-status`                  |
| OpenAPI operationId        | camelCase                            | `getNodeStatus`, `listPeers`           |
| Elasticsearch index        | kebab-case, snake_case fields        | index `node-status`; field `last_seen` |
| Test classes               | `*Test` (unit) / `*IT` (integration) | `MeshEnvelopeTest`, `DiscoveryIT`      |
| Status-console components  | PascalCase                           | `NodeStatusPanel.tsx`                  |
| Status-console hooks       | camelCase, `use` prefix              | `useNodeStatus.ts`                     |
| Environment variables      | SCREAMING_SNAKE_CASE                 | `ELASTICSEARCH_URL`, `ARTEMIS_URL`     |

### Java (21)

- **Prefer immutability.** Use **records** for DTOs, mesh envelopes, config, and value objects. A closed set of variants is a **sealed interface/class** with record implementations (pattern-match with `switch`), not an enum-plus-instanceof ladder.
- **Null-safety.** No method returns `null` for a collection - return an empty collection. For an optional single value, return `Optional<T>`; do not use `Optional` as a field or a parameter. Guard external/JSON input at the boundary and fail fast with a clear message.
- **`var` for local variables** where the right-hand side makes the type obvious; keep explicit types on method signatures and fields.
- **Explicit types on public API.** Public/exported method return types and parameters are explicit - no leaking an inferred anonymous type across a module boundary.
- **No raw generics, no unchecked suppressions without a comment.** A `@SuppressWarnings("unchecked")` carries an inline comment explaining why it is safe (the wire/deserialization case).
- **Concurrency stays on the Vert.x model.** Do not block the event loop; offload blocking work (Elasticsearch/Artemis clients that are not async) to `executeBlocking` or a worker verticle. No `Thread.sleep`, no unmanaged thread pools in handler code.
- **Text blocks** for multi-line strings (JSON fixtures, queries) instead of concatenation.
- All REST DTOs and mesh envelopes are defined in `platform/lattice-contract` - not inlined in a service (mirrors the type-placement rule).

### Logging

One logging engine across the whole system (the one-engine rule, #15): the **SLF4J** facade with a **Logback** binding, and Vert.x's own internal logging routed through SLF4J (`vertx.logger-delegate-factory-class-name=io.vertx.core.logging.SLF4JLogDelegateFactory`) so there is a single pipeline (locked #39). No second logging facade, no `System.out`/`System.err`, no `printStackTrace()`. A third-party library that logs through **commons-logging (JCL)** - notably the Elasticsearch REST client's Apache-HTTP transport - is bridged into SLF4J with **`jcl-over-slf4j`** (added in `lattice-common`, with the transitive `commons-logging` excluded from the Elasticsearch client) so it joins the one pipeline rather than printing to stderr.

- **One logger per class:** `private static final Logger LOG = LoggerFactory.getLogger(Xxx.class);`. The name is `LOG` (upper-case) - Checkstyle's `ConstantName` rule rejects a lower-case `static final` field.
- **Parameterized, never concatenated:** `LOG.error("create failed for order {}", id, ex)` - pass the exception as the trailing argument (not in the message string), and let SLF4J format the placeholders (no `"..." + value`).
- **Levels, used deliberately:**
  - `ERROR` - a caught/unexpected failure that fails the operation (always with the exception, so its stack trace is captured).
  - `WARN` - a recoverable or anomalous condition (a retry, a fallback, a dependency not ready). For an **expected, handled** condition (e.g. a classified dependency-down that returns a clean 503), log the **concise cause** (type + message via `String.valueOf(failure)`), not the full throwable - a stack trace for an anticipated outcome is noise, and the full trace belongs at `ERROR`.
  - `INFO` - lifecycle and significant business events (a verticle bound its port, an index was bootstrapped, an order was created).
  - `DEBUG` - developer detail (request/response specifics); off in prod by default.
- **Never log secrets or credentials** (tokens, passwords, connection strings) or unbounded user input.
- **Test-hygiene tie-in** ([Test hygiene](#test-hygiene)): an unexpected `error`/`warn` log fails the run. When product code logs on a path a test exercises (an expected error branch, a safety valve), the test **asserts the log fired** (a scoped Logback `ListAppender`), rather than letting it print. Framework-level noise is quieted in `logback-test.xml`, not by lowering product levels.

### Environment Variables

- Every new environment variable must be declared in the **Helm chart** (`deploy/k8s/chart`), with a one-line description, before the PR is opened. It is declared where the component that reads it is configured, which is where someone changing that component is already looking. `.env.example` used to carry this and was deleted: nothing read it, so it drifted into documentation that could disagree with the chart without anything failing (locked #77).
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
- [ ] All new env vars declared in the Helm chart (`deploy/k8s/chart`) with descriptions
- [ ] Commit messages follow conventional commits format with no authorship trailer or footer
- [ ] If an Elasticsearch mapping changed: the spec-driven integration test is in the same change and green
- [ ] If a REST operation or mesh envelope changed: the OpenAPI spec / envelope record in `platform/lattice-contract` is updated and both sides test green
- [ ] At least one test covers the happy path for any new feature or endpoint
- [ ] **Coverage is whole-tree:** a new source file ships with a test, or a justified exclusion is added (JaCoCo enforces line 90% / branch 80% per module; a module that runs no tests is not measured, so the "ships with a test" rule is what covers it)
- [ ] **No unjustified CI-runtime regression:** if the change materially increases total CI wall-clock (a new heavy step, a per-run download replacing a cached build), it is flagged and justified in the PR (see [CI runtime is a cost budget](#ci-runtime-is-a-cost-budget))

#### CI gates - all blocking

`./mvnw verify` runs across every module and **every gate blocks** - locally on any push that touches Java or build config (the pre-push hook aborts the push on failure) and again on a clean runner for the `dev` -> `main` promotion PR (not green until all pass). Per the [division of labor](#ci-triggers--qa-iteration-discipline) the full reactor runs locally as the day-to-day gate; the promotion PR re-runs the same set (plus the CI-only gates) on a pristine environment. The set:

- **Compile + unit + integration** (`./mvnw verify`) - all modules; Testcontainers integration suites run here.
- **Formatting** (Spotless / Palantir Java Format) - `spotless:check` fails on any drift; `./mvnw spotless:apply` fixes.
- **Style** (Checkstyle) - Javadoc on public API, naming, and the em-dash ban (a build-failing `RegexpMultiline`). Config: `config/checkstyle/checkstyle.xml`. Formatting is Spotless's job, so Checkstyle carries no formatting rules.
- **Coverage** (JaCoCo) - line 90% / branch 80% per module, excluding generated clients + bootstrap.
- **Dependency hygiene** (maven-enforcer) - Java 21 pinned, dependency convergence, no duplicate dependencies (the one-engine rule, #15).
- **Unused private members** (PMD, two rules: `UnusedPrivateField` + `UnusedPrivateMethod`) - the one check the other Java gates structurally cannot do. Runs over main **and test** sources. Config: `config/pmd/pmd-ruleset.xml`. See [locked_decisions.md](../reference/locked_decisions.md) #60 for why this is a second static-analysis engine and why its ruleset stays at two rules.
- **Static bug + security analysis** (SpotBugs + FindSecBugs), over **main and test** sources. Test scaffolding stands up brokers, containers, and credentials, so leaving it unscanned left the security gate blind to the code most likely to hold a stray secret. Three documented exclusions, all in `config/spotbugs/spotbugs-exclude.xml`, each with a stated removal condition: `EI_EXPOSE_REP2` in the `*.service` / `*.routes` layers (a dependency-injection false positive - a service/handler storing its injected collaborator; borderline under `effort=Max` so it flickers in the full reactor), plus `HARD_CODE_PASSWORD` and `UNENCRYPTED_SERVER_SOCKET` scoped **by class name to `*IT` integration tests only**, so neither can mask the same finding in shipped code.
- **Status console** (Biome, `tsc --noEmit`, Vitest + coverage, Semgrep, knip) - run by the console's own `verify` script, which the pre-push hook invokes after the Maven run. The console is not a Maven module, so these do not ride in `./mvnw verify`; the hook is what makes one push mean one verdict. Detail: [ui_protocol.md](ui_protocol.md#quality-gates-the-consoles-half-of-the-pre-push-run).
- **Supply-chain** (OSV-Scanner) - **CI-only** on the `dev` -> `main` PR, as its own job. It scans the console's `pnpm-lock.yaml` **and** the **CycloneDX SBOM** that `./mvnw verify` emits (`target/bom.json`, via `cyclonedx-maven-plugin`'s aggregate goal), querying osv.dev for advisories against those components. **Scanning the SBOM rather than the `pom.xml` files is deliberate:** pointed at the poms, the scanner resolves remotely via deps.dev, which cannot see `io.lattice`'s own unpublished `SNAPSHOT` modules - that failure cascades and filters out every third-party transitive, reporting a clean "0 vulnerabilities" while scanning nothing. Maven is the only resolver that knows the reactor's own modules, so it produces the graph. Known gap: test-scoped dependencies are excluded from the aggregate SBOM; they do not ship in the runtime images, so they are not production attack surface. Suppressions live in `osv-scanner.toml` at the repo root, each with a documented reason and a removal condition. A container-image scan lands with the deploy pipeline - TBD.

All but the supply-chain scan run locally (the pre-push hook enforces them on every push that can affect a build). See [locked_decisions.md](../reference/locked_decisions.md) #28, #40, #51, #52, and #53.

#### CI runtime is a cost budget

CI wall-clock is billed, so keep it lean. A change that **materially increases total CI runtime** - a step that doubles or triples a job, a dependency or tooling setup that forces a per-run download instead of a cached build, a new heavy gate - is a **cost regression**: flag and justify it in the PR, do not land it silently. If CI time suddenly balloons (doubles or triples), treat it as a defect - find the cause and revert or fix it, rather than quietly accepting the new baseline. Keep the Maven cache warm (cache `~/.m2`), pin the Testcontainers images so they are pulled from a warm layer cache, and prefer the option that keeps build/setup cached and fast.

---

## Code Commenting and Docstrings

- All public classes, records, and methods should have detailed **Javadoc** comments with appropriate tags.
- **Every test method (`@Test`, and the parameterized/repeated variants) carries a Javadoc** stating *what behavior it verifies* - the specific contract or edge case under test, not a restatement of the method name. This makes a failing test self-describing and documents the behavior the suite pins. The test class also carries a Javadoc describing the unit under test.
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
