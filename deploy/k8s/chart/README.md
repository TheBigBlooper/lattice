# The Lattice Helm chart

One release installs **one baseline**: its own identity provider, its own datastore, and its services. Two baselines are two releases, usually in two clusters - never one release with a list of baselines. Baselines are independent by design (locked #44), and a chart that deployed several would give them a shared lifecycle: one upgrade, one rollback, one blast radius.

Helm rather than kustomize is locked decision **#54**. It is an **umbrella chart** with a subchart per component (locked #77), which amends #54's "one chart" clause and leaves its "one release installs one baseline" clause exactly as above.

```
deploy/k8s/
├── chart/                    the umbrella - baseline values and the service list
│   ├── templates/            what belongs to no single component (the data jobs)
│   └── charts/
│       ├── lattice-lib/      library: fullname, labels, commonEnv, the service workload
│       ├── orders/           values.yaml + a two-line template that calls the library
│       ├── inventory/
│       ├── mesh-gateway/
│       ├── status-console/
│       ├── elasticsearch/
│       ├── artemis/          + files/ - the shared broker config
│       ├── keycloak/         + files/ - the realm template
│       └── keycloak-db/
└── generated/                rendered output, if ever committed. Never hand-edited (CLAUDE.md).
```

**Where a value goes.** A subchart only ever sees its own section plus `global`, so:

- **`global.*`** - anything crossing a component boundary: the baseline's identity, the images, the shared environment, the service list. A baseline fact set anywhere else silently renders empty.
- **`<subchart>.*`** - anything one component alone reads, in that component's own `values.yaml`, which is where someone changing it is already looking.

## Install

```bash
helm upgrade --install hub-central deploy/k8s/chart --namespace lattice --create-namespace
```

Locally, use [`mesh-clusters.sh`](../mesh-clusters.sh) rather than driving Helm by hand - it pins the host ports the realm requires and passes them to both the kind mapping and the chart:

```bash
./deploy/k8s/mesh-clusters.sh up      # three kind clusters
./deploy/k8s/mesh-clusters.sh images  # build + side-load, per baseline for the console
./deploy/k8s/mesh-clusters.sh deploy
./deploy/k8s/mesh-clusters.sh check   # render every baseline, assert no duplicate env keys
```

`global.image.pullPolicy` governs **Lattice-built images only**. Keycloak, Elasticsearch, Artemis and MySQL keep their own policy in their own subchart, because they come from a public registry and are never side-loaded - one shared policy would leave Keycloak stuck in `ErrImageNeverPull` the moment anyone did the normal thing for kind.

## Running fewer components

Every component in its own pod has an `enabled` flag, because the customer runs the clusters (locked #55) and may already run a managed Elasticsearch or database:

```bash
helm upgrade --install hub-central deploy/k8s/chart \
  --set elasticsearch.enabled=false \
  --set global.keycloak.database.deployInCluster=false \
  --set global.keycloak.database.host=mysql.internal.example
```

The three Vert.x services carry no flag: a baseline without them is not a baseline. `keycloak-db` has no flag of its own either - it is conditioned on `global.keycloak.database.deployInCluster`, so the decision to run a database and the address Keycloak is given cannot disagree.

## The realm

`charts/keycloak/templates/realm-configmap.yaml` builds the realm ConfigMap from `charts/keycloak/files/lattice-realm.json`. There is **one** realm definition in the repository and the manifest cannot drift from it. Do not hand-copy the JSON into a manifest - a stale copy is still valid YAML, so it fails silently.

**That file is a TEMPLATE, not the JSON that ships.** It is rendered with `tpl`, and its URL lists are computed: the console client's `redirectUris` and `webOrigins` come from `global.baseline.consoleUrl` plus `global.keycloak.extraConsoleOrigins`, and the docs client's come from `global.baseline.serviceUrls`. So the ports and the realm cannot disagree - which they did, and it cost a diagnosis: the file pinned every console and service port for all three baselines, nothing in the chart said so, and a console served anywhere else was refused with `Invalid parameter: redirect_uri`.

Two consequences worth knowing before you read it:

- **A reviewer reads a template rather than the shipped document**, and the file is no longer valid JSON on its own. That is the accepted cost. Roles, groups, users and client flags stay static inside it, because they are genuinely fixed and more reviewable as JSON.
- **Each baseline's realm now lists only its own addresses.** hub-east permits `localhost:3001` and its own three service ports, not all three consoles and all nine services. A redirect allow-list is a security boundary, and it is now exactly as wide as one baseline needs.

**The realm file lives inside the chart.** Helm can only read files beneath the chart directory, so a realm kept elsewhere would make the chart unpackageable - `helm package` would produce a tarball that installs an empty realm.

**Import runs only when the realm is absent.** Keycloak imports on first start and skips thereafter, so once a baseline has a persistent database this file stops being the source of truth for anything already imported, and later edits are silently ignored. Changing an imported realm is an admin operation, not a redeploy. The local stack runs persisted too (locked #72, #77), so this applies there as well: recreate the cluster to re-import.

## Keycloak persistence

`keycloak.devMode` is the switch, and it changes more than one thing:

| | `devMode: true` (default, local) | `devMode: false` (deployed) |
|---|---|---|
| Command | `start-dev` | `start` |
| Store | in memory - every user, session and admin edit is lost on restart | its own MySQL (locked #72) |
| Realm checksum annotation | present, so a realm edit rolls the pod | **absent**, deliberately - see below |
| Plain HTTP | on by default | `KC_HTTP_ENABLED=true`, set by the chart |

Keycloak cannot use this baseline's Elasticsearch - it supports relational databases and nothing else - so a persisted baseline runs one alongside it. It holds identity data only: **no Lattice service connects to it**, and `lattice.commonEnv` does not name it.

**The identity database is not reported as a component of its own** (locked #73). It has no row in `CLUSTER_INFRASTRUCTURE`; its state reaches the console through the check Keycloak already publishes about it, the same way Artemis reuses the mesh-link signal. Two things make that work, and neither is optional:

- **`KC_METRICS_ENABLED`** puts Keycloak's database check into the readiness group. Without it, `/health/ready` answers `200 UP` with the database pod deleted while every token request returns 500 - measured.
- **Keycloak's management port sits on its own Service** (`<release>-lattice-keycloak-management`) with `publishNotReadyAddresses`, because a failed readiness check removes the pod from the main Service and the probe would then time out instead of reading the 503 that names the cause. Application traffic on 8080 keeps the normal behaviour on purpose.

**The realm checksum annotation is dropped in persisted mode, and that is the point.** Against a persistent database, rolling the pod on a realm edit restarts Keycloak, skips the import, and changes nothing - reporting an ignored edit as applied. That is the same silent no-op the annotation exists to prevent, so it is not carried into the mode where it would cause one.

Either deploy the database or point at one the environment already runs:

```yaml
keycloak:
  devMode: false
  database:
    deployInCluster: true      # false to use an existing database
    host: ""                   # required when deployInCluster is false
    urlProperties: ""          # e.g. sslMode=REQUIRED against a managed database
```

`deployInCluster: false` with no `host` **fails the render** rather than installing a Keycloak that starts, cannot reach a database, and crash-loops - the same rule as never shipping a placeholder that deploys.

### The Secret it names but never carries

```bash
kubectl create secret generic keycloak-db-credentials --namespace lattice \
  --from-literal=username=keycloak \
  --from-literal=password=<password> \
  --from-literal=rootPassword=<root password>
```

Read by both MySQL (to create the user) and Keycloak (to connect as it). Verify a persisted baseline the way the deliverable was verified - the realm's signing keys must be identical across the restart, since dev mode regenerates them and invalidates every issued token:

```bash
kubectl delete pod -n <ns> -l baseline-component=identity
kubectl logs -n <ns> deploy/<release>-lattice-keycloak | grep "Import skipped"
```

## Adding a service

A list entry in `values.yaml`, not a new template:

```yaml
services:
  - name: shipping
    replicas: 1
    needsElasticsearch: true
```

`templates/services.yaml` ranges over that list. Three near-identical templates was the alternative and is the wrong one - they drift the moment one gains a probe the others lack, which is the same defect as a bespoke second component.

## The broker

Every baseline runs **its own** broker (locked #44), and the chart deploys it as a StatefulSet - the journal and paging directories are not interchangeable between pods, so a Deployment that ran two replicas would have them writing the same volume.

Its configuration is split by what it actually is:

- **Shared** (`broker.xml`, `bootstrap.xml`, `login.config`, the JAAS properties files) lives in `files/artemis/` and is identical in every baseline - one copy, read by every release, because a second copy is drift waiting to happen.
- **Peer topology** (`connectors.xml`, `federation.xml`) is *generated* from `values.artemis.peers`, because who a baseline's peers are is per environment.

Two Services, because the two acceptors have different audiences: `61616` for this baseline's own services on an internal ClusterIP, and `61617` - mutual TLS, peer brokers only - on its own mesh Service. Putting them together would expose a baseline's internal broker port to anything that could reach its mesh address.

**Naming out of the template is load-bearing, not cosmetic.** The generated federation is named `lattice-mesh-<baseline>` and every link is named for the baseline that owns it. Both were defects found with three baselines: a broker keys arriving federations by name and silently discards one whose name it already holds, and a downstream command creates its link *on the peer* under the joiner's chosen name. Generating them removes the chance of getting either wrong by hand.

### Peers

`artemis.peers` is **empty by default, and that is a complete baseline** - not an unfinished one. An existing baseline names nobody; a joiner declares both directions itself and commands each peer to open one back, so no existing broker is edited, restarted, or redeployed when a baseline appears (locked #44). Only a joiner fills it in:

```yaml
artemis:
  advertisedHost: artemis.hub-west.example   # how PEERS reach this baseline
  peers:
    - name: hub-central
      host: artemis.hub-central.example
    - name: hub-east
      host: artemis.hub-east.example
```

**Baselines in separate clusters do federate**, proven on three kind clusters with certificates carrying the external name a peer dials and a pinned NodePort exposing the federation acceptor (locked #75). What is still open is **hosting** (**P7**): the kind nodes share one Docker bridge, so no network address translation, firewall, or routable address is involved, and the exposure is a NodePort rather than the TCP load balancer a real deployment needs. `deploy/k8s/mesh-clusters.sh` is where that topology is exercised.

### Secrets it names but never carries

```bash
kubectl create secret generic artemis-credentials --namespace lattice \
  --from-literal=username=artemis --from-literal=password=<password>
```

plus `artemis-tls` below. **The broker's TLS Secret is deliberately not templated.** It holds a private key, and the chart only ever *names* it (`artemis.tls.secretName`). Templating it would mean either committing key material or shipping an empty-Secret placeholder - and a placeholder that deploys is the exact bug this ticket removed from the realm. Create it from an untracked source:

```bash
deploy/certs/issue-certs.sh
kubectl create secret generic artemis-tls --namespace lattice \
  --from-file=keystore.p12=deploy/certs/<baseline>/keystore.p12 \
  --from-file=truststore.p12=deploy/certs/truststore.p12 \
  --from-file=crl.pem=deploy/certs/ca/crl.pem \
  --from-literal=password=<the LATTICE_TLS_PASSWORD>
```

Mounted at `/var/lib/artemis-instance/tls`, read-only, with `LATTICE_TLS_PASSWORD` from a Secret. That path is load-bearing and must match `broker.xml`: the connector a joiner hands its peers is interpreted **on the peer**, so a path that differed between compose and Kubernetes would break federation in one of them only.

**Rotation**, before the 825-day leaf expiry - re-issuance against the same authority, so it costs one baseline a restart and its peers nothing:

```bash
deploy/certs/issue-certs.sh rotate <baseline>
kubectl create secret generic artemis-tls ... --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart statefulset/artemis --namespace lattice
```

Rotating the **authority** is the expensive case and deliberately not a one-liner: it invalidates every certificate at once, so it needs both authorities trusted during a transition, then every baseline re-issued, then the old one removed. Plan it; do not discover it the week it expires.

**Revocation** - `issue-certs.sh revoke <baseline>`, then update the Secret's `crl.pem` and restart the **peers'** brokers so their acceptors re-read the list. The revoked baseline is not touched; it simply stops being accepted anywhere.

## Ingress and hostnames

Not templated yet - `baseline.consoleUrl` and `baseline.apiBaseUrl` are the addresses **peers and operators** use, and in kind they are port-forwards. They become ingress hostnames when the registry and hosting decision lands (P7).
