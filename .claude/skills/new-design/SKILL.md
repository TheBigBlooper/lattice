---
name: new-design
description: Start a new design document for Lattice - runs the mandatory one-by-one questionnaire before generating any file in docs/design/*.
---

This skill enforces the Design Session questionnaire requirement from session_protocol.md. No design document may be produced until the full questionnaire is completed.

## Rules

- Questions are asked **one at a time** - never as a list.
- After each question, state how many questions remain (e.g., "3 questions remaining").
- Developer must answer each question before the next is asked.
- If an answer reveals a contradiction or unresolved branch, flag it immediately before continuing.
- Only after **all questions are answered and confirmed** does Claude generate the design file.
- The generated file must reflect every decision made during the questionnaire - no omissions.

## Step 1 - Identify the design area

Ask: **What design area or topic is this document for?**

Then read any existing related design files under `docs/design/` to avoid duplicating decisions already locked in `locked_decisions.md`. Design docs live in four subfolders:
- `docs/design/architecture/` - baseline versioning, mesh discovery, cluster/interop model, deployment shape.
- `docs/design/services/` - per-service specs (each Vert.x microservice).
- `docs/design/features/` - cross-cutting feature specs.
- `docs/design/ui/` - the React status console: the Material UI theme, component standards, proportion system.

## Step 2 - Build the questionnaire

Based on the design area, identify all relevant design decisions and edge cases. Structure them as yes/no or multiple-choice questions. Cover:
- Service boundaries and responsibilities (which service owns what).
- The REST contract (OpenAPI operations) and/or the Artemis mesh envelopes involved, and versioning/interop impact.
- Elasticsearch data model - index/mapping shape, and whether clusters may diverge while staying interoperable.
- State transitions and lifecycle (baseline version, cluster/node status).
- Edge cases (what happens when a peer cluster is unreachable, a field is empty/null, or two clusters conflict).
- MVP scope vs. deferred post-MVP.
- Any interaction with locked decisions in `locked_decisions.md`.

## Step 3 - Run the questionnaire

Ask questions one at a time. After each answer, acknowledge it and ask the next. State the count after each question.

Example format:
> **Question 1:** [question text]
> *(N questions remaining)*

## Step 4 - Confirm all decisions

After all questions are answered, present a summary of every decision made. Ask the developer to confirm the list is complete and accurate before generating the file.

## Step 5 - Generate the design file

Create the file in the matching subfolder at `docs/design/<area>/<filename>.md` (`architecture/`, `services/`, `features/`, or `ui/`). The filename should be lowercase, snake_case, and clearly reflect the domain (e.g., `mesh_discovery.md`, `baseline_versioning.md`, `status_console.md`).

The file must:
- Reflect every decision made in the questionnaire.
- Include locked decision tables where appropriate.
- Reference related files and locked decisions in `locked_decisions.md`.

## Step 6 - Post-generation updates (Tracked sessions only)

After generating the file:
1. Update `docs/reference/locked_decisions.md` - add any new locked decisions in the appropriate section.
2. Update `docs/reference/glossary.md` - add any new terms introduced.
3. Update `docs/protocol/session_protocol.md` File Inventory - add the new file with its purpose and update rule.

## Step 7 - Mockup gate (any UI / status-console surface)

If the design has a **visible surface or user flow** in the React status console (a new panel or view, or a change to how an existing one looks/flows), it is **not** done at the doc. The visual direction must be **confirmed by a founder** before the resulting tickets are worked (Enforcement Rule 16):

- Present the look as **A/B/C mockup options** for a founder to pick; record the chosen direction (on the doc or the ticket).
- **State the proportions.** The doc + the mockup must name the surface's **dominant-to-supporting relationships**, expressed as Material UI grid columns and spacing steps per the proportion system in `docs/design/ui/`. Proportion is a design decision made here, not a build-time guess.
- Until a founder confirms, any status-console ticket cut from this doc carries the **`needs-mockup`** label - surface the gap explicitly ("doc + plan + deliverable, but no confirmed mockup"); never guess visual direction.
- A pure service / contract / mesh / data-model design with no visible status-console surface is exempt.

## Step 8 - Propose the build tickets (never skip)

**A design session is not finished when the document is written.** It is finished when the work it implies exists as tickets, or has been explicitly declined.

Writing "build tickets follow from this" in a design doc feels like a handoff and is not one. Nothing is tracked, the board does not show it, and the work exists only inside a document nobody is watching. This has already happened more than once: a design landed, the follow-on was described in prose, and it was noticed only when a founder asked where the implementation ticket was.

So, immediately after the document is accepted:

1. **Derive the tickets from the document**, in dependency order. Most designs produce more than one, and the order is usually contract, then services, then console - each blocked by the one before it.
2. **State what each blocks**, so the sequence is visible from the board rather than only from the doc.
3. **Present them as a proposal** - title, one-line scope, and a suggested priority each - and **wait for the founder's go** before running `gh issue create`. Creating an issue is founder-gated (Enforcement Rule 12); proposing one is not, and is required here.
4. **A design that produces no build work says so explicitly.** "This settles a question and needs no implementation" is a valid outcome; silence is not.

Founder-gated means *propose immediately and wait for a go*, never *wait to be asked*.
