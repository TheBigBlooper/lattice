# The Lattice Helm chart

One release installs **one baseline**: its own identity provider, its own datastore, and its services. Two baselines are two releases, usually in two clusters - never one release with a list of baselines. Baselines are independent by design (locked #44), and a chart that deployed several would give them a shared lifecycle: one upgrade, one rollback, one blast radius.

Helm rather than kustomize is locked decision **#54**.

```
deploy/k8s/
├── chart/            this chart - the source of truth for a baseline's manifests
│   ├── files/        content templated INTO manifests (the realm, the shared broker config)
│   └── templates/
└── generated/        rendered output, if ever committed. Never hand-edited (CLAUDE.md).
```

## Install

```bash
helm upgrade --install hub-central deploy/k8s/chart --namespace lattice --create-namespace
```

For a local `kind` cluster, images are built and side-loaded rather than pulled:

```bash
./mvnw package
for s in orders inventory mesh-gateway; do
  docker build -t "lattice/$s:0.1.0-SNAPSHOT" "services/$s"
  kind load docker-image "lattice/$s:0.1.0-SNAPSHOT" --name lattice
done
helm upgrade --install hub-central deploy/k8s/chart --namespace lattice --create-namespace --set image.pullPolicy=Never --wait
```

`image.pullPolicy` governs **Lattice-built images only**. Keycloak and Elasticsearch keep their own policy, because they come from a public registry and are never side-loaded - one shared policy would leave Keycloak stuck in `ErrImageNeverPull` the moment anyone did the normal thing for kind.

## The realm

`templates/keycloak-realm-configmap.yaml` builds the realm ConfigMap from `files/lattice-realm.json` with `.Files.Glob ... .AsConfig`. There is **one** realm definition in the repository and the manifest cannot drift from it. Do not hand-copy the JSON into a manifest - a stale copy is still valid YAML, so it fails silently.

**The realm file lives in the chart, and docker-compose mounts it from here** (`../k8s/chart/files/lattice-realm.json`). That direction is deliberate: Helm can only read files beneath the chart directory, so a realm kept elsewhere would make the chart unpackageable - `helm package` would produce a tarball that installs an empty realm.

**Import runs only when the realm is absent.** Keycloak imports on first start and skips thereafter, so once a baseline has a persistent database this file stops being the source of truth for anything already imported, and later edits are silently ignored. Changing an imported realm is an admin operation, not a redeploy. This is why local Keycloak deliberately runs with no database.

## Keycloak persistence

`keycloak.devMode` is the switch, and it changes more than one thing:

| | `devMode: true` (default, local) | `devMode: false` (deployed) |
|---|---|---|
| Command | `start-dev` | `start` |
| Store | in memory - every user, session and admin edit is lost on restart | its own MySQL (locked #72) |
| Realm checksum annotation | present, so a realm edit rolls the pod | **absent**, deliberately - see below |
| Plain HTTP | on by default | `KC_HTTP_ENABLED=true`, set by the chart |

Keycloak cannot use this baseline's Elasticsearch - it supports relational databases and nothing else - so a persisted baseline runs one alongside it. It holds identity data only: **no Lattice service connects to it**, and `lattice.commonEnv` does not name it.

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

- **Shared** (`broker.xml`, `bootstrap.xml`, `login.config`, the JAAS properties files) lives in `files/artemis/` and is identical in every baseline. **docker-compose mounts it from here too**, the same single-source inversion the realm file made - a second copy is drift waiting to happen.
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

**Wiring two deployed baselines together is not finished.** They live in different clusters, so peer connectors need routable external addresses and the mesh Service needs to be reachable across them - which is the open hosting question (**P7**). What the chart does today is make a baseline's broker deployable and mesh-*capable*; the local three-baseline mesh in `deploy/docker` remains where the topology is actually exercised.

### Secrets it names but never carries

```bash
kubectl create secret generic artemis-credentials --namespace lattice \
  --from-literal=username=artemis --from-literal=password=<password>
```

plus `artemis-tls` below. **The broker's TLS Secret is deliberately not templated.** It holds a private key, and the chart only ever *names* it (`artemis.tls.secretName`). Templating it would mean either committing key material or shipping an empty-Secret placeholder - and a placeholder that deploys is the exact bug this ticket removed from the realm. Create it from an untracked source:

```bash
deploy/docker/artemis/tls/issue-certs.sh
kubectl create secret generic artemis-tls --namespace lattice \
  --from-file=keystore.p12=deploy/docker/artemis/tls/<baseline>/keystore.p12 \
  --from-file=truststore.p12=deploy/docker/artemis/tls/truststore.p12 \
  --from-file=crl.pem=deploy/docker/artemis/tls/ca/crl.pem \
  --from-literal=password=<the LATTICE_TLS_PASSWORD>
```

Mounted at `/var/lib/artemis-instance/tls`, read-only, with `LATTICE_TLS_PASSWORD` from a Secret. That path is load-bearing and must match `broker.xml`: the connector a joiner hands its peers is interpreted **on the peer**, so a path that differed between compose and Kubernetes would break federation in one of them only.

**Rotation**, before the 825-day leaf expiry - re-issuance against the same authority, so it costs one baseline a restart and its peers nothing:

```bash
deploy/docker/artemis/tls/issue-certs.sh rotate <baseline>
kubectl create secret generic artemis-tls ... --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart statefulset/artemis --namespace lattice
```

Rotating the **authority** is the expensive case and deliberately not a one-liner: it invalidates every certificate at once, so it needs both authorities trusted during a transition, then every baseline re-issued, then the old one removed. Plan it; do not discover it the week it expires.

**Revocation** - `issue-certs.sh revoke <baseline>`, then update the Secret's `crl.pem` and restart the **peers'** brokers so their acceptors re-read the list. The revoked baseline is not touched; it simply stops being accepted anywhere.

## Ingress and hostnames

Not templated yet - `baseline.consoleUrl` and `baseline.apiBaseUrl` are the addresses **peers and operators** use, and in kind they are port-forwards. They become ingress hostnames when the registry and hosting decision lands (P7).
