<div align="center">

<br/>

<img src="ui/status-console/public/lattice.png" width="120" alt="lattice" />

<h1>Lattice</h1>

<p><em>Interoperable clusters, federated by contract</em></p>

<br/>

<p>
  A Java 21 / Vert.x 5 microservice platform.<br/>
  Independent clusters, each a versioned baseline, discovering peers over an Artemis mesh.
</p>
<p><sub><strong>Every cluster owns its own Elasticsearch model - and stays interoperable anyway.</strong></sub></p>

<br/>

<p><strong>TECH STACK</strong></p>
<p>
  <img src="https://img.shields.io/badge/Java_21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" />
  <img src="https://img.shields.io/badge/Vert.x_5.1-782A90?style=for-the-badge&logo=eclipsevertdotx&logoColor=white" />
  <img src="https://img.shields.io/badge/Maven_3.9_multi--module-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white" />
  <img src="https://img.shields.io/badge/OpenAPI_3.1-6BA539?style=for-the-badge&logo=openapiinitiative&logoColor=white" />
</p>
<p>
  <img src="https://img.shields.io/badge/Elasticsearch_8.19-005571?style=for-the-badge&logo=elasticsearch&logoColor=white" />
  <img src="https://img.shields.io/badge/Apache_Artemis_2.44-D22128?style=for-the-badge&logo=apache&logoColor=white" />
  <img src="https://img.shields.io/badge/Keycloak_26.4-4D4D4D?style=for-the-badge&logo=keycloak&logoColor=white" />
  <img src="https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white" />
  <img src="https://img.shields.io/badge/Kubernetes-326CE5?style=for-the-badge&logo=kubernetes&logoColor=white" />
</p>
<p>
  <img src="https://img.shields.io/badge/React_19-61DAFB?style=for-the-badge&logo=react&logoColor=black" />
  <img src="https://img.shields.io/badge/Material_UI_9-007FFF?style=for-the-badge&logo=mui&logoColor=white" />
  <img src="https://img.shields.io/badge/TypeScript_5.9-3178C6?style=for-the-badge&logo=typescript&logoColor=white" />
  <img src="https://img.shields.io/badge/Vite_8-646CFF?style=for-the-badge&logo=vite&logoColor=white" />
  <img src="https://img.shields.io/badge/Node_24-5FA04E?style=for-the-badge&logo=nodedotjs&logoColor=white" />
</p>
<p>
  <img src="https://img.shields.io/badge/JUnit_5.14-25A162?style=for-the-badge&logo=junit5&logoColor=white" />
  <img src="https://img.shields.io/badge/Testcontainers_2-291A3F?style=for-the-badge&logo=testcontainers&logoColor=white" />
  <img src="https://img.shields.io/badge/Vitest_4-6E9F18?style=for-the-badge&logo=vitest&logoColor=white" />
  <img src="https://img.shields.io/badge/SLF4J_2_+_Logback_1.5-6D28D9?style=for-the-badge" />
  <img src="https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white" />
</p>

<br/>

<p><strong>WHERE WE ARE</strong></p>
<p>
  <a href="docs/planning/roadmap.md"><img src="https://img.shields.io/badge/Build-Phase_4_%C2%B7_Multi--cluster-6D28D9?style=for-the-badge&logo=apachemaven&logoColor=white" /></a>
  &nbsp;
  <a href="docs/README.md"><img src="https://img.shields.io/badge/Docs-Index-475569?style=for-the-badge&logo=readthedocs&logoColor=white" /></a>
  &nbsp;
  <a href="docs/governance/governance.md"><img src="https://img.shields.io/badge/Governance-Ethos_%26_Phases-0E7490?style=for-the-badge&logo=internetarchive&logoColor=white" /></a>
  &nbsp;
  <a href="docs/changelog.md"><img src="https://img.shields.io/badge/Changelog-Recent-334155?style=for-the-badge&logo=git&logoColor=white" /></a>
  &nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-0BSD-16A34A?style=for-the-badge&logo=opensourceinitiative&logoColor=white" /></a>
</p>

<hr/>

</div>

<p align="center"><strong>New here?</strong> Take the <a href="docs/tour/_index.md">guided tour</a> - what Lattice is, why it is built this way, and how to run three federating baselines locally. Everything else is one hub: the <a href="docs/README.md">Docs Index</a>.</p>

<p align="center"><a href="docs/tour/_index.md">Tour</a> &nbsp;·&nbsp; <a href="DEVELOPMENT.md">Dev Setup</a> &nbsp;·&nbsp; <a href="docs/README.md">Docs Index</a> &nbsp;·&nbsp; <a href="docs/README.md#protocol">Protocol</a> &nbsp;·&nbsp; <a href="docs/README.md#skills">Skills</a> &nbsp;·&nbsp; <a href="docs/design">Design</a> &nbsp;·&nbsp; <a href="docs/planning/roadmap.md">Roadmap</a> &nbsp;·&nbsp; <a href="docs/governance/governance.md">Governance &amp; Ethos</a> &nbsp;·&nbsp; <a href="docs/changelog.md">Changelog</a></p>

---

## What it is

Each service runs in a Docker container; all the services in a cluster live in one Kubernetes cluster - that collection is the versioned **baseline** (versioned services and versioned REST endpoints). Separate clusters discover and communicate with peer clusters over an **Artemis-backed mesh**. Each cluster keeps its own, possibly divergent, **Elasticsearch** data model, and clusters must stay interoperable. A **React status console** ships as its own container in each cluster to show the status of every node.

## Start here

- **[CLAUDE.md](CLAUDE.md)** - how this repo is worked (session protocol, TDD, branch safety, writing style). Read first.
- **[docs/tour/](docs/tour/_index.md)** - the guided tour, for an engineer seeing this for the first time.
- **[docs/README.md](docs/README.md)** - the documentation hub: protocols, skills, agents, reference, and design.
- **[docs/protocol/session_protocol.md](docs/protocol/session_protocol.md)** - the session lifecycle and enforcement rules.
- **[docs/reference/locked_decisions.md](docs/reference/locked_decisions.md)** - the canonical, append-only decision registry.

## Layout

```
services/<name>/            each Vert.x microservice = a Maven module + Dockerfile
platform/lattice-contract/  OpenAPI specs + Artemis mesh envelope records (the versioned contract)
platform/lattice-common/    BaseVerticle, config, health/readiness, Elasticsearch + mesh clients
ui/status-console/          React (Vite + TypeScript) status console, its own container
deploy/certs/               issue-certs.sh - the certificate authority a customer runs
deploy/k8s/                 the Helm chart + mesh-clusters.sh, the local three-cluster stack
docs/                       protocols, reference, design, changelog
```

Build tool: **Maven** multi-module (`./mvnw verify`). Namespace: `io.lattice`.

This repo runs on a disciplined Claude operating model - session protocol, deliverable-first, test-first development, branch safety, and a design-first workflow - encoded in [CLAUDE.md](CLAUDE.md), `.claude/`, and `docs/`.

## License

[0BSD](LICENSE). Use it for anything - commercially or otherwise, with or without modification, with no conditions at all. Not even the copyright notice has to travel with copies.
