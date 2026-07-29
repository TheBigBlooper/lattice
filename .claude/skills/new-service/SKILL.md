---
name: new-service
description: Scaffold a new Vert.x microservice - a Maven module under services/, a BaseVerticle subclass, Dockerfile, K8s manifest stub, baseline/mesh registration, and a failing smoke test first.
argument-hint: "<service-name>"
---

Use to stand up a **brand-new Vert.x microservice** in the Lattice baseline. Canonical rules: [service_protocol.md](../../../docs/protocol/service_protocol.md) for the verticle + data layer; [platform_protocol.md](../../../docs/protocol/platform_protocol.md) for the Docker image, the K8s manifests, and mesh registration. Shared conventions (folder structure, Java style, commits, TDD loop): [core_protocol.md](../../../docs/protocol/core_protocol.md).

> A new service is always a **Tracked ticket** - it adds a versioned service to the baseline, a new container, and a mesh participant. Before any code: have (or create via [`/new-issue`](../new-issue/SKILL.md)) the issue, then work on a `lat-<issue>-<slug>` feature branch off `dev`. One session = one branch = one PR; founder merges to `dev`. Never touch `main`.

Name the service `<name>` (lowercase, hyphen-free artifact id; the Maven `artifactId` is `<name>`, the Java package is `io.lattice.<name>`).

## Steps (in order)

1. **Create the Maven module** at `services/<name>/`:
   - `services/<name>/pom.xml` **inheriting the parent** aggregator POM (`<parent>` = the root `io.lattice:lattice`), `artifactId` `<name>`, packaging `jar`.
   - Dependencies on **`lattice-common`** (BaseVerticle, config loader, health/readiness, Elasticsearch client) and **`lattice-contract`** (the OpenAPI operations + mesh envelopes). Add the `vertx-junit5` + Testcontainers test deps (scope `test`).
   - Source layout: `src/main/java/io/lattice/<name>/`, `src/test/java/io/lattice/<name>/`, `src/main/resources/`.
2. **Failing smoke test first** (the TDD red): in `src/test/java/io/lattice/<name>/`, a **vertx-junit5** test that **deploys the verticle** and asserts **`GET /health` returns ok** (readiness likewise), driven with a `WebClient` on the test-assigned port. Run it red - the verticle does not exist yet.
3. **The main verticle** `io.lattice.<name>.<Name>Verticle` **extends `BaseVerticle`** (from `lattice-common`), which wires the config loader + the HTTP server. Expose **`/health` (liveness)** and **`/readiness`** via the shared health handler; mount the OpenAPI-driven router for `/api/v1` (routes come later via [`/new-endpoint`](../new-endpoint/SKILL.md)). Keep the verticle thin - logic lives in service classes.
4. **Register the service** in two places:
   - **Parent aggregator POM:** add `<module>services/<name></module>` so `./mvnw verify` builds it.
   - **Baseline / mesh (stub):** register the service in the baseline manifest and add a **stub mesh registration** (the service announces itself to the Artemis discovery client from `lattice-common`) - wire the hook, mark any not-yet-final broker/topic value as a clearly-labelled `TBD` per [platform_protocol.md](../../../docs/protocol/platform_protocol.md).
5. **Container + K8s stubs** (platform surface):
   - `services/<name>/Dockerfile` - build the module and run the fat jar (base image per [platform_protocol.md](../../../docs/protocol/platform_protocol.md); do not hand-edit generated image output).
   - `deploy/k8s/<name>/` - a **Deployment** (the container, health/readiness probes pointing at `/health` + `/readiness`) and a **Service** manifest **stub**. Never hand-edit generated K8s output - regenerate it from source instead.
6. **Implement to green.** Run **`./mvnw verify`** (the new module's smoke test included) locally. Add Javadoc to the verticle + exported methods.

## Checks
- [ ] Tracked issue + `lat-<issue>-<slug>` branch off `dev` (not `main`); one branch/one PR
- [ ] `services/<name>/pom.xml` inherits the parent; deps on `lattice-common` + `lattice-contract`
- [ ] failing vertx-junit5 smoke test (verticle deploys, `/health` ok) shown **before** the verticle
- [ ] verticle extends `BaseVerticle`, exposes health + readiness, router mounted for `/api/v1`
- [ ] module added to the parent aggregator POM; baseline + (stub) mesh registration in place
- [ ] `Dockerfile` + `deploy/k8s/<name>/` Deployment + Service stubs added (generated output not hand-edited)
- [ ] Javadoc present; `./mvnw verify` green
