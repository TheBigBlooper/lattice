import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, readEnvelope } from "./client.ts";

const BASE = "http://hub-central:8082/api/v1";

/** Builds a fetch stub returning the given status and body. */
function stubFetch(status: number, body: string, contentType = "application/json") {
  // The parameters are declared so the recorded calls are typed: a zero-argument stub records an
  // empty tuple, and the assertions below could then never reach the request's headers.
  const fetchMock = vi.fn((_input: string | URL | Request, _init?: RequestInit) =>
    Promise.resolve(new Response(body, { status, headers: { "content-type": contentType } }))
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("readEnvelope", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  /** A success envelope is unwrapped to its data, so callers never handle the wrapper. */
  it("unwraps the data out of a success envelope", async () => {
    stubFetch(
      200,
      JSON.stringify({ data: { clusterId: "hub-central" }, meta: { apiVersion: "v1" } })
    );

    const data = await readEnvelope<{ clusterId: string }>({ baseUrl: BASE, path: "/baseline" });

    expect(data).toEqual({ clusterId: "hub-central" });
  });

  /**
   * The error code travels from the envelope into the typed error, because the console branches on
   * the code - a signed-out operator and a forbidden one need different screens, and the HTTP
   * status alone does not carry the taxonomy the contract defines.
   */
  it("raises the envelope's error code as a typed error", async () => {
    stubFetch(
      401,
      JSON.stringify({
        error: { code: "UNAUTHORIZED", message: "A valid bearer token is required." },
        meta: { apiVersion: "v1" },
      })
    );

    const failure = await readEnvelope({ baseUrl: BASE, path: "/baseline" }).catch(
      (e: unknown) => e
    );

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ code: "UNAUTHORIZED", status: 401 });
  });

  /**
   * A proxy error page or an empty body is not JSON. Parsing it raw would surface a
   * SyntaxError that says nothing about what went wrong, so it becomes a typed error carrying the
   * status the caller can act on.
   */
  it("turns a non-JSON body into a typed error carrying the status", async () => {
    stubFetch(502, "<html>Bad Gateway</html>", "text/html");

    const failure = await readEnvelope({ baseUrl: BASE, path: "/baseline" }).catch(
      (e: unknown) => e
    );

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ code: "INTERNAL", status: 502 });
  });

  /**
   * A failure that never reached a service has no envelope to carry a code - an ingress or proxy
   * rejecting the request before it arrives. The status is mapped to the same taxonomy so callers
   * branch on one vocabulary either way.
   */
  it.each([
    [401, "UNAUTHORIZED"],
    [403, "FORBIDDEN"],
    [404, "NOT_FOUND"],
    [429, "RATE_LIMITED"],
    [503, "UNAVAILABLE"],
    [500, "INTERNAL"],
  ])("maps a bare %i response to %s", async (status, code) => {
    stubFetch(status, JSON.stringify({ message: "not our envelope" }));

    const failure = await readEnvelope({ baseUrl: BASE, path: "/baseline" }).catch(
      (e: unknown) => e
    );

    expect(failure).toMatchObject({ code, status });
  });

  /** A bearer token is attached when one is held. */
  it("attaches the bearer token", async () => {
    const fetchMock = stubFetch(200, JSON.stringify({ data: {}, meta: {} }));

    await readEnvelope({ baseUrl: BASE, path: "/baseline", token: "a-token" });

    const headers = new Headers(fetchMock.mock.calls[0]?.[1]?.headers);
    expect(headers.get("authorization")).toBe("Bearer a-token");
  });

  /** With no token the header is absent rather than empty, so an unauthenticated call looks like one. */
  it("sends no authorization header without a token", async () => {
    const fetchMock = stubFetch(200, JSON.stringify({ data: {}, meta: {} }));

    await readEnvelope({ baseUrl: BASE, path: "/baseline" });

    const headers = new Headers(fetchMock.mock.calls[0]?.[1]?.headers);
    expect(headers.has("authorization")).toBe(false);
  });

  /**
   * A caller's own cancellation ends the request too. The data layer aborts on unmount, and
   * without this the console would keep reading for a screen nobody is looking at.
   */
  it("honours the caller's cancellation", async () => {
    vi.stubGlobal("fetch", (_input: string, init?: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => {
          reject(new DOMException("aborted", "AbortError"));
        });
      });
    });
    const caller = new AbortController();

    const pending = readEnvelope({
      baseUrl: BASE,
      path: "/baseline",
      signal: caller.signal,
    }).catch((e: unknown) => e);
    caller.abort();

    expect(await pending).toBeInstanceOf(ApiError);
  });

  /**
   * A request that never settles must not hang the console forever. The abort surfaces as a typed
   * network error rather than the raw DOMException, so a caller branches on the same taxonomy it
   * uses for everything else.
   */
  it("gives up on a request that never answers", async () => {
    vi.stubGlobal("fetch", (_input: string, init?: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => {
          reject(new DOMException("aborted", "AbortError"));
        });
      });
    });

    const failure = await readEnvelope({ baseUrl: BASE, path: "/baseline", timeoutMs: 10 }).catch(
      (e: unknown) => e
    );

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ code: "NETWORK_ERROR" });
  });
});
