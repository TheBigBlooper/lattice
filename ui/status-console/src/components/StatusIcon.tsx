/**
 * The glyph paired with every status word.
 *
 * It is always decorative and always hidden from assistive technology: the word beside it carries
 * the meaning, and announcing both would say the same thing twice. Its job is for sighted operators
 * scanning a wall of services, where a shape registers faster than a word does.
 *
 * Drawn inline rather than pulled from an icon library: three glyphs do not justify a dependency,
 * and these inherit their colour from the element around them, which is what keeps the palette the
 * single source of colour.
 */
export interface StatusIconProps {
  /** Which glyph to draw. */
  tone: "ready" | "degraded" | "down";
  /** Edge length in pixels; the caller passes a value from the type scale. */
  size: number;
}

const PATHS: Record<StatusIconProps["tone"], string> = {
  // A tick inside a circle.
  ready:
    "M8 1a7 7 0 100 14A7 7 0 008 1zm3.2 5.1l-3.9 4a.7.7 0 01-1 0L4.8 8.6l1-1 1.5 1.5 3.4-3.5 1 1z",
  // A triangle with a bar and a dot: distinguishable from the tick by silhouette alone, which is
  // the point for an operator who cannot rely on the colour difference.
  degraded: "M8 1.4l7 12.2H1L8 1.4zm-.7 4.3v4h1.4v-4H7.3zm0 5v1.4h1.4v-1.4H7.3z",
  // A cross inside a circle.
  down: "M8 1a7 7 0 100 14A7 7 0 008 1zM5.6 4.6L8 7l2.4-2.4 1 1L9 8l2.4 2.4-1 1L8 9l-2.4 2.4-1-1L7 8 4.6 5.6l1-1z",
};

/**
 * Renders the glyph for a status.
 *
 * @param props which glyph to draw and how large.
 * @returns an inline SVG that inherits the surrounding colour.
 */
export function StatusIcon({ tone, size }: StatusIconProps) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="currentColor"
    >
      <path d={PATHS[tone]} />
    </svg>
  );
}
