import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";
import { ENTER_DOWN, PANEL_HELP, PanelHeader, PanelHelp } from "../../shared/index.ts";
import { scopeForKind } from "./activity.ts";
import { ScopeIcon } from "./ScopeIcon.tsx";
import { TransitionIcon } from "./TransitionIcon.tsx";
import type { ActivityEntry } from "./useActivity.ts";

/** What each scope is called on a line. "This baseline" rather than "local", which names nothing. */
const SCOPE_LABELS = { local: "this baseline", mesh: "mesh" } as const;

/** What the log needs to render. */
export interface ActivityLogProps {
  /** What this console has noticed this session, newest first. */
  entries: ActivityEntry[];
}

/**
 * What changed on this baseline and on the mesh, newest first, in one timeline.
 *
 * <p><b>It exists because the rest of the screen only says what <em>is</em>.</b> A peer ageing out or
 * returning, a service falling over, Elasticsearch dropping to yellow - each is visible only to
 * someone who happens to be watching that row at the moment it changes.
 *
 * <p><b>One stream rather than two panels.</b> Keycloak dying, orders failing and a peer going
 * unreachable are usually one incident, not three, and a single timeline shows that in the order it
 * happened. Two panels would make an operator interleave them by eye at the worst moment, and a
 * filter would let the console silently stop showing things - a worse failure than a busy panel.
 * Each line carries its scope so the two halves stay tellable apart.
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
      <PanelHeader
        help={<PanelHelp content={PANEL_HELP.activity} label="Activity" />}
        label="Activity"
      />

      {entries.length === 0 ? (
        <Typography sx={{ color: "text.secondary", py: 1 }} variant="body2">
          Nothing has changed since this page was opened.
        </Typography>
      ) : (
        // The list scrolls, not the panel: the heading and the "this session" caveat stay visible,
        // because a log read without its caveat is read as a complete record.
        <Box
          aria-label="activity"
          component="ul"
          sx={{ flex: 1, listStyle: "none", m: 0, minHeight: 0, overflowY: "auto", p: 0 }}
        >
          {entries.map((entry) => (
            /* TWO LINES: where and when, then what.
               In one row the message began wherever the scope word ended, and the two scope words
               are very different widths - so on the one panel meant to be scanned down, nothing
               lined up. This panel is a 300px rail, so four elements ahead of the sentence left it
               wrapping under its own glyph anyway. */
            <Box
              component="li"
              key={entry.id}
              // Every row carries the entry animation rather than only the newest. A CSS animation
              // runs on mount, and rows are keyed by a stable id, so a poll that adds nothing
              // remounts nothing and replays nothing - where marking "the newest" explicitly would
              // re-fire on any render that reordered the list.
              sx={{
                borderBottom: 1,
                borderColor: "divider",
                py: 1,
                "&:last-of-type": { borderBottom: 0 },
                ...ENTER_DOWN,
              }}
            >
              <Box
                sx={{
                  alignItems: "center",
                  color: "text.secondary",
                  display: "flex",
                  gap: 0.75,
                }}
              >
                {/* The glyph this console already uses for each half of the system: the hub from
                    the mesh panel, the server mark from the app bar. Borrowing the symbol an
                    operator has already learned beats inventing a scope symbol for this panel
                    alone - and it is decorative, because the word beside it says the same thing. */}
                <ScopeIcon scope={scopeForKind(entry.kind)} />
                {/* The scope stays a WORD, not only a picture. The glyphs are hidden from assistive
                    technology, so the word is the only thing announcing which half of the system a
                    line belongs to. */}
                <Typography
                  sx={{ letterSpacing: "0.07em", textTransform: "uppercase" }}
                  variant="caption"
                >
                  {SCOPE_LABELS[scopeForKind(entry.kind)]}
                </Typography>
                {/* A clock time, not an age. Everything else on this screen answers "how long ago";
                    a log answers "in what order, and when" - and an age that keeps climbing makes
                    an operator do arithmetic to line two entries up against each other.

                    Pushed right so the times form one tabular column down the panel, which is what
                    lets two entries be lined up against each other at a glance. */}
                <Typography
                  sx={{ fontVariantNumeric: "tabular-nums", ml: "auto" }}
                  variant="caption"
                >
                  {entry.at.toLocaleTimeString(undefined, {
                    hour: "2-digit",
                    minute: "2-digit",
                    second: "2-digit",
                  })}
                </Typography>
              </Box>

              {/* The state glyph sits with the sentence it colours rather than in the meta line,
                  where it competed with two greys for the eye. */}
              <Box sx={{ alignItems: "flex-start", display: "flex", gap: 0.75, mt: 0.25 }}>
                <TransitionIcon kind={entry.kind} landing={entry.landing} size={16} />
                <Typography variant="body2">{entry.message}</Typography>
              </Box>
            </Box>
          ))}
        </Box>
      )}
    </Paper>
  );
}
