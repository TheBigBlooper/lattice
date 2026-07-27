import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App.tsx";
import type { ConsoleConfig } from "./config.ts";
import { lightPalette } from "./theme/tokens.ts";

const config: ConsoleConfig = {
  apiBaseUrl: "http://hub-local:8082/api/v1",
  clusterId: "hub-local",
  keycloakUrl: "http://localhost:8083",
  keycloakRealm: "lattice",
  keycloakClientId: "lattice-console",
};

/** The session the mocked hook reports. Each test sets it before rendering. */
const session = {
  status: "signed-out" as "initialising" | "signed-in" | "signed-out",
  token: undefined as string | undefined,
  username: undefined as string | undefined,
  signIn: vi.fn(),
  signOut: vi.fn(),
};

// Mocked at the hook seam rather than at keycloak-js: the shell test is about which screen the
// session produces, and driving a real adapter through a redirect would test the adapter instead.
vi.mock("./auth/useSession.ts", () => ({
  useSession: () => session,
}));

const BASELINE = {
  clusterId: "hub-local",
  region: "local",
  baselineVersion: "0.1.0-SNAPSHOT",
  apiVersions: ["v1"],
  health: "degraded",
  services: [
    { name: "orders", status: "UP" },
    { name: "mesh-gateway", status: "DOWN" },
  ],
};

/** Renders the shell inside a query client that does not retry, so a failure settles at once. */
function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<App config={config} />, { wrapper });
}

const PEERS = [
  {
    clusterId: "hub-east",
    region: "us-east",
    baselineVersion: "0.1.0",
    health: "ready",
    consoleUrl: "http://hub-east:3000",
    apiBaseUrl: "http://hub-east:8082/api/v1",
    lastSeen: new Date().toISOString(),
    reachability: "REACHABLE",
  },
];

/** One canned HTTP response. */
interface StubResponse {
  status: number;
  body: unknown;
}

/** The mesh read most tests want: one healthy peer, so the mesh column renders normally. */
const PEERS_OK: StubResponse = { status: 200, body: { data: PEERS, meta: {} } };

/**
 * Stubs fetch, routing by path.
 *
 * The shell reads two endpoints and they return different shapes, so one canned body for every URL
 * would hand the peer list a baseline object. Each path is answered with what the contract says it
 * returns, and the two can fail independently - which is the case worth testing, since a mesh
 * failure must not take the cluster's own verdict down with it.
 */
function stubFetch(status: number, baselineBody: unknown, peers: StubResponse = PEERS_OK) {
  vi.stubGlobal("fetch", (url: RequestInfo | URL) => {
    const response = String(url).endsWith("/peers") ? peers : { status, body: baselineBody };
    return Promise.resolve(
      new Response(JSON.stringify(response.body), {
        status: response.status,
        headers: { "content-type": "application/json" },
      })
    );
  });
}

describe("App", () => {
  beforeEach(() => {
    session.status = "signed-out";
    session.token = undefined;
    session.username = undefined;
    session.signIn.mockReset();
    session.signOut.mockReset();
    vi.stubGlobal("matchMedia", () => ({
      matches: false,
      media: "",
      addEventListener: () => {},
      removeEventListener: () => {},
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** The shell mounts and takes its surface colour from the active palette, not from a literal. */
  it("renders the shell against the themed surface", () => {
    stubFetch(200, { data: BASELINE, meta: {} });
    renderApp();

    expect(screen.getByRole("main")).toHaveStyle({ backgroundColor: lightPalette.surfacePage });
  });

  /** Without a session the console says so plainly, rather than showing an empty dashboard. */
  it("starts signed out", () => {
    stubFetch(200, { data: BASELINE, meta: {} });
    renderApp();

    expect(screen.getByRole("status")).toHaveTextContent(/signed out/i);
  });

  /**
   * Signing in swaps the read cluster into the same block. Exactly one status block throughout is
   * the assertion that matters: these screens are alternatives in one position, never two things
   * that could both be present.
   */
  it("shows the cluster's own verdict once signed in", async () => {
    stubFetch(200, { data: BASELINE, meta: {} });
    session.status = "signed-in";
    session.token = "a-real-token";
    renderApp();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/degraded/i);
    });
    expect(screen.getAllByRole("status")).toHaveLength(1);
    expect(screen.getByRole("status")).toHaveTextContent(/1 of 2 services ready/i);
  });

  /**
   * The mesh sits beside the verdict, not instead of it. Both are on screen at once, which is the
   * whole point of the unified view.
   */
  it("shows the discovered mesh alongside the cluster's own verdict", async () => {
    stubFetch(200, { data: BASELINE, meta: {} });
    session.status = "signed-in";
    session.token = "a-real-token";
    renderApp();

    await waitFor(() => {
      expect(screen.getByText("1 of 1 peers reachable")).toBeInTheDocument();
    });
    expect(screen.getByRole("status")).toHaveTextContent(/degraded/i);
    expect(screen.getByText("hub-east")).toBeInTheDocument();
  });

  /**
   * A mesh-registry failure degrades the mesh column alone. The cluster's own verdict is read from a
   * different endpoint and is still true, so letting one failure blank the whole screen would hide
   * working information behind an unrelated fault.
   */
  it("keeps the verdict when the mesh registry cannot be read", async () => {
    stubFetch(
      200,
      { data: BASELINE, meta: {} },
      {
        status: 503,
        body: {
          error: { code: "UNAVAILABLE", message: "The mesh registry is unavailable." },
          meta: {},
        },
      }
    );
    session.status = "signed-in";
    session.token = "a-real-token";
    renderApp();

    await waitFor(
      () => {
        expect(screen.getByText(/cannot read the mesh registry/i)).toBeInTheDocument();
      },
      { timeout: 5000 }
    );
    expect(screen.getByRole("status")).toHaveTextContent(/degraded/i);
  });

  /**
   * A rejected session is named as such rather than shown as a broken dashboard. The operator needs
   * to know their token is the problem, not the cluster.
   */
  it("says so when the session is rejected", async () => {
    stubFetch(401, {
      error: { code: "UNAUTHORIZED", message: "A valid bearer token is required." },
      meta: {},
    });
    session.status = "signed-in";
    session.token = "a-stale-token";
    renderApp();

    // The read retries once by design, so the settled failure is the second attempt: waiting past
    // the backoff asserts the screen an operator actually ends up looking at.
    await waitFor(
      () => {
        expect(screen.getByRole("status")).toHaveTextContent(/session rejected/i);
      },
      { timeout: 5000 }
    );
  });

  /**
   * An unreachable baseline is distinguished from a rejected session, because the two need
   * different actions from the operator: one is theirs to fix, the other is not.
   */
  it("distinguishes an unreachable baseline from a rejected session", async () => {
    stubFetch(503, {
      error: { code: "UNAVAILABLE", message: "A required dependency is currently unavailable." },
      meta: {},
    });
    session.status = "signed-in";
    session.token = "a-real-token";
    renderApp();

    await waitFor(
      () => {
        expect(screen.getByRole("status")).toHaveTextContent(/cannot reach this baseline/i);
      },
      { timeout: 5000 }
    );
  });
});
