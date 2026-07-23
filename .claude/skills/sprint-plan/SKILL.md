---
name: sprint-plan
description: Plan a sprint; UI features gated on a founder-confirmed mockup (applies to the status console); rank by P0/P1/P2 label.
argument-hint: ""
---

`/sprint-plan` produces a **named sprint plan** at the start of a stretch of work: the problem + goal, the design work needed, the packed + ranked tickets under one master ticket, and a summary that lives in three linked places. It is the front half of the sprint; [`/log-work`](../log-work/SKILL.md) closes it. Keep it **simple** - the value is coverage + ranking + one clear summary, not ceremony.

**Anchored process rules** (learned the hard way, do not skip):
- **One canonical doc per feature or service.** Fold new requirements into the feature's / service's own doc under `docs/design/`, or cleanly supersede **and retire** the old one. **Never** leave a `_rework` / `_reconcile` breadcrumb trail of duplicated docs.
- **Mockup gate.** A UI / user-flow ticket for the **status console** is only "ready" on a **fourth leg** - a wireframe/mockup whose direction a founder confirmed - beyond doc + ticket + deliverable.
- **Packed tickets, no dupes.** Rescope existing tickets before creating new ones.
- **Sprints are always named** (see Step 1).

---

## Step 1 - Name the sprint + write the TLDR

- **Name it.** Every sprint gets a short evocative name (not "Phase 4"). Propose 3-5, flag any that collide with product vocabulary, let the founder pick.
- **TLDR (the summary's spine):** a **problem statement** + the **major goal** (the testable bar) + one line of **vision/why**. This is what a reader sees first on the sprint doc and the master ticket.
- Match the name + goal to a GitHub **milestone**; the sprint's tickets live in it.

## Step 2 - Design sessions (one doc per feature or service)

- From the goal, identify the design areas. Run each via [`/new-design`](../new-design/SKILL.md) - **one canonical doc per feature or service** under `docs/design/` (`architecture/`, `services/`, `features/`, `ui/`).
- **Consolidate as you go:** if a feature/service already has a doc, update *that* doc (or supersede + retire it) - do not spawn a parallel `_rework`/`_reconcile`.
- Group small/related items into **one consolidated session** rather than many (fewest sessions that still cover everything).

## Step 3 - Mockup gate (status-console UI features)

- For every **new UI / user-flow** feature in the **status console**, produce or require **A/B/C visual mockup options** and get a founder to **pick one** before its ticket is marked ready.
- Record the chosen direction in the sprint doc. A status-console UI ticket with no confirmed mockup carries a **`mockup-needed`** flag and is called out - never guess visual direction.

## Step 4 - Cut + rank the tickets, attach to the master ticket

- **Cut packed tickets** from the design docs - **rescope existing tickets first**, create new only for genuinely-new work, no duplicates. Each is short + complete (why · scope · deliverable + how verified; [Minimal Ticket Template](../../../docs/protocol/session_protocol.md#minimal-ticket-template)).
- **Rank by dependency tiers, not a flat list:** foundations/seams first (single-writer: the OpenAPI + `lattice-contract` envelope, Elasticsearch mappings, the status-console `theme`), then `lattice-common` primitives + service logic, then services/routes, then panels + features/polish; call out an **infra/platform lane** (Docker, K8s, mesh) that runs concurrently and note **what parallelizes vs serializes** (don't force it into a label).
- **Ordering trap:** if a decision **rescopes** a foundation ticket (a contract shape, a mapping), that rescope must land **before** the ticket is built - not deferred to the end-of-sprint reconcile.
- **Master ticket:** create/repurpose one umbrella issue (the sprint's coordination card) and attach every sprint ticket as a **native GitHub sub-issue** so it gets a progress bar + tree:
  - Resolve the repo slug once: `repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)`. Then `gh api repos/$repo/issues/<master>/sub_issues -F sub_issue_id=<the issue's internal id, NOT its number>` (resolve number->id with `gh api repos/$repo/issues/<n> --jq .id`; there is no `gh` subcommand).
  - **Single parent:** a sub-issue can have only one parent. **Pagination:** the `sub_issues` REST list returns **30/page** - use `--paginate` or GraphQL (`subIssuesSummary`) to see the real total; do not trust a short first-page count.
- Set each ticket's priority via a **`P0` / `P1` / `P2` label** through [`/new-issue`](../new-issue/SKILL.md). There is **no project board and no org-level Priority field** - rank is the label alone.

## Step 5 - Publish the summary (three linked places, one source)

The **sprint doc is the source of truth**; the other two mirror it.

1. **`docs/planning/sprints/<name>.md`** - the canonical checked-in doc: TLDR (problem · goal · vision) · tracks · the ranked ticket tiers · links to every design doc + the master ticket. *(Create the `sprints/` folder if absent.)*
2. **The master ticket body** - the same TLDR + track status + the pipeline, over the live sub-issue tree.
3. **A visual board** (optional) - a rendered HTML board (an Artifact) for the at-a-glance view.

## Step 6 - Close the sprint (hand to log-work)

At sprint end: run the **reconcile** (consolidate any duplicated docs into their canonical form; fold rescopes; update `locked_decisions.md` / `glossary.md` / the File Inventory), do one full **priority pass** (the P0/P1/P2 labels), then **one PR + [`/log-work`](../log-work/SKILL.md)** - founder-gated, founder merges. Advance the roadmap marker / rename the phase to the sprint name via [`/set-phase`](../set-phase/SKILL.md) if a phase graduated.

---

## Docs this skill touches (keep in sync)

- `CLAUDE.md` - references this skill + the sprint-doc convention.
- `docs/protocol/session_protocol.md` - the **mockup gate** as an enforcement rule; Status-Check reads the current sprint doc.
- `docs/README.md` - indexes this skill + the `docs/planning/sprints/` folder.
- `README.md` - the Build badge shows the **sprint name**.
- `docs/planning/roadmap.md` - sprints map to phases / milestones.
- `.claude/agents/*` - the mockup gate applies to any status-console UI work they produce.
