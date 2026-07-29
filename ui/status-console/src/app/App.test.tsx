import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ConsoleConfig } from "../config.ts";
import { App } from "./App.tsx";

const config: ConsoleConfig = {
  apiBaseUrl: "http://hub-central:8082/api/v1",
  ordersBaseUrl: "http://hub-central:8080/api/v1",
  inventoryBaseUrl: "http://hub-central:8081/api/v1",
  clusterId: "hub-central",
  region: "us-central",
  baselineVersion: "0.1.0-SNAPSHOT",
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
vi.mock("../auth/useSession.ts", () => ({
  useSession: () => session,
}));

const BASELINE = {
  clusterId: "hub-central",
  region: "us-central",
  baselineVersion: "0.1.0-SNAPSHOT",
  apiVersions: ["v1"],
  health: "degraded",
  // Three services, because the gateway now lists itself in this baseline's own breakdown. The
  // count is read from the payload rather than assumed anywhere, and this fixture is what proves it.
  services: [
    { name: "orders", status: "UP" },
    { name: "inventory", status: "UP" },
    { name: "mesh-gateway", status: "DOWN" },
  ],
  infrastructure: [
    { kind: "elasticsearch", name: "elasticsearch", status: "UP" },
    { kind: "artemis", name: "artemis", status: "UP" },
    {
      detail: "management endpoint returned 503",
      kind: "keycloak",
      name: "keycloak",
      status: "DEGRADED",
    },
  ],
};

/**
 * The same baseline from a deployment that configures no infrastructure at all. The field is
 * dropped rather than emptied by serialisation, which is exactly what such a gateway sends.
 */
const BASELINE_WITHOUT_INFRASTRUCTURE = { ...BASELINE, infrastructure: undefined };

/**
 * Renders the shell inside a query client that does not retry, so a failure settles at once, and a
 * memory router, since the app bar's destinations read the current route.
 */
function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
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

  /**
   * The shell mounts, and the app bar names the baseline.
   *
   * This previously asserted the page background came from the palette rather than a literal. That
   * check moved rather than disappeared: the surface is now painted by the theme through Material's
   * baseline reset, and "no colour outside the theme file" is enforced for the whole console by the
   * token gate rather than by one assertion here.
   */
  it("renders the shell and names the baseline", () => {
    stubFetch(200, { data: BASELINE, meta: {} });
    renderApp();

    expect(screen.getByRole("main")).toBeInTheDocument();
    // Scoped to the app bar: the baseline is now also a heading on the signed-out card, so an
    // unscoped query matches two elements and says nothing about which one carries the identity.
    expect(
      within(screen.getByRole("banner")).getByRole("heading", { name: /hub-central/ })
    ).toBeInTheDocument();
  });

  /**
   * Without a session the console offers the way in rather than an empty dashboard. It asserts the
   * action and the destination rather than the words "signed out": the screen is a landing page for
   * an operator arriving by redirect, and what it must carry is which baseline they are entering.
   */
  it("starts by offering a way in, naming the baseline", () => {
    stubFetch(200, { data: BASELINE, meta: {} });
    renderApp();

    expect(screen.getByRole("button", { name: /sign in/i })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(/hub-central/);
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
    expect(screen.getByRole("status")).toHaveTextContent(/2 of 3 services ready/i);
  });

  /**
   * The infrastructure the services depend on is rendered beneath them, from the same read.
   *
   * <p>Before this, an operator could not see that Elasticsearch, Artemis and Keycloak existed at
   * all, let alone that one of them was unhappy. The detail line is asserted here rather than only
   * in the card's own test because it travels the whole way from the payload to the screen.
   */
  it("shows the baseline's infrastructure beneath its services", async () => {
    stubFetch(200, { data: BASELINE, meta: {} });
    session.status = "signed-in";
    session.token = "a-real-token";
    renderApp();

    await waitFor(() => {
      expect(screen.getByRole("region", { name: /infrastructure/i })).toBeInTheDocument();
    });
    const card = screen.getByRole("region", { name: /infrastructure/i });
    expect(within(card).getByText("keycloak")).toBeInTheDocument();
    expect(within(card).getByText("management endpoint returned 503")).toBeInTheDocument();
    expect(within(card).getByText(/2 of 3 components healthy/i)).toBeInTheDocument();
  });

  /**
   * A baseline that configures no infrastructure shows no card, rather than one reading "unknown".
   * An empty card on a status screen reads as a fault; an absent one reads as an unset option.
   */
  it("shows no infrastructure card when the baseline reports none", async () => {
    stubFetch(200, { data: BASELINE_WITHOUT_INFRASTRUCTURE, meta: {} });
    session.status = "signed-in";
    session.token = "a-real-token";
    renderApp();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/degraded/i);
    });
    expect(screen.queryByRole("region", { name: /infrastructure/i })).not.toBeInTheDocument();
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
      expect(screen.getByText("1 of 1 Peers Reachable")).toBeInTheDocument();
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

  /**
   * A refusal is not an outage. An operator redirected to a peer where they hold no role used to
   * be told the baseline could not be reached, which reports a broken federation when the baseline
   * is serving perfectly and only their grant is missing. Membership is deliberately
   * unsynchronised across realms, so this is an ordinary outcome and must read as one.
   */
  it("names a refusal as missing access rather than an outage", async () => {
    stubFetch(403, {
      error: { code: "FORBIDDEN", message: "The operator role is required." },
      meta: {},
    });
    session.status = "signed-in";
    session.token = "a-real-token";
    renderApp();

    await waitFor(
      () => {
        expect(screen.getByRole("status")).toHaveTextContent(/no access on hub-central/i);
      },
      { timeout: 5000 }
    );
    expect(screen.getByRole("status")).not.toHaveTextContent(/cannot reach/i);
    expect(screen.getByText(/reachable and healthy/i)).toBeInTheDocument();
  });

  /**
   * The first read shows a spinner, not a sentence.
   *
   * Words here are read as a fault rather than as information: they appear only on a first paint,
   * are gone before they can be finished, and the operator has already been shown one spinner for
   * the session check moments earlier. The label carries the meaning for a screen reader, where a
   * spinner alone would say nothing at all.
   */
  it("shows a spinner while the baseline is first read", () => {
    // A fetch that never settles, which is the state this screen exists for.
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise(() => {}))
    );
    session.status = "signed-in";
    session.token = "a-token";

    renderApp();

    expect(screen.getByRole("progressbar", { name: /reading this baseline/i })).toBeInTheDocument();
    expect(screen.queryByText("Reading this baseline")).not.toBeInTheDocument();
  });
});
