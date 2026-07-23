---
name: release-notes
description: Generate the general-audience release notes for a Lattice baseline release - distill the changelog since the last release into short operator/integrator-facing notes, preview them, and on approval write docs/releases.md.
argument-hint: "[version]"
---

`/release-notes` is the **release-time** companion to `/log-work`. Where `/log-work` writes the per-day, dev-facing changelog (ticket/PR links, internal detail), this skill produces the **per-release, general-audience release notes** for a Lattice **baseline** cut - run it **when you tag a baseline release**, not daily. It is **derived from the changelog** (one source of truth, no double-bookkeeping) and **previews before writing**, exactly like `/log-work`.

**Audience - operators and integrators.** Lattice is a backend platform (versioned services + versioned REST endpoints in a Kubernetes cluster, plus the Artemis mesh and the status console). The people reading these notes **run** a cluster or **integrate** with its REST contract / mesh envelopes across clusters. So the notes are general-audience but **operationally honest**: they say what a new baseline **lets an operator/integrator do or rely on**, and they call out anything that affects how a cluster is **deployed, upgraded, or interoperates**. Unlike a consumer app store, the REST endpoints and mesh contract **are** the product here - they belong in the notes.

## Step 1 - Version + range

- **Version:** `$ARGUMENTS` if given, else the **baseline version** = the parent Maven project version. Run this **after** any deliberate version bump, so it reads the version the release will ship under.
  ```bash
  ./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout
  ```
  (TBD - if a dedicated baseline/version marker is introduced separately from the Maven project version, read that instead.)
- **Range (since the last release):** read the top of `docs/releases.md` for the last shipped version + its date. Gather every `docs/changelog.md` entry **newer than that date** as the raw material; cross-check against merged PRs in the window for anything operator- or integrator-facing the changelog missed. If `docs/releases.md` does not exist yet, this is the **first release** - use the whole changelog.

## Step 2 - Distill to general-audience bullets

Translate the dev changelog into **3-6 short, benefit-first bullets an operator or integrator understands**:

- **No internal noise** - no `#NNN`, no PR links, no "refactor / rename / test tidy". Say what an operator can now **do**, what got **more reliable**, or what changed in **how they deploy or integrate**.
- **Keep contract + operational changes** - new or changed REST endpoints, mesh envelope changes, an Elasticsearch mapping/reindex step, a new service, a config/deploy change. These matter to the audience and belong in the notes (call out any upgrade/reindex action clearly).
- **Drop purely internal work** - build tooling, docs, CI, pure refactors with no observable effect. A release of only-internal work gets a generic line (e.g. "Stability and performance improvements").
- **Group, do not enumerate** - many tickets about one capability become one bullet.
- **Plain sentence case, present-tense benefit** - "Clusters now discover peers automatically over the mesh", not "feat(mesh): discovery verticle".

## Step 3 - Preview + approve (write nothing before this)

Render **both** of the following as fenced blocks in chat, then **stop and wait for founder approval** (adjust on request; nothing is written, committed, or pushed first):

1. The `docs/releases.md` entry (Step 4 shape).
2. The **paste block** - the same bullets as **plain text, no markdown** (ready to drop into a GitHub Release / release announcement).

## Step 4 - Format (docs/releases.md, newest first)

```
## v<X.Y.Z> - YYYY-MM-DD

- <operator/integrator-facing bullet>
- <operator/integrator-facing bullet>
- <operator/integrator-facing bullet>
```

Rules:
- **Newest entry at the top**, under the file header. Never an em dash anywhere.
- **One block per released baseline version.** If `/release-notes` is re-run for a version already at the top (notes not yet shipped), update that block rather than adding a second.
- The release paste field is exactly these bullets as **plain text** (strip the `## v...` heading and any markdown).

## Step 5 - Write + hand off (only after approval)

- Insert the entry at the top of `docs/releases.md` via str_replace (create the file with a `# Releases` header on the first release) - never rewrite in full.
- Print the **plain-text paste block** again, on its own, so it is ready to copy into the GitHub Release / announcement.
- The release-notes edit **rides on the release branch / PR** (or a `docs-releases-v<X.Y.Z>` branch if none is open). PR into `dev` only (never `main`, founder-only); body ends with `Co-authored w/ Claudio`; the **founder merges**.

## Step 6 - Confirm

- Bullets are general-audience, operator/integrator-facing, derived from the changelog since the last release
- Entry previewed and founder-approved **before** anything was written
- `docs/releases.md` updated (newest first); paste block printed for copy
- Versioned to the baseline (Maven project) version, or the `$ARGUMENTS` override
