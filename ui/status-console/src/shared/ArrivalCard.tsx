import BlockIcon from "@mui/icons-material/Block";
import CloudOffIcon from "@mui/icons-material/CloudOff";
import LoginIcon from "@mui/icons-material/Login";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";
import type { ReactNode } from "react";

/** Which arrival this is, which decides the glyph and the tone. */
export type ArrivalState = "neutral" | "warning" | "error";

/** What the card needs to hold. */
export interface ArrivalCardProps {
  /** Which arrival this is. Signing in is neutral; being refused and being unable to read are not. */
  state?: ArrivalState;
  /** The heading. The baseline where the screen is about the baseline, the outcome where it is not. */
  title: string;
  /** The identity line beneath it, when this screen has one to show. */
  identity?: string;
  /** The screen's own message and actions, beneath the identity. */
  children: ReactNode;
}

/** The height the screen fills, so the card sits centred rather than pinned to the top. */
const SCREEN_MIN_HEIGHT = "70vh";

/** The glyph per arrival. Three distinct silhouettes, so the state is legible before it is read. */
const GLYPHS = { neutral: LoginIcon, warning: BlockIcon, error: CloudOffIcon } as const;

/** The palette path per arrival. Returned as a path so the active mode resolves it, never a colour. */
const TONES = {
  neutral: "text.secondary",
  warning: "warning.main",
  error: "error.main",
} as const;

/**
 * The centred card every arrival screen renders into.
 *
 * <p><b>Why the three share an object rather than a resemblance.</b> Signing in, being refused and
 * being unable to read are the same moment in an operator's journey - the first thing they see
 * after following a redirect to a peer - and they must look like one product deciding three things,
 * not three screens built at different times. They were: the second line meant the product name on
 * one, the baseline on another, and nothing at all on the third.
 *
 * <p><b>The shell owns identity, which is what stops them drifting again.</b> The glyph, the
 * heading, the identity line and the rhythm are decided once here rather than per screen. A screen
 * supplies its message and its actions, and cannot spell the shared part differently.
 *
 * <p><b>The state is carried three ways, never by colour alone.</b> Each arrival renders its own
 * glyph, its own tone, and a heading that says the outcome in words - the console's rule everywhere
 * else, which the arrival screens were the one place not to follow.
 *
 * <p>The glyph is decorative to assistive technology: the heading beneath it already says what it
 * says, and announcing both would say it twice. The card is a status region, so a screen replaced
 * under a screen-reader user is announced rather than silently swapped.
 *
 * @param props the arrival's state, heading, optional identity line, and contents.
 * @returns the shared card.
 */
export function ArrivalCard({ state = "neutral", title, identity, children }: ArrivalCardProps) {
  const Glyph = GLYPHS[state];

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
        <Glyph aria-hidden sx={{ color: TONES[state], fontSize: 40 }} />

        <Typography
          sx={{ color: state === "neutral" ? "text.primary" : TONES[state], mt: 2 }}
          variant="h5"
        >
          {title}
        </Typography>

        {identity && <Chip label={identity} sx={{ mt: 1 }} variant="outlined" />}

        {children}
      </Paper>
    </Box>
  );
}
