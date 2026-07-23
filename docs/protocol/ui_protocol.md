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

| You changed | Do this |
|---|---|
| React / TS component code | Nothing - hot module replacement applies it |
| A `.env` / `VITE_*` var | Restart the dev server (Vite inlines `import.meta.env` at server start) |
| `vite.config.ts` / `tsconfig` path aliases | Restart the dev server |
| The generated OpenAPI client (regenerated from a new spec) | Restart the dev server if types go stale; otherwise HMR picks it up |
| Stale bundle / "failed to resolve import" | Restart with a clean cache (`vite --force`) |

> **Gotcha:** `VITE_*` env vars are read through `import.meta.env` and inlined at dev-server start, so a changed value does **not** hot-reload - restart the dev server. A value that resolves in dev can still be absent in the built image if it was not present at build time (see [deploy_protocol.md](deploy_protocol.md) build-parity).

---

## Navigation

- **React Router** (chosen over a file-based router - it is an explicit, code-owned route table, which suits a small operator console with a handful of views). Define routes in one place; do not scatter `<Route>` declarations. Mock the router in component tests that navigate.
- Keep the route surface small: a cluster overview, a per-service/per-node detail view, and a mesh/peer view are the expected shape. Do not add deep nested routing without a reason.

---

## Styling and theming

> These rules apply to **all** console UI work - a new panel, a refactor, or an enhancement - not just new components. The design system is unconditional: any component you touch must use tokens, and the lint gate enforces it regardless.

- **All color/spacing/radius/type tokens come from the design-token module** (the console's `theme`). Read the active palette through the theme context; build themed styles through the provided hook rather than hand-threading colors. Light/dark are reactive.
- **No hardcoded colors.** A raw hex / `rgb()` in a component is a defect - pull the value from the active palette. Enforced by the console's lint rule (TBD - wire the no-hardcoded-color check into the console's lint step when the token module lands). Locally, verify by inspection until the rule is wired.
- **No hardcoded spacing.** Every `padding` / `margin` / `gap` references a spacing token (only `0` is a bare literal), on a consistent grid. A genuinely dynamic value escapes via a documented ignore-comment.
- Component taxonomy (button variants, status pills, etc.) and the token tables are canonical in the design docs under `docs/design/ui/` (TBD - land the token dictionary + rendered reference there). Do not redefine them here.

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
- **Live node status streams in.** The console subscribes to a live feed of node/service state rather than polling - transport **TBD** (Server-Sent Events or a WebSocket, likely surfaced from the mesh; decide in a design session and record it here). Wrap the subscription behind one hook so the transport choice is swappable without touching panels.
- **A server-data cache library** (e.g. TanStack Query) is the standard way to hold REST reads - loading/error/refetch states, cache invalidation on a live event. Type every query off the generated client so fixtures cannot drift from the contract.
- **No unstable values in effect dependencies.** Never put a value re-created every render (a refetch callback, an inline object/array/closure) in a `useEffect` dependency array - it re-subscribes the effect every render, and on a live-updating console that re-runs it in a loop. Hold the value in a `useRef`, give the effect stable deps, and read `ref.current` inside. A live-subscription hook needs a test that drives the real effect across a re-render (assert callback-identity stability), not a no-op mock - a no-op mock hides exactly this bug.
- **Mock-first is safe because of the contract.** The console can iterate against a contract-satisfying mock of the API/stream; the mock -> live cutover must not touch the UI. Why this is safe and how mocks stay typed is owned by [contract_protocol.md](contract_protocol.md).

---

## Auth

The status console is an operator tool; its access model is **TBD** (design session - likely cluster-internal / behind the cluster's ingress auth rather than a per-user identity provider). Whatever it is, the console never trusts a client-sent identity for authorization - the service verifies. Do not build a bespoke auth flow before this is decided; record the decision here when it lands.

---

## Accessibility

- Every interactive element has a visible focus/pressed state. Icons without a visible label need an accessible label.
- **Color is never the sole indicator of state.** A healthy/degraded/down status must always pair its color with text or an icon - operators may be color-blind, and a red/green-only dashboard is unreadable to them. This is a hard rule on a status console.
- WCAG AA is a hard constraint (4.5:1 normal text, 3:1 large text / UI components). Run an accessibility pass before handoff on a new panel.

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

---

## Quick reference

| Concern | Status console (`ui/status-console`) |
|---|---|
| Framework | React (single-page app) |
| Build tool | Vite + TypeScript, dev server on port 5173 |
| Tokens | design-token `theme` module + theme hook |
| Navigation | React Router (code-owned route table) |
| REST data | generated OpenAPI 3.1 client (from `lattice-contract`) |
| Live status | streamed subscription - transport TBD (SSE / WebSocket via the mesh) |
| Tests | Vitest + React Testing Library + browser smoke check |
| No-store rule | no global client store without approval |
