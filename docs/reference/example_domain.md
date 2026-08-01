# Lattice - Example Domain (Regional Fulfillment Network)

This is the **illustrative use case** the Lattice services model. It exists so that docs, specs, examples, and the first sample services all reason about **one concrete, real-world scenario** instead of abstract placeholders. It is a teaching example, not a locked product decision - the exact service names, fields, and endpoints below are illustrative and get finalized per service in the design sessions (see [locked_decisions.md](locked_decisions.md), Planned group). When a protocol, skill, or design doc needs a concrete example, draw it from here.

---

## The scenario

A company runs an online store fulfilled from several **regional fulfillment hubs** (for example: West, Central, East, and an EU hub). Each hub picks, packs, and ships orders from its own local stock, using its own local carriers. Each hub is **independent and owns all of its own data** - its own orders, its own stock, its own shipments. An operator overseeing the network sees **every hub in one unified console view**, and when they need to work on a specific hub (place or adjust an order there, check its stock), the console **redirects them to that hub's own console**, where they act on that hub directly.

This maps onto Lattice one-to-one:

| Real world | Lattice |
| --- | --- |
| One regional fulfillment hub | One **cluster** (a Kubernetes cluster) = the versioned **baseline** |
| The programs running a hub (orders, stock, shipping) | The **services** (Vert.x microservices, one Docker container each) |
| A hub's own product catalog, stock, carrier list | That cluster's own **Elasticsearch** data model (divergent per hub) |
| An operator overseeing all hubs from one screen, jumping into any hub to work | The **unified view** + **redirect** in the status console (Shape A federation) |
| Hubs finding each other + advertising where they are | Clusters exchanging **`ClusterAnnouncement` envelopes over the Artemis mesh** |
| The operations wall-board for a hub | The **React status console** (its own container per cluster) |

---

## Services in a hub (the baseline)

Each cluster runs the same versioned baseline of services. Illustrative set:

- **`orders`** - accepts and tracks this hub's own customer orders and their lines; owns order state (received -> allocated -> packed -> shipped -> delivered). Each hub owns its own orders end to end.
- **`inventory`** - stock levels per item at this hub; reserves stock against an order line; the source of truth for "can this hub fill it?".
- **`shipments`** - the outbound shipment lifecycle (label, dispatch, tracking) once an order is packed.
- **`routing`** - chooses a carrier and route for a shipment from this hub's local carrier list.
- **`mesh-gateway`** - this hub's door to the mesh: announces the cluster to peers (with its `consoleUrl` + `apiBaseUrl`), discovers peer hubs, and maintains the peer registry + liveness. It does **not** translate documents - nothing local crosses the mesh (see Interoperability below).

(The status console and the platform-level pieces - the Artemis broker, the Elasticsearch cluster, each hub's Keycloak - run alongside these; they are infrastructure, not baseline business services.)

Services and their REST endpoints are **versioned** (`/api/v1/...`). A hub can run a newer baseline than a peer and still federate, because the mesh announcement is versioned independently of the local REST APIs and each hub is reached on its own terms.

---

## Data model (divergent per hub, federated by redirect)

Each hub keeps its own Elasticsearch indices, and they are allowed to **differ**:

- The West hub may index products by an internal SKU with clothing-specific attributes; the EU hub may index the same catalog with different fields, localized text, and EU-specific carriers.
- Analyzers, field names, and even which attributes exist can vary hub to hub.

This never causes a compatibility problem because **no hub ever reads or writes a peer's data**. To work on a peer hub, an operator is **redirected to that hub's own console** and acts against that hub's own services + indices. The unified view reads a peer's status through that peer's own REST API (`apiBaseUrl`), never its raw index. So divergent local models stay federated without any shared schema or cross-hub translation.

---

## The mesh (Artemis) - what crosses between clusters

Peer clusters discover each other over the Artemis-backed mesh. Under Shape A the mesh is a **discovery phone book** - the only thing that crosses it is a hub announcing its presence and where to reach it:

- **Cluster announcement / discovery** - a hub coming online announces itself (its id, region, baseline version, health, `consoleUrl`, `apiBaseUrl`) on a 10s heartbeat, so peers add it to their peer registry, show it in the unified view, and know where to redirect an operator who wants to act on it.

No orders, shipments, stock, or work of any kind cross the mesh. There is no fulfillment handoff, shipment handoff, or stock signal between hubs - an operator who needs another hub goes to that hub. The `ClusterAnnouncement` envelope lives in `platform/lattice-contract` and is **versioned for backward/forward compatibility**, because a peer hub may run an older or newer baseline. (The exact envelope schema + the discovery/announce protocol are settled in [mesh_envelopes.md](../design/architecture/mesh_envelopes.md) + [mesh_discovery.md](../design/architecture/mesh_discovery.md).)

---

## The status console

Each cluster's React status console shows, at a glance:

- Every **service/node** in this hub and its health (ready / live / degraded), plus the baseline version it runs.
- A **unified view of every peer hub discovered over the mesh** - its health and reachability as this hub last heard them, read from this hub's own registry rather than pulled from the peer (locked #61). Detail lives on that hub's own console, reached by the redirect.
- A **redirect** on each discovered hub ("go to this hub"), navigating the operator to that hub's own console to work there (authenticating against that hub's own Keycloak).

---

## How to use this example

- **Naming things in docs/examples:** prefer these names (hub, `orders`, `inventory`, `ClusterAnnouncement`, mesh, unified view, redirect) over abstract `foo`/`bar`.
- **The first sample services** (built in later sessions) should implement a thin slice of this - almost certainly starting with `orders` + `inventory` in a single cluster, then mesh discovery + the unified view + redirect across two clusters, then richer per-hub detail.
- **It is illustrative, not binding.** If a real design session wants a different slice or names, that decision wins; update this doc when it does.
