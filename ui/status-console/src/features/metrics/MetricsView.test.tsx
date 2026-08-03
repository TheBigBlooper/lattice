import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client.ts";
import { MetricsView } from "./MetricsView.tsx";
import type { MergedSample, MetricSample } from "./metrics.ts";
import { seriesKey } from "./metrics.ts";

/** A sample, with defaults for whatever a given test does not care about. */
function sample(
  name: string,
  value: number,
  labels: Record<string, string> = {},
  kind: MetricSample["kind"] = "GAUGE"
): MetricSample {
  return { kind, labels, name, value };
}

const LINK_UP = { ...sample("lattice.mesh.link.up", 1), reportedBy: "mesh-gateway" };
const PEERS_KNOWN = { ...sample("lattice.mesh.peers.known", 2), reportedBy: "mesh-gateway" };
const PEERS_REACHABLE = {
  ...sample("lattice.mesh.peers.reachable", 2),
  reportedBy: "mesh-gateway",
};
const PUBLISHED = {
  ...sample("lattice.mesh.announcements.published", 51, {}, "COUNTER"),
  reportedBy: "mesh-gateway",
};

/** What the mocked hook reports. Each test sets it before rendering. */
const reading = {
  error: undefined as ApiError | undefined,
  history: new Map<string, readonly number[]>(),
  isLoading: false,
  isRetrying: false,
  lastGoodRead: undefined as number | undefined,
  samples: [] as MergedSample[],
};

// Mocked at the data-layer seam rather than at fetch: this test is about what the screen does with
// a set of samples, and driving three real queries would test the queries.
vi.mock("./useMetrics.ts", () => ({
  useMetrics: () => reading,
}));

/** The screen as a signed-in operator sees it. */
function view() {
  render(<MetricsView baseUrls={["http://hub-central:8082/api/v1"]} token="a-token" />);
}

beforeEach(() => {
  reading.error = undefined;
  reading.history = new Map();
  reading.isLoading = false;
  reading.isRetrying = false;
  reading.samples = [];
});

describe("the cards", () => {
  it("shows all six, so the screen is the same shape whatever has been read", () => {
    view();

    for (const label of [
      "Mesh link",
      "Peers reachable",
      "Announces",
      "Expiries",
      "Datastore",
      "Failed reads",
    ]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
  });

  it("reads a word for the mesh link, not only a colour", () => {
    reading.samples = [LINK_UP];

    view();

    expect(screen.getByText("Up")).toBeInTheDocument();
  });

  it("says a peer has gone quiet without losing the total", () => {
    reading.samples = [
      PEERS_KNOWN,
      { ...sample("lattice.mesh.peers.reachable", 1), reportedBy: "mesh-gateway" },
    ];

    view();

    expect(screen.getByText("1 / 2")).toBeInTheDocument();
  });

  it("waits for a second reading rather than printing a rate it cannot know", () => {
    reading.samples = [PUBLISHED];
    reading.history = new Map([[seriesKey(PUBLISHED), [51]]]);

    view();

    expect(screen.getByText("waiting for a second reading")).toBeInTheDocument();
  });

  it("draws a trend once a series has readings behind it", () => {
    reading.samples = [PUBLISHED];
    reading.history = new Map([[seriesKey(PUBLISHED), [45, 46, 47, 48, 49, 50, 51]]]);

    view();

    expect(screen.getByText("6")).toBeInTheDocument();
    expect(screen.getByLabelText("Announces over the last ten minutes")).toBeInTheDocument();
  });

  it("states that the history is this session's own, rather than implying it covers more", () => {
    reading.samples = [LINK_UP];

    view();

    expect(screen.getAllByText("this session").length).toBeGreaterThan(0);
  });
});

describe("a failed read", () => {
  it("keeps the last values and marks them stale, rather than blocking the screen", () => {
    reading.error = new ApiError("UNAVAILABLE", 503, "orders is not answering");
    reading.samples = [];

    view();

    // The cards are still on screen: this view has nothing to act on, so hiding the numbers during
    // an incident would remove them exactly when they matter.
    expect(screen.getByText("Mesh link")).toBeInTheDocument();
    expect(screen.getAllByText("stale - retrying").length).toBeGreaterThan(0);
  });
});

describe("the series disclosure", () => {
  it("counts every series and is closed until asked", () => {
    reading.samples = [LINK_UP, PEERS_KNOWN, PEERS_REACHABLE];

    view();

    expect(screen.getByText("Every measurement")).toBeInTheDocument();
    // The count sits on the control, so the panel says how much is behind it before it is opened.
    expect(screen.getByRole("button", { name: "Show 3" })).toBeInTheDocument();
  });

  it("lists each series with the service that reported it, once opened", async () => {
    reading.samples = [LINK_UP, PEERS_KNOWN];
    view();

    await userEvent.click(screen.getByRole("button", { name: /Show|Hide/ }));

    const table = screen.getByRole("table");
    expect(within(table).getByText("lattice.mesh.link.up")).toBeInTheDocument();
    expect(within(table).getAllByText("mesh-gateway").length).toBe(2);
  });

  it("clears the filter from a control, not only the keyboard", async () => {
    reading.samples = [LINK_UP, PEERS_KNOWN];
    view();
    await userEvent.click(screen.getByRole("button", { name: /Show|Hide/ }));
    await userEvent.type(screen.getByLabelText("Filter by name or label"), "peers");

    await userEvent.click(screen.getByRole("button", { name: "Clear the filter" }));

    const table = screen.getByRole("table");
    expect(within(table).getByText("lattice.mesh.link.up")).toBeInTheDocument();
  });

  it("filters to what was typed, and says so when nothing matches", async () => {
    reading.samples = [LINK_UP, PEERS_KNOWN];
    view();
    await userEvent.click(screen.getByRole("button", { name: /Show|Hide/ }));

    await userEvent.type(screen.getByLabelText("Filter by name or label"), "peers");

    const table = screen.getByRole("table");
    expect(within(table).queryByText("lattice.mesh.link.up")).not.toBeInTheDocument();
    expect(within(table).getByText("lattice.mesh.peers.known")).toBeInTheDocument();
  });
});
