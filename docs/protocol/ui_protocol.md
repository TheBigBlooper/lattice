# Lattice - UI Protocol

The job description for UI work, owned by the **`ui`** agent. Covers the one frontend surface:

- **Status console (`ui/status-console`)** - a **React** single-page app that renders the health of the cluster. It ships as its own Docker container in every cluster.

This document holds UI-specific rules, examples, and gotchas. Cross-cutting rules (folder structure, naming, TypeScript, env vars, commits, test-first loop, branching, docstrings) live in the shared core [core_protocol.md](core_protocol.md). The service<->console contract seam (the OpenAPI REST specs + the mesh envelope module) lives in [contract_protocol.md](contract_protocol.md). Service-side rules are in [service_protocol.md](service_protocol.md). Service/cluster QA is [qa_protocol.md](qa_protocol.md).

> Lattice's UI is a plain web app (React in the browser), not a mobile app - there is no React Native, Expo, Metro, native device layer, or over-the-air update path here. Those sections are intentionally absent.

---

## What this console shows

The status console is an operator view, not an end-user product. It renders:

- **The status of every node/service in this cluster** - each service's health/readiness (see [service_protocol.md](service_protocol.md) health contract), version + baseline tag, and live up/down/degraded state.
- **Peer clusters reachable over the mesh** - which other clusters this one has discovered and announced to over the Artemis-backed mesh, and their reachability. Mesh mechanics are owned by the `platform` agent ([platform_protocol.md](platform_protocol.md)).

Keep the surface focused on observability. It is read-mostly; any control that mutates cluster state is a deliberate, reviewed addition, not a default.

---

## Runtime primer: Vite

**Vite + TypeScript** is the build tool and dev server. `npm run dev` (or the workspace equivalent) starts the dev server on port **5173** with hot module replacement; `npm run build` produces the static bundle that the console's Docker image serves.

**When to restart what** - match the action to what changed:

| You changed                                                | Do this                                                                 |
|------------------------------------------------------------|-------------------------------------------------------------------------|
| React / TS component code                                  | Nothing - hot module replacement applies it                             |
| A `.env` / `VITE_*` var                                    | Restart the dev server (Vite inlines `import.meta.env` at server start) |
| `vite.config.ts` / `tsconfig` path aliases                 | Restart the dev server                                                  |
| The generated OpenAPI client (regenerated from a new spec) | Restart the dev server if types go stale; otherwise HMR picks it up     |
| Stale bundle / "failed to resolve import"                  | Restart with a clean cache (`vite --force`)                             |

> **Gotcha:** `VITE_*` env vars are read through `import.meta.env` and inlined at dev-server start, so a changed value does **not** hot-reload - restart the dev server. A value that resolves in dev can still be absent in the built image if it was not present at build time (see [deploy_protocol.md](deploy_protocol.md) build-parity).

---

## Navigation

- **React Router** (chosen over a file-based router - it is an explicit, code-owned route table, which suits a small operator console with a handful of views). Define routes in one place; do not scatter `<Route>` declarations. Mock the router in component tests that navigate.
- Keep the route surface small: a cluster overview, a per-service/per-node detail view, and a mesh/peer view are the expected shape. Do not add deep nested routing without a reason.

---

## Styling and theming

> These rules apply to **all** console UI work - a new panel, a refactor, or an enhancement - not just new components. The design system is unconditional: any component you touch must use tokens, and the lint gate enforces it regardless.

