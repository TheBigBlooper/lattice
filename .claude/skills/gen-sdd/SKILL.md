---
name: gen-sdd
description: Survey a repository - this one or any other local checkout, down to a single microservice - and generate a full Software Design Document (SDD) for it - the architecture, module decomposition, component designs, data design, and the mechanisms and rationale a rebuilder must understand, derived from source and docs rather than memory. Modeled on docs/replication/software_design.md.
---

# Generate a Software Design Document

Survey a repository and produce the document that answers: **how is this system put together, and why that way?** The decomposition, the responsibilities, the key mechanisms, and the rationale behind the non-obvious choices - the narrative a rebuilder reads before the requirements checklist means anything.

**Why this exists.** Requirements say what must hold and an interface document says what is served, but neither explains the shape: why the modules cut where they do, how a request or message actually travels, which design choices are load-bearing and which are habit. This skill writes that as a Software Design Document (SDD), in the structure proven by [docs/replication/software_design.md](../../../docs/replication/software_design.md) - the worked example this skill's output should resemble.

---

## Inputs

- **Target repo** - a path. Default: this repository. Any other local checkout works, including a single microservice (the document is then that service's design, and says so). If given only a remote URL, clone it to a scratch location first and say where.
- **Output path** - confirm with the founder before writing. Defaults: for this repository, regenerate `docs/replication/software_design.md` in place; for an external repo, `<target>/docs/software_design.md` if writing into the target is wanted, otherwise a founder-named location.
- **Exclusions** - ask whether any part is demonstration or example content (this repository excludes its `orders`/`inventory` demo domain); describe the pattern it demonstrates generically instead of the excluded thing itself.

---

## Step 1 - Recover the structure before describing it

1. **The module graph.** Build files first (a Maven reactor, a workspace, package manifests): what depends on what, and what is deliberately outside the build (a front end held to the contract by a generator script is a design fact). Draw it; the graph is the document's spine.
2. **The runtime topology.** Processes, containers, their ports and links, brokers and stores between them, from deploy config rather than prose.
3. **The shared spine.** The base classes, shared clients, and single-writer modules everything else leans on. What every component inherits rather than reimplements is the core of the design.
4. **The mechanisms.** For each non-obvious behaviour (discovery, liveness, federation, auth flow, health rollup, bootstrap, retry): trace the actual code path end to end before writing a word about it.
5. **The recorded rationale.** Decision registries, design docs, and substantial class documentation. Where the reasoning for built code lives only in source, say so - a rebuilder must know which parts have no doc to consult.

## Step 2 - Write the document

Follow the exemplar's skeleton, adapted to what the target has (omit what has no counterpart; never pad):

1. **Introduction** - purpose, scope, exclusions, relationship to the sibling documents (requirements, external interfaces), self-containment note if the document is meant to travel.
2. **Architectural overview** - the system in one page: context, runtime topology diagram, and the handful of principles that explain most later choices.
3. **Module decomposition** - the module graph with each module's single responsibility and its dependency rationale.
4. **Component designs** - one subsection per major component: responsibility, internal structure, the mechanisms it owns (with a sequence or state diagram where order or lifecycle is the point), and its non-obvious choices with a one-clause why.
5. **Data design** - stores, schema strategy and evolution, what is deliberately in-memory or derived.
6. **Cross-cutting design** - error handling, logging, configuration, time, security posture: the rules every component obeys.
7. **Design rationale register** - the short table of decisions a rebuilder would plausibly "fix" back: the choice, the alternative it beat, and why, one line each.

Diagram rule: Mermaid, and only where prose would describe a shape ("A connects to B which sends to C" is a diagram wearing a sentence). Pick the type from what the thing is: flowchart for topology, sequence for exchanges, state for lifecycles.

## Step 3 - Evidence discipline

- **Describe the code that exists, not the design that was intended.** Where they differ, the code is the design and the difference is a finding.
- **Trace every mechanism narrative through the source** before writing it; keep citations in working notes (an in-repo document may keep them; a self-contained export drops them).
- **Flag, never fill.** A mechanism you could not trace gets an explicit gap marker, not a plausible story.
- **Separate rationale from reconstruction.** Recorded rationale can be stated as the system's reasoning; rationale you inferred is marked as inferred and listed for founder review.

## Step 4 - Verify and hand off

- Cross-check: every module in the graph appears; every mechanism listed in Step 1 is designed or explicitly gapped; every diagram parses.
- Summarize for the founder: component count, the inferred-rationale list, the gaps, and any code-versus-doc findings. The founder accepts the document; that is the close gate.

---

## What this is not

- **Not a requirements document** - what must hold is [/gen-srd](../gen-srd/SKILL.md)'s job; this explains the shape that satisfies it.
- **Not an interface reference** - exact wire shapes are [/gen-external-api](../gen-external-api/SKILL.md)'s job; this describes how they are served, once.
- **Not a code walkthrough.** Classes appear because they carry a responsibility or a decision, not because they exist. A file-by-file tour is an index, not a design.
