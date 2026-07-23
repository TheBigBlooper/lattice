# Lattice - Claude Instructions

---

## Project

Lattice is a Java 21 / Vert.x 5 microservice platform. Each service runs in a Docker container; all services in a cluster live in one Kubernetes cluster - that collection is the versioned **baseline** (versioned services and versioned REST endpoints). Separate clusters discover and communicate with peer clusters over an Artemis-backed mesh. Each cluster keeps its own, possibly divergent, Elasticsearch data model, and clusters must stay interoperable. A React status console ships as its own container per cluster to view the status of all nodes.

Work is tracked as **GitHub issues** on this repo, prioritized with `P0` / `P1` / `P2` labels (there is no separate project board). Ranking and picking happen off those labels.

---

## Populate Initial Context

Claude reads the following files each new session.

- [changelog.md](docs/changelog.md)
- [session_protocol.md](docs/protocol/session_protocol.md)
- [core_protocol.md](docs/protocol/core_protocol.md)
- [locked_decisions.md](docs/reference/locked_decisions.md)
- [glossary.md](docs/reference/glossary.md)

---

## Skills

Project-level skills live in `.claude/skills/`. Invoke with `/skill-name` (the Skill tool). Each skill's `description:` frontmatter is the canonical one-liner. The full human-readable index of every skill is the single source at **[docs/README.md#skills](docs/README.md#skills)** - not duplicated here.

## Agents

