import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import type { ReactNode } from "react";

/** What the card needs to hold. */
export interface ArrivalCardProps {
  /** The screen's own contents, beneath the product mark. */
  children: ReactNode;
}

/** The height the screen fills, so the card sits centred rather than pinned to the top. */
const SCREEN_MIN_HEIGHT = "70vh";

/**
 * The centred card both arrival screens render into.
 *
 * **Why the two share an object rather than a resemblance.** Signing in and being refused are the
 * same moment in an operator's journey - the first thing they see after following a redirect to a
 * peer - and they must look like one product deciding two things, not two screens built at different
 * times. They were: one was a designed card, the other a bare block. A second implementation that
 * merely looked similar would drift the moment either was touched.
 *
 * **The mark is decorative.** Every screen using this shell names the baseline in text directly
 * beneath it, so an accessible name on the image would announce the same thing twice.
 *
 * It is a status region, so a screen replaced under a screen-reader user is announced rather than
 * silently swapped.
 *
 * @param props the screen's contents.
 * @returns the shared card.
 */
export function ArrivalCard({ children }: ArrivalCardProps) {
  return (
    <Box
      sx={{
        alignItems: "center",
        display: "flex",
        justifyContent: "center",
        minHeight: SCREEN_MIN_HEIGHT,
      }}
    >
      <Paper
        aria-live="polite"
        role="status"
        sx={{ maxWidth: 380, p: 4, textAlign: "center", width: "100%" }}
      >
        {/* The 192px mark rather than lattice.png, which is 1.3MB - more than three times the whole
            JavaScript bundle - for something drawn at 60 pixels, on screens an operator reaches
            before anything is cached. At 192 it still covers a retina render with room spare.
            lattice.png stays for the README, where its weight is nobody's download. */}
        <Box
          alt=""
          component="img"
          src="/android-chrome-192x192.png"
          sx={{ height: 60, width: 60 }}
        />
        {children}
      </Paper>
    </Box>
  );
}
