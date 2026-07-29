import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { type ClusterHealth, toneForHealth } from "../theme/tone.ts";
import { StatusIcon } from "./StatusIcon.tsx";

/** One thing this baseline runs, and what it is currently doing. */
export interface StatusRowProps {
  /** What the row is about: a service's name, or whatever a deployment calls a component. */
  name: string;
  /**
   * The state, already translated into the console's shared three-word vocabulary. The caller
   * translates rather than the row, because the vocabularies belong to the reporting systems.
   */
  state: ClusterHealth;
  /** The reading the reporting system gave, in its own language. Omitted when there is none. */
  detail?: string;
}

/**
 * The word each state is said with. A row describes one thing rather than a rollup of many, so
 * `ready` reads as "up" here: a datastore is up, and only a cluster of them is ready.
 */
const WORDS: Record<ClusterHealth, string> = {
  ready: "up",
  degraded: "degraded",
  down: "down",
};

/**
 * One thing on its own line: glyph, name, an optional reading, and the state.
 *
 * <p><b>One row serves both lists.</b> The services breakdown and the infrastructure list ask for
 * exactly the same shape, and a second row component beside this one would drift the first time a
 * state colour or a border was tuned - leaving one baseline's `down` drawn differently from
 * another's, on the surface whose whole job is to be compared at a glance. What made a second one
 * look necessary was this row being hardcoded to a binary; taking a state rather than a raw
 * readiness removes the reason.
 *
 * <p><b>A row rather than a pill, because this list grows.</b> Wrapped pills become a cloud where
 * nothing aligns and the one that is down sits somewhere in the middle, so finding it means reading
 * all of them. Rows put every state in one right-hand column, which is where the eye goes for the
 * only question these lists are asked: which one is wrong.
 *
 * <p><b>The detail is the row's second channel, not its state.</b> The coarse state is what the
 * console colours and counts; the reading beside it is what an operator reads to learn why a
 * component is unhappy, in the language of the system that produced it. A service never carries
 * one, and nothing is drawn in its place, so a list of services keeps no column of blanks.
 *
 * <p>The state is a word as well as a colour and a shape. A status surface that distinguishes
 * states by hue alone is unreadable to a colour-blind operator. A healthy row still says its word
 * quietly: the right-hand column can only answer "which one is wrong" if the rows that are right do
 * not shout as loudly, and the glyph carries the healthy colour regardless.
 *
 * @param props the thing, its state, and its own reading where it has one.
 * @returns the row.
 */
export function StatusRow({ name, state, detail }: StatusRowProps) {
  const tone = toneForHealth(state);

  return (
    <Box
      component="li"
      sx={{
        alignItems: "center",
        borderColor: "divider",
        borderTop: 1,
        display: "flex",
        gap: 1,
        py: 0.75,
        "&:first-of-type": { borderTop: 0 },
      }}
    >
      {/* The glyph takes the state's colour, so one decision per state covers the whole row. */}
      <Box sx={{ color: tone, display: "flex" }}>
        <StatusIcon size={16} tone={state} />
      </Box>
      <Typography variant="body2">{name}</Typography>
      {/* Truncated rather than wrapped: the rail is narrow, and a reading that pushed the state
          word onto a second line would break the one column this list is scanned down. The full
          text stays reachable on hover for the case where it matters. */}
      {detail && (
        <Typography
          sx={{
            color: "text.secondary",
            minWidth: 0,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
          title={detail}
          variant="caption"
        >
          {detail}
        </Typography>
      )}
      <Typography
        sx={{
          color: state === "ready" ? "text.secondary" : tone,
          letterSpacing: "0.04em",
          ml: "auto",
          pl: 1,
          textTransform: "uppercase",
        }}
        variant="caption"
      >
        {WORDS[state]}
      </Typography>
    </Box>
  );
}
