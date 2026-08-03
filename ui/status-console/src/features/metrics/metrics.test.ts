import { describe, expect, it } from "vitest";
import type { MetricSample } from "./metrics.ts";
import { appendReading, latest, ratePerMinute, seriesKey, sumOf } from "./metrics.ts";

/** A sample, with the fields a test cares about and defaults for the rest. */
function sample(
  name: string,
  value: number,
  labels: Record<string, string> = {},
  kind: MetricSample["kind"] = "COUNTER"
): MetricSample {
  return { kind, labels, name, value };
}

describe("seriesKey", () => {
  it("distinguishes two label sets of the same meter", () => {
    const east = sample("lattice.mesh.announcements.received", 46, { source_cluster: "hub-east" });
    const west = sample("lattice.mesh.announcements.received", 45, { source_cluster: "hub-west" });

    expect(seriesKey(east)).not.toBe(seriesKey(west));
  });

  it("is stable regardless of label order", () => {
    const one = sample("lattice.service.readiness.polls", 1, { outcome: "up", service: "orders" });
    const other = sample("lattice.service.readiness.polls", 1, {
      service: "orders",
      outcome: "up",
    });

    expect(seriesKey(one)).toBe(seriesKey(other));
  });

  it("separates the same meter reported by two different services", () => {
    const base = sample("lattice.elasticsearch.operation.errors", 0);
    const fromOrders = { ...base, reportedBy: "orders" };
    const fromInventory = { ...base, reportedBy: "inventory" };

    expect(seriesKey(fromOrders)).not.toBe(seriesKey(fromInventory));
  });

  it("keeps a meter about one service apart from a meter about another", () => {
    // The readiness counter is published BY the gateway ABOUT each watched service, so the label
    // and the reporter are different facts. Collapsing them merged three series into one history.
    const reporter = { reportedBy: "mesh-gateway" };
    const orders = {
      ...sample("lattice.service.readiness.polls", 207, { outcome: "up", service: "orders" }),
      ...reporter,
    };
    const inventory = {
      ...sample("lattice.service.readiness.polls", 211, { outcome: "up", service: "inventory" }),
      ...reporter,
    };

    expect(seriesKey(orders)).not.toBe(seriesKey(inventory));
  });
});

describe("sumOf", () => {
  it("adds every series of one meter, so a per-peer counter reads as a total", () => {
    const samples = [
      sample("lattice.mesh.announcements.received", 46, { source_cluster: "hub-east" }),
      sample("lattice.mesh.announcements.received", 45, { source_cluster: "hub-west" }),
      sample("lattice.mesh.link.up", 1, {}, "GAUGE"),
    ];

    expect(sumOf(samples, "lattice.mesh.announcements.received")).toBe(91);
  });

  it("is zero when no series matches, rather than undefined", () => {
    expect(sumOf([], "lattice.mesh.peer.expiries")).toBe(0);
  });
});

describe("latest", () => {
  it("reads a gauge that carries no labels", () => {
    const samples = [sample("lattice.mesh.link.up", 1, {}, "GAUGE")];

    expect(latest(samples, "lattice.mesh.link.up")).toBe(1);
  });

  it("is undefined when the meter has not been reported", () => {
    expect(latest([], "lattice.mesh.link.up")).toBeUndefined();
  });
});

describe("appendReading", () => {
  it("keeps readings in order", () => {
    const history = appendReading(appendReading([], 1), 2);

    expect(history).toEqual([1, 2]);
  });

  it("caps the buffer, dropping the oldest", () => {
    let history: number[] = [];
    for (let reading = 0; reading < 65; reading += 1) {
      history = appendReading(history, reading);
    }

    expect(history).toHaveLength(60);
    expect(history[0]).toBe(5);
    expect(history.at(-1)).toBe(64);
  });

  it("does not mutate the buffer it was given", () => {
    const original = [1, 2];

    appendReading(original, 3);

    expect(original).toEqual([1, 2]);
  });
});

describe("ratePerMinute", () => {
  it("derives a per-minute rate from how far a counter moved across the window", () => {
    // Six polls ten seconds apart, the counter rising by one each time: one per ten seconds.
    const history = [10, 11, 12, 13, 14, 15];

    expect(ratePerMinute(history, 10_000)).toBe(6);
  });

  it("is undefined until two readings exist, because one reading is not a rate", () => {
    expect(ratePerMinute([], 10_000)).toBeUndefined();
    expect(ratePerMinute([10], 10_000)).toBeUndefined();
  });

  it("reads zero for a counter that has stopped moving, which is the signal that matters", () => {
    expect(ratePerMinute([46, 46, 46, 46], 10_000)).toBe(0);
  });

  it("ignores a counter reset rather than reporting a negative rate", () => {
    // A restarted service starts its counters again; the drop is not a negative rate.
    expect(ratePerMinute([100, 2, 3, 4], 10_000)).toBe(0);
  });
});
