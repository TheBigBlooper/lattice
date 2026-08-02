# Baseline configuration: one authored source, and one local stack

How a baseline's configuration is authored, and which local stack expresses it.

Settles **#133**. Related: [delivery_model.md](delivery_model.md) (what a delivery is), [mesh_broker_topology.md](mesh_broker_topology.md) (the broker config this restructures), [per_baseline_identity.md](../features/per_baseline_identity.md) (the realm this templates), [locked_decisions.md](../../reference/locked_decisions.md) (#54 Helm, #55 delivered not hosted, #58 one authority per customer, #71 fast-forward promotion).

---

## The problem, as measured rather than asserted

The ticket opened with one symptom: adding a single environment variable meant editing **five** places, and nothing enforced that they agreed. Building #66, #152, #154 and #134 produced three concrete failures of exactly that shape, and each stayed invisible until one specific second consumer appeared.

| Defect | Where the copies were | Stayed hidden until |
|---|---|---|
| `BASELINE_VERSION` declared twice | **Inside one chart** - `_helpers.tpl` and `services.yaml` | Helm 4 applied server-side and rejected the duplicate outright. The chart could not install a single service |
| `CORS_ALLOWED_ORIGINS` never set | Present in compose, absent from the chart | A console ran on Kubernetes. The browser refused every call before sending it, and the console reported the baseline unreachable while every service was serving |
| A host-port list read by index in three places | One list, three readers | The list grew from three entries to five and only two readers were updated. Keycloak was configured with the *inventory* port |

Two things follow, and they reshape the question the ticket asked.

**It is not only compose-versus-chart.** One defect was intra-chart, one cross-environment, one intra-script. "One authored source" has to hold *inside* a single file, not merely between deployment mechanisms.

**Deriving beat declaring, every time.** Each clean fix replaced a second declaration with a derivation from the first. That is the principle this design generalises.

### Two constraints discovered while building

**The two local stacks cannot run at once.** They collide on 3000-3002, 8080-8083, 8090-8093 and 8100-8103. The overlap is not accidental: both serve the same three baselines to the same browser, so both want the addresses the committed Keycloak realm permits.

**Host ports are not free configuration.** They are pinned by the realm's `lattice-console` redirect list and its docs client's list. Consoles served on 3010/3020 were refused with `Invalid parameter: redirect_uri` until moved to 3001/3002. Nothing in `values.yaml` hinted at the coupling.

---

## Decision 1: compose retires; kind + Helm is the only local stack

The three options were keeping compose authored, generating it from the chart, or retiring it. Only retirement actually delivers one authored source; the other two leave the stacks mutually exclusive and the chart holding capabilities compose structurally lacks (persistent Keycloak, the management Service).

**This was not decidable when the ticket was written.** Retiring compose would have meant ceasing to demonstrate certificate revocation and foreign-authority refusal, which ran there and nowhere else. #160 closed that gap first, so the choice is now between options that are all genuinely available.

**The accepted cost is speed.** Measured: `helm upgrade` is seconds, rebuilding and rolling one service is minutes, and a host-port change forces a full cluster recreate at roughly ten minutes per baseline. Compose was faster and that is a real loss, mitigated but not erased by Decision 6.

---

## Decision 2: `issue-certs.sh` moves to `deploy/certs/`

It is the one thing under `deploy/docker/` that was never local-only. Locked #58 makes it the tool a **customer** runs to create their own certificate authority - we hold no key material their mesh depends on. Leaving it under a directory named for a retired stack would be stale naming that costs someone an hour later.

`deploy/certs/` sits as a peer of `docker/` and `k8s/`, naming what it is rather than which stack once used it.

---

## Decision 3: the retirement is sequenced, with no gap

`mesh-harness.sh` proves six things. #152 ported peer expiry; #160 ported revocation and foreign-authority. Three remain - `degraded`, `baseline-down`, `mesh-cut` - and they were left deliberately, because they assert **local** behaviour that a cluster boundary does not change.

"Does not need re-proving across a boundary" is not "does not need proving at all". Today compose is the only place they run.

