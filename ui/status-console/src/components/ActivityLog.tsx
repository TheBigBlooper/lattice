import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";
import type { ActivityEntry } from "../mesh/useMeshActivity.ts";
import { toneForTransition } from "../theme/tone.ts";

/** What the log needs to render. */
export interface ActivityLogProps {
  /** What this console has noticed this session, newest first. */
  entries: ActivityEntry[];
}

/**
 * What changed on the mesh, newest first.
 *
 * <p><b>It exists because the rest of the screen only says what <em>is</em>.</b> A peer ageing out or
 * returning is the most operationally interesting thing that happens here, and without this it is
 * visible only to someone who happens to be watching the row at the moment its age stops climbing.
 *
 * <p><b>It says plainly that it is session-scoped.</b> The log is blind to anything that happened
 * before the tab was opened, and two tabs keep two independent lists. An operator who believed this
 * was a complete record would draw a confident conclusion from a partial one, so the panel says what
 * it is rather than leaving the gap to be discovered during an incident.
 *
 * <p>Each line carries a coloured dot <em>and</em> its own words: a status surface that distinguishes
 * states by hue alone is unreadable to a colour-blind operator.
 *
 * @param props the entries to show.
 * @returns the activity panel.
 */
export function ActivityLog({ entries }: ActivityLogProps) {
  return (
    <Paper sx={{ display: "flex", flexDirection: "column", height: "100%", p: 2 }}>
      <Box
        sx={{
          alignItems: "baseline",
          borderBottom: 1,
          borderColor: "divider",
          display: "flex",
          gap: 1,
          mb: 1,
          pb: 1,
        }}
      >
        <Typography component="h2" variant="subtitle1">
          Activity
        </Typography>
        <Typography sx={{ color: "text.secondary", ml: "auto" }} variant="caption">
          this session
        </Typography>
      </Box>

      {entries.length === 0 ? (
        <Typography sx={{ color: "text.secondary", py: 1 }} variant="body2">
          Nothing has changed since this page was opened.
        </Typography>
      ) : (
        // The list scrolls, not the panel: the heading and the "this session" caveat stay visible,
        // because a log read without its caveat is read as a complete record.
        <Box
          aria-label="mesh activity"
          component="ul"
          sx={{ flex: 1, listStyle: "none", m: 0, minHeight: 0, overflowY: "auto", p: 0 }}
        >
          {entries.map((entry) => (
            <Box
              component="li"
              key={entry.id}
              sx={{
                alignItems: "flex-start",
                borderBottom: 1,
                borderColor: "divider",
                display: "flex",
                gap: 1,
                py: 1,
                "&:last-of-type": { borderBottom: 0 },
              }}
            >
              <Box
                sx={{
                  bgcolor: toneForTransition(entry.kind),
                  borderRadius: "50%",
                  flexShrink: 0,
                  height: 8,
                  mt: 0.75,
                  width: 8,
                }}
              />
              {/* A clock time, not an age. Everything else on this screen answers "how long ago";
                  a log answers "in what order, and when" - and an age that keeps climbing makes an
                  operator do arithmetic to line two entries up against each other. */}
              <Typography
                sx={{ color: "text.secondary", fontVariantNumeric: "tabular-nums" }}
                variant="caption"
              >
                {entry.at.toLocaleTimeString(undefined, {
                  hour: "2-digit",
                  minute: "2-digit",
                  second: "2-digit",
                })}
              </Typography>
              <Typography variant="body2">{entry.message}</Typography>
            </Box>
          ))}
        </Box>
      )}
    </Paper>
  );
}
