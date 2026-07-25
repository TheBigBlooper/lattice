# Local docker-compose stack

The default local environment (per [platform_protocol.md](../../docs/protocol/platform_protocol.md) and [qa_protocol.md](../../docs/protocol/qa_protocol.md)). Two compose projects, each a complete, independent **baseline**: `docker-compose.yml` is `hub-local` and `docker-compose.peer.yml` is `hub-east`.

Each baseline runs **its own Artemis broker** (locked #44). The two are joined by broker-to-broker **address federation**, so neither is privileged and neither can take the other's discovery down with it.

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
├── artemis-roles.properties    the broker roles, incl. the shared federation role
├── hub-local/                  this baseline's peers: none (it names nobody)
│   ├── connectors.xml
│   └── federation.xml
└── hub-east/                   this baseline's peers: hub-local, in both directions
    ├── connectors.xml
    └── federation.xml
```

`broker.xml` pulls the two per-baseline files in with `xi:include`, so the only thing that differs between baselines is *who my peers are*. The `href` is relative to the broker **instance** directory, not to `broker.xml`, hence the `etc/` prefix.

**Only the joining baseline is configured.** `hub-east` declares both an `upstream` (so it receives `hub-local`'s announcements) and a `downstream` (which commands `hub-local` to open an upstream back). `hub-local`'s own config names no peer and is never edited - that is locked #44's no-edit-on-join guarantee, and it is what the two-baseline run actually proves.

**The broker instance is deliberately not persisted.** The image applies `etc-override` **only when it creates the instance**, so a named volume would silently ignore config edits until someone thought to run `down -v`. The broker holds nothing worth keeping locally: announcements are ephemeral discovery traffic.

## Config parity

Compose reads the same config keys as `.env.example` / the future K8s ConfigMap (`ELASTICSEARCH_URL`, `ARTEMIS_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD`, `HTTP_PORT`), so "works in compose" and "works in the cluster" diverge only where a value differs, not where a key is missing. Inside the network, services address each other by compose service name (`http://elasticsearch:9200`, `tcp://artemis:61616`), not `localhost`.

`ARTEMIS_URL` is read by the **mesh-gateway only** - it is the cluster's sole mesh participant (locked #42) - and always points at that baseline's **own** broker. Peer brokers are reached by federation, never by pointing a service at someone else's broker.

## Two-cluster mesh pass

With both baselines up, each gateway should list exactly the other:

```bash
curl -s http://localhost:8082/api/v1/peers   # hub-local sees hub-east
curl -s http://localhost:8092/api/v1/peers   # hub-east sees hub-local
```

Failure cases worth exercising (all self-heal with no restart):

| Induce it | Expected |
|-----------|----------|
| `docker compose -f docker-compose.peer.yml stop` | `hub-east` flips to `UNREACHABLE` on `hub-local` after `PEER_TTL`, **retained** with its last-known detail; `hub-local` keeps serving its own data |
| `docker compose -f docker-compose.yml restart artemis` | Federation re-establishes itself, including the link `hub-local` never configured; discovery resumes |
| `docker compose -f docker-compose.yml stop orders` | `hub-local` announces `degraded` |

Automating this sequence is [#25](https://github.com/TheBigBlooper/lattice/issues/25).
