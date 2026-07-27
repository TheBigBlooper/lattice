import Paper from "@mui/material/Paper";
import type { ReactNode } from "react";
import { statusBlockMinHeight } from "../theme/theme.ts";

/** What the block needs to colour itself and hold its contents. */
export interface StatusBlockProps {
  /** The palette path the block's text and glyph take, resolved by the theme. */
  tone: string;
  /** The block's contents. */
  children: ReactNode;
}

/**
 * The shell the cluster verdict and the signed-out screen both render into.
 *
 * It exists to make one guarantee structural rather than coincidental: the two screens occupy the
 * same position at the same size, so signing in never reflows the page. Were each to set its own
 * height, they would agree until the day someone changed one of them.
 *
 * It is a status region for assistive technology, so a verdict changing under a screen-reader user
 * is announced rather than silently replaced.
 *
 * @param props the tone and the contents.
 * @returns the shared block.
 */
export function StatusBlock({ tone, children }: StatusBlockProps) {
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
        justifyContent: "center",
        p: 2,
      }}
    >
      {children}
    </Paper>
  );
}
