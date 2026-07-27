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

/**
 * Line heights. Body copy uses the ratio itself; the verdict is a single large word, which the
 * ratio would loosen into something that reads as two separate lines of nothing.
 */
export const leading = {
  body: 1.618,
  verdict: 1.2,
} as const;

/** Corner radii. Two values only, so a third never gets invented. */
export const radius = {
  /** Pills and controls. */
  pill: 5,
  /** Cards and panels. */
  card: 8,
} as const;

/**
 * The overview's two-column split: the cluster's own verdict against the mesh around it, in the
 * same golden ratio that governs every other size on the screen. The two shares are 1 : 1.618
 * expressed as percentages, which is what lets the columns be flex bases rather than grid tracks.
 *
 * It is a token rather than a value written into the shell because the ratio is the design system,
 * not a layout preference. A hand-picked column width here would be the one place on the console
 * where a size came from taste instead of the scale.
 *
 * They are flex bases specifically so the columns can wrap: below {@link overviewMinColumn} the
 * mesh drops beneath the verdict instead of crushing it, and the cluster's own state stays first in
 * reading order at every width. A grid template cannot do that without a media query, and this
 * console styles inline.
 */
export const overviewSplit = {
  /** The cluster's own verdict: the smaller share, 1 of the 1 : 1.618. */
  verdict: "38.2%",
  /** The mesh around it: the larger share. */
  mesh: "61.8%",
} as const;

/**
 * The width below which the overview's two columns stack rather than sit side by side.
 *
 * Sized so the widest thing a peer row must fit on one line - a cluster id, its region, its state,
 * and its age - still does. Narrower than this the row wraps mid-record, which reads as a broken
 * table rather than a narrow one.
 */
export const overviewMinColumn = 288;

/**
 * The height the verdict block reserves.
 *
 * It is a token rather than a number chosen per component because the signed-out screen occupies
 * this exact block: sharing the value is what guarantees signing in does not reflow the page.
 */
export const verdictBlockMinHeight = 89;

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
