---
name: new-panel
description: Scaffold a React status-console component/panel with a failing test first, theme tokens, and a browser smoke check.
---

Use to scaffold a **new** panel or component in the **status console** (`ui/status-console`, React + Vite + TypeScript). Canonical rules: [ui_protocol.md](../../../docs/protocol/ui_protocol.md); the styling source of truth: [ui design docs](../../../docs/design/ui/_index.md) *(planned - the theme tokens + component taxonomy are written for the status-console work, #11)*. For data, see [contract_protocol.md](../../../docs/protocol/contract_protocol.md).

> Scope: this skill only **scaffolds new additions**. Refactoring an existing panel or a feature enhancement is not a `/new-panel` task - it follows the same `ui_protocol.md` rules directly (theme + token enforcement apply to **all** console UI work, new or edited, unconditionally via CI). Same rules, no separate skill needed.

## Steps (in order)

1. **Failing test first:** write the **Vitest + React Testing Library** test capturing the rendered behavior, mocking at the data-layer seam (the query hook / fetch client) with **contract-typed** fixtures from the generated `lattice-contract` client. Run it red.
2. **Build it:**
   - The panel goes in `ui/status-console/src/` alongside its feature; keep data logic in a hook, not the view.
   - **Reuse shared console components** (the existing layout, card, table, status-badge, chart primitives) - **do not fork** a parallel "lean" copy. Grep for an existing component before building a new one.
   - Colors via the **theme tokens** - **no hardcoded colors**. Spacing via the shared spacing scale.
3. **Implement to green**, then run the **browser smoke check** via the preview tools (the panel renders against the console's dev server, in light and dark theme, no console errors).
4. If the panel needs new service data, run [`/new-contract`](../new-contract/SKILL.md) first.

## Checks
- [ ] failing Vitest + React Testing Library test shown before implementation
- [ ] theme tokens only (no hardcoded colors); shared console components reused, none forked
- [ ] browser smoke passes (renders, light + dark, no console errors)
- [ ] lint + typecheck + tests green
