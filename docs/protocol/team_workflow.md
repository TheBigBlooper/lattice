# Lattice - How We Build (Team + Multi-Agent Workflow)

This is the **human onboarding doc** for how the Lattice team develops day-to-day with Claude Code and multiple agents. It tells the story; the **canonical rules** live in the protocols this page links to, so nothing here is duplicated - if a rule and this page ever disagree, the protocol wins.

New here? Read [README.md](../../README.md) (what Lattice is), then [DEVELOPMENT.md](../../DEVELOPMENT.md) (get it running on your machine), then this page (how we work).

> **The loop in one breath:** pick the top ticket from the `/session-start` dashboard, branch off `dev`, build it test-first to green, push for local QA, then open a PR the founder merges into `dev`. PRs land through the day; when you're done, run `/log-work` with your final PR to fold the day's work into one changelog entry - `dev -> main` is a separate, founder-only promotion. Each beat is detailed below.

---

## Who works on Lattice

Lattice is a solo project (Nick) for now, but the workflow is built to support more than one person. You develop in **agent mode**: a `claude` running in the repo that delegates to role agents. You may run **more than one instance at the same time**, on different tickets, sometimes each with a service and a UI agent live at once. The whole point of the rules below is that concurrent instances running several agents never step on each other.

---

## Two senses of "agent" (read this first)

The word "agent" means two different things here, and the difference is the whole game:

