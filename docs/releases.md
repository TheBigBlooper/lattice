# Releases

What each Lattice baseline release gives an operator or an integrator. Newest first.

## v1.0.0 - 2026-08-04

- Run several independent clusters that discover each other automatically over an Apache Artemis mesh. A new cluster joins by naming its peers, and no existing cluster is edited, restarted, or redeployed to accept it.
- Each cluster keeps its own Elasticsearch data model and stays interoperable anyway. An operator who needs to act on another cluster is redirected to the one that owns the data and works there, so no schema is shared and nothing is translated between clusters.
- Every cluster runs its own identity provider. Operators sign in against that cluster's own realm, every `/api/v1` operation requires a token it issued, and `viewer` and `operator` roles separate reading from writing. Brokers authenticate each other with per-cluster certificates from an authority you run, so one can be revoked without touching any peer.
- A status console in every cluster shows its verdict, each service and infrastructure component, the peers it has discovered with the state of the broker and federation links to each, a timeline of what changed, and live metrics. Orders and stock can be browsed and acted on from the same screen.
- A versioned REST contract: OpenAPI 3.1 under `/api/v1`, with paged listings, one response envelope for success and failure alike, and browsable documentation served by each cluster.
- Delivered as image archives plus a Helm chart, so one release installs one baseline and nothing needs to reach a registry. A three-cluster local stack reproduces the whole mesh, including seven failure scenarios that assert how it behaves when something breaks rather than only demonstrating it.
