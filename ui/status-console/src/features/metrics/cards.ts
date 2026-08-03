import type { SvgIconComponent } from "@mui/icons-material";
import CampaignIcon from "@mui/icons-material/Campaign";
import HourglassEmptyIcon from "@mui/icons-material/HourglassEmpty";
import HubIcon from "@mui/icons-material/Hub";
import LanIcon from "@mui/icons-material/Lan";
import ReportProblemIcon from "@mui/icons-material/ReportProblem";
import StorageIcon from "@mui/icons-material/Storage";
import { POLL_INTERVAL_MS } from "../../api/polling.ts";
import type { PANEL_HELP } from "../../shared/panelHelpContent.ts";
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
  /** The series whose history draws this card's trend, when one series is the whole answer. */
  trendKey?: string | undefined;
  /**
   * Builds the trend where no single series holds it.
   *
   * <p>The datastore mean is total time over call count, so its history is two series divided
   * element by element rather than one series read directly. They are appended in lockstep on each
   * poll, which is what makes the division valid position by position.
   */
  trendFrom?: ((history: ReadonlyMap<string, readonly number[]>) => number[]) | undefined;
}

/** One card on the Metrics view. */
export interface CardDefinition {
  /** Stable id, used as a React key and a test handle. */
  id: string;
  /** What the card is called. */
  label: string;
  /**
   * Which entry in the shared help copy explains this card.
   *
   * <p>A key rather than the copy itself: the console keeps every panel's help together so it reads
   * as one voice rather than as six people describing six numbers.
   */
  helpKey: keyof typeof PANEL_HELP;
  /** The glyph beside the value, so a card is recognisable before it is read. */
  icon: SvgIconComponent;
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
 * The mean Elasticsearch latency at each poll, in milliseconds.
 *
 * <p>No single series holds this: the timer publishes a call count and a total time, and the mean
 * is one divided by the other. Both are appended on every poll, so position `n` of each belongs to
 * the same reading - which is what makes dividing them position by position sound rather than a
 * coincidence of array lengths.
 *
 * @param history the recorded readings per series.
 * @param samples the current samples, used to find which series to read.
 * @returns the mean in milliseconds per poll, oldest first.
 */
function meanLatencyHistory(
  history: ReadonlyMap<string, readonly number[]>,
  samples: readonly MetricSample[]
): number[] {
  const seriesFor = (statistic: string) =>
    samples
      .filter(
        (sample) =>
          sample.name === METER.elasticsearchOperation && sample.labels?.statistic === statistic
      )
      .map((sample) => history.get(seriesKey(sample)) ?? []);

  const counts = seriesFor("count");
  const totals = seriesFor("total");
  const length = Math.min(...counts.map((series) => series.length), ...totals.map((s) => s.length));
  if (!Number.isFinite(length) || length < 2) {
    return [];
  }

  const means: number[] = [];
  for (let at = 0; at < length; at += 1) {
    const calls = counts.reduce((sum, series) => sum + (series[at] ?? 0), 0);
    const seconds = totals.reduce((sum, series) => sum + (series[at] ?? 0), 0);
    means.push(calls === 0 ? 0 : (seconds / calls) * 1000);
  }
  return means;
}

/**
 * The six cards, in the order they appear.
 *
 * <p>Declared here rather than inline in the view, so the view renders from one map and a seventh
 * card is a row in this table rather than another block of markup.
 */
export const CARDS: readonly CardDefinition[] = [
  {
    helpKey: "meshLink",
    icon: HubIcon,
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
    helpKey: "peersReachable",
    icon: LanIcon,
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
    helpKey: "announces",
    icon: CampaignIcon,
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
    helpKey: "expiries",
    icon: HourglassEmptyIcon,
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
    helpKey: "datastore",
    icon: StorageIcon,
    id: "datastore",
    label: "Datastore",
    read: (samples) => {
      const count = timerStatistic(samples, METER.elasticsearchOperation, "count");
      const total = timerStatistic(samples, METER.elasticsearchOperation, "total");
      if (count === 0) {
        // The unit stays put so the card keeps the same shape as its neighbours - a value and a
        // unit, not a sentence. The value is a dash rather than 0 because there is no mean when
        // nothing has been measured, and printing 0 ms would claim a latency nobody observed.
        return { display: "-", tone: "neutral", unit: "ms mean" };
      }
      return {
        display: String(Math.round((total / count) * 1000)),
        tone: "success",
        trendFrom: (history) => meanLatencyHistory(history, samples),
        unit: "ms mean",
      };
    },
  },
  {
    helpKey: "failedReads",
    icon: ReportProblemIcon,
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