- **In-session subagent** (`service` / `ui` / `contract` / `platform`) - one `claude`, **one branch**. It delegates a scoped piece of work to a child agent that reports back into the same session. This is *not* parallel work across tickets; it is one stream that routes by surface. Defined in [session_protocol.md](session_protocol.md#working-with-agents--skills).
- **A second `claude` instance** - one on one branch, another on a second branch, each possibly delegating to role subagents. **This** is the real parallelism. It is a heavier setup (a worktree per branch) and it is where overlap can actually hurt.

So "two instances each running a service and UI agent" = **two `claude` instances**, each with its own worktree and branch. Everything in [Concurrency](#concurrency-two-people-many-agents) is about keeping those two instances disjoint.

---

## The agents and what each owns

One agent owns one surface; each defers to its protocol rather than restating it.

| Agent      | Surface                                                                                                             | Protocol                                     |
|------------|---------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| `service`  | the Vert.x microservices (`services/*`) + the Elasticsearch data layer (`platform/lattice-common`)                  | [service_protocol.md](service_protocol.md)   |
| `ui`       | the React status console (`ui/status-console`)                                                                      | [ui_protocol.md](ui_protocol.md)             |
| `contract` | the versioned **seam** - OpenAPI REST specs + the `platform/lattice-contract` mesh envelopes                        | [contract_protocol.md](contract_protocol.md) |
| `platform` | Docker images, K8s/Helm manifests, the Artemis mesh (broker + discovery), the local kind stack, deploy (`deploy/*`) | [platform_protocol.md](platform_protocol.md) |

`contract` owns only that both sides of the seam agree - the REST shape a service serves and the console consumes, and the mesh envelope peer clusters exchange; it delegates the deep service and UI work to `service` and `ui`.

---

## The loop (one ticket, pick to merge)

Work is continuous - this loop runs ticket after ticket, with no per-session ritual around it. You produce the [live status dashboard](session_protocol.md#status-check--pick-formerly-session-start) whenever you want to see where things stand, pick the next ticket, and run the loop; the only end-of-day step is the changelog wrap.

0. **Know the deliverable** before anything else - the concrete artifact or observable outcome that means "done" (the type map is in [core_protocol.md - Deliverable-first](core_protocol.md#deliverable-first-know-the-outcome-before-you-start)). If it is unclear, **stop and ask a founder, or run a design session** - never assume.
1. **Pick the ticket** from the [live dashboard](session_protocol.md#status-check--pick-formerly-session-start) ([session-start](../../.claude/skills/session-start/SKILL.md)), highest priority label first (`P0` -> `P1` -> `P2`).
2. **Claim it (the `in-progress` label + self-assign) before you touch code** - so the other founder can see you are on it. See [In-Progress discipline](#in-progress-discipline-the-coordination-signal).
3. **Branch `<type>-<issue>-<slug>` off `dev`** in your own worktree (the tag is `lat`, e.g. `lat-12-mesh-discovery`).
4. **Assign the matching agent** a grounded spec - real file paths, the OpenAPI operation / mesh envelope / theme tokens to honor, the exact failing test to write.
5. **Agent runs test-first to green** (red shown first, then implementation, then `./mvnw verify` + the [blocking CI gates](core_protocol.md#ci-gates---all-blocking)); it self-audits the diff against its protocol.
6. **You verify independently** - re-run the tests; bring the affected service(s) up on the local kind stack for anything observable in the running cluster or console. Never merge on the agent's word alone.
7. **Push; comment "Ready to test"; apply `needs-qa`.** Founder local-QA per [qa_protocol.md](qa_protocol.md) (build the module + run the cluster); on pass, `qa-passed`.
8. **Open the PR into `dev`**; CI green, merge to `dev`. `dev -> main` is a separate, founder-only promotion.

**End of the day (not per ticket):** when you are done, run [`/log-work`](../../.claude/skills/log-work/SKILL.md) with your **final PR** - it previews the day's changelog entry (one per person per day), and on your approval writes it riding that PR. See [Ending the day](#ending-the-day-the-changelog).

The full branching rules are in [core_protocol.md](core_protocol.md#branching-model-dev-integration); the local-QA workflow is in [qa_protocol.md](qa_protocol.md).

---

## Concurrency (two people, many agents)

Two `claude` instances are safe **as long as concurrent branches never touch the same files.** The canonical rule set is [core_protocol.md - Concurrent branches](core_protocol.md#concurrent-branches-multi-agent); the practical version:

- **A worktree per branch.** Each `claude` runs in its own `git worktree` checkout, so two sessions never share a working tree.
- **Partition by disjoint file sets, not just "service vs UI."** Two service tickets can still collide on one shared verticle in `lattice-common`. Before you start parallel work, agree on who owns which files.
- **The seam is single-writer.** Exactly one in-flight branch at a time may touch any of:
  - `platform/lattice-contract` (the OpenAPI specs + mesh envelope records)
  - Elasticsearch mappings in `platform/lattice-common`
  - `docs/changelog.md`

  Land a contract/mapping change **first**, then fan out the service + console work that depends on it. Two `contract` agents must never run against `platform/lattice-contract` at once.
- **Safe parallel pairing:** one instance on a service ticket (`services/<a>`, its ES mappings) + another on a disjoint status-console panel = zero file overlap.

### In-Progress discipline (the coordination signal)

There is no project board - the **`in-progress` label** is the live "who is touching what." **Any ticket an agent is actively working gets the `in-progress` label before the work starts** - this is how a concurrent instance avoids grabbing a branch that hits the same files. Claiming also means self-assigning the issue, so the assignee shows at a glance how many in-progress tickets are held (`gh issue list --label in-progress`). Rule and mechanics: [session_protocol.md - GitHub Issues + Priority Labels](session_protocol.md#github-issues--priority-labels).

---

## Tickets stay minimal

This is a small proof-of-concept built for local QA. **Keep tickets to the bare minimum** - do not split a coherent unit of work into a pile of sub-tickets. One ticket = one shippable, QA-able change. The full rule is [session_protocol.md - Enforcement](session_protocol.md#enforcement-rules) (Minimal-ticket rule).

---

## Removing vs deferring a feature

When work takes a concept **out**, know which kind it is:

- **Replacement** - the old thing is gone for good. Remove it completely in the same change (no commented-out blocks, no orphan files): grep the full hit list, categorize real references vs incidental word matches, delete in dependency order, re-grep to confirm **zero orphans**. This is [Enforcement Rule 10](session_protocol.md#enforcement-rules).
- **Deferral** - the concept is parked for later, not killed. Remove it from the **product surface** (copy, status-console UI, user-facing vocabulary, design docs) but **keep dormant data-layer scaffolding** (Elasticsearch mappings, envelope fields) that a future re-enable needs, and record the deferral as **one re-locked decision** in [locked_decisions.md](../reference/locked_decisions.md). Do not over-delete - a blanket grep-and-nuke would tear out the mapping you want to keep.

The rule lives next to Enforcement Rule 10: [session_protocol.md - Enforcement](session_protocol.md#enforcement-rules).

---

## Ending the day (the changelog)

The changelog is a **per-day, per-person** record, not a per-session one.

- **Ending a session is not the same as ending the day.** Wrapping a session writes nothing - your work lives in the branch commits and the PR. The changelog is written only when you are **done for the day**, by running [`/log-work`](../../.claude/skills/log-work/SKILL.md) with the **final PR** of the day; running it is the "done for the day" signal.
  - *Preview first* - `/log-work` gathers what you landed today (your merged PRs since your last entry, plus the final PR being opened now), then **shows you the entry exactly as it will appear and writes nothing until you approve**.
  - *Forgot to log?* - if the final PR is already submitted, cut a fresh `docs-changelog-<date>` branch and run `/log-work` on that PR.
- **One entry per person per day.** Multiple sessions in a day fold into that one dated entry as additional bullets - they never each prepend a new block (which would collide on the top of the file).
- **Bulleted, not a wall of text** - a title, a category tag, and one bullet per ticket/PR.

Format and mechanics: [log-work skill](../../.claude/skills/log-work/SKILL.md) and the `Changelog format` section at the bottom of [changelog.md](../changelog.md).

---

## Where the rules actually live

| You want                                                        | Read                                                                                                                        |
|-----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| What Lattice is                                                 | [README.md](../../README.md)                                                                                                |
| Run it on your machine                                          | [DEVELOPMENT.md](../../DEVELOPMENT.md)                                                                                      |
| Session start/end, issues + labels, enforcement, file inventory | [session_protocol.md](session_protocol.md)                                                                                  |
| TDD loop, branching, concurrency, naming, CI gates              | [core_protocol.md](core_protocol.md)                                                                                        |
| The role surfaces                                               | [service](service_protocol.md) · [ui](ui_protocol.md) · [contract](contract_protocol.md) · [platform](platform_protocol.md) |
| Local build-and-run QA                                          | [qa_protocol.md](qa_protocol.md)                                                                                            |
| The locked canon                                                | [locked_decisions.md](../reference/locked_decisions.md)                                                                     |
| The Claude-facing instruction layer                             | [CLAUDE.md](../../CLAUDE.md)                                                                                                |
