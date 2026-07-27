import { type UseQueryResult, useQuery } from "@tanstack/react-query";
import { type ApiError, readEnvelope } from "./client.ts";
import type { components } from "./generated/v1.ts";
import { POLL_INTERVAL_MS } from "./polling.ts";

/** One discovered baseline, exactly as the contract defines it. */
export type Peer = components["schemas"]["Peer"];

/** What the hook needs to read this baseline's peer registry. */
export interface UsePeersOptions {
  /** This baseline's own API base, from config. Never a peer's. */
  baseUrl: string;
  /** The operator's bearer token. Absent means signed out, and nothing is fetched. */
  token?: string | undefined;
}

/**
 * Reads the peers this baseline has discovered on the mesh.
 *
 * **It reads this baseline's own registry, and only that.** Each peer advertises an `apiBaseUrl`,
 * and the console deliberately does not call it. Every `/api/v1` operation accepts a bearer token
 * only from its own baseline's realm, a peer refuses a token minted anywhere else, and realm
 * membership is deliberately unsynchronized between baselines - so an operator may hold no grant on
 * a peer they can nonetheless see. A browser fan-out would therefore return 401 from every peer on
 * every poll. What the registry already holds - identity, region, baseline version, the health that
 * peer last announced, when it was last heard, and whether it is still within its liveness window -
 * is what the unified view renders.
 *
 * **An empty list is a success.** A baseline that has discovered nobody is a normal cold start, and
 * treating it as a failure would teach an operator to disregard the panel.
 *
 * @param options this baseline's API base and the operator's token.
 * @returns the query result, whose error is an {@link ApiError} carrying the taxonomy code.
 */
export function usePeers({ baseUrl, token }: UsePeersOptions): UseQueryResult<Peer[], ApiError> {
  return useQuery<Peer[], ApiError>({
    queryKey: ["peers", baseUrl, token],
    queryFn: ({ signal }) => readEnvelope<Peer[]>({ baseUrl, path: "/peers", token, signal }),
    enabled: Boolean(token),
    refetchInterval: POLL_INTERVAL_MS,
    // Modest for the same reason the baseline read is: an operator would rather see a degraded view
    // quickly than a spinner while the console hammers a cluster that is already struggling.
    retry: 1,
  });
}
