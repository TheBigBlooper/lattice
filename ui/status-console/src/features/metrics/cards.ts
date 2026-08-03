import { POLL_INTERVAL_MS } from "../../api/polling.ts";
import { latest, type MetricSample, ratePerMinute, seriesKey, sumOf } from "./metrics.ts";

/** How a card's current value should read, so colour is never the only signal. */
export type Tone = "neutral" | "success" | "warning" | "error";

/** One card's current reading. */
export interface CardReading {
  /** The value as it should appear, already rounded and formatted. */
  display: string;
  /** The unit or qualifier beneath it, when the number needs one. */
  unit?: string | undefined;
  /** What the value means, carried as a palette role rather than a colour. */
  tone: Tone;
  /** The series whose history draws this card's trend, when it has one. */
  trendKey?: string | undefined;
}

/** One card on the Metrics view. */
export interface CardDefinition {
  /** Stable id, used as a React key and a test handle. */
  id: string;
  /** What the card is called. */
  label: string;
  /** What this card says about itself, in the console's established shape. */
  help: { shows: string; source: string; omits: string };
  /** Reads the card's current value from the merged samples and their history. */
  read: (
    samples: readonly MetricSample[],
    history: ReadonlyMap<string, readonly number[]>
  ) => CardReading;
}

/** The meters the cards read, named once so a rename cannot drift between card and key. */
const METER = {
  announcementsPublished: "lattice.mesh.announcements.published",
  elasticsearchErrors: "lattice.elasticsearch.operation.errors",
  elasticsearchOperation: "lattice.elasticsearch.operation",
  linkUp: "lattice.mesh.link.up",
  peerExpiries: "lattice.mesh.peer.expiries",
  peersKnown: "lattice.mesh.peers.known",
  peersReachable: "lattice.mesh.peers.reachable",
} as const;

/** The key a single-series meter's history is stored under, matching what the hook recorded. */
function keyFor(samples: readonly MetricSample[], name: string): string | undefined {
  const sample = samples.find((candidate) => candidate.name === name);
  return sample ? seriesKey(sample) : undefined;
}

/** Totals one statistic of a timer across every service and index reporting it. */
function timerStatistic(samples: readonly MetricSample[], name: string, statistic: string): number {
  return samples
    .filter((sample) => sample.name === name && sample.labels?.statistic === statistic)
    .reduce((total, sample) => total + sample.value, 0);
}

/**
 * The six cards, in the order they appear.
 *
 * <p>Declared here rather than inline in the view, so the view renders from one map and a seventh
 * card is a row in this table rather than another block of markup.
 */
export const CARDS: readonly CardDefinition[] = [
  {
    help: {
      omits: "whether peers can reach their own brokers",
      shows: "this baseline's connection to its own broker",
      source: "mesh-gateway",
    },
    id: "mesh-link",
    label: "Mesh link",
    read: (samples) => {
      const up = latest(samples, METER.linkUp);
      if (up === undefined) {
        return { display: "Unknown", tone: "neutral" };
      }
      return {
        display: up >= 1 ? "Up" : "Down",
        tone: up >= 1 ? "success" : "error",
        trendKey: keyFor(samples, METER.linkUp),
      };
    },
  },
  {
    help: {
      omits: "peers this baseline has never heard from",
      shows: "discovered peers still inside their time-to-live",
      source: "mesh-gateway",
    },
    id: "peers-reachable",
    label: "Peers reachable",
    read: (samples) => {
      const known = latest(samples, METER.peersKnown);
      const reachable = latest(samples, METER.peersReachable);
      if (known === undefined || reachable === undefined) {
        return { display: "Unknown", tone: "neutral" };
      }
      return {
        display: `${reachable} / ${known}`,
        tone: reachable === known ? "success" : "warning",
        trendKey: keyFor(samples, METER.peersReachable),
      };
    },
  },
  {
    help: {
      omits: "announcements peers publish about themselves",
      shows: "how often this baseline announces itself on the mesh",
      source: "mesh-gateway",
    },
    id: "announces",
    label: "Announces",
    read: (samples, history) => {
      const key = keyFor(samples, METER.announcementsPublished);
      const rate = key ? ratePerMinute(history.get(key) ?? [], POLL_INTERVAL_MS) : undefined;
      if (rate === undefined) {
        // One reading is a value, not a rate. Saying so beats printing a number with nothing
        // behind it on a card whose entire point is the movement.
        return { display: "-", tone: "neutral", unit: "waiting for a second reading" };
      }
      return {
        display: String(rate),
        tone: rate > 0 ? "success" : "warning",
        trendKey: key,
        unit: "per minute",
      };
    },
  },
  {
    help: {
      omits: "peers that have never announced",
      shows: "times a peer has crossed its time-to-live this session",
      source: "mesh-gateway",
    },
    id: "expiries",
    label: "Expiries",
    read: (samples) => {
      const total = sumOf(samples, METER.peerExpiries);
      return {
        display: String(total),
        tone: total > 0 ? "warning" : "success",
        trendKey: keyFor(samples, METER.peerExpiries),
      };
    },
  },
  {
    help: {
      omits: "queries this baseline's services never made",
      shows: "mean time this baseline's services wait on Elasticsearch",
      source: "orders and inventory",
    },
    id: "datastore",
    label: "Datastore",
    read: (samples) => {
      const count = timerStatistic(samples, METER.elasticsearchOperation, "count");
      const total = timerStatistic(samples, METER.elasticsearchOperation, "total");
      if (count === 0) {
        return { display: "-", tone: "neutral", unit: "no calls yet" };
      }
      return {
        display: String(Math.round((total / count) * 1000)),
        tone: "success",
        unit: "ms mean",
      };
    },
  },
  {
    help: {
      omits: "failures the services retried successfully",
      shows: "Elasticsearch calls that failed",
      source: "orders and inventory",
    },
    id: "failed-reads",
    label: "Failed reads",
    read: (samples) => {
      const total = sumOf(samples, METER.elasticsearchErrors);
      return {
        display: String(total),
        tone: total > 0 ? "error" : "success",
        trendKey: keyFor(samples, METER.elasticsearchErrors),
      };
    },
  },
];
