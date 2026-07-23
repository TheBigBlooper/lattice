---
name: new-issue
description: Create a new GitHub issue for Lattice - fully automated: gh issue create then set a priority label (P0/P1/P2) in one flow.
---

**Founder-gated (Enforcement Rule 12).** Only run this skill once the founder has asked for or approved a new issue. Never create one unilaterally - not even a tracking or follow-on ticket. If you have spotted work that wants an issue, **propose it (title + one-line scope) and wait for "go"** before starting Step 1. (A mechanical backstop also lives in `.claude/settings.json` `permissions.ask`, which prompts before any `gh issue create` runs.)

Execute these steps in order. Do not skip any step.

> **Repo slug.** `gh` commands infer the repo from the local checkout; the issue URL in the output is `https://github.com/<owner>/lattice/issues/<number>`.

## Step 1 - Draft from context, then ask for priority and milestone

Infer **title**, **body**, and **type label** from the current session context - what has been discussed, decided, or identified as needing follow-up.

**Title** follows the convention `<Type>: <short description>`.

**Type label** is derived from the title prefix:
- `Design:` -> `design`
- `Docs:` -> `docs`
- `Service:` -> `service`
- `Contract:` -> `contract`
- `Platform:` -> `platform`
- `UI:` -> `ui`
- `Fix:` -> `fix`
- `Infrastructure:` / `Tooling:` -> `platform`

Present the drafted title, body, and type label to the developer, then ask:
1. **Priority** - `P0`, `P1`, or `P2` (P0 highest).
2. **Milestone** - ask if one applies; if unsure, list open milestones:
   ```bash
   gh api "repos/$(gh repo view --json nameWithOwner -q .nameWithOwner)/milestones" --jq '.[].title'
   ```

Do not proceed until the developer confirms the draft and provides priority.

## Step 2 - Ensure the priority labels exist

Priority is a plain GitHub **label** (`P0` / `P1` / `P2`) - there is no project board and no org-level field. Create the labels once if they do not exist yet (idempotent - ignore "already exists"):

```bash
gh label create P0 --color B60205 --description "Priority 0 - highest" 2>/dev/null || true
gh label create P1 --color D93F0B --description "Priority 1" 2>/dev/null || true
gh label create P2 --color FBCA04 --description "Priority 2" 2>/dev/null || true
```

## Step 3 - Create the issue with both labels

```bash
gh issue create \
  --title "<title>" \
  --body "<body>" \
  --label "<type-label>" \
  --label "<P0|P1|P2>"
```

If a milestone applies, add `--milestone "<milestone title>"`.

Capture the issue URL from the output.

## Step 4 - Confirm

Verify the issue carries the correct type + priority labels:
```bash
gh issue view <number> --json number,title,labels,milestone \
  --jq '"#\(.number) \(.title)\nlabels: \([.labels[].name] | join(", "))\nmilestone: \(.milestone.title // "none")"'
```

Show the developer the issue URL and confirmed priority label.

## Important constraints

- **Do not set parent/child relationships via API** - this is a GitHub UI-only action. Never attempt to set parent or child issue links automatically.
- Priority lives **only** on the label - do not reintroduce a project board or an org-level Priority field.
