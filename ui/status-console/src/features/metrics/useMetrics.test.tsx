import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client.ts";
import type { MetricsSnapshot } from "./metrics.ts";
import { useMetrics } from "./useMetrics.ts";

/** What each base returns when read, keyed by base URL. */
const answers = new Map<string, MetricsSnapshot | ApiError>();

vi.mock("../../api/client.ts", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  readEnvelope: ({ baseUrl }: { baseUrl: string }) => {
    const answer = answers.get(baseUrl);
    return answer instanceof ApiError ? Promise.reject(answer) : Promise.resolve(answer);
  },
}));

const GATEWAY = "http://gateway/api/v1";
const ORDERS = "http://orders/api/v1";

/** A provider with retries off, so a failing read settles in one tick rather than after a backoff. */
function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** Renders the hook over both bases. */
function read() {
  return renderHook(() => useMetrics({ baseUrls: [GATEWAY, ORDERS], token: "a-token" }), {
    wrapper,
  });
}

beforeEach(() => {
  answers.clear();
});

describe("useMetrics", () => {
  it("merges every service and stamps each sample with the one that reported it", async () => {
    answers.set(GATEWAY, {
      samples: [{ kind: "GAUGE", labels: {}, name: "lattice.mesh.link.up", value: 1 }],
      service: "mesh-gateway",
    });
    answers.set(ORDERS, {
      samples: [
        { kind: "COUNTER", labels: {}, name: "lattice.elasticsearch.operation.errors", value: 0 },
      ],
      service: "orders",
    });

    const { result } = read();

    await waitFor(() => expect(result.current.samples).toHaveLength(2));
    // Provenance is its own field. Written into the labels it overwrote any meter carrying a label
    // of that name, which merged three readiness series into one.
    expect(result.current.samples.map((sample) => sample.reportedBy).sort()).toEqual([
      "mesh-gateway",
      "orders",
    ]);
  });

  it("renders what answered when one service fails, rather than losing the rest", async () => {
    answers.set(GATEWAY, {
      samples: [{ kind: "GAUGE", labels: {}, name: "lattice.mesh.link.up", value: 1 }],
      service: "mesh-gateway",
    });
    answers.set(ORDERS, new ApiError("UNAVAILABLE", 503, "orders is not answering"));

    const { result } = read();

    await waitFor(() => expect(result.current.samples).toHaveLength(1));
    // One service failing is not the view failing: an error is reported only when nothing answered.
    expect(result.current.error).toBeUndefined();
  });

  it("reports the failure when nothing answered at all", async () => {
    answers.set(GATEWAY, new ApiError("UNAVAILABLE", 503, "gateway is not answering"));
    answers.set(ORDERS, new ApiError("UNAVAILABLE", 503, "orders is not answering"));

    const { result } = read();

    await waitFor(() => expect(result.current.error).toBeDefined());
    expect(result.current.samples).toHaveLength(0);
  });

  it("records a reading per series, which is what the trends are drawn from", async () => {
    answers.set(GATEWAY, {
      samples: [
        {
          kind: "COUNTER",
          labels: { source_cluster: "hub-east" },
          name: "lattice.mesh.announcements.received",
          value: 46,
        },
      ],
      service: "mesh-gateway",
    });
    answers.set(ORDERS, { samples: [], service: "orders" });

    const { result } = read();

    await waitFor(() => expect(result.current.history.size).toBe(1));
    const [readings] = [...result.current.history.values()];
    expect(readings).toEqual([46]);
  });

  it("fetches nothing without a token, because an unauthenticated read is a known refusal", async () => {
    answers.set(GATEWAY, {
      samples: [{ kind: "GAUGE", labels: {}, name: "lattice.mesh.link.up", value: 1 }],
      service: "mesh-gateway",
    });

    const { result } = renderHook(() => useMetrics({ baseUrls: [GATEWAY], token: undefined }), {
      wrapper,
    });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.samples).toHaveLength(0);
  });
});
