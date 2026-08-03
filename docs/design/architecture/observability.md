# Observability - Instrumentation

What every Lattice service measures, where it exposes it, and what is deliberately left unmeasured for v1.0.0. Settles the **instrumentation half** of observability.

Observability splits into two halves with different dependencies, and only one is blocked. **Instrumentation** depends on no hosting decision and is buildable against the local stack. The **collection stack** needs somewhere to run, which is what locked #56 defers. This document settles the first half and **records a default for the second without building it** (see [Collection stack](#collection-stack-recorded-not-built)).

Before this, observability did not exist: no registry, no endpoint, no instrumentation anywhere in the tree. The cost was not hypothetical. Diagnosing the mesh federation defects meant reading Artemis bytecode and counting queue messages by hand at a broker, and the duplicate-delivery defect was found by comparing announcement counts a human tallied.

Related: [mesh_discovery.md](mesh_discovery.md) (announce cadence, peer liveness, the time-to-live model), [mesh_broker_topology.md](mesh_broker_topology.md) (federation, the link this baseline holds to its own broker), [baseline_component_reporting.md](../features/baseline_component_reporting.md) (the infrastructure card, which this does not replace), [api_structure.md](api_structure.md) (the operational surface `/health` and `/readiness` already occupy), [integrations.md](../../reference/integrations.md) (the Observability section this promotes), [locked_decisions.md](../../reference/locked_decisions.md) (#15 one engine per job, #39 logging, #46 mesh-link state, #48 the open probe surface, #55 delivered not hosted, #56 hosting deferred, #66 and #67 infrastructure reporting, #73 the management-port precedent, #77 chart-declared configuration).

---

## Principle

**Measure what a defect actually cost us to diagnose, and nothing else yet.** Every metric below either instruments a model this project already documents (announce cadence, peer liveness, the health rollup, the mesh link) or answers a question somebody has already had to answer by hand. Nothing is added because a dashboard might one day want it.

The second principle is that instrumentation is a **shared runtime concern, not a per-service one**. A service does not decide whether it is measured, any more than it decides whether it has a readiness probe. It inherits both.

---

## Engine

**`vertx-micrometer-metrics` with a Micrometer `PrometheusMeterRegistry`, exposed in Prometheus text format and scraped.**

| Considered | Outcome |
|--------------------------------------|-----------|
| Vert.x Micrometer + Prometheus registry | **Chosen.** |
| OpenTelemetry software development kit, pushing over the OpenTelemetry Protocol | Rejected for v1.0.0. |
| Micrometer standalone, no Vert.x binding | Rejected. |

Three reasons, in order of weight:

1. **It is the in-stack option, so the one-engine rule (#15) is satisfied by extension rather than by exception.** `vertx-micrometer-metrics` and `micrometer-core` both ride `vertx-dependencies`, already managed in the parent pom.

   **One pin is required, and it was measured rather than assumed.** `vertx-dependencies` does **not** manage `micrometer-registry-prometheus` - the exposition registry is the one metrics artifact left unmanaged, so it carries an explicit version in the parent (`micrometer.version`), set to the `micrometer-core` that Vert.x 5.1.5 resolves (**1.16.6**). It must track that version rather than float: a registry built against a different `micrometer-core` is exactly the convergence problem the enforcer gate exists to catch, and it does catch it, so a drift fails the build rather than surfacing at runtime.
2. **Vert.x's own backend supplies most of the baseline for free.** Hypertext Transfer Protocol server metrics, event-loop and pool metrics arrive from the binding rather than from instrumentation somebody has to write and maintain. Standalone Micrometer was rejected on exactly this point: identical output format, every baseline metric hand-written.
3. **A scrape does not require a collector to exist.** The OpenTelemetry option pushes, so nothing works until a collector is deployed, which couples the buildable half of this work to the deferred half. A scrape endpoint is useful the moment it exists, even if the only thing reading it is a developer with `curl`.

**Consequence for the placeholder variables.** [integrations.md](../../reference/integrations.md) carried `OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_SERVICE_NAME` as placeholder names that nothing read. Choosing Micrometer makes them **wrong rather than pending**, so they are removed rather than left sitting as an unfilled form. The OpenTelemetry bill of materials pinned in the parent pom is untouched and unrelated: it exists solely to constrain what the Elasticsearch client pulls in transitively.

---

## Exposure

### Its own port

**A second Hypertext Transfer Protocol server on a dedicated management port serving `/metrics`, with its own Service in the chart.** Not the API port.

This is a pattern the repository has already chosen once. Locked #73 put Keycloak's management port on its own Service so an operational surface stays reachable independently of application traffic, after a failed readiness check removed the pod from its main Service and made the endpoint that would explain the failure unreachable. The same separation applies here for a second reason as well: it keeps the scrape surface off the port the console's browser talks to, so an unauthenticated `/metrics` never widens the unauthenticated surface of a port that is deliberately reachable.

| Property | Value |
|----------------|--------------------------------------------------------|
| Path | `/metrics` |
| Port | `METRICS_PORT`, default `9090` |
| Service | ClusterIP, one per component, never a NodePort |
| Format | Prometheus text exposition |
| Envelope | None. Like `/health` and `/readiness`, this is an operational surface, not business API, so it is unversioned and not wrapped in `{data|error, meta}` (#35). |

### Not protected, bounded by reachability instead

**No bearer token.** Exposure is bounded by what can route to the port: the management Service is **ClusterIP**, never a NodePort, and never published to the host.

Locked #48 left `/health` and `/readiness` open because a prober cannot carry a token. A scraper has the same problem, and locked #59 already recorded the cost of inventing a new credential path for a surface that cannot use the existing one. Requiring a token here would mean a service account or client-credentials grant in every baseline's realm that nothing else uses, handed to every collector deployment.

**The trade is real and is stated rather than glossed.** Metrics disclose more than a probe does: request rates, error counts, latency distributions, how many peers this baseline can see. They disclose no order, no stock record, and no identity. Anyone who can already route to a pod's management port inside the cluster can read those rates. That is accepted; it is not nothing.

---

## What is measured

Three layers. The first arrives from the binding, the other two are written.

### Layer 1 - automatic, from the binding

| Family | Source | Notes |
|-----------------------|-----------------------|-----------------------------------------------|
| Java Virtual Machine memory, garbage collection, threads, class loading | Micrometer binders | Standard, no configuration beyond registration. |
| Hypertext Transfer Protocol server | Vert.x binding | Request count, duration, response codes. Labelled per [Cardinality](#naming-and-cardinality). |
| Pools and event loop | Vert.x binding | Worker pool queueing and event-loop delay, which is the signal for blocked-event-loop trouble. |

Deliberately **not** enabled: Hypertext Transfer Protocol client, net client and server, event bus, and datagram families. The event bus here is in-process and mostly uninteresting, and each additional family widens the scrape payload and the label surface for questions nobody has asked.

### Layer 2 - the mesh, in mesh-gateway

Every one of these instruments a model already documented in [mesh_discovery.md](mesh_discovery.md) or [mesh_broker_topology.md](mesh_broker_topology.md), and every one has a defect or a manual count behind it.

| Metric | Type | Labels | Why it exists |
|--------------------------------------------|---------|-------------------|--------------------------------------------------------------|
| `lattice_mesh_announcements_published_total` | counter | none | The announce cadence, stated rather than inferred from logs. |
| `lattice_mesh_announcements_received_total`  | counter | `source_cluster`  | **The one that pays for itself.** The duplicate-delivery defect was found by counting messages by hand at a broker: this baseline's own announcements arriving 6 a minute against each peer's 12 to 14. This metric states that ratio outright. |
| `lattice_mesh_peers_known`                   | gauge   | none              | Registry size. |
| `lattice_mesh_peers_reachable`               | gauge   | none              | How many of them are inside the time-to-live window. |
| `lattice_mesh_peer_expiries_total`           | counter | `peer_cluster`    | A peer flapping across its time-to-live and a peer cleanly gone are **identical in a gauge**. Only the counter separates them. |
| `lattice_mesh_link_up`                       | gauge   | none              | `1` or `0` for this baseline's link to its **own** broker. Locked #46 already establishes that this signal cannot ride the mesh and must be served locally; a metric is that same argument on another surface. |
| `lattice_mesh_link_reconnects_total`         | counter | none              | Distinguishes a link that is up now from one that has been flapping all morning. |
| `lattice_baseline_health`                    | gauge   | `state`           | The rollup, as a state set: `1` on the current state, `0` on the others, across `ready` / `degraded` / `down`. |
| `lattice_service_readiness_polls_total`      | counter | `service`, `outcome` | Which service dragged the baseline to `degraded`, and how often, without reading logs. `outcome` is `up` or `not_up`, matching the rollup's own definition where a non-200, a timeout, and a connection error all count as not-UP. |

### Layer 3 - the data layer, in the shared repository base

| Metric | Type | Labels | Notes |
|---------------------------------------------|---------|----------------------|-------------------------------------------|
| `lattice_elasticsearch_operation_seconds`     | timer   | `operation`, `index` | Emits count and sum, so throughput comes free with latency. |
| `lattice_elasticsearch_operation_errors_total`| counter | `operation`, `index` | Kept separate from the timer so a failure rate is readable without a tag filter. |

Written **once in the shared repository base** in `lattice-common`, so every service inherits it without writing anything. This is the same reuse-over-rebuild move that put cross-origin handling in `BaseVerticle` once rather than per service.

It answers a question the other layers cannot: **is the datastore slow, or is the endpoint slow?** Hypertext Transfer Protocol server metrics show latency at the edge and cannot attribute it. This is also distinct from what locked #66 and #67 already report: those give Elasticsearch **cluster health** for the infrastructure card, which says whether the datastore is well, not how long it is taking to answer.

---

## Naming and cardinality

- **Prefix.** Every hand-written metric is `lattice_*`. Binding-supplied metrics keep their own names (`vertx_*`, `jvm_*`) rather than being renamed, so they match their upstream documentation.
- **Units in the name**, per Prometheus convention: `_total` for counters, `_seconds` for timers.
- **The Hypertext Transfer Protocol label is the OpenAPI route template**, not the raw path. `/api/v1/orders/{orderId}`, never `/api/v1/orders/7f3a...`. Raw paths would put every order id ever requested into the metric surface, which is unbounded cardinality and the classic way a metrics endpoint becomes the outage.
- **Every other label set is bounded by something that already exists**: `source_cluster` and `peer_cluster` by the peer count, `service` by `CLUSTER_SERVICES`, `index` and `operation` by the data model, `state` and `outcome` by fixed vocabularies.
- **No unbounded label anywhere**, and no user-supplied value ever becomes a label.

> **Verified at build time, not asserted here.** Obtaining the route template rather than the raw path is a Vert.x 5.1.5 binding detail. If it cannot be reached cleanly, the fallback is to aggregate Hypertext Transfer Protocol metrics with **no** path label at all, and that fallback is recorded here so it is a known outcome rather than a silent one. Shipping raw paths is not an option under any circumstance.

---

## Where the wiring lives

Vert.x metrics are enabled on `VertxOptions` when the `Vertx` instance is **created**, which is a bootstrap concern `BaseVerticle` cannot reach: by the time a verticle starts, the instance already exists.

Today each service's `MainVerticle.main` holds an identical block that sets the logging delegate system property and calls `Vertx.vertx()` with no options. That block is **triplicated** across orders, inventory and mesh-gateway, and a fourth service would copy it again.

**A shared bootstrap helper in `lattice-common` owns the construction**: it sets the logging delegate property (which must happen before Vert.x initialises its logging), builds `VertxOptions` with the metrics options when metrics are enabled, and returns the instance. Each `MainVerticle.main` calls it.

This is reuse-over-rebuild applied to the seam the feature actually needs, and it removes an existing triplication rather than adding a fourth copy. It is the only part of this design that reaches into every service's bootstrap, and the change to each is mechanical.

The rest divides cleanly:

| Concern | Home |
|-----------------------------|-------------------------------------------|
| Registry construction, metrics options | the shared bootstrap helper, `lattice-common` |
| The management server and `/metrics` route | `BaseVerticle`, so every service inherits it |
| Mesh and rollup metrics | mesh-gateway's existing announce and health services |
| Data-layer metrics | the shared repository base, `lattice-common` |

---

## Configuration

**On by default.** Unset means enabled; an environment that does not want it turns it off explicitly.

| Variable | Default | Meaning |
|-------------------|---------|-------------------------------------------------------|
| `METRICS_ENABLED` | `true` | When false, no registry, no management server, no port bound. |
| `METRICS_PORT`    | `9090` | The management port serving `/metrics`. |

This follows the precedent [api_structure.md](api_structure.md) already set for the interactive docs surface: unset means on, so a value nobody set never silently withdraws the surface, and an environment that wants it gone says so in a way a deploy can be checked for. It is safe here because the port is ClusterIP-only and unpublished.

Both variables are declared in the chart, in the shared environment of the library chart every subchart uses, per locked #77. They are read through the shared config loader, never by scattered `System.getenv` calls.

`METRICS_ENABLED=false` is a real state with a real code path, not a documented intention, and it is tested as such.

---

## Collection stack (recorded, not built)

**Per baseline by default, with an optional aggregation path named but not implemented.** Nothing collects anything in this change; this records the default so the next person does not have to re-derive it.

**Why per baseline is the default.** It matches the independence locked #12 and #14 give a baseline, and more decisively, under locked #55 the customer runs the clusters. We cannot assume a shared anything exists, and a design whose default presumes infrastructure the customer may not run is a design that does not ship.

**Why an aggregation path is named anyway.** The defect that motivated this work was a **cross-baseline** question: why is hub-central seeing each peer's announcements twice. A strictly per-baseline stack answers that only by being read three times and compared by hand, which is close to the hand-counting this work exists to end. So the optional path is recorded as legitimate rather than discovered later under pressure.

A shared collector as the **default** was rejected: it contradicts the independence the topology is built on, and it would need cross-cluster reachability that locked #56 records as unproven for anything except the broker link.

This stays open, to be settled when hosting is.

---

## Deliberately not instrumented for v1.0.0

This section is load-bearing. It is the boundary that keeps the work finite, and each exclusion is a decision rather than an omission.

| Excluded | Why |
|---------------------------------|------------------------------------------------------------------------|
| **Distributed tracing and spans** | The single largest thing that could be pulled in, worth little without a collector, and the collector is the deferred half. Excluding it is also what retires the `OTEL_*` placeholder variables. |
| **Log aggregation** | Logs stay as they are, per locked #39: one pipeline to standard output, read per pod. Shipping and indexing them is a collection-stack concern with its own storage question. |
| **Business metrics** (orders created, reservations held, stock levels) | The most tempting and the easiest to regret. Elasticsearch is already the source of truth for every one of them, a counter is a second and lossier copy that resets on restart, and putting domain vocabulary into the metrics surface would commit every baseline to the same semantics, which locked #14 deliberately does not do. |
| **Dashboards, alert rules, and service-level objectives** | All need the collection stack to exist. |
| **Third-party exporters** for Elasticsearch, Artemis, Keycloak and MySQL | Those components publish their own metrics and are the customer's to collect, consistent with locked #55 leaving third-party images theirs to obtain. |

> **This does not touch locked #66 or #67.** The gateway's existing Elasticsearch and Keycloak health probes feed the infrastructure card and are a different mechanism entirely. They are unchanged by this document. The two are easy to confuse, which is why it is said here plainly: "no third-party exporters" means Lattice ships no scraper for those components, not that the infrastructure card stops working.

### No console surface

The status console renders nothing from this. It already reports what an operator needs at a glance, and none of it changes: the cluster verdict, the per-service breakdown, the infrastructure card, and the mesh-link state.

Consequently the **mockup gate (Enforcement Rule 16) does not apply** to this work, and no ticket cut from this document carries `needs-mockup`. A metrics view built now would also be replaced by a real dashboard the moment the collection half lands.

---

## Edge cases

- **Metrics disabled.** No registry is created, the management server is not started, and no port is bound. The service is otherwise identical. Both states are tested.
- **The management port is already in use.** Startup fails loudly rather than continuing with metrics silently unavailable, matching how the API port behaves. A service reporting healthy while its operational surface never bound is the failure class locked #73 was written about.
- **A scrape arrives before the registry has data.** The endpoint serves what exists; counters at zero are a valid and meaningful reading, and are distinguishable from a missing series.
- **Elasticsearch is down.** The data-layer error counter climbs and the timer keeps recording, since a failed call still took time. The metric surface does not itself depend on Elasticsearch, so it keeps serving during exactly the incident it is most needed in. This is the same reasoning that keeps mesh-gateway's readiness UP when the broker is unreachable.
- **The broker is down.** `lattice_mesh_link_up` reads `0` and peer expiries climb as the registry ages every peer out at once. This is the pair of readings that distinguishes "we are cut off" from "the peers are gone", which is locked #46's whole purpose, now visible as numbers rather than only in the console.
- **A peer is removed permanently.** Its `source_cluster` and `peer_cluster` label values stop receiving updates but the series remain until the process restarts. Bounded and harmless at three peers, and noted so nobody reads a stale series as a live peer.

---

## Testing (test-first)

All of it is ordinary behaviour and none of it qualifies for a documented exception, so it is written test-first.

- **Endpoint** - `/metrics` serves Prometheus text format on the management port; it is **not** served on the API port; it needs no bearer token while `/api/v1` still refuses one that is absent.
- **Disabled state** - with `METRICS_ENABLED=false` no port is bound and the service starts and serves normally.
- **Route-template labels** - a request to a parameterised path produces a series labelled with the **template**, and two requests with different path parameters produce **one** series, not two. This is the cardinality guarantee, so it is asserted rather than assumed.
- **Mesh counters** - an announce increments the published counter; a received announcement increments the received counter under its source cluster label; a peer crossing its time-to-live increments the expiry counter; the link gauge follows broker connection and loss.
- **Rollup** - the health gauge is a well-formed state set, with exactly one state at `1`.
- **Data layer** - a repository operation records a timing sample under its operation and index labels; a failed operation increments the error counter.
- **No unexpected logs** - suites use the shared fail-on-unexpected-log harness, declaring expected warnings rather than tolerating them.

Metrics assertions read the registry directly rather than parsing the scrape body, except in the endpoint test where the wire format itself is the thing under test.

---

## Decisions settled here

- **Engine:** `vertx-micrometer-metrics` with a Prometheus registry, scraped in Prometheus text format. No new version pin; #15 satisfied by extension.
- **Exposure:** a dedicated management port serving `/metrics`, its own ClusterIP Service, following the locked #73 pattern. Never the API port, never a NodePort, never published to the host.
- **Protection:** none, bounded by reachability instead, with the disclosure trade stated rather than glossed.
- **Measured:** Java Virtual Machine, Hypertext Transfer Protocol server and pool families from the binding; nine mesh and rollup metrics in mesh-gateway; a timer and an error counter in the shared repository base.
- **Cardinality:** route templates rather than raw paths; every label set bounded by something that already exists; no user-supplied value is ever a label.
- **Wiring:** a shared bootstrap helper in `lattice-common` constructs the `Vertx` instance, removing an existing triplication; `BaseVerticle` owns the management server.
- **Configuration:** `METRICS_ENABLED` (default true) and `METRICS_PORT` (default 9090), chart-declared per locked #77.
- **Collection stack:** per baseline by default, optional aggregation named, neither built.
- **Excluded for v1.0.0:** tracing, log aggregation, business metrics, dashboards and alerting, third-party exporters, and any console surface.

## What this does not settle

- **The collection stack itself.** Still open, still waiting on hosting (locked #56), and tracked as an issue rather than in the decision registry.
- **Where dev and prod clusters run.** Unchanged by this document.
- **Whether metrics ever reach the console.** Deliberately unanswered; a dashboard is the more likely home, and that question belongs with the collection stack.

Promoted to a numbered locked decision. See [locked_decisions.md](../../reference/locked_decisions.md).