So the order is fixed: **port those three to `mesh-clusters.sh` first, retire compose second.** `mesh-harness.sh` is deleted with the compose files, never before them. Enforcement Rule 10 forbids leaving it orphaned once the files it drives are gone, so it cannot simply linger.

---

## Decision 4: `.env.example` is deleted

It carries 29 variables and **nothing reads it** - there is no `env_file` directive anywhere, and compose defaults every value inline. It is documentation, required by `core_protocol.md`'s every-new-variable rule.

With compose gone, that rule repoints at the chart. A service's configuration contract is then declared in that service's own subchart values, which is where someone changing a service will already be looking.

---

## Decision 5: an umbrella chart with per-service subcharts

```
deploy/k8s/chart/                     umbrella - baseline values, installs everything together
├── values.yaml                       clusterId, region, Keycloak, Artemis credentials, service list
└── charts/
    ├── lattice-lib/                  library chart: fullname, labels, commonEnv
    ├── orders/                       values.yaml + templates/{deployment,service}.yaml
    ├── inventory/
    ├── mesh-gateway/
    ├── status-console/
    ├── elasticsearch/
    ├── artemis/
    ├── keycloak/
    └── keycloak-db/
```

Each component running in its own pod owns its own `deployment.yaml` and `values.yaml`. The single `services.yaml` template ranging over a list is retired.

**This amends locked #54 narrowly.** Its *"one chart at `deploy/k8s/chart`"* clause becomes an umbrella plus subcharts. Its *"one release installs one baseline"* clause **survives untouched** - all services still deploy together as one delivery, which is what makes a baseline the unit a customer receives (#55).

