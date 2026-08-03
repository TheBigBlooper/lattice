import type { components } from "../../api/generated/v1.ts";

/** One measured value, exactly as the contract defines it. */
export type MetricSample = components["schemas"]["MetricSample"];

/** One service's selected meters, exactly as the contract defines it. */
export type MetricsSnapshot = components["schemas"]["MetricsSnapshot"];

/**
 * How many readings of one series the view keeps.
 *
 * <p>Sixty on the console's ten-second poll is a ten-minute window. Longer buys history that a
 * reload discards anyway, since there is no storage behind this - the trend is the console's own
 * memory of what it has seen.
 */
export const HISTORY_LENGTH = 60;

/**
 * A stable identity for one series, so two label sets of the same meter stay apart.
 *
 * Labels are sorted before joining because a snapshot makes no promise about their order, and a key
 * that changed with it would start a fresh history every time the order did.
 *
 * @param sample the sample to identify.
 * @returns the series key.
 */
export function seriesKey(sample: MetricSample): string {
  const labels = Object.entries(sample.labels ?? {})
    .map(([key, value]) => `${key}=${value}`)
    .sort()
    .join(",");
  return labels ? `${sample.name}{${labels}}` : sample.name;
}

/**
 * Adds every series of one meter together.
 *
 * Used where a meter is split by a label the card does not care about: announcements received are
 * counted per announcing cluster, and the card wants the total across all of them.
 *
 * @param samples the samples to read.
 * @param name the meter name to total.
 * @returns the sum, or zero when nothing matches - a meter nobody has incremented reads as zero
 *   rather than as missing.
 */
export function sumOf(samples: readonly MetricSample[], name: string): number {
  return samples
    .filter((sample) => sample.name === name)
    .reduce((total, sample) => total + sample.value, 0);
}

/**
 * Reads a single-series meter.
 *
 * @param samples the samples to read.
 * @param name the meter name.
 * @returns its value, or undefined when it has not been reported at all - which is different from
 *   having been reported as zero, and the card renders the two differently.
 */
export function latest(samples: readonly MetricSample[], name: string): number | undefined {
  return samples.find((sample) => sample.name === name)?.value;
}

/**
 * Appends a reading, keeping the buffer at its cap by dropping the oldest.
 *
 * Returns a new array rather than mutating: React re-renders on identity, so a buffer edited in
 * place would show the first reading forever.
 *
 * @param history the readings so far.
 * @param reading the reading to add.
 * @returns the buffer with the reading appended.
 */
export function appendReading(history: readonly number[], reading: number): number[] {
  const appended = [...history, reading];
  return appended.length > HISTORY_LENGTH ? appended.slice(appended.length - HISTORY_LENGTH) : appended;
}

/**
 * Derives a per-minute rate from a counter's readings.
 *
 * **A counter's value is not a rate, and this is the whole reason the view keeps history.** That
 * `announcements_received_total` reads 46 says nothing on its own; that it moved by six in the last
 * minute, or by nothing at all, is the answer an operator wants.
 *
 * A drop is treated as zero rather than as a negative rate: counters restart when a service does,
 * and a restart is not the mesh running backwards.
 *
 * @param history the readings, oldest first.
 * @param intervalMs how far apart the readings are.
 * @returns the rate per minute, or undefined until two readings exist - one reading is a value, not
 *   a rate, and guessing from it would put a confident number on screen with nothing behind it.
 */
export function ratePerMinute(
  history: readonly number[],
  intervalMs: number,
): number | undefined {
  if (history.length < 2) {
    return undefined;
  }
  const first = history[0] ?? 0;
  const last = history.at(-1) ?? 0;
  const moved = Math.max(0, last - first);
  const elapsedMinutes = ((history.length - 1) * intervalMs) / 60_000;
  return elapsedMinutes === 0 ? 0 : Math.round(moved / elapsedMinutes);
}
