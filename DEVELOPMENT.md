# Lattice - Development Setup

How to stand up a local machine to build and run Lattice. Follow this once per machine; the committed wrapper `./mvnw` is always preferred over any system Maven.

> Versions below are **pinned to what this project was verified against** (captured 2026-07-22 on Windows 11). Treat the "Required" column as the floor; the "Verified" column is a known-good exact version. Bump these here in one place when the toolchain moves.

---

## Prerequisites

| Tool              | Required        | Verified (2026-07-22)         | Purpose                                                                   |
|-------------------|-----------------|-------------------------------|---------------------------------------------------------------------------|
| JDK (Java)        | 21.x            | `21.0.10`                     | Compile and run every Vert.x service                                      |
| Docker Engine     | 24+             | `29.5.2`                      | Build service images; back `kind` and Testcontainers                      |
| kubectl           | 1.3x            | `v1.34.1` (client)            | Talk to the local and remote Kubernetes clusters                          |
| kind              | latest          | not yet installed - see below | Local Kubernetes-in-Docker cluster, created **on demand** (locked #27)    |
| Node.js           | 24 LTS          | `v24.16.0`                    | Build and run the React status console                                    |
| pnpm              | 11.x            | `11.5.1`                      | Status-console package management (pinned as `packageManager`)            |
| git               | 2.4x            | `2.53.0`                      | Version control                                                           |
| GitHub CLI (`gh`) | 2.x             | `2.93.0`                      | Issues, PRs, and the session workflow                                     |

Maven itself is **not** a prerequisite: the repo ships the Maven wrapper (`mvnw` / `mvnw.cmd`), which pins the Maven version (3.9.16) per checkout and downloads it on first run.

---

## Setup

Install commands use **winget** (built into Windows 11). Substitute Homebrew / apt on macOS / Linux; the versions are the same.

### Java 21

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

Verify:

```bash
java -version
javac -version
```

Both must report `21.x`. Set `JAVA_HOME` to the JDK 21 install and put its `bin` on `PATH`.

> **Caveat on this machine:** `JAVA_HOME` currently points at Android Studio's bundled JDK (`...\Android Studio\jbr`). It is a valid JDK 21 and builds work, but point `JAVA_HOME` at a standalone Temurin 21 install so the toolchain is not coupled to an IDE that may update independently.

### Docker

Install Docker Desktop, which provides the engine every other runtime here sits on - `kind`'s nodes, the service images, and Testcontainers:

```powershell
winget install Docker.DockerDesktop
```

Start Docker Desktop and confirm the daemon is up:

```bash
docker --version
docker info
```

> `docker info` failing with "the daemon is not running" means Docker Desktop is not started - launch it before `kind`, the local stack, or any Testcontainers integration test.

### kubectl

```powershell
winget install Kubernetes.kubectl
```

```bash
kubectl version --client
```

### kind (local Kubernetes)

Lattice standardizes on **kind** for local Kubernetes (locked decision #27). Install the binary here - but **do not create a cluster**. Installing is one-time setup; creating the cluster is not, and it is only needed for the occasional Kubernetes work described in [Local Kubernetes (on demand)](#local-kubernetes-on-demand).

```powershell
winget install Kubernetes.kind
```

Open a fresh terminal after installing so `kind` is on `PATH`, then confirm it is found:

```bash
kind --version
```

### Headlamp (the local cluster viewer)

**Docker Desktop cannot show you what is running in a kind cluster, and it never will.** A kind cluster *is* a Docker container, so Docker Desktop lists the node - `hub-central-control-plane` and its siblings - but every pod inside runs under containerd, which the Docker daemon does not own and cannot see. Looking for your services there and finding nothing is expected, not a fault.

**Headlamp** is the viewer this project standardizes on: an Apache-2.0 CNCF project, a real desktop application, and it reads your existing kubeconfig - so all three baseline clusters appear with no configuration.

```powershell
winget install Headlamp.Headlamp
```

Open it and each `kind-hub-*` context is listed; pick one to browse its pods, logs, and events. Headlamp was chosen over Lens because Lens now carries subscription terms for commercial use, and a licence question is a poor thing to inherit in a tool this incidental.

Two alternatives, if a desktop app is not what you want:

- **`k9s`** (`winget install Derailed.k9s`) - a terminal UI, faster for day-to-day work, `:ctx` to switch clusters.
- **The VS Code Kubernetes extension** - zero extra install if you already run VS Code; contexts appear in the sidebar.

For a quick text answer across all three clusters at once, without opening anything:

```bash
./deploy/k8s/mesh-clusters.sh pods
```

### Node.js 24 LTS and pnpm

```powershell
winget install OpenJS.NodeJS.LTS
```

pnpm is the console's package manager, and its version is pinned in `ui/status-console/package.json` as `packageManager`. Enable Corepack once and Node supplies exactly that version whenever you run `pnpm` inside the console, rather than whatever a global install has drifted to:

```bash
node --version
corepack enable
cd ui/status-console && pnpm --version
```

### git and GitHub CLI

```powershell
winget install Git.Git GitHub.cli
```

Authenticate `gh` once:

```bash
gh auth login
```

---

## Verify the whole toolchain

Run this after installing everything. Every line should print a version, and Docker should report a running daemon.

```bash
java -version
docker --version && docker info --format '{{.ServerVersion}}'
kubectl version --client
kind --version
node --version && (cd ui/status-console && pnpm --version)
git --version
gh --version
```

`kubectl` reporting no cluster context is the **normal** state, not an unfinished setup step - the local cluster is created on demand, then deleted again. See [Local Kubernetes (on demand)](#local-kubernetes-on-demand).

---

## Building

The build is a Maven multi-module project driven by the committed wrapper, so **no system Maven is required** - `./mvnw` downloads and pins the Maven version (3.9.16) on first run.

```bash
git clone <repo-url> lattice
cd lattice
./mvnw verify
```

`./mvnw verify` is the canonical build/test gate - it compiles every module and runs the unit and Testcontainers integration tests, so the **Docker daemon must be running** for the integration suites. Expect `BUILD SUCCESS` across the reactor. On Windows use `mvnw.cmd`; on macOS / Linux use `./mvnw`.

The reactor is the parent aggregator, `platform/lattice-contract`, `platform/lattice-common`, and the three services. It does **not** cover the status console, which is not a Maven module and carries its own gate set - see [Local CI gate](#local-ci-gate-required-one-time-setup) below. The reconstruction order, and what has to exist before what, is [docs/tour/build_it_yourself.md](docs/tour/build_it_yourself.md).

### Local CI gate (required one-time setup)

The repository is public, so GitHub Actions runs free on standard runners: CI is the **authoritative** full-reactor run and fires on every push to a `lat-*` branch as well as on both merge points. The committed **scope-aware** pre-push hook is the fast local gate in front of it - it runs the Maven reactor when Java or build config changed, the status console's own verify when console code changed, and nothing at all for a docs-only push. When it cannot tell what changed, it runs everything.

It runs `./mvnw verify -DskipITs`, skipping the Testcontainers suites: measured warm, they are 167s of a 222s run, while every static gate together costs 28s. So a Java push waits about 55 seconds rather than nearly four minutes, and CI re-runs the suites in full on the same commit. Point git at the tracked hooks directory once per clone:

```bash
git config core.hooksPath .githooks
```

After that, `git push` runs the gates the pushed paths can affect and **aborts the push if any fails**. A docs-only push runs nothing and says so. Because the Maven run skips the container suites, the Docker daemon is **not** needed for the hook - only for a full local `./mvnw verify`. Emergency bypass is `git push --no-verify` - use it sparingly, since it skips the gate.

`verify` includes the quality gates - Spotless (formatting), Checkstyle (style + the em-dash ban), JaCoCo (line 90% / branch 80% coverage), maven-enforcer, SpotBugs, and PMD (unused private fields + methods). If Spotless fails on formatting, fix it with:

```bash
./mvnw spotless:apply
```

A console change runs the console's own `verify` instead (Biome, types, the comment/TSDoc/token checks, knip, Vitest with coverage). It **fails** rather than skips when `ui/status-console/node_modules` is absent, so run `pnpm install` there once per clone.

The supply-chain scan (OSV-Scanner) is CI-only, running as its own job on every CI run, so it does not slow the local push. It is not a Maven plugin, so there is nothing to run locally for it.

## Running

There are **two local paths**, and most day-to-day work uses only the first:

| Path                        | What it covers                                                                             | Local cluster? |
|-----------------------------|--------------------------------------------------------------------------------------------|----------------|
| **The build** (the default) | Everyday authoring: `./mvnw verify`, the console's own `verify`, the Testcontainers suites | No             |
| **The three-cluster stack** | Running the system, founder QA, and every mesh scenario                                    | Yes            |

- **Local stack** - three `kind` clusters, one baseline each, installed with the same Helm chart a customer receives. Issue the broker certificates once (`./deploy/certs/issue-certs.sh`), then `./deploy/k8s/mesh-clusters.sh up`, `images`, `deploy`, `seed`. Full QA walkthrough in [docs/protocol/qa_protocol.md](docs/protocol/qa_protocol.md). docker-compose is retired (locked #77) - this is the only local stack.
- **Status console** - `pnpm install` then `pnpm dev` inside `ui/status-console` for the fast UI loop; a console change is not QA-ready until its image is rebuilt per baseline, because each bakes its API addresses in at build time.

Environment variables each service reads are documented in [docs/reference/integrations.md](docs/reference/integrations.md) and declared in the Helm chart (`deploy/k8s/chart`), in the values of the component that reads them.

---

## Local Kubernetes (on demand)

Create the clusters **only when you are actually running the system** - founder QA, mesh work, or anything observable in a running baseline. The build, the test suites, and the console's dev server all run without them, and nothing in this repo creates them for you.

Treat the clusters as disposable: the resting state of a machine is **no cluster** rather than three left running in the background. kind runs each cluster inside Docker, so the Docker daemon must be up first.

For Lattice, use the script rather than `kind` directly - it pins the host-port mapping the committed Keycloak realm requires, and a cluster created without it serves a console Keycloak refuses to redirect to:

```bash
./deploy/k8s/mesh-clusters.sh up
./deploy/k8s/mesh-clusters.sh status
```

`status` should show all three nodes on the shared bridge, each resolving the others. Those containers are **kind's own Kubernetes nodes, not Lattice services** - each runs the Kubernetes control plane (the API server, etcd, the scheduler, and the controller manager) that Lattice pods are then scheduled onto. They are not defined anywhere under `deploy/`, and they carry no Lattice code.

Docker Desktop will show you those three node containers and nothing else, because the pods run under containerd **inside** them. `mesh-clusters.sh pods` is the equivalent view.

Delete them as soon as you are done, so they are not left holding memory:

```bash
./deploy/k8s/mesh-clusters.sh down
```
