import type { PanelHelpContent } from "./PanelHelp.tsx";

/**
 * What each panel says about itself.
 *
 * <p><b>The copy lives together rather than beside each panel.</b> These read as one voice or they
 * read as five people describing five screens, and the third answer in particular has to be
 * consistent about what "deliberately" means - it is the console's considered exclusions, not its
 * unfinished edges.
 *
 * <p>Each exclusion below is a decision recorded in `docs/`, restated here in the terms an operator
 * needs rather than the terms the decision was argued in. That is the point of the section: a rule
 * nobody can discover in the product is indistinguishable from a bug at the moment it matters.
 */
export const PANEL_HELP = {
  baseline: {
    shows:
      "This baseline's own verdict, and the readiness of every service behind it. The verdict is one word - ready, degraded or down - and the list beneath says which service accounts for it.",
    source:
      "The mesh-gateway on this baseline, which rolls up the services it is configured to poll. The console renders that verdict rather than computing its own.",
    omits:
      "Infrastructure. Elasticsearch, the broker and the identity provider have their own panel, and are kept out of this verdict so a datastore that has merely lost a replica cannot make a peer believe this baseline cannot serve.",
  },
  infrastructure: {
    shows:
      "The non-Lattice components this baseline depends on, each with a coarse state and, where the component sends one, its own reading in its own words.",
    source:
      "The mesh-gateway probes each configured component directly. The detail line is passed through untouched, so it says what that system says.",
    omits:
      "Anything about a peer's infrastructure. This report never travels the mesh, and no peer's is read here - a baseline reports its own components on its own API only.",
  },
  mesh: {
    shows:
      "Every baseline this one has heard announce itself: the health each reported, its region and version, and how long ago it was last heard.",
    source:
      "This baseline's own peer registry, polled from its gateway. Your browser never contacts a peer.",
    omits:
      "A peer's live state. Each row is what that baseline last announced, which is why one that has gone quiet keeps its last-known health rather than disappearing. Acting on a peer means opening its own console.",
  },
  activity: {
    shows:
      "What changed since this page was opened, newest first, with both halves of the system in one timeline - this baseline's own services and components, and the mesh around it.",
    source:
      "Comparing each poll against the one before it. Nothing is stored: it is what this tab has observed.",
    omits:
      "Anything from before this page was opened, and anything another tab has seen. It is a session record rather than a log, so an empty panel means nothing has changed while you watched - not that nothing has happened.",
  },
  orders: {
    shows: "Orders held by this baseline, newest first, with the status each currently carries.",
    source:
      "This baseline's orders service, read directly. Orders are owned by the baseline that took them.",
    omits:
      "Every other baseline's orders. There is no mesh-wide order list and deliberately so - each baseline owns its own data, and a peer's orders are read on that peer's console.",
  },
  inventory: {
    shows:
      "Stock on this baseline by SKU: what is on hand, what is held against orders, and what remains available.",
    source:
      "This baseline's inventory service. Available is computed by that service from the other two rather than stored.",
    omits:
      "Stock anywhere else. A SKU held on a peer is that peer's to report, and nothing here aggregates across the mesh.",
  },
  newOrder: {
    shows:
      "The form for placing an order on this baseline, and the record it returns. A created order stays on screen until it is dismissed, because its server-generated id is your only handle on it.",
    source:
      "Nothing until you submit. The order is written to this baseline's orders service, which assigns the id and the initial status.",
    omits:
      "Any way to place an order elsewhere. An order belongs to the baseline that takes it, so ordering on a peer means opening that peer's console.",
  },
  metrics: {
    shows:
      "Every measurement this baseline's services publish about themselves, as a searchable list. The cards above are six of these; this is the rest.",
    source:
      "Each service on this baseline, read directly and merged. The service column says which one reported a given series.",
    omits:
      "Everything the runtime and the toolkit measure about themselves - memory, threads, connection pools, per-request timings. Those are carried by the scrape endpoint a collector reads, and were left out here because they were four fifths of the payload and nothing on this screen reads them.",
  },
  meshLink: {
    shows: "Whether this baseline is connected to its own message broker right now.",
    source: "The gateway's own connection, reported rather than probed a second time.",
    omits:
      "Whether peers can reach their brokers. A report about a broken link cannot travel over that link, so each baseline answers this for itself only.",
  },
  peersReachable: {
    shows:
      "How many discovered baselines are still announcing, against how many this one has ever heard from.",
    source: "This baseline's own peer registry.",
    omits:
      "Baselines that have never announced. Discovery is decentralised, so a baseline nobody has heard from is indistinguishable from one that does not exist.",
  },
  announces: {
    shows:
      "How often this baseline announces itself on the mesh, per minute, derived from how far the counter moved.",
    source:
      "The gateway's own publish counter, sampled on each poll. It counts what this baseline sent, so it is unaffected by anything happening between the brokers.",
    omits:
      "How often peers announce themselves. Those counters are in the measurement list below, one per peer, and they behave differently from this one: announcements are held durably between brokers, so a peer's count can sit still during an outage and then jump by hundreds when the link returns and the backlog drains. That is the mesh catching up rather than a fault, and it is why a peer's rate is worth reading over a window rather than between two polls. A rate of zero here means this baseline has gone quiet, which is what its peers will notice next.",
  },
  expiries: {
    shows:
      "How many times a peer has crossed its time-to-live since this page was opened, counted per peer and totalled.",
    source: "The gateway's peer registry, which ages a peer out when it stops announcing.",
    omits:
      "Whether a peer came back. A count that climbs and a peer that is currently gone are different things, which is why this sits beside the reachable count rather than replacing it.",
  },
  datastore: {
    shows: "The mean time this baseline's services wait on Elasticsearch, across every operation.",
    source: "The timers in the shared repository every service reads through.",
    omits:
      "Which operation is slow. This is one number across all of them; the series list below breaks it down by operation and index.",
  },
  failedReads: {
    shows: "Elasticsearch calls that failed, totalled across this baseline's services.",
    source: "The error counter beside those same timers.",
    omits:
      "Failures that were retried and then succeeded. A non-zero count here is calls that failed outright, not transient trouble the service recovered from.",
  },
  reserveStock: {
    shows: "The form for holding stock against an order line, and the reservation it returns.",
    source:
      "Nothing until you submit. The hold is written to this baseline's inventory service, keyed by the order and the SKU - so submitting the same pair twice holds the stock once rather than twice.",
    omits:
      "Stock held anywhere else. A reservation is against this baseline's own stock, and nothing here can hold a SKU on a peer.",
  },
} satisfies Record<string, PanelHelpContent>;
