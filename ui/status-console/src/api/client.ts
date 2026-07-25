import type { components } from "./generated/v1.ts";

/**
 * The contract's error taxonomy, plus the one code the contract cannot carry.
 *
 * `NETWORK_ERROR` has no place in the taxonomy because a request that never reached the service
 * has no response envelope to carry a code. It is the console's own, and it is named here so a
 * caller branches on one vocabulary rather than checking for a response separately.
 */
export type ApiErrorCode = components["schemas"]["ErrorCode"] | "NETWORK_ERROR";

/** How long a single request may take before it is abandoned. */
const DEFAULT_TIMEOUT_MS = 10_000;

/**
 * A failed API call, carrying the contract's error code and the HTTP status.
 *
 * The code is what callers branch on: a signed-out operator and a forbidden one need different
 * screens, and the status alone does not distinguish "your token expired" from "your role is
 * wrong" in the way the taxonomy does.
 */
export class ApiError extends Error {
  /** The taxonomy code, from the error envelope where there was one. */
  readonly code: ApiErrorCode;

  /** The HTTP status, or 0 when the request never produced a response. */
  readonly status: number;

  /**
   * Creates a typed API failure.
   *
   * @param code the taxonomy code to branch on.
   * @param status the HTTP status, or 0 when there was no response.
   * @param message a human-readable summary for logs and display.
   */
  constructor(code: ApiErrorCode, status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

/** What a single enveloped read needs. */
export interface ReadEnvelopeOptions {
  /** The API base, from config. Never hardcoded in a caller. */
  baseUrl: string;
  /** The path beneath the base, beginning with a slash. */
  path: string;
  /** The bearer token to present, when the operator holds one. */
  token?: string | undefined;
  /** How long to wait before abandoning the request. */
  timeoutMs?: number;
  /**
   * A caller's own cancellation, combined with the timeout rather than replacing it. The data layer
   * aborts on unmount or a key change; the timeout bounds a request that simply never answers.
   * Both guarantees are needed, so neither replaces the other.
   */
  signal?: AbortSignal;
}

/** The success half of the response envelope. */
interface SuccessEnvelope<T> {
  data: T;
}

/** The failure half of the response envelope. */
interface ErrorEnvelope {
  error: { code: components["schemas"]["ErrorCode"]; message: string };
}

/** True when the parsed body carries an error object shaped the way the contract defines. */
function isErrorEnvelope(body: unknown): body is ErrorEnvelope {
  if (typeof body !== "object" || body === null || !("error" in body)) {
    return false;
  }
  const { error } = body as { error: unknown };
  return typeof error === "object" && error !== null && "code" in error;
}

/**
 * Reads one enveloped endpoint and returns its `data`, or throws an {@link ApiError}.
 *
 * This is the console's single crossing point to the API, and it owns exactly three things beyond
 * making the request:
 *
 * - **A timeout.** A request that never settles would otherwise hang the console indefinitely; the
 *   abort surfaces as `NETWORK_ERROR` rather than a raw `DOMException`.
 * - **A guarded parse.** A proxy error page or an empty body is not JSON, and parsing it raw
 *   produces a `SyntaxError` that describes nothing. It becomes a typed error carrying the status.
 * - **Envelope unwrapping.** Callers receive `data` and never see the wrapper.
 *
 * It deliberately does **not** retry. Retry belongs to the data-fetching layer, which knows what is
 * worth repeating and how often; a retry loop here would multiply silently with the one above it.
 *
 * @param options where to read, what to present, and how long to wait.
 * @returns the unwrapped payload.
 * @throws ApiError on any failure, including a network one.
 */
export async function readEnvelope<T>({
  baseUrl,
  path,
  token,
  timeoutMs = DEFAULT_TIMEOUT_MS,
  signal,
}: ReadEnvelopeOptions): Promise<T> {
  const timeout = new AbortController();
  const timer = setTimeout(() => {
    timeout.abort();
  }, timeoutMs);
  // Either cancellation ends the request; neither substitutes for the other.
  const cancellation = signal ? AbortSignal.any([signal, timeout.signal]) : timeout.signal;

  const headers = new Headers({ accept: "application/json" });
  if (token) {
    headers.set("authorization", `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, { headers, signal: cancellation });
  } catch (cause) {
    const aborted = cause instanceof DOMException && cause.name === "AbortError";
    throw new ApiError(
      "NETWORK_ERROR",
      0,
      aborted ? `No answer from ${baseUrl} within ${timeoutMs}ms` : `Could not reach ${baseUrl}`
    );
  } finally {
    clearTimeout(timer);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new ApiError(
      response.ok ? "INTERNAL" : errorCodeForStatus(response.status),
      response.status,
      `Expected JSON from ${path}, got ${response.headers.get("content-type") ?? "no content type"}`
    );
  }

  if (isErrorEnvelope(body)) {
    throw new ApiError(body.error.code, response.status, body.error.message);
  }

  if (!response.ok) {
    throw new ApiError(
      errorCodeForStatus(response.status),
      response.status,
      `Request to ${path} failed with ${response.status}`
    );
  }

  return (body as SuccessEnvelope<T>).data;
}

/**
 * Maps a status to the taxonomy for the case where no error envelope arrived - a proxy or ingress
 * failing before the request reached a service, which is precisely when the body is not ours.
 */
function errorCodeForStatus(status: number): ApiErrorCode {
  switch (status) {
    case 401:
      return "UNAUTHORIZED";
    case 403:
      return "FORBIDDEN";
    case 404:
      return "NOT_FOUND";
    case 429:
      return "RATE_LIMITED";
    case 503:
      return "UNAVAILABLE";
    default:
      return "INTERNAL";
  }
}
