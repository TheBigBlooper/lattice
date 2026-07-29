import { type UseQueryResult, useQuery } from "@tanstack/react-query";
import { type ApiError, readEnvelope } from "./client.ts";
import type { components } from "./generated/v1.ts";
import { POLL_INTERVAL_MS } from "./polling.ts";

/** This cluster's baseline, exactly as the contract defines it. */
export type Baseline = components["schemas"]["Baseline"];

/** What the hook needs to read this baseline. */
export interface UseBaselineOptions {
  /** The API base for this baseline, from config. */
  baseUrl: string;
  /** The operator's bearer token. Absent means signed out, and nothing is fetched. */
  token?: string | undefined;
}

/**
 * Reads this cluster's baseline: its identity, its health rollup, and the per-service readiness
 * behind that rollup.
 *
 * **It polls rather than streaming.** The live-status transport is still an open design question,
 * and this endpoint already carries everything the overview renders, so the console does not wait
 * on that decision. When a stream lands it replaces the body of this hook and no panel changes -
 * which is the reason the data layer is behind a hook at all.
 *
 * **Nothing is fetched without a token.** An unauthenticated call is a known 401, so making it
 * anyway would turn the signed-out screen, a perfectly normal state, into a request that fails on
 * every render.
 *
 * **The token is part of the cache key.** Were it not, a change of operator would be served the
 * previous one's cached view, which on a console about who can see what is a correctness problem
 * rather than a staleness one.
 *
 * @param options the API base and the operator's token.
 * @returns the query result, whose error is an {@link ApiError} carrying the taxonomy code.
 */
export function useBaseline({
  baseUrl,
  token,
}: UseBaselineOptions): UseQueryResult<Baseline, ApiError> {
  return useQuery<Baseline, ApiError>({
    queryKey: ["baseline", baseUrl, token],
    queryFn: ({ signal }) =>
      // The query's signal aborts an in-flight read when the component unmounts or the key
      // changes; the client's timeout is a separate guarantee that a request cannot hang. The
      // client honours both.
      readEnvelope<Baseline>({ baseUrl, path: "/baseline", token, signal }),
    enabled: Boolean(token),
    refetchInterval: POLL_INTERVAL_MS,
    // Retry belongs here rather than in the client, and stays modest: a console that hammers a
    // struggling cluster makes the incident worse, and an operator would rather see a degraded
    // view quickly than a spinner while it tries five times.
    retry: 1,
  });
}
