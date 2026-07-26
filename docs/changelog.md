# Change Log

<!-- Newest entry first. The format rules live in the "Changelog format" section at the bottom of this file. -->

---

2026-07-25 21:23 MDT
Nick

## Per-baseline federated brokers, a mesh startup fix, and the identity design

[feature]
- Every baseline now runs its own Artemis broker, joined by address federation; the peer project no longer borrows the primary's (#55, PR#60)
- Per-baseline Keycloak identity - every `/api/v1` operation needs a token from that baseline's own realm, and a peer's token is refused (#30, PR#64)
- Status console skeleton - cluster verdict, PKCE sign-in, and its own container per baseline (#11, PR#67)
- Mesh harness - stands both baselines up and induces the failure states on demand, so they are demonstrated rather than described (#25, PR#68)
- A third baseline (hub-west), which turned max-hops=1 loop prevention from an assertion into a measurement and exposed two silent federation-name defects (#59, PR#69)
- Per-baseline broker identity - mutual TLS with certificates from a shared authority; the shared federation credential is retired (#62, PR#70)
- Helm chart for a baseline, with the Keycloak realm ConfigMap generated from the committed realm instead of an empty `{}` that deployed happily and rejected every token (#65, PR#73)
- The Artemis broker workload templated in the chart, so a deployed baseline can actually join the mesh (#74, PR#75)

[bug]
- A gateway started before its broker now joins the mesh on its own once the broker appears, instead of staying mesh-deaf until restarted (#54, PR#58)
- The first authenticated write after a cold start no longer fails with a 500 - the guard pauses the request while it waits for signing keys, instead of letting the body drain (#71, PR#72)

[internal]
- Per-baseline identity design - realm shape, protected surface, cross-baseline membership rules, and broker certificates (PR#61)
- Mesh broker topology design - per-baseline federated brokers (#50, PR#53)
- Create the local kind cluster on demand rather than during setup (#52, PR#57)
- P7 settled - Lattice is delivered, not hosted: exported image archives, hosting deferred, one certificate authority per customer deployment (#76, PR#81)
- Build phase advanced - Phase 2 (First cluster) -> Phase 3 (Interop).

Tickets: [#11](https://github.com/TheBigBlooper/lattice/issues/11), [#25](https://github.com/TheBigBlooper/lattice/issues/25), [#30](https://github.com/TheBigBlooper/lattice/issues/30), [#50](https://github.com/TheBigBlooper/lattice/issues/50), [#52](https://github.com/TheBigBlooper/lattice/issues/52), [#54](https://github.com/TheBigBlooper/lattice/issues/54), [#55](https://github.com/TheBigBlooper/lattice/issues/55), [#59](https://github.com/TheBigBlooper/lattice/issues/59), [#62](https://github.com/TheBigBlooper/lattice/issues/62), [#65](https://github.com/TheBigBlooper/lattice/issues/65), [#71](https://github.com/TheBigBlooper/lattice/issues/71), [#74](https://github.com/TheBigBlooper/lattice/issues/74), [#76](https://github.com/TheBigBlooper/lattice/issues/76)

**Heads up:**
- `./deploy/docker/artemis/tls/issue-certs.sh` - NEW and required before the first run. Brokers now authenticate by certificate, and nothing starts without the material. It is git-ignored, so every machine generates its own.
- `docker compose down -v` then `up -d --build`, on every project - the brokers gained a mutual-TLS acceptor and their shared config moved into the Helm chart, and a persisted broker instance ignores config changes.
- `./mvnw install` - `platform/lattice-common` gained the auth dependencies; rebuild the reactor locally.
- `pnpm install` in `ui/status-console` - the console is new (Node 24, see `.nvmrc`).
- Elasticsearch: ✅ no reindex - no mapping changed.

---

2026-07-24 23:49 MDT
Nick

## Mesh discovery, the mesh-gateway service, and Phase 2

[feature]
- Artemis announce + peer discovery over AMQP, with a two-cluster integration test (#9, PR#47)
- mesh-gateway service - announce, peer registry, and the polled cluster-health rollup (#48, PR#51)

[bug]
- Retry a failed Elasticsearch index bootstrap instead of memoizing the failure (#35, PR#45)

[internal]
- Build phase advanced - Phase 1 (Foundation) -> Phase 2 (First cluster). Phase state is now actually recorded: a current-phase marker in `governance.md`, the roadmap marker moved to "First service on a local cluster", and a WHERE WE ARE badge on the README.
- mesh-gateway service spec (#48, PR#49)
- Enforce the no-unexpected-log rule with a shared test harness (#34, PR#46)
- Replace OWASP Dependency-Check with OSV-Scanner over a Maven-built SBOM (PR#44)
- Decouple the dev-to-main promotion from cutting a release (PR#43)

Tickets: [#9](https://github.com/TheBigBlooper/lattice/issues/9), [#34](https://github.com/TheBigBlooper/lattice/issues/34), [#35](https://github.com/TheBigBlooper/lattice/issues/35), [#48](https://github.com/TheBigBlooper/lattice/issues/48)

**Heads up:**
- `./mvnw install` - a new Maven module (services/mesh-gateway) plus dependency changes across the parent and four module poms; rebuild the reactor locally.
- `docker compose up` - the mesh-gateway lands in the local stack.
- Elasticsearch: ✅ no reindex - the bootstrap change is retry behavior, not a mapping change.

---

2026-07-23 23:32 MDT
Nick

## REST contract baseline, first two services, local stack, and reservation-gate hardening

[feature]
- OpenAPI REST baseline + health/readiness reshape - the versioned /api/v1 contract seam (#17, PR#24)
- Orders service - create + get, SLF4J+Logback logging (#6, PR#36)
- Inventory service - stock + oversell-safe, idempotent reservations (#7, PR#37)

[bug]
- Close the post-gate reservation rollback edge with a PENDING->CONFIRMED lifecycle; bootstrap now applies additive mapping updates in place so an existing index gains new fields on boot (#38, PR#39)

[internal]
- Retire mesh work-exchange; adopt Shape A federation (redirect + unified read-only view) (#10, #29, PR#31)
- Local docker-compose infra stack - Elasticsearch + Artemis (#8, PR#32)
- Interactive interop console design doc (#26, PR#27)
- Bridge the Elasticsearch client's commons-logging into SLF4J (jcl-over-slf4j); trim expected-dependency-down WARN to a concise cause (#33, PR#40)

Tickets: [#17](https://github.com/TheBigBlooper/lattice/issues/17), [#6](https://github.com/TheBigBlooper/lattice/issues/6), [#7](https://github.com/TheBigBlooper/lattice/issues/7), [#38](https://github.com/TheBigBlooper/lattice/issues/38), [#10](https://github.com/TheBigBlooper/lattice/issues/10), [#29](https://github.com/TheBigBlooper/lattice/issues/29), [#8](https://github.com/TheBigBlooper/lattice/issues/8), [#26](https://github.com/TheBigBlooper/lattice/issues/26), [#33](https://github.com/TheBigBlooper/lattice/issues/33)

**Heads up:**
- `./mvnw install` - new orders + inventory services, the REST contract baseline, and dependency changes (incl. jcl-over-slf4j); rebuild the reactor locally.
- `docker compose up` (deploy/docker) - the local Elasticsearch + Artemis stack landed; bring it up to run the services locally.
- Elasticsearch: ✅ no reindex - services self-provision their indices on boot, and the reservations `status` field is applied additively to an existing index (no wipe needed).

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
