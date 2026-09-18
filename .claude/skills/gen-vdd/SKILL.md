---
name: gen-vdd
description: Generate a Version Description Document (VDD) for a specific release of a repository - this one or any other local checkout - the exact inventory of one shipped version: component versions and image digests, changes since the previous version, resolved and known issues, and the installation and upgrade notes, all derived from tags, history, and build metadata rather than memory.
---

# Generate a Version Description Document

Produce the document that accompanies **one specific shipped version**: exactly what is in it, exactly what changed since the last one, and exactly what the receiver must do to install or upgrade. A Version Description Document (VDD) is per-release and disposable-by-succession - the next release gets its own - which is why it is a generated artifact, not a maintained one.

**Why this exists.** "What is actually in the delivery" is answerable only at the moment of the release, from the tag, the build metadata, and the history since the prior tag. Reconstructed later or from memory it is guesswork wearing a table. This skill derives it while the answer is still checkable.

---

## Inputs

- **Target repo** - a path. Default: this repository. Any other local checkout works. If given only a remote URL, clone it to a scratch location first and say where.
- **The version** - a tag or ref to describe. Default: the latest version tag. Also establish the **previous** version tag; the document describes the delta between the two. If no tags exist, ask what marks a release here before proceeding.
- **Output path** - confirm with the founder before writing. Default: a versioned file the release can carry (for example `docs/vdd/<version>.md` in the target, or alongside the delivery artifacts); never overwrite a prior version's document.

---

## Step 1 - Fix the identity of the release

Everything else hangs off two commits. Record, from the repository rather than from prose:

- The version string, the tag, and the exact commit it points at.
- The previous version's tag and commit.
- Whether the tag builds clean: note if the working tree at that ref would fail its own gates, because a delivery that cannot rebuild itself is a finding, not a footnote.

## Step 2 - Inventory what ships

The heart of the document: the complete, exact bill of what the receiver gets.

- **Deliverable artifacts** - the images, archives, charts, or packages that constitute the delivery, each with its immutable tag or digest. If the project's delivery model documents how artifacts are exported, follow it; in this repository a delivery is exported image archives plus the Helm chart, tagged `<version>-<sha>`.
- **Component versions** - each shipped component (services, front end, chart) and its version at this tag.
- **Pinned third-party runtime versions** - the infrastructure images and major dependencies the release was built and verified against, read from the build files at the tag, not from the latest checkout.
- **Configuration surface changes** - environment variables and chart values added, renamed, or removed since the previous version, each with its default. A renamed variable is the single most common silent upgrade break; hunt for these specifically in the config diff.

## Step 3 - Derive the changes since the previous version

Work from `git log <prev>..<this>` plus the merged pull requests and any changelog entries in that range - and reconcile them, since each source misses things the others carry.

- **Changes** grouped as features, fixes, and internal, one line each in receiver-facing language (what it does for them, not the ticket title).
- **Resolved issues** - defects fixed in this version, named by symptom.
- **Known issues and limitations** - open defects and documented gaps that ship with this version. Absence of this section reads as "there are none", so write it honestly or write "none known".
- **Compatibility notes** - contract or wire changes in the range: new operations, new envelope versions, anything a peer or client generated against the previous version should know. Breaking changes are called out first and plainly.

## Step 4 - Installation and upgrade notes

What the receiver must do, exactly, in order:

- Fresh-install steps (or a pointer to the install runbook plus only what this version changes about it).
- **Upgrade steps from the previous version specifically** - rebuilds, migrations, reindexes, certificate re-issues, config edits, pod rolls - each with its exact command where one exists. This repository's changelog "Heads up" blocks are the raw material for this section.
- Verification: how the receiver confirms the upgrade took (the version surfaced in the running system, a smoke check, a scenario run).

## Step 5 - Evidence discipline, verify, hand off

- **Everything derives from the two tags** - the diff, the log, the build files at each ref. Nothing is written from memory of what "went in lately".
- Versions and digests are read from build output or metadata, never retyped by hand.
- Cross-check: every artifact in Step 2 exists and its tag resolves; every config change in the diff appears in Step 2's list; the change list accounts for the full commit range (spot-check that no merged PR in the range is unrepresented).
- Summarize for the founder: the delta size, the breaking or upgrade-relevant items, and any findings (a tag that does not build, an undocumented config rename). The founder accepts the document; that is the close gate.

---

## What this is not

- **Not release notes.** Release notes are the general-audience narrative (this repository's `/release-notes` skill); the VDD is the exact engineering inventory. They cite each other and do not merge.
- **Not a changelog.** The changelog accumulates across versions; a VDD describes exactly one and is superseded whole by the next version's.
- **Not a requirements, design, or interface document** - those describe the system across versions ([/gen-srd](../gen-srd/SKILL.md), [/gen-sdd](../gen-sdd/SKILL.md), [/gen-external-api](../gen-external-api/SKILL.md)); the VDD describes one delivery of it.
