# Change Log

<!-- Newest entry first. The format rules live in the "Changelog format" section at the bottom of this file. -->

---

2026-07-22 19:28 MDT
Nick

## Bootstrap Lattice repo - workflow + documentation scaffolding

[internal]
- **Established the Claude operating system** (CLAUDE.md, `.claude/` agents + skills, `docs/protocol` + `docs/reference`), adapted to Java 21 / Vert.x 5 / Elasticsearch / Kubernetes / Artemis mesh / React status console.
- **Scaffolding laid down:** 4 role agents (service, ui, contract, platform); 13 skills; 9 protocol docs; 4 reference docs (incl. the regional-fulfillment example domain); seeded locked decisions for the fixed stack.

Tickets: Bootstrap commit; no ticket/PR.

**Heads up:** nothing to run yet - this session is scaffolding only. Service code, Maven modules, and the status console land in later sessions.

---

## Changelog format

Rolling log of work, **newest at top** - this format section stays at the bottom. **One entry per person per day** (not per session or per ticket) - written at **end of day**, with one bullet per ticket/PR and a `Tickets:` footer listing every ticket. **Max 20 entries** - drop the oldest when exceeded.

The changelog is written only when a founder runs [`/log-work`](../.claude/skills/log-work/SKILL.md) - the end-of-day wrap, with the final PR of the day; running it is the "done for the day" signal. The skill **previews the entry exactly as it will appear and writes nothing until the founder approves**. Just ending a session without it writes nothing - the work lives in the branch commits + PR. Multiple sessions in one day **fold into that day's single entry** as more bullets; never a second block for the same person + day. `changelog.md` is single-writer (every entry edits the top of the file), so it is written once at end of day, never on concurrent branches.

Each entry, in order:

1. A **date line** - `YYYY-MM-DD HH:MM TZ`, the author's local time + timezone abbreviation (`date "+%Y-%m-%d %H:%M %Z"`); the time marks the last update that day. No session numbers - branch-merge-safe.
2. The **person** on the next line.
3. A `## Title` heading - a short summary of the day.
4. A **category tag in brackets** on its own line - `[feature]` | `[enhancement]` | `[bug]` | `[internal]`. A mixed day may **stack a tagged sub-block per category** (e.g. `[enhancement]` bullets, then `[internal]` bullets), or pick the dominant tag.
5. **One bullet per ticket/PR** - a short one-liner each; the deeper detail lives in the PRs/commits. No walls of text.
6. A `Tickets:` footer with issue/PR links.
7. A **`Heads up:`** section, **always present** (a standing caveat block) - brief bullets for anything a teammate must run after pulling: `./mvnw install` (deps change), an Elasticsearch reindex (mapping change), `docker compose up` (local stack change). When nothing is needed, the empty state on one line: `Heads up: nothing to run - pull and go.`

Insert new entries at the **top** (just under the file header, above the newest existing entry) via `str_replace` - never rewrite the file in full.
