import LaunchIcon from "@mui/icons-material/Launch";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import IconButton from "@mui/material/IconButton";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import type { Peer } from "../api/usePeers.ts";
import { toneForHealth } from "../theme/tone.ts";
import { StatusIcon } from "./StatusIcon.tsx";

/** What the panel needs to render the mesh around this baseline. */
export interface DiscoveredBaselinesProps {
  /** The peers this baseline has discovered, as its own registry holds them. */
  peers: Peer[];
}

/** Seconds in a minute and in an hour, so the age formatter reads in units rather than numbers. */
const SECONDS_PER_MINUTE = 60;
const SECONDS_PER_HOUR = 3600;

/**
 * Renders how long ago an announcement was heard, coarsening as it ages.
 *
 * The unit shrinks as the value grows on purpose: at four seconds the seconds matter, and at four
 * hours they are noise. A raw timestamp was rejected because the question an operator is asking is
 * "is this current?", and answering it from a clock time makes them do the subtraction.
 */
function formatAge(lastSeen: string, now: number): string {
  const elapsed = Math.max(0, Math.floor((now - Date.parse(lastSeen)) / 1000));
  if (elapsed < SECONDS_PER_MINUTE) {
    return `${elapsed}s`;
  }
  if (elapsed < SECONDS_PER_HOUR) {
    const minutes = Math.floor(elapsed / SECONDS_PER_MINUTE);
    return `${minutes}m ${elapsed % SECONDS_PER_MINUTE}s`;
  }
  const hours = Math.floor(elapsed / SECONDS_PER_HOUR);
  return `${hours}h ${Math.floor((elapsed % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)}m`;
}

/**
 * The mesh around this baseline: a rollup of how many peers are reachable, then one row per
 * discovered baseline.
 *
 * **The rollup is the reason the panel exists.** Without it, "is the mesh healthy?" is a question an
 * operator answers by counting rows. It is also the one derived value on the screen: nothing serves
 * it, so the console counts it. That is a deliberate and bounded exception to the rule that this
 * console renders verdicts rather than computing them - counting reachable peers is arithmetic over
 * a field the registry already sets, not a second definition of what `degraded` means. Health itself
 * is still never recomputed; each peer's is rendered exactly as that peer announced it.
 *
 * **A silent peer is kept, not dropped.** Removing the row would render "this baseline went quiet"
 * as "this baseline was never here", which is the most misleading thing this panel could do. It is
 * retained with the health it last announced, explicitly labelled as last-known so the stale value
 * cannot be mistaken for a current one.
 *
 * **Peers are a table rather than a list.** The data is genuinely tabular - five facts about each of
 * N baselines - and a table gives a screen-reader user the column name alongside each value, which a
 * list of rows cannot. It also lets an operator compare peers down a column rather than card by
 * card, and it is the only shape here that survives a mesh of a dozen baselines unchanged.
 *
 * @param props the discovered peers.
 * @returns the mesh panel.
 */
export function DiscoveredBaselines({ peers }: DiscoveredBaselinesProps) {
  const reachable = peers.filter((peer) => peer.reachability === "REACHABLE").length;
  // Read once per render rather than per row, so every age on screen is measured from one instant
  // and two rows cannot disagree about what "now" was.
  const now = Date.now();

  return (
    <Box aria-label="discovered baselines" component="section">
      <Box
        sx={{
          alignItems: "baseline",
          borderBottom: 1,
          borderColor: "divider",
          display: "flex",
          flexWrap: "wrap",
          gap: 1,
          justifyContent: "space-between",
          mb: 2,
          pb: 1,
        }}
      >
        <Typography component="span" sx={{ color: meshTone(reachable, peers.length) }} variant="h6">
          {peers.length === 0
            ? "No peers discovered"
            : `${reachable} of ${peers.length} peers reachable`}
        </Typography>
        {peers.length > 0 && (
          <Typography component="span" sx={{ color: "text.secondary" }} variant="caption">
            polled from this baseline
          </Typography>
        )}
      </Box>

      {peers.length === 0 ? (
        <Typography sx={{ color: "text.secondary" }} variant="body2">
          Nothing has announced itself on the mesh yet.
        </Typography>
      ) : (
        <Table aria-label="peers">
          <TableHead>
            <TableRow>
              <TableCell>Baseline</TableCell>
              <TableCell>Region</TableCell>
              <TableCell>State</TableCell>
              <TableCell>Version</TableCell>
              <TableCell align="right">Last heard</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {peers.map((peer) => (
              <PeerRow key={peer.clusterId} now={now} peer={peer} />
            ))}
          </TableBody>
        </Table>
      )}
    </Box>
  );
}

