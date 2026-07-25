import type { ReactNode } from "react";
import { type Palette, radius, scale, verdictBlockMinHeight } from "../theme/tokens.ts";

/** What the block needs to reserve its space and colour itself. */
export interface StatusBlockProps {
  /** The colour the block's text and glyph take. */
  tone: string;
  /** A translucent-free background drawn from the palette. */
  palette: Palette;
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
 * @param props the tone, palette, and contents.
 * @returns the shared block.
 */
export function StatusBlock({ tone, palette, children }: StatusBlockProps) {
  return (
    <section
      aria-live="polite"
      style={{
        alignItems: "flex-start",
        backgroundColor: palette.surfaceRaised,
        borderRadius: radius.card,
        color: tone,
        display: "flex",
        flexDirection: "column",
        justifyContent: "center",
        minHeight: `${verdictBlockMinHeight}px`,
        padding: `${scale.md}px ${scale.lg}px`,
      }}
      role="status"
    >
      {children}
    </section>
  );
}
