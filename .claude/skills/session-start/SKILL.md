---
name: session-start
description: Start a new Lattice session - read protocol context, rank open issues by P0/P1/P2 label, surface top tickets, prompt for session type, and confirm relevant design files before any work begins.
argument-hint: "[tracked|design|sandbox]"
---

Execute the session start checklist in this exact order.

> **Repo slug.** These commands infer the GitHub repo from the local checkout (the `dev` remote), so no `--repo` flag is needed. Where a browser URL is rendered below, `<owner>/lattice` is the repo slug - resolve it once with `repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)` and reuse `$repo`.

## Step 0 - Sync dev (guarded)

Bring `dev` up to date and prune merged branches **before** reading context or creating any branch - so the session starts from the latest integrated state and a clean branch list. `dev` is the integration branch every feature branch is cut from (and the GitHub default); `main` is the founder-only stable trunk and is not synced here.

**Guard:** Skip this step if resuming an in-progress feature branch or the working tree is dirty (`git status --short` non-empty). Never pull into a feature branch mid-task - flag it and continue on the current branch.

If the working tree is clean:

```bash
git checkout dev
git pull --ff-only
git fetch --prune
```

Then prune local branches whose PRs have merged. **Do not use `git branch --merged`** - it lists only branches that are ancestors of the current one, so anything merged by a method that rewrites commits (squash, rebase) never appears and is silently kept. `gh` reports what GitHub actually did, whatever the merge method. Check each local branch's PR state instead:

```bash
for b in $(git branch --format='%(refname:short)' | grep -vE '^(main|dev)$'); do
  state=$(gh pr list --head "$b" --state all --json state --jq '.[0].state' 2>/dev/null)
  if [ "$state" = "MERGED" ]; then git branch -D "$b"; fi
done
```

Use `git branch -D` (capital) - `-d` refuses any branch git cannot see as merged, which includes anything squashed or rebased on merge.

## Step 0.5 - main/dev drift check

After syncing, surface how far `main` (the founder-only release trunk) trails `dev`, so a promotion gap does not silently grow into weeks-stale production (see [CLAUDE.md - Preventing main/dev drift](../../../CLAUDE.md)):

```bash
git fetch origin main dev -q
echo "dev ahead of main: $(git rev-list --count origin/main..origin/dev) commits"
git diff --quiet origin/main origin/dev && echo "trees: in sync" || echo "trees: differ (dev has unpromoted work)"
git log origin/main -1 --format='last main promotion: %cs  %s'
```

Read this by **magnitude, not bare commit count**: the merge + promote flow always leaves `main` a few merge commits "ahead" and the trees differing by any unpromoted work - both normal between releases. **Real drift** is `dev` sitting **many commits / multiple weeks ahead** with an old last-promotion date. When that is the case, **call it out in the dashboard** ("`main` is N commits / since `<date>` behind `dev` - a founder `dev -> main` promotion (release) is due") - never promote automatically; `main` is founder-only.

## Step 1 - Read context files (in parallel)

Read all six files simultaneously:
- `docs/changelog.md`
- `docs/protocol/session_protocol.md`
- `docs/protocol/core_protocol.md`
- `docs/reference/locked_decisions.md`
- `docs/reference/glossary.md`
- `docs/governance/governance.md`

## Step 2 - Session type

If `$ARGUMENTS` carries the type (`/session-start tracked` | `design` | `sandbox`), use it and skip the prompt. Otherwise, if the developer has not yet declared session type, ask: **Tracked, Design, or Sandbox?**

## Step 3 - Rank open issues by priority label

There is **no project board** in Lattice and **no org-level Priority field** - priority is a plain GitHub **label** (`P0` / `P1` / `P2`, P0 highest). Everything below is a live `gh` read against the repo's issues + PRs; nothing is stored, so this view is re-runnable any time, not just at a "start".

**Open issues (number, priority label, assignees, milestone, created date, title):**
```bash
gh issue list --state open --limit 100 \
  --json number,title,labels,assignees,milestone,createdAt \
  --jq '.[] | "\(.number)\t\([.labels[].name] | map(select(test("^P[0-2]$"))) | join(",") // "-")\t\([.assignees[].login] | join(","))\t\(.milestone.title // "no-milestone")\t\(.createdAt[0:10])\t\(.title)"'
```
Sort by the priority label: **P0 -> P1 -> P2 -> (unlabeled)**. An issue with no `P*` label sorts last and is flagged `🏷️ no priority`.

