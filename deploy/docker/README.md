# Local docker-compose stack

The default local environment (per [platform_protocol.md](../../docs/protocol/platform_protocol.md) and [qa_protocol.md](../../docs/protocol/qa_protocol.md)). Two compose projects, each a complete, independent **baseline**: `docker-compose.yml` is `hub-local` and `docker-compose.peer.yml` is `hub-east`.

Each baseline runs **its own Artemis broker** (locked #44). The two are joined by broker-to-broker **address federation**, so neither is privileged and neither can take the other's discovery down with it.

## Before the first run: issue the certificates

Brokers authenticate to each other with a **per-baseline certificate signed by a shared Lattice authority**, over mutual TLS (locked #50). Nothing starts without that material, and it is never committed - it holds private keys:

```bash
./deploy/docker/artemis/tls/issue-certs.sh
```

That creates the authority, a certificate per baseline, the shared truststore, and an empty revocation list. Run it once per machine; re-run it any time - it keeps an existing authority rather than invalidating every certificate.

## Bring it up

One baseline:

```bash
docker compose up -d          # from deploy/docker/
```

Both baselines, for the two-cluster mesh pass. Run the primary first - it creates the network the peer joins:

```bash
docker compose -f docker-compose.yml up -d
docker compose -f docker-compose.peer.yml up -d
```

Tear down (peer first, since it borrows the primary's network):

```bash
docker compose -f docker-compose.peer.yml down -v
docker compose -f docker-compose.yml down -v
```

## What comes up

| Piece | hub-local | hub-east | Reach it |
|-------|-----------|----------|----------|
| Elasticsearch | `9200` | `9201` | `curl http://localhost:9200/_cluster/health` |
| Artemis broker | `61616` core, `8161` console | `61617` core, `8162` console | `http://localhost:8161/console` (`artemis`/`artemis`) |
| orders | `8080` | `8090` | `curl http://localhost:8080/readiness` |
| inventory | `8081` | `8091` | `curl http://localhost:8081/readiness` |
| mesh-gateway | `8082` | `8092` | `curl http://localhost:8082/api/v1/peers` |

Build the fat jars first (`./mvnw package`); the images copy them in rather than compiling inside Docker.

**What gates on what.** Services gate startup on Elasticsearch being healthy, so they never start against a not-ready datastore. The **mesh-gateway gates on nothing** - not even its own broker. Locked #42 says a broker outage must not stop it serving, and it re-attempts the connection on every announce heartbeat, so it joins the mesh on its own whenever the broker appears. Gating it would contradict that and hide it.

## Broker configuration (`artemis/`)

```
artemis/
├── broker.xml                  the standard Lattice broker config - identical in every baseline
├── bootstrap.xml               names the certificate JAAS domain - identical everywhere
├── login.config                two domains: password for own services, certificate for peers
├── artemis-roles.properties    the broker roles for this baseline's own services
├── artemis-cert-users.properties  which certificates are baselines (a regex, naming no peer)
├── artemis-cert-roles.properties  what an authenticated peer may do (one generic role)
├── tls/                        the authority + per-baseline certificates (GENERATED, git-ignored)
│   └── issue-certs.sh          issues, rotates, and revokes them
├── hub-local/                  this baseline's peers: none (it names nobody)
│   ├── connectors.xml
│   └── federation.xml
└── hub-east/                   this baseline's peers: hub-local, in both directions
    ├── connectors.xml
    └── federation.xml
```

`broker.xml` pulls the two per-baseline files in with `xi:include`, so the only thing that differs between baselines is *who my peers are*. The `href` is relative to the broker **instance** directory, not to `broker.xml`, hence the `etc/` prefix.

**Only the joining baseline is configured.** `hub-east` declares both an `upstream` (so it receives `hub-local`'s announcements) and a `downstream` (which commands `hub-local` to open an upstream back). `hub-local`'s own config names no peer and is never edited - that is locked #44's no-edit-on-join guarantee, and it is what the two-baseline run actually proves.

## Broker identity (mutual TLS)

Two acceptors, because "who may connect" has two different answers:

| Port | Who | How they authenticate |
|------|-----|-----------------------|
| `61616` | this baseline's **own services** | username and password, on its own network |
| `61617` | **peer brokers** | a per-baseline certificate, mutual TLS, `needClientAuth` |

`61617` is never published to the host: it is broker-to-broker traffic, and nothing on the host holds a certificate to present to it. (Careful with the host port table above - host `61617` reaches `hub-east`'s **61616**. The two are unrelated.)

**The truststore holds the authority and nobody else.** That is what preserves no-edit-on-join: each broker was configured once to trust the authority that signs baselines, so a baseline appearing later is accepted with no edit, restart, or redeploy anywhere. `artemis-cert-users.properties` matches a **regular expression** over the certificate's distinguished name rather than listing peers, for the same reason - listing them would be edit-on-join by another route.

**Authorization stays one generic role.** A certificate answers "which baseline is this, and is it one of ours" - never "what may this one do here". Per-peer permissions would mean naming each peer in every broker's config. Deferred by design, not overlooked.

Rotate or revoke a baseline without touching any peer:

```bash
./deploy/docker/artemis/tls/issue-certs.sh rotate hub-east
./deploy/docker/artemis/tls/issue-certs.sh revoke hub-east
```

Revoking refreshes `ca/crl.pem`; peers enforce it when their acceptors next start. `./mesh-harness.sh scenario revoked-peer` exercises the whole loop, including a control that the certificate was accepted **before** it was revoked.

**Expect one warning on every join**, and it is not a fault:

```
AMQ212079: The upstream connector from the downstream federation will ignore url parameter keyStorePath
```

Artemis refuses to ship one broker's keystore paths and passwords to another, which is correct - they are local material. The link still comes up over mutual TLS, because every baseline mounts its certificate at the **same in-container path**. That sameness is load-bearing: a path that differed per baseline would break the link a joiner asks its peer to open.

**The broker instance is deliberately not persisted.** The image applies `etc-override` **only when it creates the instance**, so a named volume would silently ignore config edits until someone thought to run `down -v`. The broker holds nothing worth keeping locally: announcements are ephemeral discovery traffic.

## Config parity

Compose reads the same config keys as `.env.example` / the future K8s ConfigMap (`ELASTICSEARCH_URL`, `ARTEMIS_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD`, `HTTP_PORT`), so "works in compose" and "works in the cluster" diverge only where a value differs, not where a key is missing. Inside the network, services address each other by compose service name (`http://elasticsearch:9200`, `tcp://artemis:61616`), not `localhost`.

`ARTEMIS_URL` is read by the **mesh-gateway only** - it is the cluster's sole mesh participant (locked #42) - and always points at that baseline's **own** broker. Peer brokers are reached by federation, never by pointing a service at someone else's broker.

## Three baselines

A third baseline (`hub-west`) exists to prove the one property two cannot: loop prevention. Two brokers cannot form a loop, so `max-hops="1"` is never exercised by the thing it exists for.

```bash
./mesh-harness.sh up --three
./mesh-harness.sh loop-check
./mesh-harness.sh down --three
```

**It is heavy.** Three Elasticsearch containers (512m of heap each), three brokers, three Keycloaks, nine services and three consoles. Bring it up for the loop-prevention pass, not for day-to-day work.

**Federation names must be unique across the mesh, not just within one broker.** A downstream command is interpreted in the *peer's* namespace, so a name two baselines share collides there and the second is silently ignored - no error, and a log line saying it deployed. This bites at both levels:

- the **link** name, so links are named for the baseline that owns them (`hub-west-to-hub-local`, not `to-hub-local`);
- the **federation** name itself, so each baseline uses `lattice-mesh-<baseline>` rather than a shared `lattice-mesh`. A broker keys arriving federations by name and discards one whose name it already holds.

Both are invisible with two baselines, and are why the third exists. When adding a fourth, name everything in `artemis/<baseline>/` after that baseline. See [mesh_broker_topology.md](../../docs/design/architecture/mesh_broker_topology.md).

## Two-cluster mesh pass

Use the harness rather than the sequence by hand:

```bash
./mesh-harness.sh qa          # stands both baselines up and walks the whole mesh checklist
./mesh-harness.sh scenarios   # the failure states it can induce on demand
./mesh-harness.sh status      # what each baseline currently sees
./mesh-harness.sh down        # tear both down
```

It waits for mutual discovery **and** for the health rollups to settle before reporting, because a baseline whose services are still starting announces `down` for a few seconds - which looks like a failure and is not.

Failure cases it induces, each restored and re-verified so the self-healing is exercised rather than asserted:

| Scenario | Expected |
|------------|------------|
| `peer-lost` | `hub-east` flips to `UNREACHABLE` on `hub-local` after `PEER_TTL`, **retained** with its last-known detail; `hub-local` keeps serving its own data |
| `degraded` | the baseline missing a service announces `degraded`, and its peer sees that rollup over the mesh |
| `baseline-down` | every service stopped announces `down` while the baseline is still **heard** - unable to serve is not the same as unheard |
| `mesh-cut` | a baseline whose own broker is stopped goes quiet on the mesh and keeps serving; federation re-establishes itself on restart, including the link `hub-local` never configured |
| `revoked-peer` | a revoked certificate is refused by its peer, with **no peer configuration edited**; re-issuing restores the mesh at the joiner's own cost |

Reading any of this by hand needs a token, since every `/api/v1` operation is protected:

```bash
TOKEN=$(curl -s -d client_id=lattice-console -d username=operator -d password=operator \
  -d grant_type=password http://localhost:8083/realms/lattice/protocol/openid-connect/token \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/v1/peers
```
