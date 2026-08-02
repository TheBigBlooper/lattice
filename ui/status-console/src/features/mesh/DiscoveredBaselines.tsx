import HubIcon from "@mui/icons-material/Hub";
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
import type { components } from "../../api/generated/v1.ts";
import type { Peer } from "../../api/usePeers.ts";
import {
  FLUSH,
  PANEL_HELP,
  PanelHeader,
  PanelHelp,
  PanelRollup,
  StatusIcon,
} from "../../shared/index.ts";
import { type ClusterHealth, toneForHealth } from "../../theme/tone.ts";

/** What the panel needs to render the mesh around this baseline. */
export interface DiscoveredBaselinesProps {
  /** The peers this baseline has discovered, as its own registry holds them. */
  peers: Peer[];
  /**
   * Whether this baseline can currently reach the mesh at all. Absent on a gateway built before the
   * field existed, which is read as `up` - the same as every render before this signal was added.
   */
  meshLink?: MeshLinkState;
}

/** Whether this baseline can reach the mesh, as its own gateway reports it. */
type MeshLinkState = components["schemas"]["MeshLinkState"];

/** Seconds in a minute and in an hour, so the age formatter reads in units rather than numbers. */
const SECONDS_PER_MINUTE = 60;
const SECONDS_PER_HOUR = 3600;

/**
 * The most recent announcement across every peer, as the instant this snapshot was taken.
 *
 * The newest rather than the oldest: the link went down after the last thing this baseline
 * successfully heard, so the freshest row is the closest thing to the moment the mesh stopped
 * arriving. Falls back to now when nothing has been heard at all, which only happens with no peers.
 */
function latestSeen(peers: Peer[], now: number): string {
  const newest = peers.reduce(
    (latest, peer) => Math.max(latest, Date.parse(peer.lastSeen)),
    Number.NEGATIVE_INFINITY
  );
  return Number.isFinite(newest) ? new Date(newest).toISOString() : new Date(now).toISOString();
}

/**
 * The rollup line: the question this panel exists to answer, in one sentence.
 *
 * **Cut off, it stops counting.** "0 of 2 peers reachable" is not a measurement when the instrument
 * is broken - it is a claim about baselines this console has no evidence about, which are most
 * likely up and talking to each other. What it can honestly report is how many it knows of and how
 * long ago it last heard anything, so that is what it says.
 */
function rollupLabel(peers: Peer[], reachable: number, cutOff: boolean, now: number): string {
  if (peers.length === 0) {
    return "No peers discovered";
  }
  if (!cutOff) {
    return `${reachable} of ${peers.length} Peers Reachable`;
  }
  const known = `${peers.length} ${peers.length === 1 ? "baseline" : "baselines"}`;
  return `${known}, last heard ${formatAge(latestSeen(peers, now), now)} ago`;
}

/**
 * A wall-clock time, for the one label that must not be relative.
 *
 * Every other age on this panel answers "how long ago", which is the right question while data is
 * arriving. Once it has stopped, the question changes to "when was this true", and answering that
 * from a relative age makes an operator do subtraction during an incident.
 */
function clockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

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
export function DiscoveredBaselines({ peers, meshLink = "up" }: DiscoveredBaselinesProps) {
  const reachable = peers.filter((peer) => peer.reachability === "REACHABLE").length;
  // Read once per render rather than per row, so every age on screen is measured from one instant
  // and two rows cannot disagree about what "now" was.
  const now = Date.now();
  const cutOff = meshLink === "down";

  return (
    <Box aria-label="discovered baselines" component="section">
      <PanelHeader
        help={<PanelHelp content={PANEL_HELP.mesh} label="Discovered Mesh" />}
        label="Discovered Mesh"
      />

      {/*
        Beneath the header rather than above it. Every other panel opens with its own name, and this
        was the one that did not once the link dropped - so the panel's identity moved down the
        screen at exactly the moment an operator was scanning for it. It still leads the panel's
        contents, which is what makes being cut off read at a glance.
      */}
      {cutOff && (
        <Box
          sx={{
            alignItems: "center",
            bgcolor: "warning.main",
            borderRadius: 1,
            color: "warning.contrastText",
            display: "flex",
            flexWrap: "wrap",
            gap: 1,
            mb: 2,
            px: 1.5,
            py: 0.75,
          }}
        >
          <Typography component="span" sx={{ fontWeight: 500 }} variant="body2">
            Mesh link down · snapshot
          </Typography>
          {/*
            A clock time rather than another relative age. Everything else on this panel answers
            "how long ago", and an operator deciding whether to act needs the one thing that does
            not: when this was last true.
          */}
          <Typography
            component="span"
            sx={{ fontVariantNumeric: "tabular-nums", ml: "auto" }}
            variant="caption"
          >
            as of {clockTime(latestSeen(peers, now))}
          </Typography>
        </Box>
      )}

      {/* The same rollup object the verdict and the infrastructure card use, configured rather than
          redrawn - a glyph, a headline and a count. Its glyph is the mesh rather than a state,
          because what this panel rolls up is a reach rather than a health, and its headline is a
          sentence for the same reason: it is not capitalised, because it is not a state word. */}
      <PanelRollup
        count={countLine(peers, reachable, cutOff)}
        icon={<HubIcon sx={{ color: "inherit", fontSize: 20 }} />}
        label={rollupLabel(peers, reachable, cutOff, now)}
        tone={cutOff ? "degraded" : meshState(reachable, peers.length)}
      />

      {peers.length === 0 ? (
        <Typography sx={{ color: "text.secondary" }} variant="body2">
          Nothing has announced itself on the mesh yet.
        </Typography>
      ) : (
        // The edge cells lose their horizontal padding so the first column starts on the same line
        // as the rail's rows opposite. A Material table indents inside a panel that already has
        // padding, which put the two lists at different left edges for no reason.
        <Table aria-label="peers" sx={FLUSH}>
          <TableHead>
            <TableRow>
              <TableCell>Baseline</TableCell>
              <TableCell>Region</TableCell>
              <TableCell>{cutOff ? "Last Known State" : "State"}</TableCell>
              <TableCell>Version</TableCell>
              <TableCell align="right">Last Heard</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {peers.map((peer) => (
              <PeerRow key={peer.clusterId} cutOff={cutOff} now={now} peer={peer} />
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
  /** Whether this baseline is cut off, in which case the row reports memory rather than state. */
  cutOff: boolean;
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
function PeerRow({ peer, now, cutOff }: PeerRowProps) {
  // Only a peer this baseline could have heard from is reported silent. Cut off, every peer looks
  // silent for a reason that has nothing to do with the peer, so the row states its last-known
  // health plainly instead of an "unreachable" this console cannot stand behind.
  const silent = !cutOff && peer.reachability === "UNREACHABLE";

  return (
    <TableRow sx={silent ? { opacity: 0.7 } : undefined}>
      <TableCell>{peer.clusterId}</TableCell>
      <TableCell sx={{ color: "text.secondary" }}>{peer.region}</TableCell>
      <TableCell>
        {silent ? (
          <Box sx={{ alignItems: "center", display: "flex", gap: 1 }}>
            <Chip label="Unreachable" variant="outlined" />
            <Typography component="span" sx={{ color: "text.secondary" }} variant="caption">
              last known:{" "}
              <Box component="span" sx={{ textTransform: "capitalize" }}>
                {peer.health}
              </Box>
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
            <Box component="span" sx={{ textTransform: "capitalize" }}>
              {peer.health}
            </Box>
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
 * The state the mesh rollup reports, in the same vocabulary every other panel speaks.
 *
 * <p>It returns a STATE rather than a colour, which is what lets the shared rollup draw it: one
 * switch decides what `degraded` looks like, and a second one here would drift from it the first
 * time a status colour was tuned - on a status console, two panels showing one state in two colours
 * is a correctness problem rather than a cosmetic one.
 *
 * <p>A mesh with no peers is neither healthy nor broken: nothing has announced itself, which is the
 * ordinary state of the first baseline to start.
 */
function meshState(reachable: number, total: number): ClusterHealth {
  if (total === 0) {
    return "unknown" as ClusterHealth;
  }
  if (reachable === total) {
    return "ready";
  }
  return reachable === 0 ? "down" : "degraded";
}

/**
 * The count beneath the mesh rollup, matching the line the other two panels carry.
 *
 * <p>Cut off, it says what an operator needs before acting rather than counting: every peer looks
 * silent for a reason that has nothing to do with any peer.
 */
function countLine(peers: Peer[], reachable: number, cutOff: boolean): string | undefined {
  if (cutOff) {
    return "This baseline is cut off. Their current state is unknown.";
  }
  if (peers.length === 0) {
    return undefined;
  }
  return `${reachable} reachable · ${peers.length - reachable} silent`;
}
