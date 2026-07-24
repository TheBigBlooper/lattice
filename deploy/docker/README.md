# Local docker-compose stack

The default local environment (per [platform_protocol.md](../../docs/protocol/platform_protocol.md) and [qa_protocol.md](../../docs/protocol/qa_protocol.md)). Currently **infra-first**: Elasticsearch + Artemis. Services (orders #6, inventory #7, ...) drop into the documented **service slot** in `docker-compose.yml` as they are built.

## Bring it up

```bash
docker compose up -d          # from deploy/docker/
docker compose ps             # both should read "healthy"
docker compose down           # stop (named volumes retained)
docker compose down -v        # stop + wipe ES/Artemis data
```

## What comes up

| Piece | Image | Host port | Reach it |
|-------|-------|-----------|----------|
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:8.19.19` (locked #34) | `9200` | `curl http://localhost:9200/_cluster/health` -> `green` |
| Artemis broker | `apache/activemq-artemis:2.44.0-alpine` | `61616` (core), `8161` (console) | `http://localhost:8161/console` (user/pass `artemis`/`artemis`) |

Both declare compose **healthchecks**; a service added to the slot gates its startup on `condition: service_healthy` so it never starts against a not-ready dependency.

## Config parity

Compose reads the same config keys as `.env.example` / the future K8s ConfigMap (`ELASTICSEARCH_URL`, `ARTEMIS_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD`, `HTTP_PORT`), so "works in compose" and "works in the cluster" diverge only where a value differs, not where a key is missing. Inside the network, services address each other by compose service name (`http://elasticsearch:9200`, `tcp://artemis:61616`), not `localhost`.

## Two-cluster mesh (later)

Mesh discovery QA needs two clusters sharing a reachable broker network (two compose projects or a profile). That wiring lands with the mesh discovery work (#9).
