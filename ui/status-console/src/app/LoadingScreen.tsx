import Box from "@mui/material/Box";
import CircularProgress from "@mui/material/CircularProgress";

/** What the loading screen needs to announce itself. */
export interface LoadingScreenProps {
  /** What is being waited on, as the spinner's accessible name. */
  label: string;
}

/** The height the screen fills, so the spinner sits centred rather than pinned to the top. */
const SCREEN_MIN_HEIGHT = "70vh";

/**
 * The screen shown while the console is waiting on something before it can render.
 *
 * **A spinner rather than a sentence.** Words here are gone before they can be finished and read as
 * a flash of something going wrong rather than as information. The label carries the meaning for a
 * screen reader, where a spinner alone would say nothing at all.
 *
 * **One component because there are two waits, back to back.** Starting the console asks the
 * provider whether a session exists, then reads the baseline. Each wait sizing its own container is
 * what made the spinner jump position between them - the page appearing to flinch rather than load.
 * Sharing this makes the position identical by construction rather than by two files agreeing.
 *
 * @param props what is being waited on.
 * @returns the loading screen.
 */
export function LoadingScreen({ label }: LoadingScreenProps) {
  return (
    <Box
      sx={{
        alignItems: "center",
        display: "flex",
        justifyContent: "center",
      }}
      // Inline rather than in sx, for the same reason StatusBlock reserves its height inline: this
      // is the guarantee that both waits occupy the same space, and inline is the one form that can
      // be read back directly to prove the two still agree.
      style={{ minHeight: SCREEN_MIN_HEIGHT }}
    >
      <CircularProgress aria-label={label} />
    </Box>
  );
}