**Recently completed (last 5 merged PRs, newest first):**
```bash
gh pr list --state merged --limit 5 --json number,title,author,mergedAt,closingIssuesReferences
```

**Phase % (live, computed - never stored; only if milestones are in use):**
```bash
gh api "repos/$repo/milestones" --jq '.[] | "\(.title): \(.closed_issues)/\(.closed_issues + .open_issues) = \((.closed_issues * 100) / (.closed_issues + .open_issues) | floor)%"'
```
If no milestones exist yet, skip the % and show the phase **name** alone (TBD - wire once milestones track the build phases). The README badge carries only the phase **name** (advanced via [set-phase](../set-phase/SKILL.md) when a phase graduates).

**Determine the current phase (the scope).** The active phase **name** is the README **Build** badge (`Build-Phase_N_·_<Name>` in the WHERE WE ARE section, mirrored by the `← you are here` marker in [roadmap.md](../../../docs/planning/roadmap.md)) and the current build phase in [governance.md](../../../docs/governance/governance.md).

**Render the dashboard.** This is the single source of truth for the snapshot. Render it exactly per the scope, standard, and layout below.

**Scope (fixed counts - how much to show):**
- **Recently completed:** the last **5** merged PRs, newest first, **always shown** - history always exists, there is no empty state. The **🕐 When** column is the **relative age** from `mergedAt` (`10h ago` / `3d ago`); the section header notes the most-recent age. The **📦 What shipped** cell is a markdown link to the PR's **closing ticket** (`closingIssuesReferences[0]`, `+#NNN` for any extra); a PR with no linked ticket shows plain text + `*(no linked ticket)*`.
- **In flight:** **all** open issues that are **assigned** (an assignee = someone owns it) + owner.
- **Up for grabs:** the **complete** list of **unassigned** open issues, priority-sorted (P0 -> P1 -> P2 -> none) - **no cap** (never a "top N"), **always shown** (there is always something to do). The section header carries the **total ticket count** (`(N tickets · by priority)`). Include a **📅 Created** column so staleness is visible at a glance.

**Empty states (only In flight can be empty; up-for-grabs always has content):**
- 🛫 In flight, none: `> 🟢 Nobody mid-flight - all clear to pick.`
- 🩺 Board health, none: `> ✅ All clean - no unlabeled or flagged issues.`

