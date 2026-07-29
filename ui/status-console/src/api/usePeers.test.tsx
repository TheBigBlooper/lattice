import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { usePeers } from "./usePeers.ts";

const BASE = "http://hub-central:8082/api/v1";

const PEERS = [
  {
    clusterId: "hub-east",
    region: "us-east",
    baselineVersion: "0.1.0",
    health: "ready",
    consoleUrl: "http://hub-east:3000",
    apiBaseUrl: "http://hub-east:8082/api/v1",
    lastSeen: "2026-07-27T00:00:00Z",
    reachability: "REACHABLE",
  },
  {
    clusterId: "hub-west",
    region: "us-west",
    baselineVersion: "0.1.0",
    health: "ready",
    consoleUrl: "http://hub-west:3000",
    apiBaseUrl: "http://hub-west:8082/api/v1",
    lastSeen: "2026-07-26T23:56:00Z",
    reachability: "UNREACHABLE",
  },
];

/** Wraps a hook in a query client that never retries, so a failure test settles at once. */
function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/**
 * Stubs fetch with one response.
 *
 * The url parameter is declared even though the body ignores it, so the recorded calls are typed
 * and a test can assert which endpoints were actually requested.
 */
function stubFetch(status: number, body: unknown) {
  const fetchMock = vi.fn((_url: RequestInfo | URL) =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { "content-type": "application/json" },
      })
    )
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("usePeers", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** The peer list arrives unwrapped from the envelope, typed off the contract. */
  it("returns the discovered peers", async () => {
    stubFetch(200, { data: PEERS, meta: { apiVersion: "v1" } });

    const { result } = renderHook(() => usePeers({ baseUrl: BASE, token: "a-token" }), { wrapper });

    await waitFor(() => {
      expect(result.current.data).toHaveLength(2);
    });
    expect(result.current.data?.[0]?.clusterId).toBe("hub-east");
    expect(result.current.data?.[1]?.reachability).toBe("UNREACHABLE");
  });

  /**
   * Reads only this baseline's own registry. The endpoint is the local one, and no request is ever
   * addressed to a peer's advertised apiBaseUrl - a cross-baseline read is refused by that peer's
   * realm, so attempting one would produce a guaranteed 401 per peer on every poll.
   */
  it("reads the local registry and never a peer's own API", async () => {
    const fetchMock = stubFetch(200, { data: PEERS, meta: {} });

    const { result } = renderHook(() => usePeers({ baseUrl: BASE, token: "a-token" }), { wrapper });
    await waitFor(() => {
      expect(result.current.data).toBeTruthy();
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const requested = fetchMock.mock.calls.map((call) => String(call[0]));
    expect(requested).toEqual([`${BASE}/peers`]);
    expect(requested.some((url) => url.includes("hub-east") || url.includes("hub-west"))).toBe(
      false
    );
  });

  /**
   * An empty mesh is a success, not an error. A baseline that has discovered nobody yet is a normal
   * state on a cold start, and rendering it as a failure would teach an operator to ignore the panel.
   */
  it("treats an empty mesh as success", async () => {
    stubFetch(200, { data: [], meta: {} });

    const { result } = renderHook(() => usePeers({ baseUrl: BASE, token: "a-token" }), { wrapper });

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });
    expect(result.current.data).toEqual([]);
  });

  /** Without a token the hook does not fire, for the same reason the baseline hook does not. */
  it("does not call the API when there is no token", () => {
    const fetchMock = stubFetch(200, { data: PEERS, meta: {} });

    const { result } = renderHook(() => usePeers({ baseUrl: BASE }), { wrapper });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.data).toBeUndefined();
  });

  /** A failure surfaces as the typed error so a caller can branch on the taxonomy code. */
  it("surfaces a typed failure", async () => {
    stubFetch(503, {
      error: { code: "UNAVAILABLE", message: "The mesh registry is unavailable." },
      meta: {},
    });

    const { result } = renderHook(() => usePeers({ baseUrl: BASE, token: "a-token" }), { wrapper });

    await waitFor(
      () => {
        expect(result.current.error).toBeTruthy();
      },
      { timeout: 5000 }
    );
    expect(result.current.error).toMatchObject({ code: "UNAVAILABLE" });
  });
});
