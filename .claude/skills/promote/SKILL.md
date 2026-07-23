---
name: promote
description: Promote an active Sandbox session to a Tracked session - verify or create a GitHub issue, create the correctly named lat-<n>-<slug> branch, and continue as Tracked.
---

Use this skill when the developer says "promote to tracked" during a Sandbox session.

> **Repo slug.** `gh` commands infer the repo from the local checkout.

## Step 1 - Identify or create the issue

Ask: **What GitHub issue number is this session being promoted against?**

If no issue exists yet, run the `/new-issue` skill first to create one (founder-gated), then return here.

Verify the issue exists and is open:
```bash
gh issue view <number> --json number,title,state,labels \
  --jq '"#\(.number) [\(.state)] \(.title)\nlabels: \([.labels[].name] | join(", "))"'
```

Confirm the issue is `OPEN` and carries a priority label (`P0` / `P1` / `P2`). If it has no priority label, set one before promoting.

## Step 2 - Determine the branch name

Branch naming convention: `lat-<issue-number>-<slug>`

- `lat` is the fixed Lattice git-issue tag.
- `<slug>` is a short kebab-case feature slug drawn from the issue title (e.g. issue #12 "Service: mesh discovery" -> `lat-12-mesh-discovery`).

Check recent branch names to confirm the pattern in use:
```bash
git branch -a | head -20
```

## Step 3 - Confirm the branch name with the developer

State the proposed branch name (e.g., `lat-12-mesh-discovery`) and ask the developer to confirm before creating it. (Creating a new branch is also gated by `.claude/settings.json` `permissions.ask`.)

## Step 4 - Create the branch off dev

Cut the branch from up-to-date `dev` (never off `main`):
```bash
git checkout dev && git pull --ff-only
git checkout -b lat-<number>-<slug>
```

Confirm the branch was created:
```bash
git branch --show-current
```

## Step 5 - Switch to Tracked mode

Self-assign the issue so a concurrently-working founder sees who owns it:
```bash
gh issue edit <number> --add-assignee @me
```

State: **"Session promoted to Tracked. Branch `lat-<number>-<slug>` is active. All Tracked session rules now apply."**

From this point:
- Every change must be committed to this branch.
- A changelog entry is required at the end of the day (`/log-work`).
- A PR into `dev` (never `main`) must be opened; the founder merges.
- The full Tracked close checklist applies.
