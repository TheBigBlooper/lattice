# Baseline Component Reporting

What a baseline reports about everything running inside it: its Vert.x services, and - kept deliberately separate - the infrastructure those services depend on. Settles how the console can answer "what is actually running here, and is any of it unhappy" without changing what travels on the mesh.

Related: [mesh-gateway.md](../services/mesh-gateway.md) (the service that computes and serves all of this), [api_structure.md](../architecture/api_structure.md) (the response envelope and the operational probe shape), [mesh_broker_topology.md](../architecture/mesh_broker_topology.md) (the mesh-link signal reused here), [per_baseline_identity.md](per_baseline_identity.md) (the Keycloak this reports on), [ui/_index.md](../ui/_index.md) (the console direction this renders into), [data_model.md](../architecture/data_model.md) (the index settings this changes), [locked_decisions.md](../../reference/locked_decisions.md) (#42 sole mesh participant, #43 health rollup, #46 mesh-link state, #48 realm shape).

---

## The gap this settles

The console reports `2 of 2 services ready` while a baseline runs **three** Vert.x services and **three** infrastructure components. The configured watch list names only orders and inventory, so mesh-gateway, Elasticsearch, Artemis and Keycloak are invisible to an operator.

The rollup is not wrong. It is true by its own definition, and that definition is narrower than the question an operator actually arrives with. A console that shows two of the six things running here, and gives no indication that the other four exist, is not under-reporting a number - it is answering a different question than the one being asked.

A second, quieter defect sits underneath it and is the more serious of the two. **Elasticsearch reachability is already known, but Elasticsearch health is not.** The readiness check each data-owning service registers is a client ping, which succeeds whenever Elasticsearch answers at all. A cluster in the **red** state - primary shards unallocated, data genuinely unavailable - answers that ping perfectly well, so it is reported as `UP` today. The baseline would announce itself `ready` while unable to serve.

---

## The split that organises everything below

Two surfaces exist, they answer different questions, and almost every decision here follows from keeping them apart.

| Surface | Read by | Answers | Carries |
|---------|---------|---------|---------|
| The announced rollup (`health` on the announcement) | peer baselines | "is redirecting an operator here worth it?" | one word, this baseline's own services only |
| `getBaseline` | this baseline's own console | "what is running here, and is any of it unhappy?" | the full picture: services, infrastructure, mesh link |

**Infrastructure is reported only on the second.** Locked #43 stands unamended: the verdict that rides the mesh remains a rollup of this baseline's services, so a yellow datastore cannot make a peer believe this baseline cannot serve, and the announcement's meaning does not drift as components are added.

Folding infrastructure into the announced verdict was considered and declined, and the reasoning is worth recording because the intuition behind it is a good one. The goal it serves - seeing Artemis, Elasticsearch and Keycloak reliably in the console - is met **entirely** by the local surface. The console never reads its own baseline's announcement; it reads `getBaseline` directly. So the fold-in buys the console nothing while costing the verdict its stability: a peer seeing `degraded` could no longer tell a broken service from a merely-redundancy-degraded datastore, and that distinction is the only thing a peer does anything with.

**Artemis is the proof that the verdict was never the route to visibility.** It is the component an operator most wants a reliable reading on, and it is precisely the one that can never appear in an announcement. If this baseline's broker link is down it cannot announce at all, so the single state "Artemis is down" exists to communicate is the exact state that prevents it being communicated. Every announcement any peer is capable of receiving would necessarily report Artemis as up. That is locked #46's reasoning, and it holds independently of preference: a report about a broken link cannot travel over that link.

---

## Services

### The gateway now lists itself

mesh-gateway appears in the per-service breakdown on `getBaseline`, so an operator sees all three Vert.x services rather than two.

It stays **excluded from the announced rollup**, which is what locked #42 actually reasons about: the gateway's own readiness cannot fail (it has no datastore, and broker loss deliberately does not fail it), so announcing it would put a constant, meaningless `ready` on the wire. That argument is about the wire. It never argued for hiding the service from its own operators, and the console saying `2 of 2` while three services run is the cost of having read it as though it did.

This is the same local-versus-announced split as everything else here, applied to the gateway itself.

### What a service reports

Unchanged. A service is `UP` only if its readiness probe answers HTTP 200; a non-200, a timeout, and a refused connection are all `DOWN`. The rollup is `ready` when all are up, `degraded` when some are, `down` when none are.

---

## Infrastructure

### Contract shape: a sibling array

`getBaseline` gains an `infrastructure` array alongside the existing `services` array. The existing field is untouched.

```json
{ "data": { "clusterId": "hub-central", "region": "us-central", "baselineVersion": "1.0.0",
            "apiVersions": ["v1"], "health": "ready",
            "services": [ { "name": "orders", "status": "UP" },
                          { "name": "inventory", "status": "UP" },
                          { "name": "mesh-gateway", "status": "UP" } ],
            "infrastructure": [
              { "name": "elasticsearch", "kind": "elasticsearch", "status": "UP" },
              { "name": "artemis", "kind": "artemis", "status": "UP" },
              { "name": "keycloak", "kind": "keycloak", "status": "DEGRADED",
                "detail": "management endpoint returned 503" } ],
            "meshLink": "up" },
  "meta": { "requestId": "...", "apiVersion": "v1" } }
```

The addition is **additive**, so the generated console client and every existing reader keep working - responses are lenient by design (no `additionalProperties: false`), which is exactly the forward-compatibility this relies on.

A separate array was chosen over tagging one combined array with a category, because the two groups do not share a shape: infrastructure carries a `kind` and a `detail` that are meaningless on a service, and a combined array would either widen the service type with fields it never uses or flatten both to a lowest common denominator. It also changes an existing field's contents rather than adding a new one, which is the difference between an additive change and a breaking one. A nested object keyed per component was declined for the opposite reason: it renders precisely but cannot be rendered generically, so every new component would require a console change as well as a service one.

### Vocabulary: a coarse shared state, plus the native reading

Every component reports one of **`UP` / `DEGRADED` / `DOWN`**, with an optional free-text `detail` carrying the reading in its own system's language.

This is the compromise the three components force. Ready/degraded/down is a service vocabulary; Elasticsearch speaks green/yellow/red, Artemis is connected or not, Keycloak is up or not. Typing each native vocabulary into the contract would be the most faithful option and was declined: the console would need per-kind rendering and a colour mapping per vocabulary, so adding a component would mean changing the console. Reusing the plain `UP`/`DOWN` of the service probes was declined for the opposite reason - it cannot express `DEGRADED` at all, which is the state this design exists to preserve.

The coarse state is what the console colours and counts; the detail line is what an operator reads when they want to know why. A new component is renderable with no console change, and nothing about its real state is lost.

`kind` is carried explicitly rather than inferred from `name`, so an operator may name a component whatever is meaningful in their deployment while the gateway still knows which probe to run.

### Where the probes live

**mesh-gateway probes everything.** It already owns a polling loop that fans out in parallel with a timeout comfortably inside the heartbeat interval, and already caches the result so the endpoint never blocks on a live poll. Infrastructure targets are two more entries in that fan-out.

The alternative - each service reporting the dependencies it already holds a client for - reuses existing connections but has the gateway merging duplicate reports of one shared Elasticsearch cluster, and leaves Keycloak unowned, since no service polls it. A dedicated probe component was disproportionate: a fourth container to deploy and monitor, for two HTTP polls.

**Infrastructure never affects the gateway's own `/readiness`.** The gateway can still serve the peer registry and this document with a red Elasticsearch or a dead Keycloak - it has no datastore of its own, and it validates bearer tokens against cached signing keys rather than by calling Keycloak per request. Reporting `DOWN` would pull the pod from rotation and give the console nothing during exactly the incident an operator most needs visibility into. This is the reasoning locked #42 already applies to broker loss, extended to the rest of the infrastructure.

---

## The three components

### Artemis - reuses the mesh-link signal, no new probe

The gateway already holds this baseline's broker connection and already reports its state, as the `meshLink` field settled by locked #46. The Artemis row in the infrastructure list **renders that same value**; nothing new is probed.

A second broker check would be a parallel implementation of a signal that exists, which is the defect the reuse-over-rebuild rule names. It is also the harder thing to build: Artemis exposes no HTTP health endpoint, so an independent check means a transport-level probe, and the local stack's own container check reaches the broker through a shell rather than over HTTP.

What is genuinely lost is the distinction between "the broker is down" and "the broker is up but our connection to it dropped". That distinction is not currently actionable - the operator's response is the same in both cases, and locked #46 already owns the semantics of the link state - so it is recorded as a deferral rather than paid for now.

| `meshLink` | Artemis row |
|------------|-------------|
| `up`       | `UP`        |
| `down`     | `DOWN`      |

There is no `DEGRADED` for Artemis: a connection is held or it is not.

### Keycloak - a new probe, in a shape that needs no translation

Keycloak serves its health probes on its **management port (9000)**, not on the port it serves tokens on. This was verified rather than assumed, from a container on the baseline's own network: `http://keycloak-central:9000/health/ready` answers **200**, and the same path on port 8080 answers **404**.

The response body is:

```json
{ "status": "UP", "checks": [] }
```

That is byte-for-byte the operational probe shape [api_structure.md](../architecture/api_structure.md) already defines for every Lattice service. **No translation is needed** - the gateway reads the same field it reads from a service readiness probe. This was expected to be the awkward component and turned out to be the simplest.

| Probe result | Keycloak row |
|--------------|--------------|
| 200 with `status` `UP` | `UP` |
| 200 with any other status, or a non-200 | `DEGRADED`, `detail` carrying the status line |
| timeout or refused connection | `DOWN` |

The management port must be reachable from the gateway in **both** compose and the Helm chart. In compose it is on the shared network already; the chart must expose it as a second port on the Keycloak service, which is a build-ticket item rather than a design question.

### Elasticsearch - the one that needed fixing, not just reporting

Elasticsearch is the only component of the three that required changing the system rather than reporting on it, for two separate reasons.

**The health signal does not exist today.** The readiness check each data-owning service registers is a client ping, so it reports reachability, not health. A **red** cluster - primaries unallocated, data genuinely unavailable - answers that ping and is reported `UP`. The gateway therefore polls `GET /_cluster/health` directly, which is the only source that distinguishes the three states.

> **What this closes, and what it does not.** The gateway's poll makes the **infrastructure row** honest. It does **not** change what a service's readiness reports: orders and inventory still ping, so with a red cluster they still answer `UP`, the services rollup still reads `ready`, and this baseline still **announces itself ready while unable to serve**. The console would show "3 of 3 services ready" beside "Elasticsearch: DOWN" - each row true on its own terms, the pair incoherent.
>
> Making the verdict itself honest means changing what a data-owning service's readiness check asks: from "does Elasticsearch answer" to "can it serve my queries". That carries a consequence beyond reporting - a red cluster would then pull every data-owning pod out of rotation, which is arguably correct but is an orchestration behaviour change, not a display one. It is therefore **left open here rather than assumed**, as the natural next question after this design rather than a part of it.

**And the reading it would return locally is permanently yellow.** Measured on the local single-node stack: cluster status `yellow`, three active primary shards and **three unassigned** shards, with all three indices carrying one replica each. Nothing in the shared Elasticsearch layer overrides the default replica count, and a single node cannot allocate a replica of its own primary. Every local baseline would show a permanent warning.

Three ways out were weighed, and the distinction that decided it is between reporting the state differently and changing the state.

| Option | Verdict |
|--------|---------|
| Relay the raw colour | Declined. Honest and free, but a warning that is always on is one operators stop reading, which costs the red signal too |
| Report serving capacity - treat yellow as ready | Declined, though the reasoning is sound: yellow genuinely does mean every primary is allocated and the cluster serves reads and writes normally. It discards the signal on a **deployed multi-node** baseline, where a lost replica is a real loss of redundancy and the operator should hear about it |
| Fix the cause | **Chosen.** Make the local cluster genuinely green rather than describing a yellow one more kindly |

The fix is Elasticsearch's own **`auto_expand_replicas: "0-1"`** index setting, which sets the replica count from the number of nodes available. Verified on the local single node: an index created with it reports `rep=0` and health **green**. On a multi-node deployed baseline it expands back to one replica, so redundancy is preserved there and yellow keeps meaning what it should.

Nothing is reinterpreted. The local cluster is not reported green; it **is** green.

The state mapping then applies honestly everywhere:

| Cluster status | Elasticsearch row | Meaning |
|----------------|-------------------|---------|
| green  | `UP`       | every shard allocated |
| yellow | `DEGRADED` | primaries allocated and serving, replicas missing - a real loss of redundancy on a multi-node baseline |
| red    | `DOWN`     | primaries unallocated, data unavailable. **Reported as healthy today** |
| unreachable | `DOWN`, `detail` naming the failure | |

This setting change touches the index settings in the shared Elasticsearch layer, which is **single-writer**, so it serializes against other mapping work and lands as its own build ticket. It is a settings change rather than a mapping change, so it needs no reindex.

---

## Configuration

A **second variable**, parsed by the same code as the existing service list:

```
CLUSTER_SERVICES=orders=http://orders-central:8080,inventory=http://inventory-central:8080,mesh-gateway=http://mesh-gateway-central:8080
CLUSTER_INFRASTRUCTURE=elasticsearch:elasticsearch=http://elasticsearch-central:9200,artemis:artemis=,keycloak:keycloak=http://keycloak-central:9000
```

Each infrastructure entry is `name:kind=url`. The `kind` selects the probe; the `name` is the label the console renders, so a deployment may call its datastore whatever it calls it. The Artemis entry carries no URL, because its state is read from the gateway's own broker connection rather than probed.

Extending the existing variable with a category was declined: it redefines the syntax of a variable that is already deployed, so every compose file, the chart, and the documented example must change together or the gateway misparses on upgrade. A separate variable leaves `CLUSTER_SERVICES` meaning exactly what it means today, and the two lists stay independently configurable.

Inferring the targets from configuration the gateway already holds was declined because it does not hold them: the gateway is the one service with no Elasticsearch dependency, so it has no datastore address, and giving it one purely to name a probe target adds a dependency to avoid a variable.

**Unset means absent.** An empty or missing `CLUSTER_INFRASTRUCTURE` yields an empty array, the console renders no infrastructure card, and the gateway logs a loud startup warning naming the misconfiguration. This matches the existing precedent for an empty service list exactly: warn clearly, do not fail startup, so a deliberately minimal deployment still runs.

Rendering a card whose every component reads "unknown" was declined - it requires the gateway to hardcode the set of components it expects in order to list them as missing, which is the thing configuration exists to avoid, and a card full of unknowns looks like a fault rather than like an unset option.

---

## The console

The confirmed visual direction is **two rollups, stacked**: the cluster verdict keeps reporting the baseline's own services, and infrastructure gets its own rollup and list beneath it, each group speaking its own vocabulary.

Keeping locked #43 is what leaves that direction intact - the top number still means what the mockup showed it meaning. Had infrastructure folded into the verdict, the confirmed mockup would have needed re-confirming, because the largest thing on the screen would silently have changed definition.

This inherits the console's settled direction with no amendment: a rollup above its breakdown, the pattern the screen already teaches once and now applies three times; every state rendering its colour, its glyph **and its word**, so colour is never the sole indicator; and the infrastructure card absent rather than empty when nothing is configured.

The two alternatives considered for this surface were grouped chips (declined: does not scale past roughly six components and carries no detail) and one grouped table (held in reserve rather than declined - it scales best, and remains compatible as a later step because it is the infrastructure card's shape without the rollup changing meaning).

---

## What was measured rather than assumed

Every load-bearing fact here was checked against the running stack, because three of them had been carried in the ticket as open questions and two turned out to be wrong.

| Claim | Result |
|-------|--------|
| Keycloak health is on management port 9000, reachable from the gateway | **Confirmed.** 200 from a container on the baseline network; port 8080 answers 404 |
| Keycloak needs vocabulary translation | **False.** Its body is already the operational probe shape this project defines |
| A local single-node cluster is permanently yellow | **Confirmed.** Yellow, three unassigned replica shards, all indices at one replica |
| `auto_expand_replicas: "0-1"` makes a single node green | **Confirmed.** Index created with it reports `rep=0` and green |
| Artemis needs a new probe | **False.** The mesh-link state already carries it |
| Elasticsearch health is known today | **False, and a live defect.** The readiness check is a ping, so a red cluster reports `UP` |

---

## Decisions settled here

- Infrastructure is reported **only on this baseline's own API** and never announced; locked #43 stands unamended and the mesh envelope does not grow.
- **mesh-gateway lists itself** in the local per-service breakdown, while staying out of the announced rollup - refining locked #42, whose reasoning concerned the wire rather than local visibility.
- `getBaseline` gains a sibling **`infrastructure` array**, additive, leaving `services` untouched.
- Infrastructure speaks a **coarse `UP` / `DEGRADED` / `DOWN` state plus an optional detail line** in its own system's language, so the console renders it generically.
- **Artemis reuses the existing mesh-link state**; no second broker probe is built.
- **Keycloak is probed on its management port**, in a response shape needing no translation.
- **Elasticsearch is fixed rather than reinterpreted**: `auto_expand_replicas: "0-1"` makes a single-node cluster genuinely green, and cluster status maps yellow to `DEGRADED` and red to `DOWN`. The gateway polls cluster health directly, which makes the infrastructure row honest - **but leaves the services rollup unchanged**, so a red cluster still announces `ready`. Closing that means changing what a service's readiness check asks, and is deliberately left open.
- A **second configuration variable** names infrastructure, as `name:kind=url`; unset yields no card plus a startup warning.
- Infrastructure **never affects the gateway's own readiness**.

---

## Open

- **Should a data-owning service's readiness ask whether Elasticsearch can serve, rather than whether it answers?** Today it pings, so a red cluster leaves the service `UP` and the baseline announcing `ready` while unable to serve. This design makes that visible on the infrastructure row without resolving it, because the fix changes orchestration behaviour (a red cluster would drain every data-owning pod) and not merely what is displayed. The next design question after this one.

---

## Deferred

- **Distinguishing a down broker from a dropped connection to a healthy one.** The operator's response is currently identical in both cases, and the mesh-link state already owns the semantics.
- **Per-component history** - how long a component has been degraded, rather than its state at this instant. The same information the console needs to tell a blip from an outage on a failed read, and it belongs with that work rather than here.
- **Infrastructure the gateway cannot reach at all** as a distinct state from down. A probe that cannot resolve its target and a component that is genuinely dead are reported the same way, matching how service readiness already treats unreachable and broken alike.
- **Alerting.** This design makes state visible; it does not act on it. Thresholds and notification belong with the observability work.

---

## Sources

- [Elasticsearch index-level shard allocation](https://www.elastic.co/guide/en/elasticsearch/reference/current/index-modules.html) - `auto_expand_replicas` and its node-count behaviour.
- [Elasticsearch cluster health](https://www.elastic.co/guide/en/elasticsearch/reference/current/cluster-health.html) - the green/yellow/red definitions this maps from.
- [Keycloak health checks](https://www.keycloak.org/observability/health) - the management port and the `/health/ready` endpoint.

Both Elasticsearch behaviours above were additionally verified by running them against the local stack rather than taken from the documentation alone.
