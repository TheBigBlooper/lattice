# Lattice - QA Protocol

How manual, hands-on QA is run and recorded. Automated checks (`./mvnw verify` - compile, JUnit 5 + vertx-junit5 unit + integration tests against real Elasticsearch + Artemis via Testcontainers, the console's Vitest suite, lint) are the in-repo gate and run before any PR; this document covers the **human** layer on top of that: standing the running stack up and exercising it by hand.

> "Manual QA" for Lattice means bringing the service stack up (Elasticsearch + Artemis + the services + the status console) and exercising it, locally via docker-compose or in a Kubernetes dev namespace - there are no phones, simulators, `adb`, or app-store builds. Cluster-specific mechanics are marked TBD where not yet settled.

---

## Bringing the stack up (quick start)

A practical how-to for a human running a feature branch's stack. Exact commands are in [Building the branch](#building-the-branch) below; the full compose/K8s bring-up runbook is owned by the `platform` agent in [platform_protocol.md](platform_protocol.md) and cross-referenced from [deploy_protocol.md](deploy_protocol.md).

1. **Check out the feature branch** (`lat-<issue>-<slug>`).
2. **Build the images / artifacts** for the branch (`./mvnw install`, then the image build) so the stack runs *your* code, not a stale image - see [Building the branch](#building-the-branch).
3. **Bring the whole stack up.** The default is **docker-compose** (`deploy/docker/`): Elasticsearch + the Artemis broker + the services + the status console, one command. A **Kubernetes dev namespace** is the alternative when you need to exercise anything K8s-shaped (probes, config/secrets, mesh across namespaces).
4. **Smoke the health/readiness endpoints** of each service (see the health contract in [service_protocol.md](service_protocol.md)) - every service must report ready before the stack is considered up.
5. **Open the status console** (port TBD - the console container's mapped port) and confirm it shows every node/service **green**.
6. **For mesh-affecting changes, bring up two clusters** (two compose projects or two namespaces) and confirm they **discover and announce each other over the Artemis mesh** - see [Mesh discovery QA](#mesh-discovery-qa).

> **Gotcha:** the two most common "it won't come up / won't update" causes are **a stale image** (you rebuilt code but the stack is still running the old image - rebuild + recreate) and **a dependency not ready yet** (Elasticsearch or the Artemis broker still starting, so a service's readiness probe is failing). Check those first: a service that is "down" in the console is often just waiting on Elasticsearch or the broker, not broken.

---

## Local stack networking (reaching the services)

Getting the pieces to reach each other recurs every QA session. The essentials:

### Service-to-service addressing

- **Inside docker-compose**, services reach each other by **compose service name** on the compose network (e.g. `http://search:8080`, `elasticsearch:9200`, the Artemis broker on its connector port), not `localhost`. `localhost` from inside a container is that container, not the host.
- **From your machine** (curling a health endpoint, opening the console), reach a service on its **published host port** (`localhost:<mapped-port>`). Only ports a service publishes are reachable from the host.
- **In a K8s dev namespace**, services reach each other by **Service DNS name** (`<service>.<namespace>.svc`); from your machine use `kubectl port-forward` to a Service.

### Config / env

- Service config (Elasticsearch URL, Artemis broker URL, the baseline/version) comes from the environment / ConfigMap, never hardcoded - so pointing the stack at a different Elasticsearch or broker is a config change, not a code change. Editing compose/K8s config needs the stack **recreated** to take effect, not just a container restart.
- **Which Elasticsearch am I on?** Each cluster has its own, possibly divergent, Elasticsearch data model. Confirm the stack is pointed at the intended Elasticsearch (compose volume vs a shared dev index) before trusting query results - an empty screen is often the wrong or unseeded index, not a bug.

### Mesh connectivity

- Two local clusters discover each other over the **Artemis mesh**; each must be able to reach the mesh broker. In compose this means both projects share (or bridge to) the broker network; in K8s it means the broker Service is reachable across the namespaces. Exact wiring is **TBD** - owned by `platform` ([platform_protocol.md](platform_protocol.md)).

### Process hygiene

- A stale stack can hold published ports - a "port already in use" error usually means a previous compose project is still up. Bring the old one down (`docker compose down`) rather than remapping ports.
- Elasticsearch and Artemis carry state in volumes; a "why is the old data still here" surprise is usually a reused named volume. Wipe volumes for a true clean slate (see [Clean rebuild](#clean-rebuild--wipe-state)).

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
- **Container/pod state:** `docker compose ps` / `docker logs`, or `kubectl get pods` / `kubectl logs` - scan for crash loops, failing probes, and startup exceptions after each change.
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

**CI discipline during the fix loop:** manual QA is the gate here, not CI. Push iteration commits with **`[skip ci]`** and **batch findings into one fix push**; run CI once as the pre-merge gate (final push without `[skip ci]` after `qa-passed`). Full rule: [core_protocol.md](core_protocol.md) CI triggers + QA-iteration discipline.

The `needs-qa` / `qa-passed` **labels are the whole signal - there is no board "QA" column.** The label is the durable record on the PR.

A reusable checklist shape (adapt per feature):

```
## QA checklist (manual, running stack)
- [ ] Bring the stack up (compose / K8s dev namespace)
- [ ] All services report ready; console shows every node green
- [ ] <test case - endpoint / query / console view>  - _tester, how run_
- [ ] <mesh case, if applicable - two clusters discover each other>  - _tester_
```

---

## Environment matrix

Kept current as setups change. Fill in exact versions.

| Tester | Laptop / OS            | Docker / Compose | Local K8s (kind / minikube / other) | `kubectl`   | Notes |
|--------|------------------------|------------------|-------------------------------------|-------------|-------|
| Nick   | Windows 11 `<version>` | `<version>`      | `<TBD>`                             | `<version>` |       |

The **docker-compose** stack is the default for fast iteration; a **local Kubernetes** cluster (kind / minikube - choice **TBD**, owned by `platform`) is used for anything K8s-shaped (probes, config/secrets, mesh across namespaces). Which founder runs which is in [Roles](#roles).

---

## Building the branch

To run a feature branch's stack for QA, from the repo root:

```bash
git fetch origin && git checkout lat-<issue>-<slug>   # the feature branch

# Build the modules and the service/console images so the stack runs THIS code:
./mvnw install                       # build all Maven modules + run tests
# then build images (exact build + tag commands owned by platform_protocol.md):
docker compose -f deploy/docker/compose.yml build     # path/name TBD

# Bring the stack up (Elasticsearch + Artemis + services + console):
docker compose -f deploy/docker/compose.yml up        # TBD

# OR into a local K8s dev namespace:
kubectl apply -k deploy/k8s/overlays/dev              # path/tooling TBD (kustomize/Helm)
```

Exact compose file names, image tags, and K8s manifest/Helm paths are **TBD** and owned by the `platform` agent - see [platform_protocol.md](platform_protocol.md) (image standards + bring-up runbook) and [deploy_protocol.md](deploy_protocol.md).

### Clean rebuild / wipe state

Recreating containers keeps volume state (an Elasticsearch index and Artemis queues survive). For a true clean slate - fresh images **and** wiped data:

```bash
# docker-compose: down with volumes, rebuild without cache, up
docker compose -f deploy/docker/compose.yml down -v          # -v wipes ES + Artemis volumes
docker compose -f deploy/docker/compose.yml build --no-cache
docker compose -f deploy/docker/compose.yml up

# K8s dev namespace: delete + recreate the namespace for a clean slate
kubectl delete namespace lattice-dev && kubectl apply -k deploy/k8s/overlays/dev   # TBD
```

Three reset levels (least to most destructive):
- **Recreate containers only:** `docker compose up --force-recreate` (fresh containers, keeps volume data)
- **Rebuild images:** `build --no-cache` then up (new code, keeps volume data)
- **Full clean slate:** `down -v` (wipes Elasticsearch + Artemis volumes), then rebuild + up

---

## Test data

- **Seeded index (preferred for data-facing QA):** load a known dataset into Elasticsearch so queries and console panels have something to show. The seed mechanism (a seed job / a documented reindex command) is **TBD** - land it and reference it here; until then, index a small fixture set by hand and record how.
- **Empty / fresh-cluster state:** bring the stack up with no seed (or a wiped volume) to exercise genuinely empty responses - this doubles as the new-cluster / first-run test. Do not fake empty with a runtime toggle; use a real empty index.
- **Two-cluster interop data:** for mesh QA, each cluster has its own (possibly divergent) Elasticsearch data model; seed both and verify they stay **interoperable** across the mesh, not just that each works alone.
- Never point a QA stack at production data - use synthetic seed data at realistic scale.

### QA data mode - live is the default

**Feature QA runs against the real stack:** Elasticsearch + Artemis + the services + the console, all up, so you exercise the actual wiring, persistence, and cross-cluster behavior. This is the standard QA mode; a contract-mock console is a UI-dev tool, not the QA loop.

- **Live QA needs the whole stack up, or panels read empty.** Before live QA confirm all of: (1) **Elasticsearch** up and reachable; (2) the **Artemis broker** up (mesh + messaging depend on it - the piece most often forgotten, and a dead broker reads as "no peers / no mesh" while single-service reads still work); (3) every **service** started and passing readiness; (4) the **index seeded** so "search"/data panels have data.
- **A dependency-down-at-startup latches a failing state.** If a service starts while Elasticsearch or the broker is not yet ready, its readiness probe fails and it stays out of rotation; the console shows it down. Bring **Elasticsearch and the broker up first**, then the services - or recreate the service once its dependencies are ready.
- **Mesh QA needs two clusters actually up.** A single cluster can never show discovery; stand up two (two compose projects / two namespaces) pointed at a reachable mesh broker, then verify announce + discover (see below).
- A steward-only runtime "data mode" toggle is **not** used (it would put a dev affordance into the running services); the seed + wipe approach covers the same QA need with no code.

---

## Mesh discovery QA

For any change touching cluster registration, announce, or peer discovery, single-cluster QA is not enough - bring up **two clusters** and verify the mesh:

- [ ] Both clusters start and each passes its own health/readiness.
- [ ] Each cluster **announces itself** onto the Artemis mesh and **discovers the peer** - the console (or the mesh/peer view) on each side lists the other.
- [ ] A cross-cluster interaction that the change targets works end to end (interop holds despite divergent Elasticsearch models).
- [ ] Bring one cluster down and confirm the peer reflects it (discovery is live, not one-shot).

Exact mesh mechanics (envelope format, announce cadence, discovery protocol) are **planned - design session**, owned by the `platform` agent ([platform_protocol.md](platform_protocol.md)) with envelope versioning in [contract_protocol.md](contract_protocol.md).

---

## Recording & sign-off

- The PR's checked QA boxes (with how-run notes) are the durable record - they live with the change.
- `qa-passed` label = sign-off that required coverage ran green.
- No separate QA log is kept; if a release-level summary is ever needed, derive it from merged PRs' QA sections.