- **The console is built on Material UI** (locked #62). Components come from the library rather than being hand-rolled; a bespoke reimplementation of something Material already provides is a defect. Styling goes through `sx` and `styled` - Emotion is the **only** styling engine, and an inline `style` prop is a defect except where it is load-bearing and documented as such.
- **All colour, spacing, radius, and type come from the theme** (`src/theme/theme.ts`). Spacing is expressed in grid units (`p: 2`), never pixels. Light and dark are reactive, selected from the operator's system preference.
- **No hardcoded colors.** A raw hex / `rgb()` in a component is a defect - use a palette key such as `success.main`. **Machine-enforced** by `check:tokens`, which fails the build on a colour literal anywhere in the console except the theme module itself, **including inside an `sx` prop** - `sx` accepts a raw colour as readily as a palette key, which is where this drift now appears. Escape hatch: `// allow-colour-literal: <reason>`, with founder sign-off.
- **No hardcoded spacing.** Every `padding` / `margin` / `gap` uses a theme spacing unit (only `0` is a bare literal). A genuinely dynamic value escapes via a documented ignore-comment.
- Component taxonomy and the palette are **Material UI's** (locked #62), so there is no token dictionary to maintain: `src/theme/theme.ts` is the single place a colour may be written, and `check:tokens` points at it. The visual direction those components serve is canonical in [docs/design/ui/](../design/ui/_index.md). Do not redefine either here.

> **What not to do:** `style={{ color: "#3fb950" }}` for an "up" state - it breaks theming and dark mode. Do: read the semantic status color from the palette (e.g. `t.statusHealthy`).

---

## Component standards

Beyond the color/spacing rules above, `.tsx` components follow a short checklist:

- **Build styles through the theme hook**, splitting static color-free styles from theme-dependent ones; do not re-implement that split per file.
- **Tokens, never literals** for spacing, radius, icon size, control dimensions, motion durations, and z-index.
- **Emphasis via the type scale**, never an inline `fontWeight`.
- **Memoize pure presentational components** (`React.memo`) paired with stable (`useCallback`) handler props - memo does nothing while a parent passes fresh inline closures. This matters on a console that re-renders on every live status tick.
- **Extract list/table data to a sibling config**, not inline constants in the rendering file; render from one map.
- **A shared `<Section />`** (or equivalent) owns repeated panel headers, so the header style has one place to change.
- Every interactive element carries an accessibility role (see Accessibility below).

Reuse over rebuild: before creating any new component, grep for an existing one that already does the job and configure it via props. A bespoke second copy of an existing component is a defect.

---

## Source layout - features, and what earns a place in `shared/`

The console is organized **by feature**, not by file kind. A folder of every component in the app tells you nothing about which ones belong together, and it actively invites copying a row into a feature rather than reaching for the shared one - which is the reuse-over-rebuild defect above, invited by the layout instead of caught in review.

```
src/
├── app/          the shell: App, the app bar, the screen frame, the loading state
├── features/
│   ├── status/   this baseline's own verdict, services, and infrastructure
│   ├── mesh/     discovered peers
│   └── activity/ what changed - both halves, one timeline
├── shared/       what more than one feature renders
├── api/  auth/  theme/     horizontal tiers, each already single-purpose
└── main.tsx  config.ts     the entry point and its configuration
```

**What earns a place in `shared/`** - one of two tests, and nothing else:

1. **Two or more features render it today.** `StatusIcon` and `PanelHeader` qualify: status and mesh both use them.
2. **It is a primitive the next feature will certainly need.** `StatusRow` qualifies - it is rendered only by status today, and the operational views are built from rows.

Everything else **stays inside the feature that uses it**. A component used by one feature belongs to that feature no matter how generic it looks; move it out only when a second feature actually reaches for it. Promoting on the *anticipation* of reuse is how a shared tier becomes a second dumping ground.

**A feature exposes itself through its `index.ts` barrel and nothing else.** Import a feature's public surface from `features/<name>/index.ts`; never reach past a barrel into another feature's internals. What a barrel does *not* export is as deliberate as what it does - `TransitionIcon` and the mesh activity reducer are mesh's own business.

> **Barrels are safe here because they were measured, not assumed.** They are a known source of both false positives and *masked* unused exports, so `knip` was probed before the pattern was adopted: an export re-exported through a barrel and imported nowhere is reported **twice** - at the barrel and at its source. The gate therefore reads straight through a barrel, and re-exporting something no other feature imports **fails the build** rather than quietly widening the surface. If a future knip upgrade changes that, drop the barrels - do not weaken `check:deadcode`.

**The path-scanning gates need no maintenance for this.** `check:tokens`, `check:comments`, and `check:tsdoc` walk recursively from `src`, so a new feature folder is covered the moment it exists - verified by planting a violation at `features/mesh/` depth and confirming each gate fails. The one path-literal to remember is `check:tokens`'s theme exemption (`src/theme/theme.ts`, the only file permitted colour literals): **moving the theme silently un-exempts it**. `src/api/generated` is named literally in both `knip.jsonc` and `biome.jsonc` for the same reason. Those three paths are why `api/` and `theme/` stayed put.

---

## Layout

- **Flex/grid-fit by default; scroll where content can overflow.** A live cluster can have many services and peers; a fixed panel must scroll its own overflow rather than clip it. Wrap long lists/tables in a scroll container, and use a virtualized list for large node counts rather than rendering every row.
- **Responsive to the operator's window.** The console runs in a browser at unknown widths - use relative units and grid/flex; the page body must never scroll horizontally. Wide content (tables, logs) scrolls inside its own container.
- Verify the **narrowest realistic operator width** first (a half-screen browser), where a missing scroll container shows up.

---

## State management

- **Local UI state** (`useState`, `useReducer`) for panels, inputs, toggles, component-scoped state.
- **No global client state store** (Zustand, Redux, Jotai) without explicit approval. If you feel you need one, the component boundary is probably wrong - discuss first.
- Server / live-status data is **not** UI state - it lives in the data layer (below).

---

## Data layer

The console has two data sources: the request/response REST API for point-in-time reads, and a live stream for continuous node status.

- **REST reads go through the generated OpenAPI client.** The versioned OpenAPI 3.1 specs in `platform/lattice-contract` are the contract; the client is generated from them, so response types cannot drift from what a service actually returns. Do not hand-write fetch wrappers or hand-type responses. Base URL comes from config, never hardcoded.
- **Live node status is polled**, at a 10-second interval against `/api/v1/baseline`, behind one hook so the transport stays swappable. This was settled by measurement rather than preference (locked #59): staleness is dominated by the peer time-to-live, not by the poll, so a push transport would remove only the smaller quarter of the latency budget. Neither Server-Sent Events nor a WebSocket can carry the bearer token either, so both would add a credential path the bearer design avoids. Detail and the revisit triggers: [live_status_transport.md](../design/ui/live_status_transport.md).
- **A server-data cache library** (e.g. TanStack Query) is the standard way to hold REST reads - loading/error/refetch states, cache invalidation on a live event. Type every query off the generated client so fixtures cannot drift from the contract.
- **No unstable values in effect dependencies.** Never put a value re-created every render (a refetch callback, an inline object/array/closure) in a `useEffect` dependency array - it re-subscribes the effect every render, and on a live-updating console that re-runs it in a loop. Hold the value in a `useRef`, give the effect stable deps, and read `ref.current` inside. A live-subscription hook needs a test that drives the real effect across a re-render (assert callback-identity stability), not a no-op mock - a no-op mock hides exactly this bug.
- **Mock-first is safe because of the contract.** The console can iterate against a contract-satisfying mock of the API/stream; the mock -> live cutover must not touch the UI. Why this is safe and how mocks stay typed is owned by [contract_protocol.md](contract_protocol.md).

---

## Auth

The console signs in against **its own baseline's Keycloak realm** as a **public client using authorization code with PKCE** (locked #38, #48). Public because no secret can be kept in a browser, and PKCE is what makes that safe.

The console never trusts a client-sent identity for authorization - the service verifies every `/api/v1` call against that realm's signing keys, and a token issued by another baseline's realm is refused. Membership is deliberately unsynchronised across baselines (locked #49), so the same person may be `operator` here and `viewer` there: a screen shows its write controls disabled with the missing grant named rather than hiding them, because hiding leaves an operator unable to tell a missing capability from a missing grant.

Detail: [per_baseline_identity.md](../design/features/per_baseline_identity.md).

---

## Accessibility

- Every interactive element has a visible focus/pressed state. Icons without a visible label need an accessible label.
- **Color is never the sole indicator of state.** A healthy/degraded/down status must always pair its color with text or an icon - operators may be color-blind, and a red/green-only dashboard is unreadable to them. This is a hard rule on a status console.
- WCAG AA is a hard constraint (4.5:1 normal text, 3:1 large text / UI components), **with one recorded exception: status colours are held to 3:1** (locked #63). That exception exists because Material UI's light-mode `warning` measures 3.11:1 and no colour in its orange ramp clears 4.5:1, so it is a deliberate trade rather than an oversight - and it is bounded by the rule above it, since the word carries the state regardless of hue. Everything that is not a status colour still meets 4.5:1. Run an accessibility pass before handoff on a new panel.

---

## Quality gates (the console's half of the pre-push run)

The console is the first non-Java surface in the repo, so it carries its own gate set - but not its own gate *philosophy*. The local pre-push run is the primary gate here for the same reason it is on the Java side ([core_protocol.md](core_protocol.md#ci-triggers--qa-iteration-discipline)): GitHub Actions is deliberately sparing on this repo. The hook runs `./mvnw verify`, then the console's `verify` script.

| Gate | Tool | Enforces |
|--------|--------|------------|
| Format + lint | **Biome** | One tool for both (the console's Spotless + Checkstyle). Rules are committed at `error` with a comment saying why each is on. |
| Types | `tsc --noEmit` | `strict`, plus the correctness flags `strict` does not cover and a linter cannot replicate because they need whole-program type semantics: **`noUncheckedIndexedAccess`** (so `arr[i]` is `T \| undefined`), `noImplicitOverride`, `noImplicitReturns`, `isolatedModules`, `forceConsistentCasingInFileNames` |
| Tests + coverage | **Vitest** + React Testing Library | The standard below |
| Static analysis | **Semgrep** | Registry packs plus `.semgrep/lattice-rules.yml`, which holds the project rules no pack covers - the no-hardcoded-color rule above is the first |
| Dead code | **knip** | Enforcement Rule 10 (no dead code on replacement), machine-checked rather than by review. Configured with `ignoreExportsUsedInFile`, without which it flags an export a file uses internally (which is what a token module does) |
| Docstrings | **`check:tsdoc`** | Exported API carries a docstring, the same bar the Java side enforces via Javadoc - so one documentation standard covers both languages rather than half the tree |
| Comment hygiene | **`check:comments`** | Fails on an issue reference in a code comment, enforcing the [core_protocol.md](core_protocol.md#code-commenting-and-docstrings) rule that until now nothing checked. A `locked #NN` citation passes, which is the same standard's permitted form; the exemption is pinned by `scripts/check-comments.test.mjs` so a gate that stopped matching would fail loudly rather than silently |
| Supply chain | **OSV-Scanner** | Scans `pnpm-lock.yaml` in CI alongside the Maven SBOM, so the console's dependency tree is not invisible to the gate |

Repo-wide and therefore run in CI rather than here: **gitleaks** (secret scanning, backing the "no hardcoded secrets, no exceptions" rule) and **actionlint** (lints the workflow YAML itself, including shell injection in `run:` steps).

**Run them with one command** (`pnpm verify` in the console package). Running `./mvnw verify` alone does **not** cover the console - the [scope-aware hook](core_protocol.md#ci-triggers--qa-iteration-discipline) runs whichever applies, and **fails** rather than skipping when console code changed but its dependencies are not installed.

---

## Testing

- **Vitest + React Testing Library** (`@testing-library/react`). Mock at the **data-layer seam** (the query/stream hook), never a real network or backend - the console talks to the API/stream contract, not directly to a service or Elasticsearch.
- Type every fixture from the generated OpenAPI client / contract types so it cannot drift from the contract.
- Component tests are required for any component with non-trivial conditional rendering or state (a status panel that branches on healthy/degraded/down qualifies). Pure helpers get unit tests. Reset mocks between tests.
- **Test hygiene is enforced:** an unexpected `console.error` / `console.warn` fails the test, so await async state (no act-warnings), spy + assert expected logs, and never reach a real network. Keep the suite leak-free.

```tsx
import { render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"
import { ClusterStatus } from "../ClusterStatus"

// Stub the data hook with a contract-typed fixture.
vi.mock("../useClusterStatus", () => ({
  useClusterStatus: () => ({
    data: [{ service: "search", state: "healthy" }],
    isLoading: false,
  }),
}))

describe("ClusterStatus", () => {
  it("renders a healthy service row", () => {
    render(<ClusterStatus />)
    expect(screen.getByText("search")).toBeInTheDocument()
    expect(screen.getByText(/healthy/i)).toBeInTheDocument()
  })
})
```

> **What not to do:** do not hit a live service or the Elasticsearch/Artemis layer in a console test. The only seam is the data hook against the generated contract.

### Browser smoke check

A **browser smoke test** (load the built or dev-served console in a real browser via the preview tools) is a NON-visual sanity check: does the console mount against a running (or mocked) backend, and are there console errors. It confirms the page boots and wires up; it is not a substitute for the component tests above, and layout/visual sign-off happens in the service/cluster QA loop ([qa_protocol.md](qa_protocol.md)) with the stack actually up. A dead backend reads as empty panels, not an app bug - bring the stack up first.

**The dev server is not the deliverable - rebuild the image before asking for QA.** `pnpm dev` proves the source runs; it does not prove the *bundle* does, and the console ships as a static bundle in a container. A move, a rename, a barrel, or a path-alias change can pass HMR and fail `vite build`. So a console change is not ready until:

1. **`pnpm build` passes** (`tsc --noEmit` + a real production bundle), and
2. **the container is rebuilt** for every baseline being QA'd - the bundle bakes each baseline's `VITE_*` values in at **build** time, so one rebuilt console does not cover the others:

   ```bash
   ./deploy/k8s/mesh-clusters.sh images   # builds and loads one console image per baseline
   kubectl --context kind-hub-central -n lattice rollout restart deploy/hub-central-lattice-status-console
   kubectl --context kind-hub-east    -n lattice rollout restart deploy/hub-east-lattice-status-console
   kubectl --context kind-hub-west    -n lattice rollout restart deploy/hub-west-lattice-status-console
   ```

> **A rebuilt image does not reach a running pod on its own.** The images are loaded into each kind cluster with `imagePullPolicy: Never` and the tag does not change, so nothing tells Kubernetes anything is different - the rollout restart is what picks the new image up. Skipping it is the modern form of the stale-image trap: everything reports healthy and you are looking at the old bundle.

**Reading HMR errors after a file move.** A dev server left running while files are moved logs a cascade of `Failed to reload ... does not provide an export named X` - artefacts of the intermediate states it tried to hot-reload, not the final tree. They also persist in the browser tab's console buffer across a server restart. Judge the tree by `pnpm build` and a **fresh tab**, not by a buffer that recorded the refactor happening.

---

## Quick reference

| Concern       | Status console (`ui/status-console`)                                 |
|---------------|----------------------------------------------------------------------|
| Framework     | React (single-page app)                                              |
| Build tool    | Vite + TypeScript, dev server on port 5173                           |
| Tokens        | design-token `theme` module + theme hook                             |
| Navigation    | React Router (code-owned route table)                                |
| REST data     | generated OpenAPI 3.1 client (from `lattice-contract`)               |
| Live status   | polled every 10s behind one hook (locked #59)                        |
| Tests         | Vitest + React Testing Library + browser smoke check                 |
| No-store rule | no global client store without approval                              |