Role subagents live in `.claude/agents/`; delegate to them via the Agent tool. Each owns a surface and defers its rules to the matching protocol. The four roles are **service** (Vert.x services + Elasticsearch data), **ui** (the React status console), **contract** (the OpenAPI specs + the mesh envelope module), and **platform** (Docker, Kubernetes, the Artemis mesh, deploy). Index: **[docs/README.md#agents](docs/README.md#agents)**.

---

## Session Protocol

**Session Type - Declare First (or Claude will ask):**

- `Session type: Tracked, Design, or Sandbox`

Detailed rules for each session type are defined in [session_protocol.md](docs/protocol/session_protocol.md#session-types-work-modes). Work is continuous - these types are **work modes**, not bounded sessions with start/end rituals; the real units are the ticket (the loop) and the day (the changelog wrap).

**Branch Naming - Environment Conflict Rule:**

If the session environment pre-assigns a branch name that does not follow the `<git_issue_tag>-<git_issue_number>-<slug>` convention (the tag is `lat`) defined in session_protocol.md, Claude must halt and flag the conflict before making any changes.

Do not proceed on the pre-assigned branch without explicit developer approval.

**Status Check + Pick (formerly "session start"):**

Produce the live status dashboard and pick a ticket per [session_protocol.md](docs/protocol/session_protocol.md#status-check--pick-formerly-session-start). Re-runnable any time, not just at a start.

**Deliverable-first:**

Before working a ticket, its deliverable must be known; if unclear, ask a founder or run a design session - never assume (Enforcement Rule 14; type map in [core_protocol.md](docs/protocol/core_protocol.md#deliverable-first-know-the-outcome-before-you-start)).

**Closing work:**

Per-ticket close + the end-of-day wrap (changelog only when "done for the day") are defined in [session_protocol.md](docs/protocol/session_protocol.md#closing-work-per-ticket-close--end-of-day-wrap).

---

## Project Documentation Map

Structure is documented in two places, by concern (these are two different inventories, not one thing split):

- **Docs inventory** - which markdown file does what + its update rule: [session_protocol.md - File Inventory](docs/protocol/session_protocol.md#file-inventory).
- **Code layout** - the Maven `services/` + `platform/` + `ui/` + `deploy/` source trees: [core_protocol.md - Folder Structure](docs/protocol/core_protocol.md#folder-structure).

A brief map of the `docs/` tree itself is in [docs/README.md](docs/README.md).

---

## Response Style

Answer the exact question asked, then stop. Default to the **shortest correct answer**.

- Asked for a command -> give the command (plus at most one line of context). No walls of text.
- No preamble, no restating the question, no summary of what you just did unless asked.
- Do **not** volunteer alternatives, caveats, tips, or "you could also..." unless asked or genuinely critical.
- Expand only when the developer asks for detail, or the task is genuinely complex (a real trade-off, a risk, a decision needing input).
- Prefer one short paragraph or a few bullets over sections and headers.

---

## Accuracy Bar (CRITICAL)

You are continually compared against other LLM solutions by the developers, who correct your hallucinations. Earn the comparison: be right, and be honest about uncertainty.

- **Verify against source before asserting.** Read the file, run the query, check the API - do not answer a factual question about this codebase, its config, or an external service (Elasticsearch, Artemis, Kubernetes, Vert.x) from memory.
- **Cite the evidence.** Point to the file path + line/anchor, the command output, or the doc you are relying on, so a claim is checkable.
- **Flag uncertainty instead of confabulating.** "I am not sure - let me check" beats a confident wrong answer. If you cannot verify, say so.
- **When corrected, correct the record** (the doc, the memory, the protocol), not just the reply.

---

## Decisions and Continuity Enforcement

Decisions and continuity enforcement guidelines are defined in [session_protocol.md](docs/protocol/session_protocol.md#enforcement-rules).

---

## Commit Messages and Pull Requests

Do **not** append any authorship trailer or footer to commit messages or PR bodies - no `Co-authored-by`, no `Generated with Claude Code`, none. Commit messages are Conventional Commits and nothing more; PR bodies carry their content only.

---

## Writing Style

- **Never use the em dash (the `-` long-dash character) anywhere** - copy, code, comments, commit messages, PR bodies, docs, changelog. Use a spaced hyphen ` - `, a comma, a colon, or parentheses instead. This is a hard project style rule; apply it while writing, not as a cleanup pass.
- **No unexplained acronyms or jargon.** Do not use an abbreviation the founder has not established. Either spell it out in plain words, or write the full term the first time with the short form in parentheses after it. Applies to chat, questions, mockups, and docs. When in doubt, say the plain-English thing.

---

## Branch & Merge Workflow (CRITICAL - do not deviate)

**`main` is founder-only. Claude never merges to `main`** and never commits app work straight to it. All app work lands on the long-lived **`dev`** integration branch via a `<type>-<issue>-<slug>` feature branch (tag `lat`); `dev -> main` is a separate, deliberate, founder-only promotion.

The full sequence (feature branch -> green `./mvnw verify` + smoke + push -> "Ready to test" comment -> founder QA -> PR -> merge to `dev`) is defined once in the [Branching Model](docs/protocol/core_protocol.md#branching-model-dev-integration); QA labels and the workflow are in [qa_protocol.md](docs/protocol/qa_protocol.md). Do not deviate.

**Preventing `main`/`dev` drift.** `main` only advances via a deliberate, founder-only `dev -> main` promotion, but it must not silently fall behind `dev`. Guard against it:

- **A release *is* the promotion.** Promote `dev -> main` at each release point (pair it with `/release-notes`); do not let shippable `dev` work pile up unpromoted indefinitely.
- **Judge sync by *tree*, not commit count.** A squash-merge + promote flow always leaves `main` a few merge commits "ahead" - that is normal, not drift. The real signal is content: `git diff origin/main origin/dev` empty means in sync. Real drift is `dev` sitting many commits / weeks *ahead* of `main`. The Status Check surfaces this each session.
- **Promotion is non-destructive + founder-only.** Advance `main` via a merge whose tree equals `dev`, then fast-forward `git push`. **Never** force-push or `reset --hard` `main` (Git Safety).

**One session = one branch = one PR (do not fragment).** A "do the work" / "wrap it up" / "make it happen" instruction authorizes work **on the current branch** - it is **never** permission to open a new branch, a new ticket, or a second PR. Creating any of those mid-session requires an **explicit in-the-moment "go"** naming the new thing; when work arises that feels separate, **stop and ask "same branch or new?"** rather than deciding. Mechanically backstopped: `.claude/settings.json` `permissions.ask` prompts the founder before any `gh issue create`, `gh pr create`, or new-branch (`git checkout -b` / `git switch -c`) command runs.

---

## Git Safety (CRITICAL - never rewrite shared history)

Claude only ever **fast-forward pushes** (`git push`). Claude must **never** rewrite or destroy shared history without explicit, in-the-moment founder approval. Forbidden without that approval:

- **Force push** in any form: `git push --force`, `git push -f`, `git push --force-with-lease`.
- **History rewrite of a pushed branch:** `git rebase` of shared history, `git commit --amend` after a push, `git reset --hard` on a pushed branch, `git filter-branch`.
- **Deleting shared work:** `git push origin --delete <branch>`, or `git branch -D` of a branch others may have.

Why this is a hard rule: a force-push or rewrite changes history that already exists on `origin`, so every other clone diverges from the remote and committed work can be lost. There is almost never a legitimate reason to force-push a shared branch; if a rewrite genuinely seems necessary, **STOP and ask the founder first**. A mechanical block on the force-push variants lives in [`.claude/settings.json`](.claude/settings.json) (`permissions.deny`); it is a backstop, not a substitute for this rule.

---

## Test-First Development (TDD)

All new behavior is **test-first** - write the failing test that captures the behavior, show it fail, then implement to green. Applies to Vert.x routes/services, shared contract logic (OpenAPI operations + mesh envelopes), and the status console; integration spans both sides of the contract. Documented exception: Elasticsearch mapping/index work (a mapping cannot be queried until it exists). Full standard: [core_protocol.md](docs/protocol/core_protocol.md#test-first-development-tdd); enforcement: [session_protocol.md](docs/protocol/session_protocol.md#enforcement-rules).

---

## File Access Rules

- Edit Style:
  - Surgical edits only - never full rewrites unless structurally required throughout.
- Make Changes:
  - State what you are about to change and why before making the change.
- Changelog File
  - Insert one session entry (covering all tickets worked) directly via str_replace - never rewrite in full.

Full commit rules and enforcement rules: [session_protocol.md](docs/protocol/session_protocol.md#enforcement-rules).

---

## Reuse Over Rebuild (CRITICAL - never fork a shared component)

When a shared class, verticle base, utility, or console component already does what a surface needs, **reuse it and configure it** - never fork a parallel "lean" copy. Every service extends the shared **`BaseVerticle`** (from `platform/lattice-common`) for config, health/readiness, and mesh registration; it does **not** re-implement those. Every service reads and writes Elasticsearch through the shared repository base, not a hand-rolled client. The status console uses its shared components (one `NodeCard`, one `StatusPill`, one health indicator) on every panel; it does **not** redraw them per panel. A bespoke second implementation of an existing component is a **defect**, even when it "looks leaner" - it drifts from the original and duplicates the code.

- **Grep before you build.** Before creating any new class, verticle, or component, search for an existing one that already does the job; extend or configure it first.
- **This covers the contract too.** REST shapes come from the OpenAPI specs and mesh messages from the `lattice-contract` envelopes - never hand-declare a parallel shape on one side of the wire.
- **Agents inherit this.** When delegating, name the shared classes + components to reuse and forbid parallel ones; review the returned diff for any net-new component that overlaps an existing one before pushing.

This extends the enforcement rules (one engine per job; no dead code) to components and services.

---

## Files to Never Touch

The following files should never be touched or modified by Claude.

- Generated OpenAPI clients/models (produced from the specs in `platform/lattice-contract`) - regenerate, do not hand-edit.
- `deploy/k8s/generated/` - any generated Kubernetes manifests / rendered Helm output.
- `target/` - Maven build output.
- `node_modules/` and `ui/status-console/dist/` - Node install + build output.
