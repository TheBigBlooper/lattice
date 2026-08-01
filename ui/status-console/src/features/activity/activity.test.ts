import { describe, expect, it } from "vitest";
import type { components } from "../../api/generated/v1.ts";
import type { Peer } from "../../api/usePeers.ts";
import {
  type ConsoleSnapshot,
  scopeForKind,
  type TransitionKind,
  transitionsBetween,
} from "./activity.ts";

type ServiceHealth = components["schemas"]["ServiceHealth"];
type ComponentHealth = components["schemas"]["ComponentHealth"];

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

function snapshot(peers: Peer[], meshLink: "up" | "down" = "up"): ConsoleSnapshot {
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

  /**
   * A peer changing health while reachable is worth saying, and names both states.
   *
   * <p>It also carries the state it LANDED in, which no other kind needs. Every other kind names
   * its own outcome, so its drawing is fixed; a rollup change does not - "went ready to degraded"
   * and "went down to ready" are the same kind and opposite news. Without this field both were
   * drawn as one amber warning, so a baseline recovering rendered identically to one dying.
   */
  it("reports a reachable peer changing health, and where it landed", () => {
    const before = snapshot([peer("hub-east", "REACHABLE", "ready")]);
    const after = snapshot([peer("hub-east", "REACHABLE", "degraded")]);

    expect(transitionsBetween(before, after)).toEqual([
      {
        kind: "peer-health",
        landing: "degraded",
        message: "hub-east went ready to degraded",
        subject: "hub-east",
      },
    ]);
  });

  /** Recovering to ready is the same kind as falling to down, and must not be drawn as one. */
  it("carries a recovery's landing state as readily as a decline's", () => {
    const before = snapshot([peer("hub-east", "REACHABLE", "down")]);
    const after = snapshot([peer("hub-east", "REACHABLE", "ready")]);

    expect(transitionsBetween(before, after)).toEqual([
      {
        kind: "peer-health",
        landing: "ready",
        message: "hub-east went down to ready",
        subject: "hub-east",
      },
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

/** A service at a readiness, with nothing else moving. */
function service(name: string, status: "UP" | "DOWN"): ServiceHealth {
  return { name, status };
}

/** An infrastructure component at a state, optionally carrying its own reading. */
function component(
  name: string,
  status: "UP" | "DEGRADED" | "DOWN",
  detail?: string
): ComponentHealth {
  return { name, kind: "elasticsearch", status, ...(detail === undefined ? {} : { detail }) };
}

/** A snapshot of this baseline's own half, with a healthy mesh and no peers. */
function local(
  services: ServiceHealth[],
  infrastructure: ComponentHealth[] = [],
  meshLink: "up" | "down" = "up"
): ConsoleSnapshot {
  return { peers: [], meshLink, services, infrastructure };
}

describe("transitionsBetween - this baseline's own half", () => {
  /**
   * The first poll stays silent about local state for the same reason it does about peers: a
   * baseline coming up cold would otherwise fire a toast per service for services that are merely
   * starting, which is the wall of noise that teaches an operator to dismiss toasts unread.
   */
  it("reports nothing local on the first poll", () => {
    expect(transitionsBetween(undefined, local([service("orders", "UP")]))).toEqual([]);
  });

  /** A service falling over is the local event most worth interrupting for. */
  it("reports a service going down", () => {
    const before = local([service("orders", "UP")]);
    const after = local([service("orders", "DOWN")]);

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "service-lost", subject: "orders", message: "orders went down" },
    ]);
  });

  /** And its recovery, which is the half an operator is waiting for. */
  it("reports a service coming back", () => {
    const before = local([service("orders", "DOWN")]);
    const after = local([service("orders", "UP")]);

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "service-returned", subject: "orders", message: "orders came back" },
    ]);
  });

  /**
   * A component losing redundancy without losing service is the middle state a service cannot
   * express, and the detail line is what makes it actionable - "degraded" alone does not say which
   * of Elasticsearch's several meanings applies.
   */
  it("reports a component degrading, carrying its own reading", () => {
    const before = local([], [component("elasticsearch", "UP")]);
    const after = local([], [component("elasticsearch", "DEGRADED", "cluster status yellow")]);

    expect(transitionsBetween(before, after)).toEqual([
      {
        kind: "component-degraded",
        subject: "elasticsearch",
        message: "elasticsearch degraded - cluster status yellow",
      },
    ]);
  });

  /** A component with no detail still reads as a sentence rather than a dangling dash. */
  it("reports a component degrading with no detail to carry", () => {
    const before = local([], [component("keycloak", "UP")]);
    const after = local([], [component("keycloak", "DEGRADED")]);

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "component-degraded", subject: "keycloak", message: "keycloak degraded" },
    ]);
  });

  /** Unable to serve is a different event from merely degraded, and is drawn differently. */
  it("reports a component going down", () => {
    const before = local([], [component("elasticsearch", "DEGRADED")]);
    const after = local([], [component("elasticsearch", "DOWN", "cluster status red")]);

    expect(transitionsBetween(before, after)).toEqual([
      {
        kind: "component-lost",
        subject: "elasticsearch",
        message: "elasticsearch went down - cluster status red",
      },
    ]);
  });

  /** Recovery from either lower state is one event: it is serving fully again. */
  it("reports a component recovering", () => {
    const before = local([], [component("elasticsearch", "DEGRADED", "cluster status yellow")]);
    const after = local([], [component("elasticsearch", "UP", "cluster status green")]);

    expect(transitionsBetween(before, after)).toEqual([
      {
        kind: "component-returned",
        subject: "elasticsearch",
        message: "elasticsearch recovered - cluster status green",
      },
    ]);
  });

  /**
   * A service or component absent from the previous poll is not an event.
   *
   * <p>Unlike a peer, which is discovered and whose arrival is news, these are a configured list: a
   * name appearing means the deployment changed, and there is no earlier state it moved from.
   * Announcing it would report a rollout as an incident.
   */
  it("says nothing about a service it has not seen before", () => {
    const before = local([service("orders", "UP")]);
    const after = local([service("orders", "UP"), service("inventory", "UP")]);

    expect(transitionsBetween(before, after)).toEqual([]);
  });

  /**
   * The mesh being down must not silence this baseline's own half.
   *
   * <p>Peer transitions are suppressed while cut off because a blind cluster has no evidence about
   * anyone else. It has complete evidence about itself: the baseline read is local. Suppressing
   * local events here would blank the half of the screen that still works, at exactly the moment an
   * operator needs it.
   */
  it("still reports local changes while the mesh link is down", () => {
    const before = local([service("orders", "UP")], [], "down");
    const after = local([service("orders", "DOWN")], [], "down");

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "service-lost", subject: "orders", message: "orders went down" },
    ]);
  });

  /**
   * Losing the mesh link replaces the per-peer story, never the local one.
   *
   * <p>The link entry stands in for every peer ageing out at once. It says nothing about this
   * baseline's own services, so a service that fell over on the same poll must still be reported.
   */
  it("reports a local change alongside the mesh link dropping", () => {
    const before = local([service("orders", "UP")], [], "up");
    const after = local([service("orders", "DOWN")], [], "down");

    expect(transitionsBetween(before, after)).toEqual([
      { kind: "mesh-lost", subject: "mesh", message: "Mesh link down - peer data is last-known" },
      { kind: "service-lost", subject: "orders", message: "orders went down" },
    ]);
  });

  /** A snapshot carrying neither array is a baseline that reports no breakdown, not a fault. */
  it("says nothing when the baseline reports no local breakdown", () => {
    const before: ConsoleSnapshot = { peers: [], meshLink: "up" };
    const after: ConsoleSnapshot = { peers: [], meshLink: "up" };

    expect(transitionsBetween(before, after)).toEqual([]);
  });
});

describe("scopeForKind", () => {
  /** Every mesh kind reads as mesh, so a line can be tagged without a second source of truth. */
  it.each([
    "peer-lost",
    "peer-returned",
    "peer-joined",
    "peer-health",
    "mesh-lost",
    "mesh-returned",
  ])("reads %s as a mesh event", (kind) => {
    expect(scopeForKind(kind as TransitionKind)).toBe("mesh");
  });

  /** And every local kind as local. The kind already determines this; nothing stores it twice. */
  it.each([
    "service-lost",
    "service-returned",
    "component-degraded",
    "component-lost",
    "component-returned",
  ])("reads %s as a local event", (kind) => {
    expect(scopeForKind(kind as TransitionKind)).toBe("local");
  });
});
