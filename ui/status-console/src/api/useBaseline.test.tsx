import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useBaseline } from "./useBaseline.ts";

const BASE = "http://hub-central:8082/api/v1";

const BASELINE = {
  clusterId: "hub-central",
  region: "us-central",
  baselineVersion: "0.1.0-SNAPSHOT",
  apiVersions: ["v1"],
  health: "degraded",
  services: [
    { name: "orders", status: "UP" },
    { name: "mesh-gateway", status: "DOWN" },
  ],
};

/** Wraps a hook in a query client that never retries, so a failure test settles at once. */
function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** Stubs fetch with one response. */
function stubFetch(status: number, body: unknown) {
  const fetchMock = vi.fn(() =>
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

describe("useBaseline", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** The baseline arrives unwrapped, typed off the contract. */
  it("returns the cluster's baseline", async () => {
    stubFetch(200, { data: BASELINE, meta: { apiVersion: "v1" } });

    const { result } = renderHook(() => useBaseline({ baseUrl: BASE, token: "a-token" }), {
      wrapper,
    });

    await waitFor(() => {
      expect(result.current.data?.clusterId).toBe("hub-central");
    });
    expect(result.current.data?.health).toBe("degraded");
  });

  /**
   * Without a token the hook does not fire. The console already knows an unauthenticated call is a
   * 401, so making it anyway would turn the signed-out screen - a normal state - into a request
   * that fails on every render.
   */
  it("does not call the API when there is no token", () => {
    const fetchMock = stubFetch(200, { data: BASELINE, meta: {} });

    const { result } = renderHook(() => useBaseline({ baseUrl: BASE }), { wrapper });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.data).toBeUndefined();
  });

  /** A failure surfaces as the typed error, so a caller can branch on the taxonomy code. */
  it("surfaces a typed failure", async () => {
    stubFetch(503, {
      error: { code: "UNAVAILABLE", message: "Identity is currently unavailable." },
      meta: {},
    });

    const { result } = renderHook(() => useBaseline({ baseUrl: BASE, token: "a-token" }), {
      wrapper,
    });

    // The hook retries once by design, so the settled failure is the second attempt's. Waiting
    // past the backoff is the honest assertion: it is the state an operator actually sees.
    await waitFor(
      () => {
        expect(result.current.error).toBeTruthy();
      },
      { timeout: 5000 }
    );
    expect(result.current.error).toMatchObject({ code: "UNAVAILABLE" });
  });

  /**
   * The token is part of the cache key. Were it not, a token change would serve the previous
   * operator's cached view - which on a console showing who-can-see-what is a correctness problem,
   * not a staleness one.
   */
  it("refetches when the token changes", async () => {
    const fetchMock = stubFetch(200, { data: BASELINE, meta: {} });

    const { result, rerender } = renderHook(
      ({ token }: { token: string }) => useBaseline({ baseUrl: BASE, token }),
      { wrapper, initialProps: { token: "first" } }
    );
    await waitFor(() => {
      expect(result.current.data).toBeTruthy();
    });

    rerender({ token: "second" });
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
  });
});
