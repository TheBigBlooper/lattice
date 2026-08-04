import WarningAmberIcon from "@mui/icons-material/WarningAmberRounded";
import Box from "@mui/material/Box";
import Tooltip from "@mui/material/Tooltip";
import type { components } from "../../api/generated/v1.ts";

type FederationState = components["schemas"]["FederationState"];

/**
 * What each state means, and what to do about it.
 *
 * <p>Declared here rather than inline so the three readings are read together, which is how they
 * were written: `Down` says what it has <em>ruled out</em> rather than claiming the certificate is
 * fine, because ruling out expiry does not rule out revocation - a revoked but unexpired certificate
 * is invisible to a baseline and reads as `Down`.
 */
const EXPLANATION: Record<FederationState, string> = {
  up: "This broker holds a live federation link to that peer, so announcements are crossing.",
  down: "The federation link to that peer is not carrying traffic. This baseline's certificate has not expired, but revocation cannot be checked from here - if every peer is down, check the authority.",
  refused:
    "This baseline's own certificate has expired, so peer brokers refuse the connection. Re-issue it with issue-certs.sh, then roll this baseline's broker.",
};

/** The word shown for each state. Sentence case, matching every other state word on the screen. */
const LABEL: Record<FederationState, string> = {
  up: "Up",
  down: "Down",
  refused: "Refused",
};

/** What the reading needs. */
export interface FederationReadingProps {
  /** The measured state, or undefined when this baseline could not read the link. */
  state?: FederationState | undefined;
}

/**
 * One peer's federation link state, with its meaning attached.
 *
 * **A different link from the one in the panel header.** That one is this baseline's connection to
 * its own broker; this is that broker's connection to a peer's. One reads healthy while the other is
 * dead whenever a certificate is refused or expires, which is the case this column exists for.
 *
 * **Nothing is rendered when nothing was measured.** Absent means "not read", never "healthy" - a
 * baseline that could not reach its broker must not show an all-clear it has not earned.
 *
 * **The explanation is reachable without a mouse.** The reading carries `tabIndex` so the tooltip
 * opens on focus as well as hover: this console's standing position is that a tooltip is the wrong
 * container for anything worth writing down, and the compromise that makes one acceptable here is
 * that a keyboard and a screen reader can both get to it.
 *
 * @param props the measured state.
 * @returns the reading, or nothing when there is none.
 */
export function FederationReading({ state }: FederationReadingProps) {
  if (!state) {
    return null;
  }

  const alarming = state !== "up";
  const describedBy = `federation-${state}-explanation`;

  return (
    <Tooltip title={EXPLANATION[state]}>
      <Box
        aria-describedby={describedBy}
        component="span"
        sx={{
          alignItems: "center",
          columnGap: 0.5,
          cursor: "help",
          // A fixed gutter for the icon, present whether or not there is one to draw. Sized from the
          // icon rather than the text so the words share a left edge down the column: an icon in the
          // flow shifts only the rows that have one, which is the one place a status column must not
          // move - a reader scans these vertically.
          display: "inline-grid",
          gridTemplateColumns: "18px auto",
        }}
        tabIndex={0}
      >
        <Box component="span" sx={{ display: "inline-flex", justifyContent: "center" }}>
          {alarming && (
            <WarningAmberIcon sx={{ color: "warning.main", display: "block", fontSize: 16 }} />
          )}
        </Box>
        <Box
          component="span"
          sx={{
            // The word stays at full contrast and only the icon carries the warning colour. The
            // accepted 3:1 status-colour bar covers a status LABEL, where the user-interface-component
            // rule applies; this is body text, where the bar is 4.5:1. See material_ui.md.
            textDecoration: "underline dotted",
            textUnderlineOffset: 3,
          }}
        >
          {LABEL[state]}
        </Box>
        {/* The explanation lives in the accessibility tree permanently rather than only while a
            tooltip happens to be open. A tooltip is a visual affordance; a screen-reader user
            should not have to trigger one to learn what a status word means. */}
        <Box
          component="span"
          id={describedBy}
          sx={{
            border: 0,
            clip: "rect(0 0 0 0)",
            height: "1px",
            m: "-1px",
            overflow: "hidden",
            p: 0,
            position: "absolute",
            whiteSpace: "nowrap",
            width: "1px",
          }}
        >
          {EXPLANATION[state]}
        </Box>
      </Box>
    </Tooltip>
  );
}
