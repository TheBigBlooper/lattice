import { useEffect, useRef, useState } from "react";

/** How long a change stays marked. Long enough to outlast a glance, short enough not to linger. */
const MARKED_FOR_MS = 600;

/**
 * Whether a value has just changed, for the brief moment after it did.
 *
 * <p><b>Each row asks about itself.</b> The alternative was threading a previous snapshot down from
 * whichever panel owns the list, which would put every row's history in the panel and make "did this
 * one change" a question answered somewhere other than where it is drawn. A row already knows its
 * own state; this lets it know the previous one too.
 *
 * <p><b>The first render never counts as a change.</b> A screen opening would otherwise mark every
 * row at once, which says "everything just changed" at the exact moment nothing has.
 *
 * <p>What it drives is decoration and nothing else: the row renders its glyph, its word and its
 * colour whether or not this returned true, so a mark missed - by looking away, or by having reduced
 * motion on - loses only the cue that the change was recent.
 *
 * @param value the state to watch.
 * @returns true for a moment after it changes, false otherwise.
 */
export function useJustChanged(value: string): boolean {
  const previous = useRef(value);
  const [marked, setMarked] = useState(false);

  useEffect(() => {
    if (previous.current === value) {
      return;
    }
    previous.current = value;
    setMarked(true);
    const timer = setTimeout(() => setMarked(false), MARKED_FOR_MS);
    return () => clearTimeout(timer);
  }, [value]);

  return marked;
}
