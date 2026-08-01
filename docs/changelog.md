# Change Log

<!-- Newest entry first. The format rules live in the "Changelog format" section at the bottom of this file. -->

---

2026-08-01 02:15 MDT
Nick

## One authored source for a baseline, and five defects that only a real run could find

[feature]
- A baseline's configuration has one authored source. The chart became an umbrella with a subchart per component over a mandatory library chart, so each component owns its values and an `enabled` flag - and infrastructure can be switched off and pointed at something the environment already runs, which is a real case where the customer runs the clusters (#163, PR#167)
- The realm derives its redirect URIs from the console URL and service ports. It had pinned every console port and all nine service ports for all three baselines with nothing in the chart saying so, and a console served elsewhere was refused with `Invalid parameter: redirect_uri` - a Keycloak error whose cause was a chart value. Each realm now permits only its own addresses (#163, PR#167)
- kind plus Helm is the only local stack. docker-compose retired with `.env.example`, which held 29 variables nothing read, and `issue-certs.sh` moved to `deploy/certs/`: it was never local-only, it is the tool a customer runs to create their own authority (#162, PR#166)
- Every container declares a security context, measured per image rather than assumed. Each already ran non-root except MySQL, which runs as root to fix data-directory ownership before dropping - so it keeps the one setting that still bites and its values file says why (#168, PR#169)
- `mesh-clusters.sh redeploy <baseline> <service>` rebuilds one image and rolls it, so the fast path is the easy path rather than a documented practice nobody follows (#163, PR#167)

[bug]
- Every peer announcement was delivered twice. Naming a peer builds both an upstream and a downstream link, so both sides naming each other made two links per pair carrying the same address. The registry dedupes by cluster id, so only the broker could show it - measured at hub-central: its own announcements 6 a minute, each peer's 12 to 14 (#162, PR#166)
- A premature seed corrupted a baseline permanently. It beat the service owning the index and wrote to the write alias; Elasticsearch auto-creates an index for an unknown target, so an index appeared carrying the alias's name and that service could never bootstrap again. Closed at both ends: the seed refuses before writing, and Elasticsearch now refuses to invent an index at all (#162, #168, PR#166, PR#169)
- A cold bring-up reported success while a baseline held no data - the seed raced Elasticsearch, and a failed seed printed its log and returned zero. The symptom surfaced later as views that open empty, reading as a console fault (#162, PR#166)
- The chart version was rolling every pod. `helm.sh/chart` carries it and sat in every pod template, so bumping the chart restarted every pod even when nothing about them changed (#163, PR#167)
- Both shell scripts were committed non-executable, so `./deploy/k8s/mesh-clusters.sh` failed on any Linux or macOS clone - the exact command the docs give. Git Bash on Windows ignores the bit, so it hid until CI ran a script for the first time (#168, PR#169)

[internal]
- The three local-behaviour scenarios moved to the kind stack before compose was deleted, so nothing was undemonstrated. A fourth went with them that the design had missed: `loop-check` measures `max-hops=1` at the broker and was a command rather than a scenario, so it would have been deleted leaving loop prevention proven nowhere. It found the duplicate delivery above on its first run (#162, PR#166)
- Every scenario gained a control asserting the healthy pre-state, and failures now become the exit status - one that printed a failure and exited zero reported success to anything reading the status (#162, PR#166)
- The chart is no longer unscanned. Semgrep skips it entirely because a Helm template is not valid YAML, so CI now renders every baseline and checks that, asserting no duplicate environment key and that every container declares a security context. It self-tests against fixtures first, because a lint that silently stopped matching would pass everything forever and read as health (#163, #168, PR#167, PR#169)
- Where a baseline's configuration is authored was settled by design session (#133, PR#164)
- 22 guiding documents repointed at the kind stack, three corrected rather than renamed: `integrations.md` claimed local Keycloak runs unpersisted when it does not, and the chart README still called cross-cluster federation unfinished after it was proven (#162, PR#166)

Tickets: [#133](https://github.com/TheBigBlooper/lattice/issues/133), [#162](https://github.com/TheBigBlooper/lattice/issues/162), [#163](https://github.com/TheBigBlooper/lattice/issues/163), [#168](https://github.com/TheBigBlooper/lattice/issues/168)

**Heads up:**
- **`docker compose` is gone.** Use `./deploy/k8s/mesh-clusters.sh` - `up`, `images`, `deploy`, `seed`.
- **Re-run `deploy/certs/issue-certs.sh`** - it moved, and the compose service name is no longer among the certificate's subject alternative names.
- **Rebuild the service and console images** - all four Dockerfiles now declare a numeric user, which is what `runAsNonRoot` needs; an old image will not start under the new chart.
- `./mvnw install` - `lattice-common` changed (the data jobs refuse a premature write).
- **Chart `--set` paths changed.** Baseline-wide values go under `global.`, component values under the subchart name (`status-console.*`, not `statusConsole.*`).
- **A host-port change is a full cluster recreate**, not a redeploy. Everything else is `deploy` (seconds) or `redeploy` (minutes).
- Elasticsearch: ✅ no reindex - no mapping changed.

---

2026-07-30 23:53 MDT
Nick

## The mesh crosses a cluster boundary, identity that survives a restart, and three duplicates that each hid until one specific thing looked

[feature]
- A deployed baseline keeps its operators. Keycloak runs against its own MySQL, so accounts and sessions survive a pod restart instead of vanishing with the process - and locked #7 is scoped rather than broken, because Keycloak cannot use Elasticsearch and never could (#66, PR#153)
- The mesh crossed a routing boundary for the first time. Three baselines now run in three separate Kubernetes clusters, each with its own broker, datastore, identity provider and database, and each discovers both peers across the boundary. What is not claimed is written down beside it: the clusters share one Docker bridge, so this is a cluster boundary rather than a network one (#152, PR#158)
- A revoked certificate is refused across that boundary, and so is one from an authority nobody trusts - with only the enforcing baseline touched, which is the property trusting an authority rather than a peer is supposed to buy (#160, PR#161)
- The identity database reports through the check Keycloak already publishes about it, rather than gaining a probe of its own. It has no row in the infrastructure card and does not need one (#154, PR#156)

[bug]
- Keycloak reported itself ready while unable to issue a single token. Its database check is not in the readiness group by default, so with the database pod deleted `/health/ready` answered 200 for over a minute while every token request returned 500 - the same defect a ping-based Elasticsearch check once had, arriving by another route (#154, PR#156)
- Enabling that check then broke the reporting path: a failed readiness check removes the pod from its Service, so the probe stopped reaching the endpoint that would explain the failure. A health endpoint unreachable precisely when unhealthy reports nothing (#154, PR#156)
- The chart could not install a single service. `BASELINE_VERSION` was declared twice inside it, which Helm 3 tolerated and Helm 4 rejects outright; every earlier deployment happened to pass an empty service list, which is why it sat unnoticed (#152, PR#158)
- The chart never set `CORS_ALLOWED_ORIGINS`. Compose had carried it since the console existed, so the gap was invisible until a console ran on Kubernetes and reported its baseline unreachable while every service was serving perfectly (#152, PR#158)
- All three brokers published their core port inside the Windows ephemeral range, where the operating system reserves blocks while it is up. A measured reservation covered all three at once, and because a running container keeps its binding, it only ever failed on the next recreate - days later, looking like a new problem (#134, PR#159)

[internal]
- The pre-push gate stopped taxing every push. Measured warm: the container suites are 167 seconds of a 222-second run while every static gate together costs 28, so only the containers were cut - a Java push now waits about 55 seconds. CI runs on feature branches to cover what the hook stopped running, and once per push rather than twice (#155, PR#157)
- Keycloak's schema migration is guarded by a startup probe. Running three persisted baselines at once showed the liveness probe killing it mid-migration, which on MySQL is data loss rather than delay: non-transactional DDL does not roll back, so every later start failed on an inconsistent schema (#66, PR#153)

Tickets: [#66](https://github.com/TheBigBlooper/lattice/issues/66), [#134](https://github.com/TheBigBlooper/lattice/issues/134), [#152](https://github.com/TheBigBlooper/lattice/issues/152), [#154](https://github.com/TheBigBlooper/lattice/issues/154), [#155](https://github.com/TheBigBlooper/lattice/issues/155), [#160](https://github.com/TheBigBlooper/lattice/issues/160)

**Heads up:**
- `./mvnw install` - the mesh-gateway and its config both changed; build the reactor before running anything.
- **Re-run `deploy/docker/artemis/tls/issue-certs.sh`** - certificates now carry the external name a peer in another cluster dials, and material issued before today lacks it. Without this the three-cluster mesh fails host verification before federation begins.
- **The broker's host ports moved to 41616-41618** (container side is still 61616). Anything pointing at 61616-61618 from the host - a script, an IDE run configuration - needs updating.
- **The local mesh has a second stack:** `deploy/k8s/mesh-clusters.sh up` then `images`, `deploy`, `seed`. It cannot run at the same time as docker-compose - they collide on almost every host port, so stop one before starting the other.
- **A persisted baseline needs a rebuild, not a restart:** Keycloak now takes `KC_METRICS_ENABLED` and reads its health on a separate management Service, and neither reaches a running pod through a Secret change.
- `git config core.hooksPath .githooks` if it is not already set - the hook now runs `./mvnw verify -DskipITs` and the integration suites run on CI instead.
- Elasticsearch: ✅ no reindex - no mapping changed.

---

2026-07-29 01:11 MDT
Nick

## Placing an order from the console, and three faults that only a browser could find

[feature]
- An operator can browse and place orders, browse stock, update it behind a confirmation, and hold it against an order - all on their own baseline, and all from the console rather than a shell (#111, PR#148)
- The console gained routing and three destinations, Status staying home; the peer redirect deliberately did not become a fourth, because a tab that navigates away from the console is a strange thing for a tab to be (#111, PR#148)
- A viewer sees every screen and every control, disabled, above a notice naming the grant they are missing **on this baseline** - they may well hold it on a peer, and hiding the controls would leave them unable to tell which (#111, PR#148)

[bug]
- Three faults, one per layer, none of which any test could see. The console read orders from the mesh-gateway, which serves no such operation, and got a 404 page where JSON was expected. The image discarded two undeclared build arguments in silence, so both peer consoles pointed at hub-central's services while hub-central itself looked correct by coincidence. And every service allowed cross-origin reads but no writes, so each write was refused by the browser before it was sent (#111, PR#148)
- A validation failure explained itself nowhere: the services name every problem against the field `body` rather than the offending one, so the console matched no input and suppressed the summary as well. Both forms now fall back to saying it plainly (#111, PR#148)
- A screen whose service stops answering is now held rather than annotated - behind that dialog sit a form and a list that has become a memory, and acting on either is what it prevents (#111, PR#148)

[internal]
- Writes travel through the one API client rather than a second one, and it now carries the field-level details a validation failure names (#111, PR#148)
- A dialog label that was clipped survived one wrong fix: Material zeroes a dialog's top padding with a two-class selector, which outranks anything an `sx` prop can write, so the styles were applied and lost the cascade (#111, PR#148)

Tickets: [#111](https://github.com/TheBigBlooper/lattice/issues/111)

**Heads up:**
- `pnpm install` in `ui/status-console` - React Router is installed for the first time; the console has needed a router since the app bar was built and never had one.
- `./mvnw install` then `docker compose up -d --build` for **orders and inventory on every baseline** - the CORS change is in `BaseVerticle`, so a service running the old image refuses every write from the console and reports it as unreachable.
- **Rebuild the console per baseline**, not just one: `VITE_ORDERS_BASE_URL` and `VITE_INVENTORY_BASE_URL` are baked in at build time, and a stale peer console reads hub-central's data.
- Seed each baseline (`DataJobRunner seed`) or the operational views open empty.
- Elasticsearch: ✅ no reindex - the list operations are plain sorted searches and no mapping moved.

---

2026-07-28 22:53 MDT
Nick

## Everything a baseline runs, reported, and an Elasticsearch that only looked broken

[feature]
- A baseline reports its infrastructure (Elasticsearch, Artemis, Keycloak) beside its services, on its own API only: the announced verdict stays a services-only rollup, so a datastore that has merely lost a replica cannot make a peer believe this baseline cannot serve (#103, PR#127)
- The contract carries an infrastructure breakdown, additively, so a client generated before it still validates (#123, PR#128)
- The gateway probes Elasticsearch and Keycloak and renders Artemis from the mesh-link state it already holds; it also lists itself, so the console reports three services rather than two (#125, PR#129)
- The console shows that infrastructure beneath its service breakdown, each component carrying its own reading in its own words (#126, PR#135)
- The console says what changed on this baseline, not only on the mesh - a service falling over or Elasticsearch dropping to yellow now reaches the same timeline as a peer going quiet, because they are usually one incident rather than three (#136, PR#144)
- An ended session says why: the signed-out card swaps one sentence when a session expired, rather than showing the same words to somebody who never signed in and somebody who just lost a dashboard (#115, PR#145)
- A failed read says whether it is still trying and how long since the last good one, so a blip and an outage stop looking identical (#115, PR#145)

[bug]
- A session died on a StrictMode remount rather than expiring. The hook built a second Keycloak adapter, both called init, and the adapter consumes the authorization code from the URL - so the first authenticated and the second found nothing and settled signed out moments later. It never reached the built image, which is why it went undiagnosed so long (#115, PR#145)

- A red Elasticsearch was reported healthy. Every data-owning service's readiness check is a ping, which a cluster with unallocated primaries answers perfectly well, so a baseline would announce itself ready while unable to serve (#125, PR#129)
- A single-node Elasticsearch was permanently yellow, because a single node cannot allocate a replica of its own primary. Fixed at the cause rather than reinterpreted: the local cluster is not reported green, it is green (#124, PR#130)
- A reindex silently undid that fix, rebuilding the index from its mapping alone and reverting to the default replica count (#124, PR#130)

[internal]
- Artemis needs no probe and Keycloak needs no translation: the mesh-link state already carries one, and the other already answers in the operational shape this project defines. Both were expected to be work and were not (#103, PR#127)
- Reading Elasticsearch cluster health is monitoring rather than data access, so service_protocol.md carves out operational endpoints only, rather than leaving the gateway's probe reading as a rule violation (#125, PR#129)
- One row component serves both lists, and one definition record now creates an index, so neither the mapping nor its settings can travel without the other (#124, #126, PR#130, PR#135)
- Build phase advanced - Phase 3 (Interop) -> Phase 4 (Multi-cluster + hardening). Interop's exit criteria were met when the Shape A redirect, the unified view and the mesh-link state landed (#12, #28, #56)

Tickets: [#103](https://github.com/TheBigBlooper/lattice/issues/103), [#115](https://github.com/TheBigBlooper/lattice/issues/115), [#123](https://github.com/TheBigBlooper/lattice/issues/123), [#124](https://github.com/TheBigBlooper/lattice/issues/124), [#125](https://github.com/TheBigBlooper/lattice/issues/125), [#126](https://github.com/TheBigBlooper/lattice/issues/126), [#132](https://github.com/TheBigBlooper/lattice/issues/132), [#136](https://github.com/TheBigBlooper/lattice/issues/136), [PR #145](https://github.com/TheBigBlooper/lattice/pull/145)

**Heads up:**
- `./mvnw install` - the shared contract gained the infrastructure types. Building a single module against a stale `lattice-contract` fails with "cannot find symbol".
- **`docker compose down -v`, not a restart** - the index settings apply on create only, so any index created before today keeps its one replica and a single-node cluster stays yellow. Without the `-v` the replica fix looks broken when it is not.
- `docker compose up -d --build` - every service image changed, all three baselines gained `CLUSTER_INFRASTRUCTURE`, and the chart now exposes Keycloak's management port 9000.
- `docker compose up -d --build status-console-<baseline>` on each - the console was reorganised by feature and gained the local activity log and the session and read-failure surfaces.
- Elasticsearch: ✅ no reindex - `auto_expand_replicas` is a settings change, not a mapping change, and no mapping moved.

---

2026-07-27 23:37 MDT
Nick

## Travelling the mesh, telling a cut-off baseline from a dead one, and docs that describe their own host

[feature]
- An operator can travel from one baseline's console to a peer's and get back, with the return origin confirmed by the browser rather than trusted from the address bar (#28, PR#116)
- A baseline reports its own mesh-link state, so "we are cut off" reads differently from "the peers are gone" - a broker outage ages every peer out at once and used to look like a total mesh failure (#56, PR#117)
- Orders and inventory are browsable rather than only addressable by id: paged list operations on the contract (#109, PR#118), backed by sorted Elasticsearch pages with no mapping change (#110, PR#119)
- The docs page obtains its own token from this baseline's own realm, so a guarded operation can be tried from the page instead of pasting a token in (#100, PR#121)
- The console says what changed on the mesh, not only what it is - toasts as transitions happen, and a session activity log beside them (#107, PR#122)

[bug]
- A 403 from a peer was reported as "cannot reach this baseline" when that baseline was serving perfectly and only the operator's grant was missing (#28, PR#116)
- Every service published the whole baseline contract at /docs, advertising operations it answers 404 for, and logged a warning for each operation it had not claimed on every start; services now declare what they own, and the contract is narrowed before the router is built (#99, PR#120)

Tickets: [#28](https://github.com/TheBigBlooper/lattice/issues/28), [#56](https://github.com/TheBigBlooper/lattice/issues/56), [#99](https://github.com/TheBigBlooper/lattice/issues/99), [#100](https://github.com/TheBigBlooper/lattice/issues/100), [#107](https://github.com/TheBigBlooper/lattice/issues/107), [#109](https://github.com/TheBigBlooper/lattice/issues/109), [#110](https://github.com/TheBigBlooper/lattice/issues/110), [PR #122](https://github.com/TheBigBlooper/lattice/pull/122)

**Heads up:**
- `./mvnw install` - the shared contract gained the list operations, an oauth2 scheme and the mesh-link state. Building a single module against a stale `lattice-contract` fails with "cannot find symbol".
- **Recreate Keycloak, do not restart it** - the realm import runs only when the realm is absent, so an existing Keycloak never sees the new `lattice-docs` client and Authorize answers "Client not found". `docker compose -p hub-<name> -f <file> up -d --force-recreate keycloak-<name>`.
- `docker compose up -d --build` - every service image changed, and orders and inventory now receive `CLUSTER_ID`, `REGION` and `BASELINE_VERSION` so their docs page can name the baseline.
- Elasticsearch: ✅ no reindex - a sorted page is a plain search, and no mapping moved.

---

2026-07-26 23:40 MDT
Nick

## The console on Material UI, the unified mesh view, and three gates that were not gating

[feature]
- The console shows every discovered baseline beside this one's verdict, read from the local peer registry rather than pulled from each peer (#12, PR#94)
- The status console rebuilt on Material UI - app bar, outlined surfaces, peers as a table (#101, PR#104)
- Browsable API docs at /docs with the OpenAPI document at /docs/json, both gated off in prod (#79, PR#89)
- Guarded reindex, seed, and reset jobs for a baseline's Elasticsearch data (#80, PR#90)

[bug]
- The console image had been unbuildable since the supply-chain overrides landed - the Dockerfile never copied pnpm-workspace.yaml, so a frozen install aborted (PR#94)
- Three CI gate defects the first dev to main run exposed (#84, PR#85)
- Two vulnerable transitive dev dependencies forced past their fixes (#86, PR#87)

[internal]
- PMD gates unused private fields and methods, after measuring that Checkstyle has no such check and SpotBugs sees neither case (#91, PR#92)
- SpotBugs now scans test sources, which had never been analysed; five findings, each excluded with a removal condition and scoped by class name to integration tests so shipped code cannot be masked (#93, PR#96)
- Material UI adopted by design session; the golden-section scale retired and the status-colour contrast bar relaxed to 3:1, both measured rather than assumed (#95, PR#98)
- The console's operational views settled by design session, which found the contract has no list operation at all - so that work spans contract, services, and console (#102, PR#108)
- Polling settled as the live-status transport (#77, PR#88)
- /qa-steps, a skill producing numbered QA scripts with an expected result per step (PR#105)
- Every baseline named for a place rather than a position - hub-local became hub-central, and the compose projects, services, broker hosts and shared network follow the same naming, so no baseline is the implicit default (#106, PR#112)
- MIT licence, declared in the repository, the Maven metadata and the console package (#113, PR#114)

Tickets: [#12](https://github.com/TheBigBlooper/lattice/issues/12), [#77](https://github.com/TheBigBlooper/lattice/issues/77), [#79](https://github.com/TheBigBlooper/lattice/issues/79), [#80](https://github.com/TheBigBlooper/lattice/issues/80), [#84](https://github.com/TheBigBlooper/lattice/issues/84), [#86](https://github.com/TheBigBlooper/lattice/issues/86), [#91](https://github.com/TheBigBlooper/lattice/issues/91), [#93](https://github.com/TheBigBlooper/lattice/issues/93), [#95](https://github.com/TheBigBlooper/lattice/issues/95), [#101](https://github.com/TheBigBlooper/lattice/issues/101), [#102](https://github.com/TheBigBlooper/lattice/issues/102), [#106](https://github.com/TheBigBlooper/lattice/issues/106), [#113](https://github.com/TheBigBlooper/lattice/issues/113)

**Heads up:**
- `./mvnw install` - the parent pom gained the PMD gate and SpotBugs now includes test sources; rebuild the reactor locally.
- `pnpm install` in `ui/status-console` - the console gained Material UI, its icon set, and Emotion.
- `docker compose build status-console` - the console image changed, and could not be built at all before this; the Dockerfile now copies `pnpm-workspace.yaml`.
- `./deploy/docker/artemis/tls/issue-certs.sh` then a full `docker compose down -v` per baseline - the baselines were renamed, so existing broker certificates carry the old name and mutual TLS fails silently, and old containers belong to the old compose projects. Tear the old projects down with `docker compose -p lattice down -v` (and `-p lattice-peer`, `-p lattice-peer2`), peers before the primary.
- Elasticsearch: ✅ no reindex - no mapping changed.

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
