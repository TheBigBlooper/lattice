# Lattice - QA Protocol

How manual, hands-on QA is run and recorded. Automated checks (`./mvnw verify` - compile, JUnit 5 + vertx-junit5 unit + integration tests against real Elasticsearch + Artemis via Testcontainers, the console's Vitest suite, lint) are the in-repo gate and run before any PR; this document covers the **human** layer on top of that: standing the running stack up and exercising it by hand.

> "Manual QA" for Lattice means bringing the service stack up (Elasticsearch + Artemis + Keycloak + the services + the status console) and exercising it - there are no phones, simulators, `adb`, or app-store builds. There is **one** local stack: three `kind` clusters, one baseline each, driven by [`deploy/k8s/mesh-clusters.sh`](../../deploy/k8s/mesh-clusters.sh). docker-compose is retired (locked #77).

---

## Bringing the stack up (quick start)

A practical how-to for a human running a feature branch's stack. Exact commands are in [Building the branch](#building-the-branch) below; the full bring-up runbook is owned by the `platform` agent in [platform_protocol.md](platform_protocol.md) and cross-referenced from [deploy_protocol.md](deploy_protocol.md).

1. **Check out the feature branch** (`lat-<issue>-<slug>`).
2. **Issue the broker certificates once per machine** - `./deploy/certs/issue-certs.sh`. Nothing federates without them and they are deliberately not committed.
3. **Build the images / artifacts** for the branch (`./mvnw install`, then `mesh-clusters.sh images`) so the stack runs *your* code, not a stale image - see [Building the branch](#building-the-branch).
4. **Bring the whole stack up** - `mesh-clusters.sh up`, then `deploy`, then `seed`. That is three kind clusters, each with Elasticsearch, an Artemis broker, Keycloak and its database, the services and the console.
5. **Smoke the health/readiness endpoints** of each service (see the health contract in [service_protocol.md](service_protocol.md)) - every service must report ready before the stack is considered up. These are the only unauthenticated endpoints.
6. **Get a token before touching `/api/v1`.** Every business endpoint requires a bearer token from that baseline's own Keycloak; an unauthenticated call is a 401 by design, not a defect. The committed realm seeds a `viewer` and an `operator` demo user; `mesh-clusters.sh`'s `kc_token` helper is the one-line call, against `http://localhost:<keycloak-port>/realms/lattice/protocol/openid-connect/token`. A `viewer` reads and an `operator` also writes, so a 403 on a write is the `viewer` token doing its job.
7. **Open the status console** - hub-central on `:3000`, hub-east on `:3001`, hub-west on `:3002` - and confirm it shows every node/service **green**.
8. **For mesh-affecting changes**, confirm the three baselines **discover and announce each other over the Artemis mesh** - see [Mesh discovery QA](#mesh-discovery-qa). All three come up together, so there is no separate two-cluster step.

> **Gotcha - the stale jar, which is not the stale image.** If a change is provably absent from a running service *while the pod is new and the redeploy reported success*, suspect the artifact rather than the code. A service image only copies `target/<name>-fat.jar`, so an edit verified with `./mvnw test` is not in any jar and the image built from it is old. `mesh-clusters.sh` compiles first now, so this should not recur - but if you build an image by hand, `ls -l` the jar against the source file before doubting anything else. It cost a real debugging detour once, chasing a metrics label that the code was already handling correctly.

> **Gotcha:** the two most common "it won't come up / won't update" causes are **a stale image** (you rebuilt code but the stack is still running the old image - rebuild + recreate) and **a dependency not ready yet** (Elasticsearch or the Artemis broker still starting, so a service's readiness probe is failing). Check those first: a service that is "down" in the console is often just waiting on Elasticsearch or the broker, not broken.

---

## Local stack networking (reaching the services)

Getting the pieces to reach each other recurs every QA session. The essentials:

### Service-to-service addressing

- **Inside a cluster**, services reach each other by **Service DNS name** (`<release>-lattice-<component>`, e.g. `hub-central-lattice-elasticsearch:9200`), not `localhost`. `localhost` from inside a pod is that pod, not the node and not your machine.
- **From your machine** (curling a health endpoint, opening the console), reach a service on the **host port** the kind node maps: console `3000/3001/3002`, then orders, inventory, the gateway and Keycloak on `8080-8083` (hub-central), `8090-8093` (hub-east), `8100-8103` (hub-west). Those numbers are not free choices - the committed realm's redirect list pins them, and a console served anywhere else is refused with `Invalid parameter: redirect_uri`. `host_ports_for` in `mesh-clusters.sh` is the one place they are written.
- **Between clusters**, a pod egresses through its own kind node and dials a peer's node container by name on a NodePort over the shared `kind` bridge. That is the boundary the mesh crosses (locked #75).

### Config / env

- Service config (Elasticsearch URL, Artemis broker URL, the baseline/version) comes from the chart, never hardcoded - so pointing the stack at a different Elasticsearch or broker is a values change, not a code change. A config change needs the pod **rolled** to take effect; a Secret change does not reach a running pod at all.
- **Which Elasticsearch am I on?** Each baseline runs its own, with its own possibly divergent data model, and all three answer on near-identical names in the same namespace. A forgotten `--context` acts on whichever cluster the kubeconfig last selected, and the failure looks like the baseline you meant is fine - so confirm the context before trusting anything you read. An empty screen is more often an unseeded index (`mesh-clusters.sh seed`) than a bug.

### Mesh connectivity

- Every baseline runs its **own** broker (locked #44) and they are joined by Artemis federation, so "can they see each other" is a question about the federation link rather than about a shared broker. `mesh-clusters.sh status` answers the layer beneath it - whether each kind node resolves and reaches the others on the shared bridge - which is worth separating before spending an afternoon on broker configuration. Wiring is owned by `platform` ([platform_protocol.md](platform_protocol.md)).

### Process hygiene

- A stale cluster can hold host ports - a "port already in use" error usually means a previous `kind` cluster is still up. `mesh-clusters.sh down` rather than remapping ports; the mapping is pinned by the realm and cannot move freely.
- On Windows, a bind can also fail with a permissions error because the operating system auto-reserved a block of the ephemeral range (49152-65535). Every host port here is deliberately below that, and a running container keeps its binding - so this only ever appears on the next recreate, days later, looking like a new problem.
- Elasticsearch, Artemis and the Keycloak database carry state in PersistentVolumeClaims; a "why is the old data still here" surprise is usually a reused claim. Delete the cluster for a true clean slate (see [Clean rebuild](#clean-rebuild--wipe-state)).

---

## When manual QA is required

| Change type                                                                                                                                                      | Manual QA                                                             |
|------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Service-facing behavior (a REST endpoint's observable result, an Elasticsearch query/mapping, mesh discovery/announce, anything an operator sees in the console) | **Required before merge**                                             |
| Status console (`ui/status-console`) visible change                                                                                                              | Required before merge (one browser against a running stack is enough) |
| Docs, protocol, CI, tooling, types, refactors with no observable behavior change                                                                                 | Not required - automated checks suffice                               |

If in doubt, treat it as service-facing.

**Where this gate sits (dev-branch model):** work merges to **`dev`** via PR (CI-gated). A change needing manual QA is labeled **`needs-qa`** on `dev`; a founder brings the stack up, exercises it, and applies **`qa-passed`**. `qa-passed` is the prerequisite for the **founder-only `dev -> main` promotion** (see [core_protocol.md](core_protocol.md) Branching Model + [session_protocol.md](session_protocol.md) enforcement rules). Claude never merges to `main`.

**Where CI carries the load vs where a human is needed:** most service changes lean on **CI integration tests** (real Elasticsearch + Artemis via Testcontainers, every PR), so `needs-qa` applies when something genuinely needs a human to exercise by hand - a multi-service interaction, mesh discovery between two clusters, or a console view that a headless test cannot judge.

---

## Claude-observed local-stack QA (Claude sees, human drives)

When a change needs eyes on the running stack and Claude is working on the founder's machine, Claude **inspects the running stack directly** - it can read what the stack reports; the human drives anything Claude cannot.

**Claude can SEE the running stack headlessly:**

- **Health/readiness:** `curl` each service's health + readiness endpoint and read the JSON.
- **Metrics:** port-forward a service's `-metrics` Service and `curl :9090/metrics`. It is ClusterIP by design, so it is never reachable from the host without the forward - if it answers on a host port, that is a defect rather than convenience.
- **Pod state:** `mesh-clusters.sh pods` for all three baselines at once, or `kubectl --context kind-<baseline> -n lattice get pods` / `logs` - scan for crash loops, failing probes, and startup exceptions after each change. Docker Desktop cannot answer this: it lists the three kind **node** containers and nothing else, because the pods run under containerd inside those nodes.
- **The console's own reported state:** load the status console in the preview browser and read the rendered node/service states (a browser smoke pass, [ui_protocol.md](ui_protocol.md)).
- **Elasticsearch + Artemis directly:** query the Elasticsearch index (`_cat/indices`, a search) and inspect Artemis (broker console / management) to confirm data landed and mesh messages flowed.

**What needs the human:** granting access to a shared dev namespace, anything requiring credentials Claude must not handle, or a visual/layout judgement call on the console. Claude names what to check; the human runs it where needed and reports back; Claude reads the logs/endpoints and calls the next step. Claude's hot-reloadable console edits refresh live over the Vite dev server so it can change + re-check a console fix in place.

Prereq: the stack must be up and its data seeded (see [QA data mode](#qa-data-mode---live-is-the-default)) - a dead Elasticsearch or an unseeded index reads as empty panels, not a bug.

---

## Roles

Lattice is QA'd by the founder (Nick). Coverage is organized so every service-facing change is exercised before merge.

- Default coverage: bring up the single-cluster stack and smoke the services + console; add the **two-cluster mesh** path when a change touches discovery/announce or interop. Record which passes actually ran.
- A change may merge once its required coverage is checked off. A single-cluster change needs only the single-cluster pass.

(Machine/OS details live in the [Environment matrix](#environment-matrix).)

---

## Ready-to-test handoff (acceptance criteria)

When Claude has a feature branch green (CI passing) and ready for manual QA, **before** the founder brings the stack up, Claude posts a **"Ready to test" comment on the GitHub Issue** with the **acceptance criteria** as a checkbox list - the observable behaviors to verify against a running stack (one box per behavior: an endpoint returns X, the console shows service Y green, two clusters discover each other, an Elasticsearch query returns the expected doc).

The founder brings the stack up and ticks each box. Because manual QA precedes the PR (Branch & Merge Workflow), this lives on the **Issue**; when QA passes, the PR Claude opens links the issue (`Closes #N`) and carries the same checklist as the durable record.

---

## Per-PR QA checklist convention

Every service-facing PR carries a **QA checklist as GitHub checkboxes in the PR description**. The author seeds it; testers tick boxes and note how they ran it (compose vs K8s namespace, single vs two-cluster) next to each.

- On open, label the PR **`needs-qa`** - by team convention it is not merged while this label is on.
- When the required coverage is checked off, swap the label to **`qa-passed`**; the PR is then mergeable. Merging auto-moves the linked issue to Done.
- A failed case -> comment with how it was run + repro, leave `needs-qa`, fix, re-QA.

**CI discipline during the fix loop:** manual QA is the gate here, and CI runs alongside it rather than instead of it - the workflow triggers on **`push`** to `dev` and to any `lat-*` branch, so every fix push during a QA round is covered on a clean runner (locked #74, #76). There is no `pull_request` trigger, so opening the PR does not start a run; the branch's last push already did. Push freely: a concurrency group cancels a superseded run, so a rapid series settles into one run on the latest commit. Full rule: [core_protocol.md](core_protocol.md) CI triggers + QA-iteration discipline.

The `needs-qa` / `qa-passed` **labels are the whole signal - there is no board "QA" column.** The label is the durable record on the PR.

A reusable checklist shape (adapt per feature):

```
## QA checklist (manual, running stack)
- [ ] Bring the three-cluster kind stack up (`mesh-clusters.sh up` / `deploy` / `seed`)
- [ ] All services report ready; console shows every node green
- [ ] <test case - endpoint / query / console view>  - _tester, how run_
- [ ] <mesh case, if applicable - two clusters discover each other>  - _tester_
```

---

## Environment matrix

Kept current as setups change. Fill in exact versions.

| Tester | Laptop / OS            | Docker           | Local K8s | `kubectl`   | Notes |
|--------|------------------------|------------------|-----------|-------------|-------|
| Nick   | Windows 11 `<version>` | `<version>`      | `kind`    | `<version>` |       |

Three `kind` clusters, one baseline each, are the whole local stack (locked #27, #77). The accepted cost is speed: a chart or values change is seconds, rebuilding and rolling one service is minutes, and a host-port change forces a full cluster recreate.

---

## Building the branch

To run a feature branch's stack for QA, from the repo root:

```bash
git fetch origin && git checkout lat-<issue>-<slug>   # the feature branch

./deploy/certs/issue-certs.sh          # once per machine - nothing federates without it

./mvnw install                         # build all Maven modules + run tests
./deploy/k8s/mesh-clusters.sh images   # build the service + per-baseline console images, load them
./deploy/k8s/mesh-clusters.sh up       # create the three kind clusters
./deploy/k8s/mesh-clusters.sh deploy   # helm upgrade --install, one release per baseline
./deploy/k8s/mesh-clusters.sh seed     # otherwise Orders and Inventory open empty
```

`up` is idempotent - it leaves a cluster that already exists alone - so the usual inner loop is `images` then `deploy`. The console images are built **per baseline** because their API addresses are inlined at build time: one shared image points every baseline at whichever addresses it was built with.

Image standards and the deployed bring-up runbook are owned by the `platform` agent - see [platform_protocol.md](platform_protocol.md) and [deploy_protocol.md](deploy_protocol.md).

### Clean rebuild / wipe state

`helm upgrade` keeps volume state (an Elasticsearch index, Artemis queues and the Keycloak database survive). Three reset levels, least to most destructive:

```bash
# 1. Roll one component - new image, keeps all data
kubectl --context kind-hub-central -n lattice rollout restart deploy/hub-central-lattice-orders

# 2. Reinstall the release - new chart/values, keeps the PersistentVolumeClaims
./deploy/k8s/mesh-clusters.sh deploy

# 3. Full clean slate - deletes the clusters and everything in them
./deploy/k8s/mesh-clusters.sh down && ./deploy/k8s/mesh-clusters.sh up
```

A **host-port change is level 3 whether you like it or not**: the mapping is fixed when the kind cluster is created, so it cannot be changed by any redeploy.

---

## Test data

- **Seeded index (preferred for data-facing QA):** `./deploy/k8s/mesh-clusters.sh seed` loads the dev dataset into every baseline, so the operational views open with something to show. It runs the seed and only the seed - arming the chart's data jobs through values would arm reset alongside it, and reset destroys what seed just wrote. The jobs themselves ship as suspended CronJobs for a deliberate one-off ([deploy_protocol.md](deploy_protocol.md)).
- **Empty / fresh-cluster state:** bring the stack up with no seed (or a wiped volume) to exercise genuinely empty responses - this doubles as the new-cluster / first-run test. Do not fake empty with a runtime toggle; use a real empty index.
- **Two-cluster interop data:** for mesh QA, each cluster has its own (possibly divergent) Elasticsearch data model; seed both and verify federation holds - each discovers the other and the redirect reaches the peer's own console (Shape A - interop is by redirecting to the owning baseline, not shared schema). **The unified view does not pull a peer's API** (locked #61): every peer row renders from this baseline's own registry, because a browser fan-out would be refused by each peer's realm.
- Never point a QA stack at production data - use synthetic seed data at realistic scale.

### QA data mode - live is the default

**Feature QA runs against the real stack:** Elasticsearch + Artemis + the services + the console, all up, so you exercise the actual wiring, persistence, and cross-cluster behavior. This is the standard QA mode; a contract-mock console is a UI-dev tool, not the QA loop.

- **Live QA needs the whole stack up, or panels read empty.** Before live QA confirm all of: (1) **Elasticsearch** up and reachable; (2) the **Artemis broker** up (mesh + messaging depend on it - the piece most often forgotten, and a dead broker reads as "no peers / no mesh" while single-service reads still work); (3) every **service** started and passing readiness; (4) the **index seeded** so "search"/data panels have data.
- **A dependency-down-at-startup latches a failing state.** If a service starts while Elasticsearch or the broker is not yet ready, its readiness probe fails and it stays out of rotation; the console shows it down. Bring **Elasticsearch and the broker up first**, then the services - or recreate the service once its dependencies are ready.
- **Mesh QA needs more than one baseline actually up.** A single baseline can never show discovery. All three come up together (`mesh-clusters.sh up` / `deploy`), so this is the default rather than a separate step - but confirm all three are running before reading anything into "no peers".
- A steward-only runtime "data mode" toggle is **not** used (it would put a dev affordance into the running services); the seed + wipe approach covers the same QA need with no code.

---

## Mesh discovery QA

**Run it with the harness, not by hand:**

```bash
./deploy/k8s/mesh-clusters.sh scenario all
```

That walks every scenario against the running three-cluster stack and exits non-zero if any assertion failed, so the result is a verdict rather than a wall of output to read carefully. It drives Kubernetes only - there is no demo mode in a service and no test affordance in the console, because a service that can be told to pretend is a service that can lie in production.

One at a time, which is how the console's harder screens get exercised and demonstrated. A narrated order through all seven, with what to point at on each and how long each recovery takes, is the [demo runbook](../tour/demo_runbook.md):

```bash
./deploy/k8s/mesh-clusters.sh scenario            # lists them
./deploy/k8s/mesh-clusters.sh scenario peer-lost  # and runs one
```

| Scenario | Induces | Shows |
|---------------------|------------------------------------|--------------------------------------------------------------------------------------------------------|
| `peer-lost` | stops a peer's gateway | the peer flips to `UNREACHABLE` and is **retained** with its last-known detail, rather than vanishing |
| `degraded` | stops one service | that baseline announces `degraded`, and its peer sees the degraded rollup over the mesh |
| `baseline-down` | stops every service | it announces `down` while still being **heard** - a cluster that cannot serve is not a cluster nobody can hear |
| `mesh-cut` | stops one baseline's broker | discovery goes quiet for that baseline while it keeps serving its own data, and rejoins with no restart |
| `loop-check` | silences one baseline's announcer | a third baseline adds **one** copy of its announcements, not two - `max-hops=1` doing its job |
| `revoked-east` | revokes a peer at the authority | the enforcing baseline refuses it, with **no edit** to the revoked baseline's own cluster |
| `foreign-authority` | mints a certificate elsewhere | a well-formed certificate from an untrusted authority is refused - the truststore is the gate |

Every scenario opens with a **control** asserting the healthy pre-state, and every one restores what it broke and verifies the recovery - so the self-healing claims are exercised rather than asserted, and a scenario cannot pass most loudly exactly when it is broken.

`loop-check` needs all three baselines, because two brokers cannot form a loop and a two-baseline pass cannot exercise loop prevention at all. It measures at the broker as a difference, because the peer registry dedupes by cluster id and would hide a duplicate entirely. It is slow by nature - two 90-second windows plus a settle.

The whole run is heavy: three Elasticsearch instances, three brokers, three Keycloaks with three databases, nine services and three consoles.

The checklist the harness automates, for reference and for anything it cannot yet cover:

- [ ] Both clusters start and each passes its own health/readiness.
- [ ] Each cluster **announces itself** onto the Artemis mesh and **discovers the peer** - the console (or the mesh/peer view) on each side lists the other.
- [ ] The unified view on each console lists the peer (health + reachability) **read from this baseline's own registry, never pulled from the peer** (locked #61), and the "go to this baseline" redirect opens the peer's own console (Shape A federation).
- [ ] Bring one cluster down and confirm the peer reflects it as `UNREACHABLE` (discovery is live, not one-shot).

Exact mesh mechanics (the `ClusterAnnouncement` shape, 10s announce cadence, 30s peer TTL, the discovery protocol) are **settled** under Shape A - see [mesh_discovery.md](../design/architecture/mesh_discovery.md) + [mesh_envelopes.md](../design/architecture/mesh_envelopes.md), owned by the `platform` agent ([platform_protocol.md](platform_protocol.md)).

---

## Recording & sign-off

- The PR's checked QA boxes (with how-run notes) are the durable record - they live with the change.
- `qa-passed` label = sign-off that required coverage ran green.
- No separate QA log is kept; if a release-level summary is ever needed, derive it from merged PRs' QA sections.
