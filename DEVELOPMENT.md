# Lattice - Development Setup

How to stand up a local machine to build and run Lattice. Follow this once per machine; after the Maven skeleton lands the wrapper `./mvnw` is preferred over any system Maven.

> Versions below are **pinned to what this project was verified against** (captured 2026-07-22 on Windows 11). Treat the "Required" column as the floor; the "Verified" column is a known-good exact version. Bump these here in one place when the toolchain moves.

---

## Prerequisites

| Tool              | Required        | Verified (2026-07-22)         | Purpose                                                                   |
|-------------------|-----------------|-------------------------------|---------------------------------------------------------------------------|
| JDK (Java)        | 21.x            | `21.0.10`                     | Compile and run every Vert.x service                                      |
| Docker Engine     | 24+             | `29.5.2`                      | Build service images; run the local stack; back `kind` and Testcontainers |
| Docker Compose    | v2+             | `v5.1.3` (plugin)             | Local stack: Elasticsearch + Artemis + services                           |
| kubectl           | 1.3x            | `v1.34.1` (client)            | Talk to the local and remote Kubernetes clusters                          |
| kind              | latest          | not yet installed - see below | Local Kubernetes-in-Docker cluster (locked decision #27)                  |
| Node.js           | 24 LTS          | `v24.16.0`                    | Build and run the React status console                                    |
| npm               | ships with Node | `11.13.0`                     | Status-console package management                                         |
| git               | 2.4x            | `2.53.0`                      | Version control                                                           |
| GitHub CLI (`gh`) | 2.x             | `2.93.0`                      | Issues, PRs, and the session workflow                                     |

Maven itself is **not** a prerequisite: the repo ships the Maven wrapper (`mvnw` / `mvnw.cmd`), which pins the Maven version per checkout. It arrives with the build skeleton.

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

### Docker (Engine + Compose)

Install Docker Desktop, which provides the engine and the Compose plugin:

```powershell
winget install Docker.DockerDesktop
```

Start Docker Desktop and confirm the daemon is up:

```bash
docker --version
docker compose version
docker info
```

> `docker info` failing with "the daemon is not running" means Docker Desktop is not started - launch it before running the local stack, `kind`, or any Testcontainers integration test.

### kubectl

```powershell
winget install Kubernetes.kubectl
```

```bash
kubectl version --client
```

### kind (local Kubernetes)

Lattice standardizes on **kind** for local Kubernetes (locked decision #27). It runs the cluster inside Docker, so it needs the Docker daemon running.

```powershell
winget install Kubernetes.kind
```

Open a fresh terminal after installing so `kind` is on `PATH`, then confirm it is found:

```bash
kind --version
```

Create the local cluster, point kubectl at it, and confirm the node is Ready:

```bash
kind create cluster --name lattice
kubectl cluster-info --context kind-lattice
kubectl get nodes
```

`kubectl get nodes` should show one node `lattice-control-plane` in `Ready` state.

Tear it down when finished:

```bash
kind delete cluster --name lattice
```

### Node.js 24 LTS

```powershell
winget install OpenJS.NodeJS.LTS
```

```bash
node --version
npm --version
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
docker --version && docker compose version && docker info --format '{{.ServerVersion}}'
kubectl version --client
kind --version
node --version && npm --version
git --version
gh --version
```

Missing local Kubernetes cluster context is expected until you run `kind create cluster` (above).

---

## Building

The build is a Maven multi-module project driven by the committed wrapper, so **no system Maven is required** - `./mvnw` downloads and pins the Maven version (3.9.16) on first run.

```bash
git clone <repo-url> lattice
cd lattice
./mvnw verify
```

`./mvnw verify` is the canonical build/test gate - it compiles every module and runs the unit and (as they land) Testcontainers integration tests, so the **Docker daemon must be running** for the integration suites. Expect `BUILD SUCCESS` across the reactor. On Windows use `mvnw.cmd`; on macOS / Linux use `./mvnw`.

The tree today is the skeleton (parent aggregator + `platform/lattice-common` + `platform/lattice-contract`); services, the status console, and the local stack fill in over later tickets.

### Local CI gate (required one-time setup)

This is a private repo on the GitHub Free plan, so GitHub Actions is deliberately sparing (it runs only on the `dev` -> `main` promotion PR, plus manual dispatch). The **full `./mvnw verify` runs locally on every push** instead, enforced by a committed pre-push hook. Point git at the tracked hooks directory once per clone:

```bash
git config core.hooksPath .githooks
```

After that, `git push` runs the full-reactor `./mvnw verify` first and **aborts the push if it fails**. The Docker daemon must be up (integration suites). Emergency bypass is `git push --no-verify` - use it sparingly, since it skips the gate.

`verify` includes the quality gates - Spotless (formatting), Checkstyle (style + the em-dash ban), JaCoCo (line 90% / branch 80% coverage), maven-enforcer, and SpotBugs. If Spotless fails on formatting, fix it with:

```bash
./mvnw spotless:apply
```

The heavier OWASP dependency scan is CI-only (the `security-scan` profile on the `dev` -> `main` PR), so it does not slow the local push.

## Running

These land in later tickets; pointers so this file is the one place a new machine starts:

- **Local stack** - `docker compose up` from `deploy/docker` brings up Elasticsearch + Artemis + services for local run and QA.
- **Status console** - `npm install` then `npm run dev` inside `ui/status-console`.

Environment variables each service reads are documented in [docs/reference/integrations.md](docs/reference/integrations.md) and templated in `.env.example` (copy it to `.env` for a local run).
