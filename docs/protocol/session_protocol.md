# Lattice - Session Protocol

---

## Session Workflow

Work is **continuous** - a loop of tickets, not bounded sessions with heavyweight start/end rituals. "Session" is a light label for a stretch of work; the real units are the **ticket** (the pick-to-merge loop in [team_workflow.md](team_workflow.md#the-loop-one-ticket-pick-to-merge)) and the **day** (the changelog wrap). At any point you produce the **live status view** (Status Check + Pick, below) to see where things stand, pick the next ticket, and go. The only end-of-day ceremony is the wrap (changelog + Up Next). The three types below are **work modes**, not rituals.

### Session Types (work modes)

**Tracked Session:**

- Issue Requirement
   - A GitHub Issue must exist before any branch is created - no exceptions.
   - Create a new issue if it does not already exist.
- Issue Number Confirmation Requirement
   - Ask developer for the issue number before proceeding.
- Branch Confirmation Requirement
   - Prompt developer for branch to open the session against.
- Branch Naming Requirement
   - Create a new branch following the convention `<git_issue_tag>-<git_issue_number>-<slug>` (before any changes) - tag + ticket number + a short kebab-case feature slug, e.g. `lat-12-mesh-discovery`. The slug makes the branch self-describing at a glance; the ticket number keeps it linked. The tag for this repo is `lat`.
   - App work branches off `dev` (Branch & Merge Workflow); see the Branching Model in [core_protocol.md](core_protocol.md#branching-model-dev-integration).
- Changelog Requirement
   - The changelog is a **per-day, per-person** record, written at **end of day, not every session**. At session wrap, Claude asks: **done for the day, or just ending this session?** Only "done for the day" writes the changelog; "just ending the session" leaves the work captured in the branch commits + PR.
   - **Founder-gated - never written as part of building a ticket.** Claude does **not** add a changelog entry inside a feature commit or feature PR, and does **not** write one on its own initiative. It is written only at the end-of-day wrap, and only after the founder confirms "done for the day". A "proceed" / "complete the ticket" instruction never includes the changelog.
   - **One entry per person per day**, keyed by the **date** + **person**. The entry is a **date line** `YYYY-MM-DD HH:MM TZ` (author's local time + timezone abbreviation; the time marks the last update that day, no session numbers - branch-merge-safe), then the **person** on the next line, a `## Title` heading, a **bracketed category tag** (`[feature]` | `[enhancement]` | `[bug]` | `[internal]`), then **one bullet per ticket/PR**, and a `Tickets:` footer with issue links. The format rules live in the `## Changelog format` section at the bottom of `changelog.md`; the full template + end-of-day mechanics are in the [log-work skill](../../.claude/skills/log-work/SKILL.md).
   - **Multiple sessions in one day fold into that day's single entry** - gathered from the person's landed PRs since their last entry, appended as additional bullets; they never each prepend a new block. Keep **max 20 entries**; drop the oldest. `docs/changelog.md` is **single-writer**: every entry edits the top of the file, so it is written once at end of day on a single wrap-up PR, never on more than one concurrent branch.
- Branch Confirmation Requirement
   - Commit all changes, prompt developer for branch to open PR against.

**Sandbox Session:**

- Issue Requirement
   - No GitHub Issue is required to be selected.
- Branch Requirement
   - No specific branch, no PR.
- Changelog Requirement
   - Exploration only - optional one-line changelog note at user's request.
- Session Promotion Requirement
   - Promote to Tracked at any point: operator says "promote to Tracked" -> Claude creates branch, continues as Tracked.

**Design Session:**

When initiating a design session (e.g., data model, mesh envelope model, API structure), adhere to the following guidelines.

1. Questionnaire Requirement
   - When creating a new design markdown file (docs/design/\*), Claude must conduct a structured series of yes/no questions to resolve all relevant design decisions and edge cases.
   - No design document may be produced until the full questionnaire is completed and all answers are confirmed by the operator.
   - All questions must be asked one-by-one, not as a giant list.
   - When a question is asked, Claude should also indicate the number of questions remaining.
2. Sequential Answer Requirement
   - Developer must answer each question sequentially.
3. Clarification Requirement
   - Claude identifies unresolved branches or contradictions.
4. Question Completion Requirement
   - Only after all questions are answered does Claude generate the corresponding design markdown file in docs/design/\*.
5. Content Verification Requirement
   - The resulting generated file must reflect all decisions made during the questionnaire.

---

## Status Check + Pick (formerly "session start")

Run this to start a stretch of work **or any time you want to know where things stand** - it is a live view, not a one-time ritual. It produces the **status dashboard** (phase + live %, the Todo pick-list for the current phase, who is In Progress on what, what is concurrent-safe vs must-serialize, and what is unclear or missing) and lands on a picked ticket. The dashboard's mechanics are the single-source-of-truth of the [session-start skill](../../.claude/skills/session-start/SKILL.md); this checklist is the policy.

1. Sync Dev (Guarded)
   - Before reading context or creating any branch, fast-forward `dev` (the integration branch features are cut from, and the GitHub default - **not** `main`, the founder-only trunk), `fetch --prune`, and delete local branches whose PRs have merged.
   - Detect merged branches via `gh pr` state rather than `git branch --merged`, which reports only what is an ancestor of the current branch and so misses anything merged by a method that rewrites commits; protect both `main` and `dev` from the prune.
   - Skip when resuming an in-progress feature branch or the working tree is dirty.
2. Session Type
   - Developer declares session type (or Claude asks).
3. Fetch Open Issues
   - List open issues via `gh issue list`, reading each issue's **priority label** (`P0` / `P1` / `P2`) and its **in-progress** state (the `in-progress` label + assignee) - see [GitHub Issues + Priority Labels](#github-issues--priority-labels). There is no project board; the label is the only priority signal.
   - Surface the picks from the **complete Todo list** for the current phase (no top-N cap), ranked by priority label (`P0` highest, then `P1`, then `P2`), alongside what recently landed and what is in flight. The exact render - the snapshot layout, the fixed emoji standard, the scope counts, and the ticket/PR link rule - is the [session-start skill](../../.claude/skills/session-start/SKILL.md)'s Step 3, the single source of truth for it; do not restate it here.
   - Developer picks the ticket(s) before any branch is created or work begins.
4. Read Relevant Markdown
   - Claude determines and reads relevant markdown files under docs/design/ folder related to the selected issue(s) (i.e. services/_index.md, ui/_index.md, etc.).
5. Markdown File Confirmation
   - Confirm with developer which markdown files are determined to be relevant prior to proceeding.

---

## Ticket Lifecycle (claim -> ready -> QA -> PR)

The full path a ticket travels, in one place. [Status Check + Pick](#status-check--pick-formerly-session-start) is the entry and [Closing Work](#closing-work-per-ticket-close--end-of-day-wrap) is the exit; this is the spine between them.

1. **Pick + claim.** Choose a ticket, confirm its **deliverable is known** (Enforcement Rule 14; if not, stop and ask or run `/new-design`), then apply the **`in-progress`** label and **self-assign** *before* touching code (Enforcement Rule 13).
2. **Branch + build.** Cut a `<git_issue_tag>-<issue>-<slug>` branch off `dev`, build **test-first**, keep edits surgical, and remove any replaced code in the same change (Enforcement Rule 10).
3. **Ready for QA.** *Only* when the deliverable is met, **all gates pass locally** (`./mvnw verify`), and the branch is pushed: comment **"Ready to test"** and apply **`needs-qa`**. "Ready" means a founder can verify it locally (build the module + run the cluster, no extra setup). **Never ask for QA or a PR before this bar is met.**
4. **Founder local-QA.** The founder verifies locally: build the affected module(s) and run the service / cluster (`docker compose up`); pass -> `qa-passed`; fail -> back to step 2 with notes.
5. **PR + merge.** Only after `qa-passed` + founder confirmation, open the PR; the **founder merges to `dev`** (Claude never merges - Enforcement Rule 11). Then run the [per-ticket close](#closing-work-per-ticket-close--end-of-day-wrap).

## Minimal Ticket Template

Every ticket is **short but complete** - a unit of work, not a design doc. Long context lives in the linked spec, not the ticket. The minimum:

- **Title** - one specific line (`<type>: <what>`).
- **Why** - 1-2 lines of context. **Link the source**: the `docs/design/*` spec it derives from, the parent ticket, and - **if it is a bug** - the defect (what is broken + where, or the linked bug report).
- **Scope** - a few bullets, the minimum detail for someone to pick it up cold. Not an essay.
- **Deliverable** - end every ticket with the concrete artifact or observable outcome that means "done" (Enforcement Rule 14) **and how it is verified**. E.g. "Deliverable: a cluster discovers a peer over the Artemis mesh from a cold start; verified by the mesh-discovery integration test + local run." A bug ticket ends "Fixes `<defect>`; verified by `<repro/test>` + QA."

New tickets (drafted via [`/new-issue`](../../.claude/skills/new-issue/SKILL.md)) follow this shape; the priority-label + claim/assign moves are Enforcement Rule 13.

---

## Closing Work (per-ticket close + end-of-day wrap)

There is no per-session ceremony. Two things close work: a lightweight **per-ticket close** every time a ticket lands (normal loop behavior, no skill), and a single **end-of-day wrap** fired only when the founder runs [`/log-work`](../../.claude/skills/log-work/SKILL.md) with the final PR of the day. The wrap mechanics are the single-source-of-truth of the [log-work skill](../../.claude/skills/log-work/SKILL.md); this section is the policy for both.

**Per-ticket close (every ticket, in the loop):**

1. Summary - what was decided/built; which issues were opened and which completed; each file changed with a one-line description.
2. Same-change doc updates - any decision affecting a guiding document (`locked_decisions.md`, the protocols, `glossary.md`, `docs/design/*`) is updated in the **same change**, never deferred (Enforcement Rule 6).
3. Unresolved -> issue - any item not fully resolved becomes a GitHub Issue before the ticket closes; no open-ended prose left anywhere, reference the issue number only.

**End-of-day wrap (only when "done for the day"):**

4. Changelog - write or merge into today's single entry for this person (one per person per day), gathered from the day's merged PRs. **Founder-gated** - never written as part of building a ticket.
5. Changelog cleanup - if [changelog.md](../changelog.md) exceeds 20 entries, remove the oldest, keeping 20.
6. Up Next - an explicit list of GitHub Issue numbers in priority-label order, pulled live from `gh issue list`.
7. Roadmap marker - if a phase completed today, move the "<- you are here" marker (and advance the README badge via `/set-phase`).

---

## Working with Agents + Skills

The role **agents** (`.claude/agents/`: `service`, `ui`, `contract`, `platform`) and the scaffolding **skills** (`.claude/skills/`: `/new-service`, `/new-endpoint`, `/new-panel`, `/new-contract`, `/index-change`) are how the work inside a Tracked session gets executed. The full human-facing operating manual - the pick-to-merge loop and the multi-agent concurrency model - is **[team_workflow.md](team_workflow.md)**; this section holds only the definition the rest of the docs lean on, plus the operational gotchas.

### What they are (and are not)

- **Agents are in-session subagents, not separate terminals.** You run one `claude` in the repo. When it delegates to a role agent, that agent runs as a scoped child with its own context and reports back into the same session - no terminal-per-role, no agent that pulls its own ticket or opens its own PR. Two founders each running their own `claude` (one worktree/branch each) is the **separate, parallel** setup, covered in [team_workflow.md - Concurrency](team_workflow.md#concurrency-two-people-many-agents) and [core_protocol.md - Concurrent branches](core_protocol.md#concurrent-branches-multi-agent).
- **One agent owns one surface.** `service` = the Vert.x microservices (`services/*`) + the Elasticsearch data layer in `platform/lattice-common`; `ui` = the React status console (`ui/status-console`); `contract` = the versioned seam (the OpenAPI REST specs + the `platform/lattice-contract` mesh-envelope module); `platform` = Docker images, K8s/Helm manifests, the Artemis mesh (broker + discovery), local docker-compose, and deploy (`deploy/*`). Each agent defers to its protocol (`*_protocol.md`) rather than restating it.
- **Skills scaffold the test-first change.** A skill is the ordered checklist for adding a unit of work the right way: failing test first, tokens or contract honored, green. `/new-contract` runs before `/new-endpoint` or `/new-panel` whenever the change needs new shared shape.

The pick-to-merge loop (branch off `dev` -> grounded spec -> agent runs test-first to green -> orchestrator verifies independently -> push + `needs-qa` -> founder local-QA -> PR into `dev`) lives in [team_workflow.md - The loop](team_workflow.md#the-loop-one-ticket-pick-to-merge).

### Gotchas (real failure modes - a seed list; adapt as Lattice hits its own)

1. **Most gates run locally; a few are CI-only.** The Java quality gates - Spotless, Checkstyle (incl. the em-dash ban), JaCoCo (line 90% / branch 80%), maven-enforcer, SpotBugs, PMD (unused private fields + methods) - **do** run in the local `./mvnw verify` (so the pre-push hook enforces them every push); run `./mvnw spotless:apply` to auto-fix formatting. What is **not** local: the OSV-Scanner supply-chain scan (CI-only on `dev` -> `main`, its own job, not a Maven plugin) and the status-console "no hardcoded colors" scan (a UI-side check, not part of the Maven toolchain). An agent told to "run lint and confirm no hardcoded colors" cannot execute that UI scan locally; verify tokens by inspection and let CI run it. Two JaCoCo notes: a module that runs **no tests** is not measured (the "every source ships a test" rule covers it), and the container-image scan is still TBD (lands with deploy).
2. **An integration-suite startup failure is not always your change.** A Testcontainers-backed integration run can fail at container startup (Docker not ready, an Elasticsearch or Artemis container slow to become healthy) while the assertions themselves are fine. Read the actual failure - a container/startup error vs a test assertion - and re-run before concluding your change broke the suite.
3. **An explicit task instruction outranks a generic hook nudge.** If a `PostToolUse` hook fires mid-run with a generic prompt that contradicts the task's explicit instruction, follow the task. Precedence when a hook fires mid-run: explicit task instruction > generic hook prompt.
4. **A leaf primitive has no smoke surface until a consumer mounts it.** The status-console smoke and local QA verify a change *observable in the running console*. An unmounted primitive renders only in the unit test until a panel mounts it, so its smoke and QA steps are correctly **N/A** and are first exercised by the consuming panel. Do not add throwaway mounting code just to force a smoke run - that violates the no-dead-code rule.

---

## GitHub Issues + Priority Labels

This is a **personal repo**: there is **no** GitHub Project board. Tickets are plain GitHub Issues; their state and priority live on **labels**, which Claude reads and writes via `gh`.

**State is tracked by label + assignee, not a board column:**

- **Todo** - an open issue with no `in-progress` label.
- **In Progress** - an open issue carrying the **`in-progress`** label **and** an assignee.
- **Done** - the issue is closed (a merged PR with `Closes #N` closes it automatically; the repo default branch is `dev`, so this fires on a `dev` merge).

**Claim before you code (manual In Progress + self-assign).** When you (or an agent) start a ticket, do two things before touching code: (1) add the `in-progress` label (`gh issue edit <n> --add-label in-progress`), and (2) assign yourself (`gh issue edit <n> --add-assignee <your-login>`). Remove the label and unassign if you stop without landing. With two founders running agents concurrently, the `in-progress` label is the live "who is touching what" signal (Enforcement Rule 13), and the assignee is the "who owns it" signal: `gh issue list --label in-progress` shows how many in-progress tickets each person holds, keeping parallel branches off the same files.

### Priority - the `P0` / `P1` / `P2` labels (rank by label)

Priority is a **single label per issue**, applied at creation by [`/new-issue`](../../.claude/skills/new-issue/SKILL.md) and read at [Status Check + Pick](#status-check--pick-formerly-session-start):

- **`P0`** - highest; do first.
- **`P1`** - normal.
- **`P2`** - lowest / nice-to-have.

Read priority (rank the Todo list by label, `P0` -> `P1` -> `P2`):
```bash
gh issue list --state open --label P0 --json number,title,labels
gh issue list --state open --json number,title,labels,assignees   # then sort by the P-label
```
Set priority (apply exactly one `P*` label; swap it if it changes):
```bash
gh issue edit <number> --add-label P0 --remove-label P1
```

> Use the [`/new-issue`](../../.claude/skills/new-issue/SKILL.md) skill (which applies the label correctly) rather than hand-rolling, so every issue lands with exactly one priority label.

---

## File Inventory

**Single-source rule.** Each protocol/topic is defined in exactly ONE leaf doc; the indexes (root [README.md](../../README.md), [docs/README.md](../README.md), [CLAUDE.md](../../CLAUDE.md)) only **link**, never restate. [docs/README.md](../README.md) is the human **hub** ("find anything in one or two clicks") and holds the canonical Skills + Agents index (CLAUDE.md links to it, does not keep a copy).

| File                               | Purpose                                                                                                                                                                                 | Update rule                                                                                                |
|------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| .claude/skills/                    | Project-level Claude Code skills. Auto-loaded on `/skill-name`.                                                                                                                         | Add/update when session skills change. Version-controlled.                                                 |
| .claude/skills/session-start/      | Read context, rank open issues by `P0`/`P1`/`P2` label, surface picks, prompt session type.                                                                                             | Update when the status-dashboard render changes. Version-controlled.                                       |
| .claude/skills/log-work/           | End-of-day changelog entry gathered from the day's merged PRs; the always-present Heads-up block.                                                                                       | Update when the changelog/wrap workflow changes. Version-controlled.                                       |
| .claude/skills/new-design/         | The mandatory one-by-one design questionnaire before any `docs/design/*` file.                                                                                                          | Update when the design workflow changes. Version-controlled.                                               |
| .claude/skills/new-issue/          | `gh issue create` + apply exactly one `P0`/`P1`/`P2` priority label (no board, no org field).                                                                                           | Update when the issue-creation flow changes. Version-controlled.                                           |
| .claude/skills/promote/            | Sandbox -> Tracked: verify/create the issue, create the `lat-<n>-<slug>` branch, continue Tracked.                                                                                      | Update when the promotion flow changes. Version-controlled.                                                |
| .claude/skills/release-notes/      | General-audience "What's New" notes distilled from the changelog -> `docs/releases.md`.                                                                                                 | Update when the release-notes flow changes. Version-controlled.                                            |
| .claude/skills/set-phase/          | Advance the build/governance phase: update the source doc + the README badge together.                                                                                                  | Update when phase mechanics change. Version-controlled.                                                    |
| .claude/skills/sprint-plan/        | Name a sprint, write the TLDR, run per-feature design sessions, gate status-console UI on mockups, cut label-ranked tickets under a master tracking ticket, publish the summary.        | Update when the sprint-planning workflow changes. Version-controlled.                                      |
| .claude/skills/new-contract/       | Add/change an OpenAPI operation or a `lattice-contract` mesh envelope; wire across service + status console; test-first both sides.                                                     | Update when the contract/wiring flow changes. Version-controlled.                                          |
| .claude/skills/new-endpoint/       | Scaffold a Vert.x route under `/api/v1` with its OpenAPI operation + a failing contract/integration test first.                                                                         | Update when the endpoint-scaffold flow changes. Version-controlled.                                        |
| .claude/skills/new-panel/          | Scaffold a React status-console component/panel with a failing test first, theme tokens, smoke check.                                                                                   | Update when the panel-scaffold flow changes. Version-controlled.                                           |
| .claude/skills/index-change/       | Change an Elasticsearch mapping/index + the spec-driven integration test in the same change (the documented TDD exception).                                                             | Update when the index-change flow changes. Version-controlled.                                             |
| .claude/skills/new-service/        | Scaffold a new Vert.x microservice: Maven module under `services/`, a BaseVerticle subclass, Dockerfile, K8s manifest stub, baseline/mesh registration, and a failing smoke test first. | Update when the service-scaffold flow changes. Version-controlled.                                         |
| .claude/agents/service.md          | The `service` role agent: Vert.x services (`services/*`) + the Elasticsearch data layer in `lattice-common`.                                                                            | Update when the service surface or its rules change. Version-controlled.                                   |
| .claude/agents/ui.md               | The `ui` role agent: the React status console (`ui/status-console`).                                                                                                                    | Update when the UI surface or its rules change. Version-controlled.                                        |
| .claude/agents/contract.md         | The `contract` role agent: the OpenAPI REST specs + the `platform/lattice-contract` envelope module.                                                                                    | Update when the contract surface or its rules change. Version-controlled.                                  |
| .claude/agents/platform.md         | The `platform` role agent: Docker, K8s/Helm, the Artemis mesh, docker-compose, deploy (`deploy/*`).                                                                                     | Update when the platform surface or its rules change. Version-controlled.                                  |
| CLAUDE.md                          | Auto-loaded every session. Behavior rules.                                                                                                                                              | Surgical edits only. Never rewrite in full.                                                                |
| README.md                          | The project brand; what Lattice is.                                                                                                                                                     | Update when project scope changes.                                                                         |
| docs/README.md                     | The **hub**: navigation map of the whole docs tree + the canonical Skills + Agents index + the onboarding "Start here" path.                                                            | Update when a doc/folder/skill/agent is added, moved, or removed. Links only - never restate leaf content. |
| docs/protocol/session_protocol.md  | This file. Session rules and conventions.                                                                                                                                               | Update when workflow changes. Version-stamp changes.                                                       |
| docs/protocol/core_protocol.md     | Shared dev core: TDD loop, branching, folder structure, naming, Java conventions, env vars, commits, docstrings.                                                                        | Update when a cross-cutting convention changes.                                                            |
| docs/protocol/team_workflow.md     | Human onboarding narrative: how we build with Claude + multi-agent (links into the protocols).                                                                                          | Update when the team workflow, agent model, or concurrency rules change.                                   |
| docs/protocol/ui_protocol.md       | UI rules: the React status console (`ui/status-console`); theming, component standards, testing, smoke check.                                                                           | Update when a UI convention, panel rule, or token rule changes.                                            |
| docs/protocol/service_protocol.md  | Service rules: Vert.x routes/verticles, Elasticsearch data access (`lattice-common`), service testing.                                                                                  | Update when a service or data-layer convention changes.                                                    |
| docs/protocol/contract_protocol.md | The versioned seam: OpenAPI REST specs + `lattice-contract` mesh envelopes; both-sides-test-first, generated client, mock->live cutover.                                                | Update when the contract, client, or cutover approach changes.                                             |
| docs/protocol/qa_protocol.md       | Local QA workflow: build + run the cluster (`docker compose up`), per-PR checklist convention.                                                                                          | Update when the QA workflow changes.                                                                       |
| docs/protocol/deploy_protocol.md   | Build/deploy/parity: Docker image build + push, K8s/Helm apply, environment parity, the baseline promotion runbook.                                                                     | Update when a build, deploy, or environment-parity rule changes.                                           |
| docs/protocol/platform_protocol.md | Platform rules: base image(s), K8s/Helm manifests, the Artemis mesh (broker + discovery), local docker-compose, cross-cluster interop.                                                  | Update when a platform, mesh, or container convention changes.                                             |
| docs/reference/locked_decisions.md | Current decisions, locked items, open questions, project status.                                                                                                                        | Update every Tracked session end.                                                                          |
| docs/reference/glossary.md         | Shared vocabulary. Definitions only.                                                                                                                                                    | Add when new term introduced or renamed.                                                                   |
| docs/reference/integrations.md     | External service runbook - setup steps and env vars per service (Elasticsearch, Artemis, registries).                                                                                   | Update when a new external service is added or changed.                                                    |
| docs/reference/example_domain.md   | The illustrative use case the services model (regional fulfillment network). Teaching example, not binding.                                                                             | Update when a design session changes the example slice or names.                                           |
| docs/changelog.md                  | Recent work log. 20-entry max.                                                                                                                                                          | Insert at top via str_replace. Never rewrite.                                                              |
| docs/releases.md                   | User-facing "What's New" per release, newest first. General-audience, derived from the changelog.                                                                                       | Insert at top via `/release-notes` at release-build time. Never rewrite.                                   |
| docs/governance/governance.md      | Living governance doc - philosophy, non-goals, build phases.                                                                                                                            | Update when platform philosophy or growth model changes.                                                   |
| docs/planning/roadmap.md           | Build/delivery roadmap - phases to MVP + "you are here" marker.                                                                                                                         | Update the marker at session end when a phase completes.                                                   |
| docs/planning/sprints/             | Per-sprint plan docs (one file per sprint) - the canonical sprint plan mirrored by the master ticket. Produced via the sprint-plan skill.                                               | Add one file per sprint at sprint start; update its wrap checklist as the sprint closes.                   |
| docs/design/architecture/\*        | Technical architecture specs - baseline, mesh, cluster topology, deployment.                                                                                                            | Create/update when an architecture decision changes.                                                       |
| docs/design/services/\*            | Per-service specs - one file per microservice (the Service Spec Lifecycle below). `_index.md` is the at-a-glance overview.                                                              | Create/update when a service's API, data model, or rules change.                                           |
| docs/design/features/\*            | Cross-service feature design - a capability spanning more than one service.                                                                                                             | Update when a feature design decision changes.                                                             |
| docs/design/features/per_baseline_identity.md | Per-baseline identity: realm shape, what `/api/v1` protection covers, cross-baseline membership rules, per-baseline broker certificates.                                    | Update when a realm, role, protected-surface, or broker-identity decision changes.                         |
| docs/design/features/baseline_component_reporting.md | Baseline component reporting: the services-versus-infrastructure split, what stays off the mesh, the `infrastructure` array + its vocabulary, and the Elasticsearch replica fix. | Update when a reported component, its state vocabulary, or the local-versus-announced split changes.       |
| docs/design/ui/\*                  | Status-console UI/UX specs - panels, style guide, token rules.                                                                                                                          | Update when a panel, token, or UX rule changes.                                                            |
| docs/design/ui/material_ui.md      | Material UI adoption: what it replaces (proportion scale, palette, icons, styling engine), the measured contrast trade, and how the migration lands.                                     | Update when a Material UI adoption decision changes.                                                       |
| docs/design/ui/operational_views.md | The console acting on its own baseline: the list operations it needs first, navigation, role-aware controls, confirmation, and error placement. | Update when an operational-view decision changes. |
| pom.xml                            | Parent aggregator POM (packaging `pom`) - the Maven multi-module root.                                                                                                                  | Update when a module is added/removed or a shared dependency/plugin version changes.                       |
| services/                          | Each Vert.x microservice = a Maven module + Dockerfile (thin main verticle; routers; wired to `lattice-common` + `lattice-contract`).                                                   | Feature branches only. Never commit directly to `main`.                                                    |
| platform/lattice-contract/         | The versioned contract: OpenAPI specs (resources) + REST DTOs + Artemis mesh envelope records. Single-writer.                                                                           | Update when the REST contract or a mesh envelope changes.                                                  |
| platform/lattice-common/           | Shared runtime: BaseVerticle, config loader, health/readiness, the Elasticsearch client + repositories, the Artemis mesh discovery client.                                              | Update when a shared runtime primitive or the ES/mesh client changes.                                      |
| ui/status-console/                 | React (Vite + TypeScript) status console; its own Docker container.                                                                                                                     | Feature branches only. Never commit directly to `main`.                                                    |
| deploy/docker/                     | Base image(s) + local docker-compose (Elasticsearch + Artemis + services).                                                                                                              | Update when the base image or local compose stack changes.                                                 |
| deploy/k8s/                        | K8s manifests / Helm charts. **Never hand-edit generated output.**                                                                                                                      | Update when a manifest or chart changes; regenerate generated output.                                      |
| .github/workflows/ci.yml           | GitHub Actions CI: `./mvnw verify` (lint/coverage/security gates as they land - TBD).                                                                                                   | Update when CI steps or the JDK version changes.                                                           |
| .env.example                       | Documented environment variables.                                                                                                                                                       | Update whenever a new env var is introduced.                                                               |

---

## Service Spec Lifecycle

Each microservice has exactly one spec, living in `docs/design/services/<name>.md`. It is the canonical description of that service's API, data model (Elasticsearch mappings), and mesh participation.

### Adding a service

1. **Verify a completed design doc exists** in `docs/design/` for this service. If one does not exist, stop - schedule a Design session (`/new-design`) before proceeding. This may be a separate tracked session.
2. **Read the design doc** before creating the service spec file.
3. **Create `services/<name>.md`** - the service's REST operations (its slice of the OpenAPI contract) + Elasticsearch mappings + the mesh envelopes it produces/consumes + related-service links. Derived from the design doc.
4. **Add to `services/_index.md`** - one row in the overview table; add topology diagram line(s) if the service talks to others over the mesh.
5. **Add mesh/contract links** to any related service files (the envelopes and endpoints they share).
6. **Add term to `glossary.md`** - one-line definition + link to the new service file.

### Removing a service

1. **Grep `docs/` for all references** to the service name before touching any file. Full hit list first - no deletions until the list is reviewed.
2. **Categorize hits** - service reference (must change) vs. incidental word match (leave alone).
3. **Confirm the list with the developer** before executing any changes.
4. **Delete `services/<name>.md`**.
5. **Execute surgical edits in order:** `services/_index.md` (overview table + topology diagram) -> related service files (shared envelopes + endpoints) -> `glossary.md` -> `locked_decisions.md` -> any affected `docs/design/` files.
6. **Re-run grep** to confirm zero orphaned service references remain before committing.

---

## Enforcement Rules

1. Locked Decision Requirement
   - If work contradicts a locked decision, Claude must flag it and halt until resolved.
2. Conflict Resolution Requirement
   - If a requirement is vague or contradicts another requirement, Claude must flag it before proceeding.
3. Pushback Requirement
   - Agreement is not a valid response to a decision conflict - pushback is required.
4. Unlock Process Requirement
   - Operator states reason -> Claude flags downstream -> change made, documented, re-locked.
5. No Speculative Code
   - If guiding documents do not dictate which library or implementation approach should be used, Claude must ask the developer before proceeding.
6. Markdown File Update Requirement
   - Any decision made in a session that affects the project's guiding documents (locked_decisions.md, protocol.md, glossary.md, or design/spec files) must be updated during the same session.
   - Claude must notify the operator when a decision requires a document change, and the session cannot close until the update is made.
   - Do not reference session numbers as justification when updating markdown files (except for changelog.md).
   - Do not reference GitHub issue numbers when updating markdown files (except for changelog.md).
7. Pull Request Creation Requirement
   - Confirm with the user that all desired issues have been completed for the session before creating the pull request.
   - Do not create a pull request until confirmation has been provided.
8. Document Table Requirement
   - When updating a table in a .md markdown file, preserve the existing column formatting and spacing for easier readability without a markdown renderer.
9. Test-First Requirement
   - New behavior is written **test-first**: a failing test capturing the expected behavior must exist (and be shown failing) before the implementation is written. Applies to Vert.x routes/services, shared contract logic (OpenAPI + mesh envelopes), and status-console components/hooks.
   - Elasticsearch mapping/index work uses spec-driven integration tests written in the same change (the documented exception - a mapping cannot be queried until the index exists). Any other exception requires a stated reason.
   - Full standard in [core_protocol.md](core_protocol.md#test-first-development-tdd).
10. No Dead Code on Replacement
   - When a feature, flow, service, or approach **replaces** an existing one, the old code must be **removed in the same change**, safely (delete the modules/routes, drop now-unused imports, deps, tests, and config). No commented-out blocks, no orphaned files, no superseded flow left behind "just in case".
   - "Safely" means: confirm nothing else references it (grep first), then remove; the change must build, verify, and test green after removal. Leaving the old path alongside the new one is not acceptable.
   - **Removal vs deferral.** The above is *replacement* - the concept is gone for good (grep the full hit list, delete in dependency order, re-grep to confirm zero orphans). A **deferral** (a concept parked for later, not killed) is different: remove it from the **product surface** (copy, status-console UI, user-facing vocabulary, design docs) but **keep dormant data-layer scaffolding** (Elasticsearch mappings, envelope fields) a future re-enable needs, and record the deferral as **one re-locked decision** in [locked_decisions.md](../reference/locked_decisions.md). Do not over-delete - a blanket grep-and-nuke tears out the mapping you mean to keep.
11. Branch Promotion Authority (`main` is founder-only)
   - **Claude never merges to `main`** and never commits app work directly to `main`. All work lands on the long-lived **`dev`** integration branch.
   - `dev -> main` is a **separate, deliberate, founder-only promotion**, once accumulated `dev` work is stable.
   - **Follow the full flow without deviating** - it is defined once in the [Branching Model](core_protocol.md#branching-model-dev-integration) (feature branch off `dev` -> green + push -> founder local-QA -> PR -> merge to `dev`). The QA labels (`needs-qa` / `qa-passed`) and local-run workflow live in [qa_protocol.md](qa_protocol.md).
12. Minimal-Ticket Rule
   - This is a small proof-of-concept built for the founder's own local QA. Keep tickets to the **bare minimum**: one ticket = one shippable, QA-able unit of work. Do **not** split a coherent change into a pile of sub-tickets, and do not open speculative tickets for work not needed for the current milestone.
   - When a task looks like it wants many tickets, default to the smallest set that still lets each land and be QA'd independently (Ethos = simplicity; reuse existing or derived over new).
   - **Creating a GitHub issue is founder-gated.** Claude never creates an issue unilaterally - not even a tracking or follow-on ticket. **Propose it (title + one-line scope) and wait for the founder's "go" before running `gh issue create`.** Unresolved work is surfaced as a proposal, not auto-filed. (Gate enforced in the [new-issue skill](../../.claude/skills/new-issue/SKILL.md) and at the [per-ticket close](#closing-work-per-ticket-close--end-of-day-wrap).)
13. Concurrent-Work Coordination
   - You may run more than one Claude instance at the same time (each in its own worktree and branch). A ticket actively being worked must be given the **`in-progress`** label + assignee **before** code is touched, so a concurrent instance can see who owns which files (see [GitHub Issues + Priority Labels](#github-issues--priority-labels)).
   - Concurrent branches must touch **disjoint file sets**; the seam (`platform/lattice-contract`, Elasticsearch mappings in `platform/lattice-common`, `docs/changelog.md`) is **single-writer**. Full model: [core_protocol.md - Concurrent branches](core_protocol.md#concurrent-branches-multi-agent).
14. Deliverable-First
   - Before working a ticket, its **deliverable must be known** - the concrete artifact or observable outcome that defines "done". If it is unclear or ambiguous, **stop and ask a founder, or run a design session** (`/new-design`); never assume or guess the intent.
   - The default deliverable per ticket type and its close gate live in one place: [core_protocol.md - Deliverable-first](core_protocol.md#deliverable-first-know-the-outcome-before-you-start).
15. Single-Engine / Minimal-Dependency Rule
   - **Before adding a dependency, check whether the stack already solves the job** and prefer **one library per job across services + common + UI** over a second library that does the same thing. Search the POMs and run `./mvnw dependency:tree`; reuse or wrap the existing engine before introducing a parallel one. Being the popular per-layer pick is not sufficient justification - cohesion is.
   - A duplicate engine (two JSON libraries, two HTTP clients, two logging facades) is a smell: converge on one, do not patch around the conflict. Full standard in [core_protocol.md - Dependencies](core_protocol.md#dependencies-one-engine-per-job).
16. Mockup Gate (visual direction for UI)
   - A **status-console UI / user-flow** ticket is only "ready" on a **fourth leg** - a wireframe/mockup whose visual direction the founder has confirmed - **beyond** the design doc + the ticket + the deliverable. Doc + plan + deliverable still leaves the *look* a guess; guessing UI wastes build effort.
   - For a **new panel or a change to an existing one that has a visible surface or user flow**, present the visual as **A/B/C options** for a founder to pick before the ticket is worked; record the chosen direction. Applies to both new panels and refinements of existing ones.
   - **Mark the gap, never guess.** A UI ticket without a confirmed mockup carries the **`needs-mockup`** label; the label is removed only when a founder confirms a direction. `label:needs-mockup` is the live backlog of what still needs a visual. Surface the gap explicitly ("doc + plan + deliverable, but no confirmed mockup") rather than proceeding into visual gray area.
   - Baked into the [`/sprint-plan`](../../.claude/skills/sprint-plan/SKILL.md) + [`/new-design`](../../.claude/skills/new-design/SKILL.md) skills; role agents defer to this rule for status-console UI work.
