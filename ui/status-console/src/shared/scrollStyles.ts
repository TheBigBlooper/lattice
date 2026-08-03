import type { SxProps, Theme } from "@mui/material/styles";

/**
 * Anything in this console that scrolls its own contents.
 *
 * <p><b>Why this is shared rather than set per panel.</b> The same defect was reported four times on
 * four different panels: a classic scrollbar takes width from the content box, so the right edge of
 * every row sits underneath it - a value column clipped, a timestamp half covered. Each panel had
 * been fixed on its own with whatever padding looked right, which left three different answers and a
 * fourth panel still waiting to be noticed. One token means the next scrolling panel inherits the
 * answer rather than joining the queue.
 *
 * <p>`scrollbarGutter: stable` is what actually reserves the space, so the layout does not shift when
 * a scrollbar appears. The padding beside it is breathing room rather than clearance - on platforms
 * with overlay scrollbars the gutter costs nothing and the padding is all that shows.
 *
 * <p>Spread it <b>after</b> any padding a caller sets, or a `p: 0` on a list will win and put the
 * clearance back to zero.
 */
export const SCROLL_PANE: SxProps<Theme> = {
  minHeight: 0,
  overflowY: "auto",
  pr: 1,
  scrollbarGutter: "stable",
};
