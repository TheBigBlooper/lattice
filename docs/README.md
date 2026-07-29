# Lattice docs

**The hub - find anything from here in one or two clicks,** high-level (what Lattice is, how we work) down
to in-the-weeds (a specific protocol rule, a tooling detail). Repo overview is the root [README.md](../README.md); local dev setup is DEVELOPMENT.md (TBD - lands with the code).

This page is the **navigation map** (what each doc/folder is). Content lives in the leaf docs and is defined **once** - this index only links, never restates. Per-file **update rules** are the File Inventory in session_protocol.md - the single source for *when* to touch each doc.

## Start here (onboarding path)

1. **What is Lattice** - a Java 21 / Vert.x 5 microservice system; each service is a Docker container, a cluster of them in one Kubernetes cluster is a versioned baseline, and separate clusters discover + communicate over an Artemis-backed mesh with interoperable Elasticsearch data models. A React status console ships per cluster.
2. **Set up your dev environment** - DEVELOPMENT.md (planned - written with the toolchain + skeleton, #1 / #3).
3. **How we build** - [team_workflow.md](protocol/team_workflow.md): the Claude + multi-agent workflow (narrative).
4. **The rules** - the [protocol/](#protocol) tables below (session, core, service, ui, contract, qa, deploy, platform).
5. **The tools** - [Skills](#skills) (slash-commands) · [Agents](#agents) · [integrations.md](reference/integrations.md) (external services).

| Path                         | What's here                                                |
|------------------------------|------------------------------------------------------------|
| [changelog.md](changelog.md) | Rolling per-day work log (20-entry max).                   |
| [protocol/](protocol)        | How we work and how we build - the protocols.              |
| [reference/](reference)      | Canonical reference material.                              |
| [governance/](governance)    | Philosophy, non-goals, build phases.                       |
| [design/](design)            | System, service, feature, and status-console design specs. |
| [planning/](planning)        | Roadmap + sprints.                                         |

## protocol/

| File                                                  | Purpose                                                                                  |
|-------------------------------------------------------|------------------------------------------------------------------------------------------|
| [team_workflow.md](protocol/team_workflow.md)         | **Start here:** how we build with Claude + multi-agent (onboarding narrative).           |
| [session_protocol.md](protocol/session_protocol.md)   | Session lifecycle, enforcement rules, issue ranking, file inventory.                     |
| [core_protocol.md](protocol/core_protocol.md)         | Shared dev core: TDD, branching, folder structure, naming, Java style, commits, Javadoc. |
| [service_protocol.md](protocol/service_protocol.md)   | Vert.x services + Elasticsearch data-layer rules.                                        |
| [ui_protocol.md](protocol/ui_protocol.md)             | Status-console (Vite + React) rules.                                                     |
| [contract_protocol.md](protocol/contract_protocol.md) | The OpenAPI specs + `lattice-contract` mesh-envelope seam.                               |
| [qa_protocol.md](protocol/qa_protocol.md)             | QA workflow.                                                                             |
| [deploy_protocol.md](protocol/deploy_protocol.md)     | Build / deploy / environments / release.                                                 |
| [platform_protocol.md](protocol/platform_protocol.md) | Docker images, K8s/Helm, the Artemis mesh, packaging + operational concerns.             |

## skills

Slash-commands run in a session (`/skill-name`). The canonical index - each skill's own
`.claude/skills/<name>/SKILL.md` `description:` frontmatter is the source for its one-liner; this table
is the human-readable index (CLAUDE.md links here rather than keeping a second copy).

| Skill            | Purpose                                                                                                                                                                                 |
|------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/session-start` | Reads context, ranks open issues by P0/P1/P2 label (no board), prompts session type.                                                                                                    |
| `/log-work`      | End-of-day changelog entry; Heads-up commands are Maven/UI (`./mvnw install`, reindex, `docker compose up`).                                                                            |
| `/new-design`    | Mandatory one-by-one questionnaire before any `docs/design/*` file.                                                                                                                     |
| `/new-issue`     | `gh issue create` + set a priority label `P0/P1/P2` (no project board, no org field).                                                                                                   |
| `/promote`       | Sandbox -> Tracked: verify/create issue, create `lat-<n>-<slug>` branch.                                                                                                                |
| `/release-notes` | General-audience notes from changelog -> `docs/releases.md`.                                                                                                                            |
| `/set-phase`     | Advance build/governance phase; update source doc + README badge.                                                                                                                       |
| `/sprint-plan`   | Plan a sprint; UI features gated on a founder-confirmed mockup (applies to status console); rank by label.                                                                              |
| `/new-contract`  | Add/change an OpenAPI operation or a `lattice-contract` envelope; wire across service + status console; test-first both sides.                                                          |
| `/new-endpoint`  | Scaffold a Vert.x route under `/api/v1` with its OpenAPI operation + a failing contract/integration test first.                                                                         |
| `/new-panel`     | Scaffold a React status-console component/panel with a failing test first, theme tokens, smoke check.                                                                                   |
| `/index-change`  | Change an Elasticsearch mapping/index + the spec-driven integration test in the same change (the documented TDD exception - a mapping cannot be queried until it exists).               |
| `/new-service`   | Scaffold a new Vert.x microservice: Maven module under `services/`, a BaseVerticle subclass, Dockerfile, K8s manifest stub, baseline/mesh registration, and a failing smoke test first. |
| `/qa-steps`      | Produce a numbered step-by-step QA script for a branch: exact action, exact expected result, and a place to record pass/fail per step, so feedback names a step rather than a feeling.  |

## agents

Role subagents delegated to via the Agent tool; each owns a surface and defers to the matching protocol.

| Agent      | Use for                                                                                                                                                       |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `service`  | Vert.x services + Elasticsearch data layer ([service_protocol.md](protocol/service_protocol.md)).                                                             |
| `ui`       | the React status console ([ui_protocol.md](protocol/ui_protocol.md)).                                                                                         |
| `contract` | the OpenAPI + `lattice-contract` mesh-envelope seam ([contract_protocol.md](protocol/contract_protocol.md)).                                                  |
| `platform` | Docker images, K8s/Helm, the Artemis mesh, deploy ([platform_protocol.md](protocol/platform_protocol.md), [deploy_protocol.md](protocol/deploy_protocol.md)). |

## reference/

| File                                                 | Purpose                                                                      |
|------------------------------------------------------|------------------------------------------------------------------------------|
| [locked_decisions.md](reference/locked_decisions.md) | The locked-decisions registry (the canon).                                   |
| [glossary.md](reference/glossary.md)                 | Shared vocabulary.                                                           |
| [integrations.md](reference/integrations.md)         | External-service setup runbook.                                              |
| [example_domain.md](reference/example_domain.md)     | The illustrative use case the services model (regional fulfillment network). |

## governance/

| File                                      | Purpose                              |
|-------------------------------------------|--------------------------------------|
| [governance.md](governance/governance.md) | Philosophy, non-goals, build phases. |

## design/

| Folder                               | Purpose                                                 |
|--------------------------------------|---------------------------------------------------------|
| [architecture/](design/architecture) | System + mesh + deployment architecture.                |
| [services/](design/services)         | One spec per microservice (the Service Spec Lifecycle). |
| [features/](design/features)         | Cross-cutting feature design.                           |
| [ui/](design/ui)                     | Status-console design + design tokens.                  |

## planning/

| File                              | Purpose                        |
|-----------------------------------|--------------------------------|
| [roadmap.md](planning/roadmap.md) | Build phases + "you are here". |
| [sprints/](planning/sprints)      | Per-sprint plans.              |

## docs/ tree at a glance

```
docs/
  README.md            this navigation hub (links only)
  changelog.md         newest-first work log; format section at the bottom
  protocol/            the 9 protocol docs (how we work + how we build)
  reference/           locked_decisions, glossary, integrations, example_domain
  governance/          governance.md (philosophy, non-goals, build phases)
  design/              architecture/ services/ features/ ui/ (each an _index.md this session)
  planning/            roadmap.md + sprints/
```