**Render standard (fixed emoji vocabulary - do not improvise):**
- **Priority** (label): 🔴 P0 · 🟠 P1 · 🟡 P2 · ⚪ none.
- **🧪 Parallel Agents?** (reasoned): ✅ parallel-safe · ⛔ seam (serialize) · ⚠️ caution (same surface). `⛔ seam` = touches a single-writer file (`platform/lattice-contract`, an Elasticsearch mapping in `platform/lattice-common`, `docs/changelog.md`). After `✅`, a surface tag from: `service · ui · contract · platform · docs · isolated`.
- **Flags** (reasoned): `-` none · ✂️ scope too big (split?) · ❓ unclear deliverable (confirm) · 🔗 relates to recent work (+ref) · 🏷️ no priority label · 🔁 possible dup (+ref).
- **People:** 🦈 Nick. Any other contributor is shown by their login.
- **Section headers:** 📋 phase · ✅ Recently completed · 🛫 In flight · 🧐 Up for grabs · 🩺 Board health · 🔑 legend · 🛠️ Plan of attack.
- **Column headers (full word + correlating emoji):** 🔀 PR · 🧑 Who · 🕐 When · 📦 What shipped · #️⃣ (number, no text) · 🚦 Priority · 📅 Created · 📝 Title · 🧪 Parallel Agents? · 🚩 Flags.
- **Column order (identifier-first, all three tables):** **col 1 = identifier** (🔀 PR for completed, #️⃣ for in flight / up for grabs) · **col 2 = owner-or-priority** (🧑 Who for completed + in flight; 🚦 Priority for up for grabs, which has no owner yet) · then context columns · the description/title column (📦 What shipped / 📝 Title) last.

**Links (both tables):** every ticket/PR reference renders as a markdown link so it opens in a browser - `#NNN` -> `https://github.com/<owner>/lattice/issues/NNN`, `PR#NNN` -> `https://github.com/<owner>/lattice/pull/NNN`.

**Layout:**
```
# 📋 **Phase N · <Name>** · **<live % or "-">** *(<closed> / <total> closed · <open> open)*

### ✅ **Recently completed** *(last 5 merged · most recent ~<age> ago)*
| 🔀 PR | 🧑 Who | 🕐 When | 📦 What shipped |
| PR#<n> | 🦈 Nick | ~<age> ago | [<what>](issue link) (+#<extra>) |   ← What shipped links to closing ticket

### 🛫 **In flight** *(claimed - don't grab the same files)*      [empty state if none]
| #️⃣ | 🧑 Who | 📝 Title |

### 🧐 **Up for grabs** *(<N> tickets · by priority)*
| #️⃣ | 🚦 Priority | 📅 Created | 📝 Title | 🧪 Parallel Agents? | 🚩 Flags |

> 🩺 **Board health:** open issues with no priority label, likely dupes, anything unclear
> 🔑 *emojis fixed per this standard · Priority / Parallel Agents? / Flags text is reasoned per ticket, confirm first*

### 🛠️ **Plan of attack** *(proposed - approve, tweak, or override)*

> **Pick:** [#NNN](issue link) - <title>
> **Why now:** <one line - priority / unblocks / leverage>
> **Branch:** `lat-NNN-<slug>`
> **Deliverable:** <concrete artifact> -> <close gate>
> **Shape:** <2-3 step approach sketch>

👉 **Your call:** reply **"go"** to run this, or name your own pick (`#NNN`) and I'll re-plan around it.
```

**Plan of attack (the close):** every render ends with one **proposed** pick - the top-priority, parallel-safe, deliverable-clear ticket - spelled out as Pick / Why now / Branch / Deliverable / Shape so a founder can approve in one word or override with a number. It is a recommendation, never an auto-start: no branch is cut until the founder confirms (Step 4). When the founder names a different ticket, re-plan the block around it.

**Fixed vs judgment:** the emoji vocabulary (section + column), section order, and column set are fixed - identical every render. Only the *text after* an emoji is judgment (the surface tag, the flag wording, the 🔗 target, the Plan-of-attack contents), derived per ticket from the issue body + the seam rule, and always presented as **reasoned** (confirm before acting).

## Step 4 - Developer selects issue(s)

Wait for the developer to **approve the proposed Plan of attack** ("go") or **name their own pick**. Do not create a branch or make any changes before this step.

## Step 5 - Read relevant design markdown

Based on the selected issue(s), determine which files under `docs/design/` are relevant. Design docs live in four subfolders:

- `docs/design/architecture/` - baseline versioning, mesh discovery, cluster/interop model, deployment shape.
- `docs/design/services/` - per-service specs (each Vert.x microservice).
- `docs/design/features/` - cross-cutting feature specs.
- `docs/design/ui/` - the React status console: the Material UI theme, component standards, and the proportion system.

Read the relevant files. Use judgment - a **service or Elasticsearch data-model** ticket needs the relevant `docs/design/services/*` + `docs/design/architecture/*`; **mesh / interop / baseline** tickets need `docs/design/architecture/*`; **status-console UI** work needs `docs/design/ui/*` (which owns the theme and the canonical proportion system - Material UI's 8px spacing grid, locked #62 - that every UI ticket, refactor, and refinement must honor).

## Step 6 - Confirm with developer

State which markdown files were read and why. Ask the developer to confirm before proceeding.

**Confirm the deliverable before any code (Enforcement Rule 14).** Name the concrete deliverable for the picked ticket and its close gate (per the type map in [core_protocol.md - Deliverable-first](../../../docs/protocol/core_protocol.md)). If it is unclear or ambiguous, **stop and ask a founder, or switch to a design session** (`/new-design`) - never assume or guess.

Only after confirmation: for **Tracked sessions**, first **self-assign the issue** with `gh issue edit <n> --add-assignee @me` - claim it before coding, so a concurrently-working founder sees who owns it. Then create the branch `lat-<issue>-<slug>` off `dev` - the `lat` tag + ticket number + short kebab-case feature slug (e.g. `lat-12-mesh-discovery`, `lat-31-baseline-versioning`). If another founder may be working at the same time, cut the branch in its own `git worktree` so two sessions never share a checkout ([core_protocol.md - Concurrent branches](../../../docs/protocol/core_protocol.md)).
