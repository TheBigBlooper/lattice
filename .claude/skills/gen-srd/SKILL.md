---
name: gen-srd
description: Survey a repository - this one or any other local checkout, down to a single microservice - and generate a full System Requirements Document (SRD) for it - numbered shall statements per area, each with a verification method, derived from what the system demonstrably does and enforces rather than from memory. Modeled on docs/replication/system_requirements.md.
---

# Generate a System Requirements Document

Survey a repository and produce the document that answers: **what must a system do and be to count as this system?** Stated as numbered, testable **shall** statements - the binding checklist a rebuild, an audit, or an acceptance run is verified against.

**Why this exists.** A codebase encodes hundreds of requirements nobody ever wrote down as requirements: they live in tests, gates, config, decision registries, and load-bearing code shapes. This skill reads them out and writes them as a System Requirements Document (SRD), in the structure proven by [docs/replication/system_requirements.md](../../../docs/replication/system_requirements.md) - the worked example this skill's output should resemble.

---

## Inputs

- **Target repo** - a path. Default: this repository. Any other local checkout works, including a single microservice (the document is then that service's SRD, and says so). If given only a remote URL, clone it to a scratch location first and say where.
- **Output path** - confirm with the founder before writing. Defaults: for this repository, regenerate `docs/replication/system_requirements.md` in place; for an external repo, `<target>/docs/system_requirements.md` if writing into the target is wanted, otherwise a founder-named location.
- **Exclusions** - ask whether any part of the repo is demonstration or example content (this repository excludes its `orders`/`inventory` demo domain). An excluded area is named in the scope section and its requirements generalized ("a domain service shall...") rather than dropped.

---

## Step 1 - Mine requirements from where they actually live

Requirements hide in six places; sweep all of them, because each encodes a different kind:

1. **Decision registries and architecture docs** - the *what* and the locked constraints. The richest source when present, but verify each claim against code before promoting it to a shall.
2. **Tests** - every meaningful assertion is a requirement already stated in executable form. Integration and contract tests are the highest-value seam.
3. **Quality gates and CI config** - coverage floors, style bans, static analysis, supply-chain scans: these are requirements about the build itself.
4. **Interface definitions** - specs and schemas yield the interface-conformance requirements. If an External API document exists (or [/gen-external-api](../gen-external-api/SKILL.md) has produced one), cite it rather than restating shapes.
5. **Deploy config** - security contexts, probes, ports, replica and persistence choices, derivation rules between values.
6. **Load-bearing code shapes** - a shared base class every service extends, a guard mounted in one place, a client that is generated rather than written: structural facts that a rebuild must preserve.

Build a raw candidate list with a source citation per candidate before writing any final statement.

## Step 2 - Shape each statement

- **One requirement, one shall, one testable claim.** If a candidate needs "and" between two independently checkable things, split it.
- **State the behaviour, not the implementation** - except where the implementation *is* the requirement (a shared guard, a single-writer module, a generated client). Then say so plainly, because that structural fact is what a rebuild would otherwise lose.
- **Carry the why only when it prevents a wrong "fix"** - one clause, not a paragraph. Rationale essays belong in the design docs.
- **Choose shall / should / may honestly.** Shall = the system is wrong without it. Should = a strong default with a recorded escape. May = an allowed option. Do not inflate defaults into shalls.
- **Assign a verification method per requirement**: **T** (an automated test proves it), **D** (observed on a running system), **I** (read the artifact). If none fits, the statement is not yet testable - sharpen it or flag it.

## Step 3 - Organize and number

- Group into areas matching the target's real seams (architecture, contract, runtime, data, messaging, identity, observability, user interface, deployment, build quality - use what applies, invent area codes that fit the target).
- Number as `<AREA>-<nnn>`, stable forever: a withdrawn requirement keeps its number and is marked withdrawn, never reused.
- End with an **acceptance area**: the shortest set of end-to-end requirements that mean "this is a faithful instance" (the exemplar's `VER` section).
- Open with a conventions section (shall/should/may, numbering, verification codes) and a scope section (target, exclusions, self-containment note if the document is meant to travel).

## Step 4 - Evidence discipline

- **Every shall traces to evidence** gathered in Step 1 - a file, a test, a config line. Keep the trace in working notes; a self-contained export drops citations, an in-repo document may keep them.
- **Never write a requirement from memory of how such systems usually work.** If the repo does not demonstrate or enforce it, it is not this system's requirement.
- **Mark inference.** Where a requirement is inferred from a single code shape rather than demonstrated by a test or doc, mark it for founder review in the summary; the founder confirms or strikes it.
- **Contradictions are findings**: a doc that claims what a test disproves, a gate that cannot pass. Report them; do not silently pick a side.

## Step 5 - Verify and hand off

- Cross-check: every area of the Step 1 sweep is represented or explicitly excluded; every statement has a verification method; numbering is gapless per area.
- Summarize for the founder: statement count per area, the inferred-not-demonstrated list, and the findings. The founder accepts the document; that is the close gate.

---

## What this is not

- **Not an interface reference.** Exact wire shapes belong in the External API document ([/gen-external-api](../gen-external-api/SKILL.md)); the SRD requires conformance to them in one line.
- **Not a design rationale document.** The why-it-is-this-way narrative is the Software Design Document's job ([/gen-sdd](../gen-sdd/SKILL.md)); the SRD states what must hold, with at most a clause of why.
- **Not a wish list.** A requirement the current system does not meet is a finding to report, never a shall to smuggle in.
