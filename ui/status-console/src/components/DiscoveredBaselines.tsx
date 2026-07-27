import type { Peer } from "../api/usePeers.ts";
import { leading, type Palette, radius, scale, type as typeScale } from "../theme/tokens.ts";
import { toneForHealth } from "../theme/tone.ts";
import { StatusIcon } from "./StatusIcon.tsx";

/** What the panel needs to render the mesh around this baseline. */
export interface DiscoveredBaselinesProps {
  /** The peers this baseline has discovered, as its own registry holds them. */
  peers: Peer[];
  /** The active palette. */
  palette: Palette;
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
 * @param props the discovered peers and the palette to render them with.
 * @returns the mesh panel.
 */
export function DiscoveredBaselines({ peers, palette }: DiscoveredBaselinesProps) {
  const reachable = peers.filter((peer) => peer.reachability === "REACHABLE").length;
  // Read once per render rather than per row, so every age on screen is measured from one instant
  // and two rows cannot disagree about what "now" was.
  const now = Date.now();

  return (
    <section aria-label="discovered baselines">
      <div
        style={{
          alignItems: "baseline",
          borderBottom: `1px solid ${palette.border}`,
          display: "flex",
          flexWrap: "wrap",
          gap: scale.sm,
          justifyContent: "space-between",
          margin: `0 0 ${scale.md}px`,
          padding: `0 0 ${scale.md}px`,
        }}
      >
        <span
          style={{
            color: meshTone(reachable, peers.length, palette),
            fontSize: typeScale.section,
            lineHeight: leading.verdict,
          }}
        >
          {peers.length === 0
            ? "No peers discovered"
            : `${reachable} of ${peers.length} peers reachable`}
        </span>
        {peers.length > 0 && (
          <span style={{ color: palette.textSecondary, fontSize: typeScale.meta }}>
            polled from this baseline
          </span>
        )}
      </div>

      {peers.length === 0 ? (
        <p
          style={{
            color: palette.textSecondary,
            fontSize: typeScale.body,
            lineHeight: leading.body,
            margin: 0,
          }}
        >
          Nothing has announced itself on the mesh yet.
        </p>
      ) : (
        <ul aria-label="peers" style={{ listStyle: "none", margin: 0, padding: 0 }}>
          {peers.map((peer, index) => (
            <PeerRow
              key={peer.clusterId}
              isFirst={index === 0}
              now={now}
              palette={palette}
              peer={peer}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

/** What one row needs. */
interface PeerRowProps {
  /** The discovered baseline this row reports. */
  peer: Peer;
  /** The instant every age on this render is measured against. */
  now: number;
  /** Whether this is the first row, which carries no separating rule above it. */
  isFirst: boolean;
  /** The active palette. */
  palette: Palette;
}

/**
 * One discovered baseline: its glyph, its identity and region, its state, and how long ago it was
 * heard.
 *
 * A silent peer states `unreachable` and carries its last-known health beneath, so the row reports
 * both facts at once: the baseline has gone quiet, and this is what it said before it did. Both are
 * words rather than colours, because a status console that distinguishes states by hue alone is
 * unreadable to a colour-blind operator.
 */
function PeerRow({ peer, now, isFirst, palette }: PeerRowProps) {
  const silent = peer.reachability === "UNREACHABLE";
  const tone = silent ? palette.textSecondary : toneForHealth(peer.health, palette);

  return (
    <li
      style={{
        alignItems: "baseline",
        borderTop: isFirst ? "none" : `1px solid ${palette.border}`,
        display: "grid",
        gap: scale.md,
        gridTemplateColumns: `${typeScale.body}px minmax(0, 1fr) auto auto`,
        padding: `${scale.sm}px 0`,
      }}
    >
      <span style={{ color: tone, lineHeight: 1 }}>
        <StatusIcon size={typeScale.body} tone={silent ? "degraded" : peer.health} />
      </span>

      <span style={{ fontSize: typeScale.body }}>
        <span style={{ color: silent ? palette.textSecondary : palette.textPrimary }}>
          {peer.clusterId}
        </span>
        <span
          style={{ color: palette.textSecondary, fontSize: typeScale.meta, marginLeft: scale.sm }}
        >
          {peer.region}
        </span>
      </span>

      <span style={{ fontSize: typeScale.meta, textAlign: "right" }}>
        {silent ? (
          <>
            <span
              style={{
                border: `1px solid ${palette.textSecondary}`,
                borderRadius: radius.pill,
                color: palette.textSecondary,
                padding: `1px ${scale.xs}px`,
                whiteSpace: "nowrap",
              }}
            >
              unreachable
            </span>
            <span
              style={{
                color: palette.textSecondary,
                display: "block",
                marginTop: scale.xs,
                whiteSpace: "nowrap",
              }}
            >
              last known: {peer.health}
            </span>
          </>
        ) : (
          <span style={{ color: tone }}>{peer.health}</span>
        )}
      </span>

      <span
        style={{
          color: palette.textSecondary,
          fontSize: typeScale.meta,
          fontVariantNumeric: "tabular-nums",
          textAlign: "right",
          whiteSpace: "nowrap",
        }}
      >
        {formatAge(peer.lastSeen, now)}
      </span>
    </li>
  );
}

/**
 * Colours the mesh rollup.
 *
 * A mesh with every peer reachable reads as ready, one with some reachable as degraded, and one
 * with none as down - the same three-state vocabulary the cluster verdict uses, so an operator
 * learns it once. An empty mesh is neutral rather than alarming: having discovered nobody is a
 * cold-start fact, not a fault.
 */
function meshTone(reachable: number, total: number, palette: Palette): string {
  if (total === 0) {
    return palette.textSecondary;
  }
  if (reachable === total) {
    return palette.statusReady;
  }
  return reachable === 0 ? palette.statusDown : palette.statusDegraded;
}
