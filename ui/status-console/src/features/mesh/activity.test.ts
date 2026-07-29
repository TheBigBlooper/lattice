import { describe, expect, it } from "vitest";
import type { Peer } from "../../api/usePeers.ts";
import { type MeshSnapshot, transitionsBetween } from "./activity.ts";

/** A peer at a given reachability, with everything else held still. */
function peer(
  clusterId: string,
  reachability: "REACHABLE" | "UNREACHABLE",
  health: "ready" | "degraded" | "down" = "ready"
): Peer {
  return {
    clusterId,
    region: "us-test",
    baselineVersion: "0.1.0",
    health,
    consoleUrl: `http://${clusterId}:3000`,
    apiBaseUrl: `http://${clusterId}:8082/api/v1`,
    lastSeen: "2026-07-28T00:00:00Z",
    reachability,
  };
}

function snapshot(peers: Peer[], meshLink: "up" | "down" = "up"): MeshSnapshot {
  return { peers, meshLink };
}

describe("transitionsBetween", () => {
  /**
   * The first poll has nothing to compare against, so it reports nothing.
   *
   * <p>Without this every peer the console has ever discovered announces itself the moment the page
   * opens - a wall of toasts describing no change at all, which teaches an operator to dismiss them
   * without reading, exactly when the next one matters.
   */
  it("reports nothing on the first poll", () => {
    expect(transitionsBetween(undefined, snapshot([peer("hub-east", "REACHABLE")]))).toEqual([]);
  });

  /** A peer ageing out is the event this whole feature exists to surface. */
  it("reports a peer going quiet", () => {
    const before = snapshot([peer("hub-east", "REACHABLE")]);
    const after = snapshot([peer("hub-east", "UNREACHABLE")]);

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "peer-lost", subject: "hub-east", message: "hub-east went quiet" },
    ]);
  });

  /** And its recovery, which is the half an operator waits for during an incident. */
  it("reports a peer coming back", () => {
    const before = snapshot([peer("hub-east", "UNREACHABLE")]);
    const after = snapshot([peer("hub-east", "REACHABLE")]);

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "peer-returned", subject: "hub-east", message: "hub-east came back" },
    ]);
  });

  /** A peer that has not moved says nothing, however many polls go by. */
  it("stays silent when nothing changed", () => {
    const still = snapshot([peer("hub-east", "REACHABLE"), peer("hub-west", "REACHABLE")]);

    expect(transitionsBetween(still, snapshot([...still.peers]))).toEqual([]);
  });

  /**
   * Losing the broker is ONE event, not one per peer.
   *
   * <p>A cluster cut off from the mesh hears nothing, so its registry ages every peer out at the same
   * moment. Reported naively that is three "went quiet" toasts for three baselines that are almost
   * certainly fine and talking to each other - the console blaming its peers for its own outage. This
   * is exactly why the gateway reports its own link state separately, and why the two situations
   * need different words rather than the same one repeated.
   */
  it("coalesces losing the mesh link into a single entry", () => {
    const before = snapshot([peer("hub-east", "REACHABLE"), peer("hub-west", "REACHABLE")]);
    const after = snapshot(
      [peer("hub-east", "UNREACHABLE"), peer("hub-west", "UNREACHABLE")],
      "down"
    );

    expect(transitionsBetween(before, after)).toEqual([
      {
        kind: "mesh-lost",
        subject: "mesh",
        message: "Mesh link down - peer data is last-known",
      },
    ]);
  });

  /** The recovery is likewise one line, not one per peer rejoining. */
  it("coalesces regaining the mesh link into a single entry", () => {
    const before = snapshot([peer("hub-east", "UNREACHABLE")], "down");
    const after = snapshot([peer("hub-east", "REACHABLE")]);

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "mesh-returned", subject: "mesh", message: "Mesh link restored" },
    ]);
  });

  /**
   * While the link is down, peer transitions stay silent.
   *
   * <p>They are not observations - a cut-off cluster has no evidence about anyone. Reporting them
   * would attribute this baseline's blindness to the baselines it cannot see.
   */
  it("says nothing about peers while cut off", () => {
    const before = snapshot(
      [peer("hub-east", "UNREACHABLE"), peer("hub-west", "REACHABLE")],
      "down"
    );
    const after = snapshot(
      [peer("hub-east", "UNREACHABLE"), peer("hub-west", "UNREACHABLE")],
      "down"
    );

    expect(transitionsBetween(before, after)).toEqual([]);
  });

  /** A peer changing health while reachable is worth saying, and names both states. */
  it("reports a reachable peer changing health", () => {
    const before = snapshot([peer("hub-east", "REACHABLE", "ready")]);
    const after = snapshot([peer("hub-east", "REACHABLE", "degraded")]);

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "peer-health", subject: "hub-east", message: "hub-east went ready to degraded" },
    ]);
  });

  /** A newly discovered baseline is an arrival, not a recovery - it was never here to come back. */
  it("reports a baseline appearing for the first time", () => {
    const before = snapshot([peer("hub-east", "REACHABLE")]);
    const after = snapshot([peer("hub-east", "REACHABLE"), peer("hub-west", "REACHABLE")]);

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "peer-joined", subject: "hub-west", message: "hub-west joined the mesh" },
    ]);
  });

  /** Several independent peers moving in one poll each get their own line. */
  it("reports each peer separately when the link is healthy", () => {
    const before = snapshot([peer("hub-east", "REACHABLE"), peer("hub-west", "UNREACHABLE")]);
    const after = snapshot([peer("hub-east", "UNREACHABLE"), peer("hub-west", "REACHABLE")]);

    expect(transitionsBetween(before, after)).toHaveLength(2);
  });
});
