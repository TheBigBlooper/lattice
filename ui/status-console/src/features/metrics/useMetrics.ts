import { useQueries } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { type ApiError, readEnvelope } from "../../api/client.ts";
import { POLL_INTERVAL_MS } from "../../api/polling.ts";
import { appendReading, type MetricSample, type MetricsSnapshot, seriesKey } from "./metrics.ts";

/** What the hook needs to read every service on this baseline. */
export interface UseMetricsOptions {
  /** The three API bases this baseline serves, from config. */
  baseUrls: readonly string[];
  /** The operator's bearer token. Absent means signed out, and nothing is fetched. */
  token?: string | undefined;
}

/** Everything the view renders: the current samples, their history, and how the reads are going. */
export interface MetricsReading {
  /** Every service's samples, merged, each carrying the service that produced it. */
  samples: MetricSample[];
  /** Readings this session has seen per series, oldest first. */
  history: ReadonlyMap<string, readonly number[]>;
  /** True until the first read of every service has settled. */
  isLoading: boolean;
  /** Why the reads failed, when every one of them did. */
  error?: ApiError | undefined;
  /** Whether a read is in flight behind a failure. */
  isRetrying: boolean;
  /** When the last complete read landed, so a blip and an outage stop looking identical. */
  lastGoodRead?: number | undefined;
}

/**
 * The service label attached to every sample, so two services reporting the same meter stay apart
 * once merged. The snapshot names its own producer; this promotes that name onto each sample.
 */
const SERVICE_LABEL = "service";

/** Flattens one service's snapshot, stamping each sample with the service that produced it. */
function labelled(snapshot: MetricsSnapshot): MetricSample[] {
  return (snapshot.samples ?? []).map((sample) => ({
    ...sample,
    labels: { ...(sample.labels ?? {}), [SERVICE_LABEL]: snapshot.service },
  }));
}

/**
 * Reads this baseline's own instrumentation from every service that serves it, and remembers what
 * it has seen.
 *
 * <p><b>Every service, not one.</b> The operation is served by each service rather than aggregated
 * by the gateway, so the console reads all of them and merges - the same shape it already uses to
 * read three services for its other data. A service that fails is not allowed to take the others
 * down with it: the view renders what answered.
 *
 * <p><b>The history is the console's own memory.</b> Nothing stores these numbers, so a trend
 * exists only for as long as this tab has been open. That is a real limitation and the view states
 * it rather than letting a reader assume the line covers more than it does.
 *
 * @param options the API bases and the operator's token.
 * @returns the merged samples, their history, and the state of the reads.
 */
export function useMetrics({ baseUrls, token }: UseMetricsOptions): MetricsReading {
  const results = useQueries({
    queries: baseUrls.map((baseUrl) => ({
      enabled: Boolean(token),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        readEnvelope<MetricsSnapshot>({ baseUrl, path: "/metrics", signal, token }),
      queryKey: ["metrics", baseUrl, token],
      refetchInterval: POLL_INTERVAL_MS,
      // Modest, for the same reason every other read here is: a console that hammers a struggling
      // cluster makes the incident worse.
      retry: 1,
    })),
  });

  const samples = results.flatMap((result) => (result.data ? labelled(result.data) : []));
  const isLoading = results.some((result) => result.isLoading);
  const answered = results.filter((result) => result.data !== undefined);
  const error = answered.length === 0 ? (results[0]?.error as ApiError | undefined) : undefined;
  const isRetrying = results.some((result) => result.isFetching);

  // Kept in a ref and mirrored into state: the buffer must survive a re-render, and the view must
  // re-render when it grows. A ref alone would never repaint; state alone would reset on remount.
  const historyRef = useRef(new Map<string, number[]>());
  const [history, setHistory] = useState<ReadonlyMap<string, readonly number[]>>(
    historyRef.current
  );
  const lastGoodReadRef = useRef<number | undefined>(undefined);

  // When the most recent read landed. It advances on every successful poll even when the numbers
  // are identical, which is exactly what a reading is: a counter that has stopped moving must still
  // record one, because a flat line is the signal the trend exists to show.
  const lastReadAt = Math.max(0, ...results.map((result) => result.dataUpdatedAt ?? 0));

  // Recorded through a ref rather than by depending on the sample array, whose identity changes on
  // every render and would otherwise record a reading per repaint rather than per poll.
  const samplesRef = useRef(samples);
  samplesRef.current = samples;

  useEffect(() => {
    const current = samplesRef.current;
    if (current.length === 0) {
      return;
    }
    const next = new Map(historyRef.current);
    for (const sample of current) {
      next.set(seriesKey(sample), appendReading(next.get(seriesKey(sample)) ?? [], sample.value));
    }
    historyRef.current = next;
    lastGoodReadRef.current = lastReadAt;
    setHistory(next);
  }, [lastReadAt]);

  return {
    error,
    history,
    isLoading,
    isRetrying,
    lastGoodRead: lastGoodReadRef.current,
    samples,
  };
}
