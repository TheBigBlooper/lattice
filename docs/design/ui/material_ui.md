# Material UI - the status console's component library

The status console moves from a hand-rolled design system to **Material UI**. This document settles what that means for the proportion system, the palette, the accessibility bar, the token gate, the styling engine, the icons, and the test suite, and records what is traded in each case.

Related: [_index.md](_index.md) (the visual direction this supersedes in part), [ui_protocol.md](../../protocol/ui_protocol.md) (the rules the console is built to), [locked_decisions.md](../../reference/locked_decisions.md) (#9 the stack, #15 one engine per job, #51 the console's quality gates).

---

## Why this document exists

Material UI was expected to be the console's component library and turned out never to have been decided: it appears nowhere in the protocols, the design docs, or the locked decisions, which stop at "React (Vite + TypeScript)". Meanwhile the console had grown a hand-rolled system - a golden-section proportion scale, a contrast-measured palette, and inline styles.

Adopting Material UI is not a drop-in. It collides with several settled positions, and each collision has a cheap answer decided deliberately and an expensive one discovered mid-build. Every collision below was put to a founder as an explicit choice.

---

## The decisions

| # | Question | Decision |
|----|------------|------------|
| 1 | Adoption scope | **Full replacement.** Every existing primitive is rebuilt on Material UI; nothing hand-rolled survives. |
| 2 | Proportion | **Material UI's 8px grid.** The golden-section scale is retired. |
| 3 | Palette | **Material UI's `success` / `warning` / `error`.** The contrast-measured custom palette is dropped. |
| 4 | Contrast bar | **Relaxed to 3:1 for status colours**, from a hard 4.5:1. |
| 5 | Token gate | **Kept**, retargeted at the Material UI theme file. |
| 6 | Styling engine | **Emotion only.** Inline styles are removed in the same change. |
| 7 | Bundle size | **No ceiling.** Measured and recorded, not gated. |
| 8 | Icons | **Replaced** with `@mui/icons-material`. |
| 9 | Tests | **All existing tests must pass unchanged.** |
| 10 | Staging | **One ticket, one pass**, gated on a confirmed mockup. |

---

## 1. Full replacement

Every primitive is rebuilt: the status block, the status pill, the status icon, the cluster verdict, the signed-out screen, and the discovered-baselines panel. Nothing hand-rolled remains.

The alternative considered was a boundary - Material UI for structure, hand-rolled for the status-specific pieces. It was rejected because a boundary needs a written rule to survive, and a rule about which of two systems to reach for is exactly the ambiguity that "reuse over rebuild" exists to remove. A console running two visual systems indefinitely is the outcome with no advocate.

**Consequence:** the migration is one large diff rather than several small ones. That is accepted, and is why decision 9 (tests unchanged) matters so much - it is the safety net that makes a large diff reviewable.

---

## 2. Proportion: the golden-section scale is retired

Material UI's 8px-multiple spacing governs the console. The golden sequence (5, 8, 13, 21, 34, 55) and the type scale derived from it are withdrawn.

The alternative was to override `theme.spacing` so every Material UI component landed on the golden sequence. It was rejected because it makes every component's built-in density assumptions subtly wrong, requiring per-component correction - paying the cost of a library while fighting it. If the library's conventions are not wanted, the library is not what is wanted.

**What this withdraws**, all of which was written down with reasons that no longer hold:

- The **single-ratio argument** in [_index.md](_index.md) - that one sequence means any two sizes on screen are already in proportion.
- The **`1 : 1.618` layout split** used by the unified baselines view for the verdict-to-mesh columns, and the `overviewSplit` token expressing it. Column proportion becomes a Material UI grid decision.
- **`verdictBlockMinHeight = 89`**, a Fibonacci value chosen to sit on the scale, recalculated on 8px multiples.

**What survives:** the **12px floor**. Nothing renders smaller, because below that it stops being readable at the distance an operator sits from a wall-mounted or side monitor. Material UI's `caption` is 12px by default, so the floor holds without intervention.

---

## 3. Palette: Material UI's semantic colours

`ready`, `degraded`, and `down` map onto Material UI's `success`, `warning`, and `error`. The custom palette is dropped.

Material UI supplies `primary`, `secondary`, and the surface colours, which this console barely exercises - it is a status view, and status colour is the only colour that carries meaning.

---

## 4. Contrast: the bar drops to 3:1 for status colours

**This is a deliberate accessibility regression on the light theme, recorded as such.**

Measured against Material UI 9.2.0's defaults, on that mode's own background:

| State | Material UI default | Ratio | Previous custom value | Ratio |
|---------|------------------------|---------|--------------------------|---------|
| `success` light | `#2e7d32` on `#ffffff` | 5.13:1 | `#1a7f37` | 5.08:1 |
| **`warning` light** | **`#ed6c02` on `#ffffff`** | **3.11:1** | `#9a6700` | 4.87:1 |
| `error` light | `#d32f2f` on `#ffffff` | 4.98:1 | `#cf222e` | 5.36:1 |
| `success` dark | `#66bb6a` on `#121212` | 7.92:1 | `#3fb950` | 7.45:1 |
| `warning` dark | `#ffa726` on `#121212` | 9.64:1 | `#d29922` | 7.50:1 |
| `error` dark | `#f44336` on `#121212` | 5.09:1 | `#f85149` | 5.65:1 |

Five of the six clear WCAG AA for normal text. **Material UI's light-mode warning does not**, at 3.11:1 against a required 4.5:1.

It cannot be fixed from within Material UI's own palette. The whole orange ramp was measured on white:

| Candidate | Ratio | AA for normal text |
|-------------|---------|----------------------|
| `warning.main` `#ed6c02` | 3.11:1 | fails |
| `orange[800]` `#ef6c00` | 3.08:1 | fails |
| `orange[900]` `#e65100` | 3.79:1 | fails |
| the withdrawn `#9a6700` | 4.87:1 | passes |

**Stated precisely, because the distinction matters:** WCAG's 3:1 threshold applies to user-interface components and large text. It is not an alternative bar for normal text. Applying it to the word `degraded` at body size does not mean the console meets a different standard - it means **that label does not meet WCAG AA**. Dark mode is unaffected.

Two alternatives were declined: overriding `warning.main` with the measured amber (rejected as a deviation from a stock palette), and rendering `degraded` only at large-text size where 3:1 legitimately applies (rejected as a constraint every future panel would have to remember).

**What still holds.** Colour was never the sole indicator and still is not: every state renders its colour, its glyph, **and its word**. The word is what carries the meaning for an operator who cannot resolve the hue, and it is unaffected by this decision. That redundancy is the reason this regression is bounded rather than disqualifying.

**Reversible.** Restoring the bar means overriding one palette entry. The measured numbers are recorded here so a future reader sees the trade rather than inheriting it as folklore.

---

## 5. The token gate survives, retargeted

`check:tokens` fails the build on a colour literal anywhere outside the single file that is allowed to hold them. That file becomes the **Material UI theme definition** rather than the token module.

Components read colour through the theme - palette keys and `sx` - and a raw hex anywhere else still fails. The scan is rewritten rather than repointed, because it must understand theme access rather than a single import.

Retiring the gate was considered and rejected. The gate is what made the palette auditable, and decision 4 makes auditability more valuable, not less: a palette carrying a known below-AA value must not also be a palette anyone can quietly add to.

---

## 6. Emotion becomes the only styling engine

Material UI styles through Emotion. Rather than an exception to one-engine-per-job (#15), **inline style props are removed from every component in the same change**, leaving `sx` and `styled` as the single way anything is styled. The rule is satisfied, not excepted - there is one engine, and it changed.

This enlarges the migration: every style object moves, not only the ones Material UI replaces. That is consistent with decision 1, which already leaves nothing hand-rolled.

**A side benefit worth recording.** Inline styles cannot express a media query, which is why the unified view's responsive behaviour had to be built from flex wrapping rather than a grid template. Emotion removes that constraint, so responsive layout can be expressed directly.

---

## 7. Bundle size is recorded, not gated

The console before migration:

| Measure | Value |
|-----------|---------|
| Raw | 261.58 kB |
| Gzipped | 81.47 kB |
| Modules | 78 |

No ceiling is set. The console is an internal operator tool served from a container on the operator's own network, not a public page competing for first paint, so size is honesty rather than a constraint. The post-migration figure is recorded in this table when the build lands, so the delta is visible.

---

## 8. Icons come from Material UI

The three hand-drawn status glyphs are replaced by `@mui/icons-material`.

The glyphs being replaced were deliberately shaped to differ **by silhouette alone** - a tick in a circle, a triangle, a cross in a circle - so a colour-blind operator could distinguish states without resolving hue. Under this decision that property becomes **inherited from Material UI's icon set rather than verified**, and the option to verify it explicitly was declined.

Combined with decision 4, two of the three redundancy signals move from measured to assumed. The **word** remains, and remains the signal that actually carries meaning. This is recorded together in one place because the two decisions compound, and neither is visible from the other's ticket.

---

## 9. Every existing test must pass unchanged

The suite asserts roles, labels, and text - not styles. An unchanged suite is therefore strong evidence the migration changed appearance and nothing else, which is the only practical safety net for a diff this size.

A test that genuinely cannot survive is treated as a **signal to inspect**, not a chore: Material UI renders different markup, and a query that breaks is either an accessibility change worth knowing about or a behaviour change that should not be in a re-skin. Each one is justified individually rather than waved through.

---

## 10. One pass, gated on a mockup

The migration is **a single build ticket** re-skinning the whole console, including the unified baselines panel, so it is never half-migrated across two visual systems.

It carries **`needs-mockup`** (Enforcement Rule 16). Rule 16 applies to any change with a visible surface, and this changes every surface, so the founder confirms an A/B/C visual direction for the themed console before it is built. Treating Material UI's own design language as an implicit confirmation was declined.

**Proportions to state at mockup time.** [_index.md](_index.md)'s requirement that a mockup names its dominant-to-supporting relationships still applies; with the golden section withdrawn, those relationships are expressed as Material UI grid columns and spacing steps rather than as φ.

---

## What this supersedes

| Document | What changes |
|------------|----------------|
| [_index.md](_index.md) | The **proportion system** section is rewritten, not amended: its argument for a single ratio no longer holds. The token tables are replaced by the theme definition. The verdict-first direction and the failure-state requirements are **unaffected**. |
| [ui_protocol.md](../../protocol/ui_protocol.md) | The WCAG line changes from a hard 4.5:1 to 3:1 for status colours. The no-hardcoded-colour rule stands, pointing at the theme file. |

Both edits land with the **build** ticket rather than here, because until the migration ships those documents accurately describe the console as it runs. This document is the decision; they are the description.

---

## Deferred

- **The post-migration bundle figure**, recorded once measured.
- **Restoring the contrast bar**, should the light-theme `degraded` label prove hard to read in practice. It is one palette override.
- **Material UI's own dark-mode mechanism** versus the console's existing reactive theme hook, settled during the build - both follow the operator's system preference, so this is an implementation choice rather than a behavioural one.
