import type { components } from "./generated/v1.ts";

/**
 * The contract's error taxonomy, plus the one code the contract cannot carry.
 *
 * `NETWORK_ERROR` has no place in the taxonomy because a request that never reached the service
 * has no response envelope to carry a code. It is the console's own, and it is named here so a
 * caller branches on one vocabulary rather than checking for a response separately.
 */
export type ApiErrorCode = components["schemas"]["ErrorCode"] | "NETWORK_ERROR";

/** One field-level problem, exactly as the contract defines it. */
export type ErrorDetail = components["schemas"]["ErrorDetail"];

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
   * The field-level problems, when the service named any. Empty rather than absent.
   *
   * <p>These are what let a validation failure be rendered **against the offending field** instead
   * of as a banner. The service is the one that knows which field is wrong, so carrying its answer
   * keeps that mapping out of the console, where it could drift from the contract.
   *
   * <p>Empty for every other code: a conflict or an outage is about the world rather than the
   * input, and is not attributable to a field at all.
   */
  readonly details: readonly ErrorDetail[];

  /**
   * Creates a typed API failure.
   *
   * @param code the taxonomy code to branch on.
   * @param status the HTTP status, or 0 when there was no response.
   * @param message a human-readable summary for logs and display.
   * @param details the field-level problems, where the service named any.
   */
  constructor(
    code: ApiErrorCode,
    status: number,
    message: string,
    details: readonly ErrorDetail[] = []
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
    this.details = details;
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
  /**
   * The HTTP method. Defaults to a read.
   *
   * <p>Writes travel through this function rather than a second client, so the envelope, the error
   * taxonomy, the timeout and the abort handling are shared by construction. A parallel writer
   * would be a second place for any of those to drift.
   */
  method?: "GET" | "POST" | "PUT";
  /** The request body, serialised as JSON. Omitted for a read. */
  body?: unknown;
}

/** The success half of the response envelope. */
interface SuccessEnvelope<T> {
  data: T;
}

/** The failure half of the response envelope. */
interface ErrorEnvelope {
  error: {
    code: components["schemas"]["ErrorCode"];
    message: string;
    /** Present only on a validation failure, which is why it is optional here. */
    details?: ErrorDetail[];
  };
}

/** What building one request needs, separated from deciding what its answer meant. */
interface RequestParts {
  /** The combined caller and timeout cancellation. */
  cancellation: AbortSignal;
  /** The HTTP method. */
  method: "GET" | "POST" | "PUT";
  /** The body to serialise, when there is one. */
  payload: unknown;
  /** The bearer token to present, when the operator holds one. */
  token: string | undefined;
}

/**
 * Builds the request, so {@link readEnvelope} is left deciding what the answer meant.
 *
 * <p>Extracted when writes landed and the complexity gate objected. That objection was right:
 * assembling headers and a body has nothing to do with interpreting an envelope, and the two were
 * only ever adjacent because they happened in the same function.
 */
function requestInit({ cancellation, method, payload, token }: RequestParts): RequestInit {
  const headers = new Headers({ accept: "application/json" });
  if (payload !== undefined) {
    headers.set("content-type", "application/json");
  }
  if (token) {
    headers.set("authorization", `Bearer ${token}`);
  }
  return {
    headers,
    method,
    signal: cancellation,
    ...(payload === undefined ? {} : { body: JSON.stringify(payload) }),
  };
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
  method = "GET",
  body: payload,
}: ReadEnvelopeOptions): Promise<T> {
  const timeout = new AbortController();
  const timer = setTimeout(() => {
    timeout.abort();
  }, timeoutMs);
  // Either cancellation ends the request; neither substitutes for the other.
  const cancellation = signal ? AbortSignal.any([signal, timeout.signal]) : timeout.signal;

  const init = requestInit({ cancellation, method, payload, token });

  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, init);
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
    throw new ApiError(
      body.error.code,
      response.status,
      body.error.message,
      body.error.details ?? []
    );
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
