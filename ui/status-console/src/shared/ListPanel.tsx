import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Skeleton from "@mui/material/Skeleton";
import Typography from "@mui/material/Typography";
import type { ReactNode } from "react";
import { ConnectionLost } from "./ConnectionLost.tsx";
import { PanelHeader } from "./PanelHeader.tsx";
import { PanelHelp, type PanelHelpContent } from "./PanelHelp.tsx";
import { SCROLL_PANE } from "./scrollStyles.ts";

/** Row widths for the waiting state, varied so it reads as content rather than as a progress bar. */
const SKELETON_WIDTHS = [96, 72, 88];

/** What the panel needs to frame one read. */
export interface ListPanelProps {
  /** The panel's name. */
  label: string;
  /**
   * How many rows the read returned, or undefined until the first one completes.
   *
   * <p>The distinction is load-bearing: none-yet and none-at-all are different answers, and only
   * one of them is something this console knows.
   */
  count?: number;
  /** What to say when the read returned nothing. */
  emptyMessage: string;
  /** Why the service could not be read, when it could not be. */
  errorDetail?: string;
  /** Whether a read is in flight behind that failure. */
  isRetrying?: boolean;
  /** When the last good read landed, so a blip and an outage stop looking identical. */
  lastGoodRead?: number | undefined;
  /** What this panel says about itself, when it has an entry. */
  help?: PanelHelpContent;
  /** The table this panel frames. Rendered only once a read has returned rows. */
  children: ReactNode;
}

/**
 * The frame around one operational read: its name, its failure state, its emptiness, and its table.
 *
 * <p><b>Why it exists.</b> Orders and Inventory each built this frame themselves, and each built the
 * same defect into it: the table was rendered whenever data existed, and an empty array exists - so
 * a baseline with no rows drew a full header row with nothing beneath it, then a sentence saying
 * there was nothing. Fixed twice it would have stayed fixable twice; fixed here it cannot recur, and
 * the next operational screen inherits the answer rather than re-deciding it.
 *
 * <p><b>The failure blocks rather than annotates.</b> Behind it sit a form and a list that has become
 * a memory, and acting on either is what the dialog prevents. It closes itself when the read
 * succeeds, so there is no way out except the service answering.
 *
 * <p>The table itself stays each view's own: the columns are the one genuinely different thing
 * between the two screens, and a component that templated those would be configuring a table
 * through a prop rather than writing one.
 *
 * @param props the panel's identity, the state of its read, and the table to frame.
 * @returns the panel.
 */
export function ListPanel({
  label,
  count,
  emptyMessage,
  errorDetail,
  isRetrying = false,
  lastGoodRead,
  help,
  children,
}: ListPanelProps) {
  return (
    // Takes the height the form above it leaves, and scrolls its table inside that rather than
    // letting the page run short. The heading and any failure stay put while a long list is read.
    <Paper
      sx={{
        display: "flex",
        flex: { md: 1 },
        flexDirection: "column",
        minHeight: 0,
        p: 2,
      }}
    >
      <PanelHeader help={help && <PanelHelp content={help} label={label} />} label={label} />

      <ConnectionLost detail={errorDetail} isRetrying={isRetrying} lastGoodRead={lastGoodRead} />

      {/* The table scrolls inside its own frame rather than widening the page: a console runs at an
          unknown width and the body must never scroll sideways.

          IT STAYS ON AN EMPTY READ, headers and all. An operator scanning a list with nothing in it
          still needs to know what the columns would have been, and a bare sentence on its own reads
          like a screen that failed to load rather than a baseline with no rows. */}
      {/* The right padding clears the scrollbar this frame owns. FLUSH pulls the last cell to the
          panel edge, which is right against a static edge and cramped against a scrollbar. */}
      {count !== undefined && <Box sx={{ flex: { md: 1 }, ...SCROLL_PANE }}>{children}</Box>}

      {/* SKELETON ROWS RATHER THAN A SPINNER, while the first read is still out. The panel keeps its
          shape, so nothing jumps when the answer lands, and it reads as a list before it is one -
          which a centred spinner never does. The count is deliberately a fixed few: how many rows
          are coming is exactly what is not known yet, and pretending otherwise would be a guess an
          operator could mistake for a reading. */}
      {count === undefined && (
        <Box aria-label={`Loading ${label.toLowerCase()}`} role="status" sx={{ py: 0.5 }}>
          {SKELETON_WIDTHS.map((width) => (
            <Box key={width} sx={{ alignItems: "center", display: "flex", gap: 1, height: 33 }}>
              <Skeleton sx={{ width }} />
              <Skeleton sx={{ ml: "auto", width: 64 }} />
            </Box>
          ))}
        </Box>
      )}

      {count === 0 && (
        <Typography sx={{ color: "text.secondary", py: 1 }} variant="body2">
          {emptyMessage}
        </Typography>
      )}
    </Paper>
  );
}
