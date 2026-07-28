import Paper from "@mui/material/Paper";
import type { ReactNode } from "react";
import { statusBlockMinHeight } from "../theme/theme.ts";

/** What the block needs to colour itself and hold its contents. */
export interface StatusBlockProps {
  /** The palette path the block's text and glyph take, resolved by the theme. */
  tone: string;
  /** The block's contents. */
  children: ReactNode;
  /**
   * Fills the height of its column and distributes its contents to the edges, rather than sitting
   * at its reserved height with everything centred.
   *
   * Set where the block sits beside a taller panel. Two cards of visibly different height read as
   * one finished and one still loading, which is the wrong thing to suggest on a status screen -
   * and the extra room is spent on the breakdown reaching the base of the card rather than on
   * padding, so the height is earned.
   */
  fill?: boolean;
}

/**
 * The shell the cluster verdict and the baseline's error states render into.
 *
 * It exists to make one guarantee structural rather than coincidental: whatever fills the status
 * position occupies the same space at the same size, so the page never reflows as that choice
 * changes. Were each screen to set its own height, they would agree until the day someone changed
 * one of them.
 *
 * The signed-out and refusal screens no longer render here. They became the front door once an
 * operator could arrive by peer redirect, and a landing page wants a centred card rather than a
 * block sized to sit beside a verdict - see {@link ArrivalCard}, which is now their shared shell.
 *
 * It is a status region for assistive technology, so a verdict changing under a screen-reader user
 * is announced rather than silently replaced.
 *
 * @param props the tone and the contents.
 * @returns the shared block.
 */
export function StatusBlock({ tone, children, fill = false }: StatusBlockProps) {
  return (
    <Paper
      aria-live="polite"
      role="status"
      // The reserved height stays an inline style rather than moving into sx. It is the guarantee
      // that the verdict and the signed-out screen occupy identical space, and inline is the one
      // form that can be read back directly to prove the two still agree - an Emotion class would
      // make the guarantee real but unobservable.
      style={{ minHeight: `${statusBlockMinHeight}px` }}
      sx={{
        alignItems: "flex-start",
        color: tone,
        display: "flex",
        flexDirection: "column",
        // Filling: the verdict tops the card and the breakdown reaches its base, so the column ends
        // level with the panel beside it. Otherwise the reserved height above is the whole card and
        // its contents sit centred in it.
        height: fill ? "100%" : undefined,
        justifyContent: fill ? "space-between" : "center",
        p: 2,
        width: fill ? "100%" : undefined,
      }}
    >
      {children}
    </Paper>
  );
}
