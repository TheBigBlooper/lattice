---
name: log-work
description: End-of-day work log - gather the day's merged PRs into one changelog entry with a Maven/UI Heads-up block, preview it exactly as it will appear, and on approval write it (+ Up Next + roadmap marker) riding on the final PR of the day.
argument-hint: ""
---

`/log-work` is the **end-of-day wrap** - the only closing ceremony. Run it **once, when you are done for the day**, in conjunction with the **final PR** you submit. Running it *is* the "done for the day" signal and the founder gate on the changelog (the changelog is never written as part of building a ticket). The per-ticket close - commit, push, open a PR per ticket - is normal loop behavior and needs no skill.

It produces **one changelog entry per person per day**, gathered from that person's PRs merged today plus the final PR being opened now, and it **shows you the entry before writing anything**.

> **Repo slug.** `gh` commands infer the repo from the local checkout; where a browser URL is rendered, `<owner>/lattice` is the repo slug (`gh repo view --json nameWithOwner -q .nameWithOwner`).

## Step 1 - Author + scope

- **Author:** derive from `git config user.name` / `gh api user --jq .login` (🦈 Nick = `TheBigBlooper`; confirm if it is someone else).
- **The day's work:** this person's PRs merged today, since their last changelog entry, plus the final PR being submitted now (the current branch).

```bash
gh pr list --state merged --search "merged:>=$(date +%Y-%m-%d) author:@me" --json number,title,url,closingIssuesReferences
```

Add the **current branch's** PR (open or about to open) and its closing tickets. Detect post-pull callouts from the day's diffs for the **Heads up** block (see Step 4): a changed `pom.xml` or dependency (-> `./mvnw install`), a changed Elasticsearch mapping (-> reindex), a changed `deploy/docker/docker-compose*.yml` or new service (-> `docker compose up`).

## Step 2 - Draft + find today's block

Look at the top of `docs/changelog.md`. If the newest entry is **already today's date for this person**, you are **folding into that block** (append bullets, refresh the time, extend `Tickets:`), not prepending a new one. Otherwise you draft a fresh top entry. Either way, draft it as the exact shape in Step 4 - do not write yet.

## Step 3 - Preview + approve (write nothing before this)

**Render the full entry exactly as it will appear in `docs/changelog.md`** - date line, person, `## Title`, `[category]`, one bullet per ticket/PR, `Tickets:` footer, and the `**Heads up:**` block - as a fenced block in chat. Then **stop and wait for founder approval**. Adjust the title / tags / bullets / callouts on request. Nothing is written, committed, or pushed until the founder approves.

## Step 4 - Format (the exact shape)

```
YYYY-MM-DD HH:MM TZ
<Person>

## <Title>

[<category>]
- <ticket/PR one-liner>
- <ticket/PR one-liner>

Tickets: [#N](https://github.com/<owner>/lattice/issues/N), [PR #N](https://github.com/<owner>/lattice/pull/N)

**Heads up:**
- `./mvnw install` - <why, e.g. new module / dependency change>
- reindex Elasticsearch - <why, e.g. mapping change in lattice-common>
```

The `**Heads up:**` block is **always present** (a standing caveat section). When nothing must be run, collapse it to the one-line empty state:
```
**Heads up:** ✅ nothing to run - pull and go.
```

Rules:
- **Order:** date line -> person -> `## <Title>` -> `[category]` -> bullets -> `Tickets:` -> `**Heads up:**`. Never an em dash anywhere; stamp the time in the author's **own** local zone (`date "+%Y-%m-%d %H:%M %Z"`; the time marks the last update today).
- **Category:** `[feature]` (new capability) · `[enhancement]` (improve existing) · `[bug]` (fix) · `[internal]` (tooling / docs / refactor / process). A mixed day **may stack a tagged sub-block per category** (e.g. `[enhancement]` bullets then `[internal]` bullets), or pick the dominant tag.
- **One bullet per ticket/PR** - a short one-liner; the deep detail lives in the PR/commits, not here.
- **Folding into today's block:** append the new bullets, refresh the timestamp, extend the `Tickets:` footer, and re-evaluate the `**Heads up:**` block against the full day - never a second block for the same person + day.
- **Heads up (always present):** detect from the day's diffs -
  - a changed `pom.xml` (parent or any module), or a new Maven module -> `` `./mvnw install` `` (rebuild + install the reactor locally).
  - a changed Elasticsearch mapping / index definition in `platform/lattice-common` -> **reindex Elasticsearch** (the mapping changed; existing indices need a rebuild).
  - a changed `deploy/docker/docker-compose*.yml`, a new/renamed service, or a new container -> `` `docker compose up` `` (rebuild the local cluster).
  One brief bullet per command (with the why). Nothing detected -> the `✅ nothing to run` empty state, so a teammate never has to wonder.

## Step 5 - Write + cleanup (only after approval)

Insert via str_replace at the top of `docs/changelog.md` (or fold into today's block) - never rewrite the file in full. Then count entries: if more than 20, remove the oldest (at the bottom). Max 20 at all times.

## Step 6 - Up Next + roadmap marker

- **Up Next** (for the chat summary, not embedded in the entry - the issue list is the canonical next-work source): live open issues by priority label.
  ```bash
  gh issue list --state open --limit 50 --json number,title,labels   # sort by P0 -> P1 -> P2 -> none
  ```
- **Roadmap marker:** if a build phase graduated today, move the `← you are here` marker in `docs/planning/roadmap.md` (and advance the README badge + governance.md via `/set-phase`).

## Step 7 - Commit + ride the final PR

The changelog edit **rides on the final PR of the day**. Two cases:

- **Final ticket's branch is open (normal):** commit the changelog onto the current branch and include it in that PR - open it, or `gh pr edit` the body if the PR is already open.
- **Forgot to log - the final PR is already submitted/merged (fallback):** from up-to-date `dev`, cut `docs-changelog-$(date +%Y-%m-%d)`, write + commit the entry there, and open a dedicated PR for just the changelog.

Commit message `<type>(<scope>): <description>` (e.g. `docs(changelog): <date> work log`), ending with:
```
Co-authored w/ Claudio
```
PR into `dev` only (never `main`, which is founder-only); the PR body ends with `Co-authored w/ Claudio`; no `🤖 Generated with Claude Code` footer. The **founder merges** - Claude never merges (Enforcement Rule 11).

## Step 8 - Confirm

- Entry previewed and founder-approved **before** anything was written
- Entry written (or folded into today's block) with the correct PR number(s)
- Changelog <= 20 entries
- Up Next surfaced; roadmap marker checked
- Changelog committed and riding the final PR (or its fallback branch), handed off for the founder to merge
