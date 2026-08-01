import type { SxProps, Theme } from "@mui/material/styles";

/**
 * How long each kind of movement takes. Short and flat, deliberately.
 *
 * <p>A dashboard that overshoots reads as a toy, so nothing here bounces and nothing runs long
 * enough to be waited on. The settle is the exception at half a second: it is not a movement but a
 * mark fading out, and it has to outlast a glance to be seen at all.
 */
const DURATION = { enter: 260, exit: 200, settle: 520 } as const;

/**
 * How long a leaving element stays mounted, so it can animate out.
 *
 * <p>Exported because the exit is driven by a timer rather than by the animation ending. An
 * animation that has been turned off by reduced motion never fires its end event, so a component
 * waiting for one would keep a dismissed toast on screen forever - the failure mode is silent, and
 * only on the machines least able to tolerate it.
 */
export const EXIT_MS = DURATION.exit;

/**
 * The query every animation here answers.
 *
 * <p>Reduced motion means effectively off rather than merely shorter. That is safe because nothing
 * in this console carries meaning through movement alone: every state renders its colour, its glyph
 * and its word whether or not it animated on the way in.
 */
const REDUCED = "@media (prefers-reduced-motion: reduce)";

/** How far a thing travels on its way in. Small enough to read as settling rather than as flying. */
const DRIFT_PX = 6;
const SLIDE_PX = 14;

/**
 * A new row settling into place, from above.
 *
 * <p>Applied to every row rather than to the newest one: a CSS animation runs when an element
 * mounts, and rows are keyed by a stable id, so an existing row is not remounted by a poll and does
 * not replay. Marking "the newest" explicitly would re-fire on every render that reordered nothing.
 */
export const ENTER_DOWN: SxProps<Theme> = {
  "@keyframes latticeEnterDown": {
    from: { opacity: 0, transform: `translateY(-${DRIFT_PX}px)` },
    to: { opacity: 1, transform: "none" },
  },
  animation: `latticeEnterDown ${DURATION.enter}ms cubic-bezier(0.2, 0, 0.2, 1) both`,
  [REDUCED]: { animation: "none" },
};

/**
 * A toast arriving from the edge it is anchored to.
 *
 * <p>Direction carries meaning: it comes from where it lives, so the movement says "this arrived"
 * rather than "this was pushed at you".
 */
export const ENTER_RIGHT: SxProps<Theme> = {
  "@keyframes latticeEnterRight": {
    from: { opacity: 0, transform: `translateX(${SLIDE_PX}px)` },
    to: { opacity: 1, transform: "none" },
  },
  animation: `latticeEnterRight ${DURATION.enter}ms cubic-bezier(0.2, 0, 0.2, 1) both`,
  [REDUCED]: { animation: "none" },
};

/**
 * A destination replacing another, with no direction.
 *
 * <p>A slide would imply the views sit in an order. Status, Orders and Inventory are three views of
 * one baseline rather than steps in a flow, so the change is a plain cross-fade.
 */
export const CROSS_FADE: SxProps<Theme> = {
  "@keyframes latticeCrossFade": { from: { opacity: 0 }, to: { opacity: 1 } },
  animation: `latticeCrossFade ${DURATION.exit}ms ease-out both`,
  [REDUCED]: { animation: "none" },
};

/**
 * A brief wash behind a row whose state just changed, fading out.
 *
 * <p>It marks the moment and gets out of the way. Nothing rides on the flash itself - the row keeps
 * its glyph, its word and its colour - so an operator who looked away, or who has reduced motion
 * on, has lost nothing but the cue that it was recent.
 *
 * @param palettePath the state's own palette entry, so the mark is the colour of the news.
 * @returns the sx for the wash.
 */
export function settle(palettePath: string): SxProps<Theme> {
  return {
    "@keyframes latticeSettle": {
      from: { backgroundColor: "currentColor", opacity: 0.16 },
      to: { backgroundColor: "transparent", opacity: 0 },
    },
    "&::before": {
      animation: `latticeSettle ${DURATION.settle}ms ease-out both`,
      borderRadius: 1,
      color: palettePath,
      content: '""',
      inset: 0,
      pointerEvents: "none",
      position: "absolute",
      [REDUCED]: { animation: "none", display: "none" },
    },
    position: "relative",
  };
}

/**
 * A toast leaving the way it arrived.
 *
 * <p>Out to the edge it is anchored to, so a dismissal reads as the toast going home rather than as
 * content being lost. Under reduced motion it simply stops being rendered, which is the same
 * outcome an instant removal always had.
 */
export const EXIT_RIGHT: SxProps<Theme> = {
  "@keyframes latticeExitRight": {
    from: { opacity: 1, transform: "none" },
    to: { opacity: 0, transform: `translateX(${SLIDE_PX}px)` },
  },
  animation: `latticeExitRight ${DURATION.exit}ms ease-in both`,
  [REDUCED]: { animation: "none", opacity: 0 },
};