/** What one row needs. */
interface PeerRowProps {
  /** The discovered baseline this row reports. */
  peer: Peer;
  /** The instant every age on this render is measured against. */
  now: number;
}

/**
 * One discovered baseline: its identity, where it runs, its state, its version, and how long ago it
 * was heard.
 *
 * A silent peer states `unreachable` and carries its last-known health beside it, so the row reports
 * both facts at once: the baseline has gone quiet, and this is what it said before it did. Both are
 * words rather than colours, because a status console that distinguishes states by hue alone is
 * unreadable to a colour-blind operator.
 */
function PeerRow({ peer, now }: PeerRowProps) {
  const silent = peer.reachability === "UNREACHABLE";

  return (
    <TableRow sx={silent ? { opacity: 0.7 } : undefined}>
      <TableCell>{peer.clusterId}</TableCell>
      <TableCell sx={{ color: "text.secondary" }}>{peer.region}</TableCell>
      <TableCell>
        {silent ? (
          <Box sx={{ alignItems: "center", display: "flex", gap: 1 }}>
            <Chip label="unreachable" variant="outlined" />
            <Typography component="span" sx={{ color: "text.secondary" }} variant="caption">
              last known: {peer.health}
            </Typography>
          </Box>
        ) : (
          <Box
            sx={{
              alignItems: "center",
              color: toneForHealth(peer.health),
              display: "flex",
              gap: 0.5,
            }}
          >
            <StatusIcon size={16} tone={peer.health as "ready" | "degraded" | "down"} />
            {peer.health}
          </Box>
        )}
      </TableCell>
      <TableCell sx={{ color: "text.secondary", fontVariantNumeric: "tabular-nums" }}>
        {peer.baselineVersion}
      </TableCell>
      <TableCell align="right" sx={{ fontVariantNumeric: "tabular-nums" }}>
        {formatAge(peer.lastSeen, now)}
      </TableCell>
      <TableCell align="right" padding="none">
        {/* The tooltip repeats the accessible name rather than adding to it, deliberately. An icon
            with no text left sighted operators reading the URL in the browser's status bar to work
            out where the control went - the name was there all along, only announced to screen
            readers. One string, two audiences. */}
        {!silent && (
          <Tooltip title={`Go to ${peer.clusterId}'s console`}>
            <IconButton
              aria-label={`Go to ${peer.clusterId}'s console`}
              color="primary"
              component="a"
              href={redirectTo(peer.consoleUrl)}
              size="small"
            >
              <LaunchIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
      </TableCell>
    </TableRow>
  );
}

/**
 * The address of a peer's console, carrying the origin this operator is arriving from.
 *
 * The parameter exists so the peer can offer a way back if it refuses them, which under
 * deliberately unsynchronized realm membership is an ordinary outcome rather than a fault. It is a
 * hint and nothing more: the peer confirms it against the browser's referrer before acting on it,
 * because a parameter alone would be an open redirect.
 *
 * A malformed `consoleUrl` is returned untouched rather than thrown away. It came from the peer's
 * own announcement, and a link that visibly fails is more diagnosable than a row that quietly lost
 * its action.
 */
function redirectTo(consoleUrl: string): string {
  try {
    const target = new URL(consoleUrl);
    target.searchParams.set("from", location.origin);
    return target.toString();
  } catch {
    return consoleUrl;
  }
}

/**
 * Colours the mesh rollup.
 *
 * A mesh with every peer reachable reads as ready, one with some reachable as degraded, and one
 * with none as down - the same three-state vocabulary the cluster verdict uses, so an operator
 * learns it once. An empty mesh is neutral rather than alarming: having discovered nobody is a
 * cold-start fact, not a fault.
 */
function meshTone(reachable: number, total: number): string {
  if (total === 0) {
    return "text.secondary";
  }
  if (reachable === total) {
    return "success.main";
  }
  return reachable === 0 ? "error.main" : "warning.main";
}
