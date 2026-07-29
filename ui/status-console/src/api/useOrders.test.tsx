import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./client.ts";
import { type Order, useCreateOrder, useOrders } from "./useOrders.ts";

const BASE = "http://hub-central:8082/api/v1";
const TOKEN = "a-real-token";

const ORDER: Order = {
  orderId: "ord-9c40ab",
  customerId: "acme-northwest",
  status: "RECEIVED",
  lines: [{ sku: "SKU-40119", quantity: 2 }],
  createdAt: "2026-07-29T14:11:37Z",
};

/** A client that does not retry, so a failure settles inside one assertion. */
function wrapper() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

/** Stubs fetch with one canned envelope, recording what was asked for. */
function stubFetch(status: number, body: unknown) {
  const fetchMock = vi.fn((_input: string | URL | Request, _init?: RequestInit) =>
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

describe("useOrders", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /**
   * It asks for one page rather than the collection.
   *
   * <p>The contract caps a page at 100 precisely so a client cannot request an unbounded read, and
   * a console that ignored paging would be one request away from pulling a baseline's whole index.
   */
  it("reads a bounded page", async () => {
    const fetchMock = stubFetch(200, { data: [ORDER], meta: {} });

    const { result } = renderHook(() => useOrders({ baseUrl: BASE, token: TOKEN }), {
      wrapper: wrapper(),
    });

    await waitFor(() => {
      expect(result.current.data).toHaveLength(1);
    });
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain("/orders?page=0&size=20");
  });

  /**
   * Nothing is read without a token.
   *
   * <p>An unauthenticated call is a known 401, so making it anyway would turn the signed-out
   * screen - a perfectly normal state - into a request that fails on every render.
   */
  it("reads nothing without a token", () => {
    const fetchMock = stubFetch(200, { data: [], meta: {} });

    renderHook(() => useOrders({ baseUrl: BASE }), { wrapper: wrapper() });

    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useCreateOrder", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /**
   * It returns the order the service made, not merely that it succeeded.
   *
   * <p>The id is server-generated and there is no search operation, so what comes back is the
   * operator's only precise handle on the record they just created.
   */
  it("returns the created order", async () => {
    stubFetch(201, { data: ORDER, meta: {} });

    const { result } = renderHook(() => useCreateOrder({ baseUrl: BASE, token: TOKEN }), {
      wrapper: wrapper(),
    });
    result.current.mutate({
      customerId: "acme-northwest",
      lines: [{ sku: "SKU-40119", quantity: 2 }],
    });

    await waitFor(() => {
      expect(result.current.data?.orderId).toBe("ord-9c40ab");
    });
  });

  /**
   * A validation failure arrives with the field-level problems intact.
   *
   * <p>This is what lets the form put the message against the offending input rather than in a
   * banner - the placement the design settles, and the reason the client carries `details` at all.
   */
  it("surfaces the field a validation failure names", async () => {
    stubFetch(400, {
      error: {
        code: "VALIDATION_ERROR",
        details: [{ field: "lines[0].quantity", issue: "must be at least 1" }],
        message: "Request failed validation",
      },
      meta: {},
    });

    const { result } = renderHook(() => useCreateOrder({ baseUrl: BASE, token: TOKEN }), {
      wrapper: wrapper(),
    });
    result.current.mutate({
      customerId: "acme-northwest",
      lines: [{ sku: "SKU-40119", quantity: 0 }],
    });

    await waitFor(() => {
      expect(result.current.error).toBeInstanceOf(ApiError);
    });
    expect(result.current.error?.details[0]?.field).toBe("lines[0].quantity");
  });
});
