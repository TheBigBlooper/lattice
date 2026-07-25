/**
 * The console's design tokens: the only place in this codebase where a colour literal, a spacing
 * value, or a radius is written down. Every component reads from here.
 *
 * The values and the reasoning behind them are settled in `docs/design/ui/_index.md`; this module is
 * that document expressed as code, not a second source of truth. Changing a value here without
 * changing it there leaves the two disagreeing, and the document is the one under review.
 *
 * The `check:tokens` gate enforces the boundary: a colour literal anywhere else in `src` fails the
 * build, and this file is its single documented exception.
 */

/**
 * The golden-section scale. Rounding the powers of 1.618 to whole pixels produces this sequence,
 * and every size in the console comes from it: type, spacing, radius, and the layout split.
 *
 * The floor is 12: below that a value stops being readable at the distance an operator sits from a
 * wall-mounted or side-monitor dashboard, which is what this console is for.
 */
export const scale = {
  xs: 5,
  sm: 8,
  md: 13,
  lg: 21,
  xl: 34,
  xxl: 55,
} as const;

/** Type sizes, drawn from the same scale. */
export const type = {
  /** Metadata and timestamps. The smallest readable size. */
  meta: 12,
  /** Body copy and service names. */
  body: 13,
  /** Section headings. */
  section: 21,
  /** The cluster verdict, the largest thing on the screen. */
  verdict: 34,
} as const;

// Line heights and corner radii are specified in the design document but not defined here yet:
// nothing renders a pill or the verdict so far, and exporting a token no component reads is dead
// code the moment it is written. Each lands in the change that first uses it.

/**
 * One palette per mode. Light and dark are reactive: the console follows the operator's system
 * preference rather than baking a choice in at build time.
 *
 * Every status colour was measured against the surface it sits on and clears the WCAG AA 4.5:1
 * minimum for normal text. The ratios are recorded in the design document.
 */
export interface Palette {
  /** Page background. */
  surfacePage: string;
  /** A card or panel lifted off the page. */
  surfaceRaised: string;
  /** Body copy. */
  textPrimary: string;
  /** Supporting copy and metadata. */
  textSecondary: string;
  /** Hairline borders. */
  border: string;
  /** Every service is up. */
  statusReady: string;
  /** Some services are up. Serving, but not whole. */
  statusDegraded: string;
  /** Nothing could be reached. */
  statusDown: string;
}

/** The light-mode palette. */
export const lightPalette: Palette = {
  surfacePage: "#ffffff",
  surfaceRaised: "#f6f8fa",
  textPrimary: "#1f2328",
  textSecondary: "#59636e",
  border: "#d1d9e0",
  statusReady: "#1a7f37",
  statusDegraded: "#9a6700",
  statusDown: "#cf222e",
};

/** The dark-mode palette. */
export const darkPalette: Palette = {
  surfacePage: "#0d1117",
  surfaceRaised: "#161b22",
  textPrimary: "#e6edf3",
  textSecondary: "#8d96a0",
  border: "#30363d",
  statusReady: "#3fb950",
  statusDegraded: "#d29922",
  statusDown: "#f85149",
};
