# The Lattice Helm chart

One release installs **one baseline**: its own identity provider, its own datastore, and its services. Two baselines are two releases, usually in two clusters - never one release with a list of baselines. Baselines are independent by design (locked #44), and a chart that deployed several would give them a shared lifecycle: one upgrade, one rollback, one blast radius.

Helm rather than kustomize is locked decision **#54**.

```
deploy/k8s/
├── chart/            this chart - the source of truth for a baseline's manifests
│   ├── files/        content templated INTO manifests (the Keycloak realm)
│   └── templates/
└── generated/        rendered output, if ever committed. Never hand-edited (CLAUDE.md).
```

## Install

```bash
helm upgrade --install hub-local deploy/k8s/chart --namespace lattice --create-namespace
```

For a local `kind` cluster, images are built and side-loaded rather than pulled:

```bash
./mvnw package
for s in orders inventory mesh-gateway; do
  docker build -t "lattice/$s:0.1.0-SNAPSHOT" "services/$s"
  kind load docker-image "lattice/$s:0.1.0-SNAPSHOT" --name lattice
done
helm upgrade --install hub-local deploy/k8s/chart --namespace lattice --create-namespace --set image.pullPolicy=Never --wait
```

`image.pullPolicy` governs **Lattice-built images only**. Keycloak and Elasticsearch keep their own policy, because they come from a public registry and are never side-loaded - one shared policy would leave Keycloak stuck in `ErrImageNeverPull` the moment anyone did the normal thing for kind.

## The realm

`templates/keycloak-realm-configmap.yaml` builds the realm ConfigMap from `files/lattice-realm.json` with `.Files.Glob ... .AsConfig`. There is **one** realm definition in the repository and the manifest cannot drift from it. Do not hand-copy the JSON into a manifest - a stale copy is still valid YAML, so it fails silently.

**The realm file lives in the chart, and docker-compose mounts it from here** (`../k8s/chart/files/lattice-realm.json`). That direction is deliberate: Helm can only read files beneath the chart directory, so a realm kept elsewhere would make the chart unpackageable - `helm package` would produce a tarball that installs an empty realm.

**Import runs only when the realm is absent.** Keycloak imports on first start and skips thereafter, so once a baseline has a persistent database this file stops being the source of truth for anything already imported, and later edits are silently ignored. Changing an imported realm is an admin operation, not a redeploy. This is why local Keycloak deliberately runs with no database.

## Adding a service

A list entry in `values.yaml`, not a new template:

```yaml
services:
  - name: shipping
    replicas: 1
    needsElasticsearch: true
```

`templates/services.yaml` ranges over that list. Three near-identical templates was the alternative and is the wrong one - they drift the moment one gains a probe the others lack, which is the same defect as a bespoke second component.

## Not in this chart yet

**The Artemis broker workload.** Its configuration (`broker.xml`, the federation and connector files, the JAAS files) lives in `deploy/docker/artemis/`, and a chart cannot read files outside itself - so that config has to move in here before the workload can be templated, exactly as the realm file did. Until then `artemis.enabled` is `false` and a deployed baseline has no broker, so it serves its own data but does not join the mesh.

**The broker's TLS Secret is deliberately not templated.** It holds a private key, and the chart only ever *names* it (`artemis.tls.secretName`). Templating it would mean either committing key material or shipping an empty-Secret placeholder - and a placeholder that deploys is the exact bug this ticket removed from the realm. Create it from an untracked source:

```bash
deploy/docker/artemis/tls/issue-certs.sh
kubectl create secret generic artemis-tls --namespace lattice \
  --from-file=keystore.p12=deploy/docker/artemis/tls/<baseline>/keystore.p12 \
  --from-file=truststore.p12=deploy/docker/artemis/tls/truststore.p12 \
  --from-file=crl.pem=deploy/docker/artemis/tls/ca/crl.pem
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