**The umbrella authors the service list**, which is load-bearing rather than incidental: `CLUSTER_SERVICES` and `CLUSTER_INFRASTRUCTURE` are what the mesh-gateway polls to compute the health rollup that rides the mesh (locked #43). Split across subcharts with no umbrella, no chart would know the full list and the rollup would have no author.

**A library chart is mandatory, not optional.** With nine subcharts, copying `lattice.fullname`, `lattice.labels` and `lattice.commonEnv` into each would recreate precisely the drift this design exists to remove.

---

## Decision 6: redeploy granularity is scripted *and* documented

Choosing kind-only makes this load-bearing rather than a convenience. The costs are not uniform:

| Change | Path | Cost |
|---|---|---|
| Chart or values only | `helm upgrade` | seconds |
| One service's code | rebuild that image, `kind load`, `rollout restart` **that** deployment | minutes |
| Console code | rebuild **per baseline** - its API addresses are inlined at build time | minutes |
| Host port mapping | full cluster recreate | ~10 minutes per baseline |

`mesh-clusters.sh` gains `redeploy <baseline> <service>` doing exactly the middle path, and `core_protocol.md` gains the table.

**Both, not either.** A documented practice alone is discipline, and the evidence against trusting discipline here is direct: the author of this design fell into the full-rebuild reflex more than once while the correct path already existed. Making the fast path the *easy* path is what changes behaviour.

---

## Decision 7: the realm derives its redirect URIs

The committed realm pins the host ports, and nothing in the chart says so. Retiring compose is what makes fixing this possible: the realm file was mounted by both stacks, which is why it stayed a static document.

The `lattice-console` client's `redirectUris` and `webOrigins`, and the docs client's list, are **templated from `baseline.consoleUrl` and the service ports**. The console URL is authored once and the realm follows, so they cannot disagree.

**Accepted cost:** the realm ConfigMap stops being a byte-for-byte copy of a committed file, so a reviewer reads a template rather than the JSON that ships. The chart README's warning against hand-copying the realm is rewritten accordingly. Roles, groups and client flags stay static - they are genuinely fixed and are more reviewable as JSON.

---

## Decision 8: derive over declare, with one mechanical check

**The principle:** do not re-declare what can be derived. When two things must agree, make one the source and derive the other.

Every clean fix tonight took that shape - CORS from `consoleUrl`, one destructuring per port list, and now the realm from the console URL.

**The check:** a chart-render test asserting no container declares the same environment variable name twice. That single check would have caught `BASELINE_VERSION` **before Helm 4 did**.

**Its limits, stated rather than implied:** it covers one of the three defect shapes. It cannot see a value present in one deployment mechanism and absent from another, nor a list read by index in three places. Claiming it enforces the principle would be the overstatement this project's design docs exist to avoid.

---

## Decision 9: infrastructure is subcharted too

Elasticsearch, Artemis, Keycloak and MySQL each become subcharts with `enabled` flags, alongside the services and the console.

The alternative - subcharting only what Lattice builds - keeps a cleaner conceptual split but loses the thing that matters under this delivery model. **The customer runs the clusters (#55), and may already run a managed Elasticsearch or a managed database.** `elasticsearch.enabled=false` plus an address is the canonical Helm expression of that, and the chart already does a hand-rolled version with `keycloak.database.deployInCluster`. Subcharting generalises an existing pattern rather than inventing one.

**Accepted cost:** nine subcharts to maintain rather than one template and a few files, which is exactly why the library chart is mandatory.

---

## What is removed

`deploy/docker/` is emptied and deleted: the three compose files, `mesh-harness.sh`, `artemis/hub-central|east|west/` connector fragments, and the two READMEs. Only `artemis/tls/` survives, relocated to `deploy/certs/`.

Per Enforcement Rule 10 this happens **in the retirement change**, not before and not later - and only after Decision 3's scenario port has landed.

---

## Decisions settled here

- **Compose retires; kind + Helm is the only local stack.** Speed is the accepted cost.
- **`issue-certs.sh` moves to `deploy/certs/`** - it is a customer tool, not local scaffolding.
- **The retirement is sequenced** so nothing is ever undemonstrated.
- **`.env.example` is deleted**; the every-new-variable rule repoints at the chart.
- **An umbrella chart with per-service subcharts**, one release per baseline; **amends #54's "one chart" clause, not its "one release" clause**.
- **A library chart is mandatory** for the shared helpers.
- **Redeploy granularity is scripted and documented**, both.
- **The realm's redirect URIs derive** from the console URL and service ports.
- **Derive over declare**, plus a duplicate-env-key check whose limits are stated.
- **Infrastructure is subcharted** with `enabled` flags, because bring-your-own is a real customer case.

Promoted to [locked_decisions.md](../../reference/locked_decisions.md) - see #77.

---

## What each container runs as, measured

The chart declares a security context **per component** rather than one blanket policy, because a hardening setting that crash-loops a component is worse than none, and what an image tolerates is a property of that image. Each was measured on a running baseline by asking the container what it runs as:

| Component                          | Runs as              | Policy                        |
|------------------------------------|----------------------|-------------------------------|
| orders / inventory / mesh-gateway  | uid 10001 `lattice`  | strict                        |
| elasticsearch                      | uid 1000             | strict                        |
| artemis                            | uid 1001             | strict                        |
| keycloak                           | uid 1000             | strict                        |
| status-console                     | uid 101 `nginx`      | strict                        |
| keycloak-db (MySQL)                | uid 0, **root**      | cannot take `runAsNonRoot`    |

MySQL is the single exception: its entrypoint chowns the data directory before dropping to the `mysql` user, so `runAsNonRoot` refuses to start the pod at all and dropping `ALL` capabilities removes the `CHOWN`/`SETUID`/`SETGID` that drop needs. What it keeps is the setting that still bites - no process can gain more privilege than it began with. Running it non-root needs a different image or a pre-chowned volume, which is a change to how the database is delivered rather than a setting.

---

## Open

- ~~**`mesh-harness.sh`'s three local scenarios** must be ported before compose is retired (Decision 3).~~ **Done**, and the port found a fourth the six-scenario framing had missed: `loop-check`, which measures `max-hops=1` at the broker, was a separate harness command rather than a scenario and would have been deleted with compose, leaving locked #44's loop prevention demonstrated nowhere. It is ported too, so the retirement kept its no-gap property by a wider margin than Decision 3 asked for.
- **Whether the umbrella's service list can itself be derived** rather than declared. It is currently the one list an operator maintains by hand, which sits uneasily beside Decision 8; no better source was identified in this session.
