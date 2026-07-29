---
name: set-phase
description: Advance the build or governance phase - update the source doc (governance.md) + the README badge together. Fire only when a phase actually graduates (rare).
---

Use this **only when a phase graduates** - it happens a handful of times in the project's life, usually at a session end. Confirm the graduation first (build: the roadmap exit criteria are met and the phase's work is fully closed; governance: the growth-phase criteria in governance.md). Founder judgment is the trigger.

Progress **%** is never set here - it is computed live at session start from the open/closed issue split (see [session-start](../session-start/SKILL.md)). Badges carry the phase **name** only.

> **Repo slug.** `gh` commands infer the repo from the local checkout.

## Build phase

The build phases live in [governance.md](../../../docs/governance/governance.md). Update the source doc **and** the README badge in one change:

1. Confirm the founder agrees the current phase's roadmap exit criteria are met. See [roadmap.md](../../../docs/planning/roadmap.md). If milestones are in use, also confirm the phase's milestone is fully closed (`gh api "repos/$(gh repo view --json nameWithOwner -q .nameWithOwner)/milestones"` - closed == total).
2. Advance the current build phase in the build-phases section of [governance.md](../../../docs/governance/governance.md).
3. Move the `← you are here` marker in [roadmap.md](../../../docs/planning/roadmap.md) to the new phase row.
4. Update the README **Build** badge in the **WHERE WE ARE** section (the `Build-Phase_N_·_<Name>` shields badge) to the new phase name.
5. Add one changelog bullet: `Internal: build phase advanced - Phase N (<old>) -> Phase N+1 (<new>).`

## Governance phase

1. Confirm the growth-phase criteria in [governance.md](../../../docs/governance/governance.md) are met (founder judgment).
2. Update the current growth phase in [governance.md](../../../docs/governance/governance.md).
3. Update the README **Governance** badge in the **WHERE WE ARE** section to the new phase name.
4. Add one changelog bullet for the governance phase change.

## Rules

- Update the **source doc (governance.md) AND the README badge in the same change** - they must never disagree.
- **Never bake a progress % into a doc or badge** - it drifts on every merge. The name is the only stored signal; the % is the live issue-count read.
