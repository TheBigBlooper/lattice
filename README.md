# Lattice

A Java 21 / Vert.x 5 microservice platform. Each service runs in a Docker container; all the services in a cluster live in one Kubernetes cluster - that collection is the versioned **baseline** (versioned services and versioned REST endpoints). Separate clusters discover and communicate with peer clusters over an **Artemis-backed mesh**. Each cluster keeps its own, possibly divergent, **Elasticsearch** data model, and clusters must stay interoperable. A **React status console** ships as its own container in each cluster to show the status of every node.

## Start here

- **[CLAUDE.md](CLAUDE.md)** - how this repo is worked (session protocol, TDD, branch safety, writing style). Read first.
- **[docs/README.md](docs/README.md)** - the documentation hub: protocols, skills, agents, reference, and design.
- **[docs/protocol/session_protocol.md](docs/protocol/session_protocol.md)** - the session lifecycle and enforcement rules.
- **[docs/reference/locked_decisions.md](docs/reference/locked_decisions.md)** - the canonical, append-only decision registry.

## Layout (planned - built in later sessions)

```
services/<name>/            each Vert.x microservice = a Maven module + Dockerfile
platform/lattice-contract/  OpenAPI specs + Artemis mesh envelope records (the versioned contract)
platform/lattice-common/    BaseVerticle, config, health/readiness, Elasticsearch + mesh clients
ui/status-console/          React (Vite + TypeScript) status console, its own container
deploy/docker/              base images + local docker-compose
deploy/k8s/                 Kubernetes manifests / Helm charts
docs/                       protocols, reference, design, changelog
```

Build tool: **Maven** multi-module (`./mvnw verify`). Namespace: `io.lattice`.

This repo runs on a disciplined Claude operating model - session protocol, deliverable-first, test-first development, branch safety, and a design-first workflow - encoded in [CLAUDE.md](CLAUDE.md), `.claude/`, and `docs/`.
