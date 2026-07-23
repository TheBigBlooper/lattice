# Lattice - Example Domain (Regional Fulfillment Network)

This is the **illustrative use case** the Lattice services model. It exists so that docs, specs, examples, and the first sample services all reason about **one concrete, real-world scenario** instead of abstract placeholders. It is a teaching example, not a locked product decision - the exact service names, fields, and endpoints below are illustrative and get finalized per service in the design sessions (see [locked_decisions.md](locked_decisions.md), Planned group). When a protocol, skill, or design doc needs a concrete example, draw it from here.

---

## The scenario

A company runs an online store fulfilled from several **regional fulfillment hubs** (for example: West, Central, East, and an EU hub). Each hub picks, packs, and ships orders from its own local stock, using its own local carriers. A customer order is usually served by the nearest hub, but when the nearest hub cannot fill it (out of stock, out of region, oversized), the order or one of its lines is **handed off to a peer hub** that can.

This maps onto Lattice one-to-one:

| Real world | Lattice |
| --- | --- |
| One regional fulfillment hub | One **cluster** (a Kubernetes cluster) = the versioned **baseline** |
| The programs running a hub (orders, stock, shipping) | The **services** (Vert.x microservices, one Docker container each) |
| A hub's own product catalog, stock, carrier list | That cluster's own **Elasticsearch** data model (divergent per hub) |
| Two hubs coordinating a cross-region order/shipment | Clusters exchanging **envelopes over the Artemis mesh** |
| The operations wall-board for a hub | The **React status console** (its own container per cluster) |

---

## Services in a hub (the baseline)

Each cluster runs the same versioned baseline of services. Illustrative set:

- **`orders`** - accepts and tracks customer orders and their lines; owns order state (received -> allocated -> packed -> shipped -> delivered).
- **`inventory`** - stock levels per item at this hub; reserves stock against an order line; the source of truth for "can this hub fill it?".
- **`shipments`** - the outbound shipment lifecycle (label, dispatch, tracking) once an order is packed.
- **`routing`** - chooses a carrier and route for a shipment from this hub's local carrier list.
- **`mesh-gateway`** - this hub's door to the mesh: announces the cluster to peers, discovers peer hubs, and translates local order/shipment handoffs into the shared envelope and back (see Interoperability below).

(The status console and the platform-level pieces - the Artemis broker, the Elasticsearch cluster - run alongside these; they are infrastructure, not baseline business services.)

Services and their REST endpoints are **versioned** (`/api/v1/...`). A hub can run a newer baseline than a peer and still interoperate, because the mesh envelopes are versioned independently of the local REST APIs.

---

## Data model (divergent per hub, interoperable across the mesh)

Each hub keeps its own Elasticsearch indices, and they are allowed to **differ**:

- The West hub may index products by an internal SKU with clothing-specific attributes; the EU hub may index the same catalog with different fields, localized text, and EU-specific carriers.
- Analyzers, field names, and even which attributes exist can vary hub to hub.

They stay **interoperable** because cross-hub coordination never passes a hub's raw local document - it passes a **shared envelope** that both hubs agree on. Each hub's `mesh-gateway` maps between its local model and the shared envelope. A hub with extra local fields simply does not put them in the envelope; a hub that receives an envelope with a field it does not use ignores it.

---

## The mesh (Artemis) - what crosses between clusters

Peer clusters discover each other and exchange messages over the Artemis-backed mesh. Illustrative message flows:

- **Cluster announcement / discovery** - a hub coming online announces itself (its id, region, baseline version, health) so peers can add it to their status console and route handoffs to it.
- **Fulfillment handoff** - West receives an order for an item only stocked in Central; West emits a `FulfillmentHandoff` envelope; Central's `orders`/`inventory` pick it up, reserve stock, and acknowledge.
- **Shipment handoff** - a shipment that transfers between hubs mid-route emits a `ShipmentHandoff` envelope so the receiving hub takes over tracking.
- **Stock / availability signals** - hubs share coarse availability so `routing` can prefer a peer that can actually fill an order.

These envelopes live in `platform/lattice-contract` and are **versioned for backward/forward compatibility**, because a peer hub may run an older or newer envelope version. A breaking change is a new envelope version coordinated across hubs, never an in-place rewrite. (The exact envelope schema, the discovery/announce protocol, and idempotency rules are a **design-session** deliverable - see [locked_decisions.md](locked_decisions.md).)

---

## The status console

Each cluster's React status console shows, at a glance:

- Every **service/node** in this hub and its health (ready / live / degraded), plus the baseline version it runs.
- Every **peer hub discovered over the mesh** and its reachability.
- Recent **cross-hub handoffs** and their state (so an operator can see an order that is waiting on a peer).

---

## How to use this example

- **Naming things in docs/examples:** prefer these names (hub, `orders`, `inventory`, `FulfillmentHandoff`, mesh) over abstract `foo`/`bar`.
- **The first sample services** (built in later sessions) should implement a thin slice of this - almost certainly starting with `orders` + `inventory` in a single cluster, then a two-cluster `FulfillmentHandoff` over the mesh, then the status console.
- **It is illustrative, not binding.** If a real design session wants a different slice or names, that decision wins; update this doc when it does.
