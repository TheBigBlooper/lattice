import { describe, expect, it } from "vitest";
import { PANEL_HELP } from "../../shared/panelHelpContent.ts";
import { CARDS } from "./cards.ts";
import type { MetricSample } from "./metrics.ts";
import { seriesKey } from "./metrics.ts";

/** A sample, with defaults for the fields a given test does not care about. */
function sample(
  name: string,
  value: number,
  labels: Record<string, string> = {},
  kind: MetricSample["kind"] = "GAUGE"
): MetricSample {
  return { kind, labels, name, value };
}

/** Reads one card by id, so a test names the card rather than an index. */
function read(
  id: string,
  samples: MetricSample[],
  history: ReadonlyMap<string, readonly number[]> = new Map()
) {
  const card = CARDS.find((candidate) => candidate.id === id);
  if (!card) {
    throw new Error(`no card ${id}`);
  }
  return card.read(samples, history);
}

describe("the card set", () => {
  it("has an entry for each of the six confirmed cards", () => {
    expect(CARDS.map((card) => card.id)).toEqual([
      "mesh-link",
      "peers-reachable",
      "announces",
      "expiries",
      "datastore",
      "failed-reads",
    ]);
  });

  it("points every card at help copy that exists, in the console's shared voice", () => {
    for (const card of CARDS) {
      const help = PANEL_HELP[card.helpKey];
      expect(help, `no help copy for ${card.id}`).toBeDefined();
      expect(help.shows).toBeTruthy();
      expect(help.source).toBeTruthy();
      expect(help.omits).toBeTruthy();
    }
  });
});

describe("mesh link", () => {
  it("reads a word as well as a tone, so colour is never the only signal", () => {
    expect(read("mesh-link", [sample("lattice.mesh.link.up", 1)])).toMatchObject({
      display: "Up",
      tone: "success",
    });
    expect(read("mesh-link", [sample("lattice.mesh.link.up", 0)])).toMatchObject({
      display: "Down",
      tone: "error",
    });
  });

  it("says it does not know rather than guessing when the meter is absent", () => {
    expect(read("mesh-link", [])).toMatchObject({ display: "Unknown", tone: "neutral" });
  });
});

describe("peers reachable", () => {
  it("reads reachable against known, and is well when they match", () => {
    const samples = [
      sample("lattice.mesh.peers.known", 2),
      sample("lattice.mesh.peers.reachable", 2),
    ];

    expect(read("peers-reachable", samples)).toMatchObject({ display: "2 / 2", tone: "success" });
  });

  it("warns when a peer has gone quiet without losing the total", () => {
    const samples = [
      sample("lattice.mesh.peers.known", 2),
      sample("lattice.mesh.peers.reachable", 1),
    ];

    expect(read("peers-reachable", samples)).toMatchObject({ display: "1 / 2", tone: "warning" });
  });
});

describe("announces", () => {
  it("waits for a second reading rather than printing a rate it cannot know", () => {
    const published = sample("lattice.mesh.announcements.published", 46, {}, "COUNTER");
    const history = new Map([[seriesKey(published), [46]]]);

    expect(read("announces", [published], history)).toMatchObject({
      display: "-",
      tone: "neutral",
    });
  });

  it("derives a rate once it has history", () => {
    const published = sample("lattice.mesh.announcements.published", 51, {}, "COUNTER");
    const history = new Map([[seriesKey(published), [45, 46, 47, 48, 49, 50, 51]]]);

    expect(read("announces", [published], history)).toMatchObject({
      display: "6",
      tone: "success",
      unit: "per minute",
    });
  });

  it("warns when the counter has stopped moving, which is the failure worth seeing", () => {
    const published = sample("lattice.mesh.announcements.published", 46, {}, "COUNTER");
    const history = new Map([[seriesKey(published), [46, 46, 46, 46]]]);

    expect(read("announces", [published], history)).toMatchObject({
      display: "0",
      tone: "warning",
    });
  });
});

describe("expiries", () => {
  it("totals every peer's expiries and is well at zero", () => {
    expect(read("expiries", [])).toMatchObject({ display: "0", tone: "success" });
  });

  it("warns once any peer has aged out", () => {
    const samples = [
      sample("lattice.mesh.peer.expiries", 2, { peer_cluster: "hub-east" }, "COUNTER"),
      sample("lattice.mesh.peer.expiries", 1, { peer_cluster: "hub-west" }, "COUNTER"),
    ];

    expect(read("expiries", samples)).toMatchObject({ display: "3", tone: "warning" });
  });
});

describe("datastore", () => {
  it("reads a mean in milliseconds from the timer's total and count", () => {
    const samples = [
      sample("lattice.elasticsearch.operation", 4, { statistic: "count" }, "TIMER"),
      sample("lattice.elasticsearch.operation", 0.048, { statistic: "total" }, "TIMER"),
    ];

    expect(read("datastore", samples)).toMatchObject({ display: "12", unit: "ms mean" });
  });

  it("says there have been no calls rather than dividing by zero", () => {
    expect(read("datastore", [])).toMatchObject({ display: "-", tone: "neutral" });
  });
});

describe("failed reads", () => {
  it("is well at zero and an error above it", () => {
    expect(read("failed-reads", [])).toMatchObject({ display: "0", tone: "success" });

    const failed = [sample("lattice.elasticsearch.operation.errors", 2, {}, "COUNTER")];
    expect(read("failed-reads", failed)).toMatchObject({ display: "2", tone: "error" });
  });
});
