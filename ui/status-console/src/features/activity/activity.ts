import type { components } from "../../api/generated/v1.ts";
import type { Peer } from "../../api/usePeers.ts";

/** One service's readiness, exactly as the contract defines it. */
type ServiceHealth = components["schemas"]["ServiceHealth"];

/** One infrastructure component's state, exactly as the contract defines it. */
type ComponentHealth = components["schemas"]["ComponentHealth"];

/** What this console could see at one poll - both this baseline's own half and the mesh. */
export interface ConsoleSnapshot {
  /** The peers its registry held. */
  peers: Peer[];
  /** Whether it could reach the mesh at all. Absent is read as reachable. */
  meshLink?: components["schemas"]["MeshLinkState"];
  /** This baseline's own services. Absent is a baseline serving no breakdown, not a fault. */
  services?: ServiceHealth[];
  /** The infrastructure beneath them. Absent when nothing is configured, which is supported. */
  infrastructure?: ComponentHealth[];
}

/**
 * The kinds of change worth telling an operator about.
 *
 * <p>Local and mesh kinds sit in one union rather than two types, because they travel in one
 * timeline: an operator reading about Keycloak dying and a peer going unreachable is usually reading
 * about one incident, and splitting the type would split the stream that shows it.
 */
export type TransitionKind =
  | "peer-lost"
  | "peer-returned"
  | "peer-joined"
  | "peer-health"
  | "mesh-lost"
  | "mesh-returned"
  | "service-lost"
  | "service-returned"
  | "component-degraded"
  | "component-lost"
  | "component-returned";

/** Which half of the screen an entry is about. */
export type TransitionScope = "local" | "mesh";

/**
 * Which half of the screen a kind belongs to.
 *
 * <p><b>Derived rather than stored.</b> The kind already determines the scope, so carrying a second
 * field on every transition would be one fact in two places - and the two would drift the first time
 * a kind was added and its scope was not. The log tags a line by asking this.
 *
 * <p>A keyed record rather than a switch, and that is the load-bearing part: `Record` over a closed
 * union demands every kind be listed, so adding one without answering this question fails to
 * compile. A switch would need a default clause to satisfy the linter, and a default is exactly the
 * silent wrong answer this has to avoid - it would file an unclassified kind under one half of the
 * screen without anyone deciding it should be there.
 *
 * @param kind the kind of change observed.
 * @returns whether it happened here or out on the mesh.
 */
export function scopeForKind(kind: TransitionKind): TransitionScope {
  return SCOPES[kind];
}

/** Which half each kind belongs to. Every kind appears, or this does not compile. */
const SCOPES: Record<TransitionKind, TransitionScope> = {
  "peer-lost": "mesh",
  "peer-returned": "mesh",
  "peer-joined": "mesh",
  "peer-health": "mesh",
  "mesh-lost": "mesh",
  "mesh-returned": "mesh",
  "service-lost": "local",
  "service-returned": "local",
  "component-degraded": "local",
  "component-lost": "local",
  "component-returned": "local",
};

/** One thing that changed between two polls. */
export interface Transition {
  /** What kind of change it was, which decides how it is drawn. */
  kind: TransitionKind;
  /** What it happened to - a baseline id, or the mesh itself. */
  subject: string;
  /** The sentence shown to an operator. */
  message: string;
}

/**
 * What changed between two polls of the mesh.
 *
 * <p><b>A pure comparison of two snapshots.</b> It holds no state, starts no timers and renders
 * nothing, so every case below - including the ones that are awkward to reach in a browser - is
 * settled by a test rather than by watching a stack of three baselines.
 *
 * <p><b>The first poll reports nothing.</b> There is no previous state to have changed from, and
 * announcing every peer on page load would be a wall of toasts describing no change at all - which
 * teaches an operator to dismiss them unread, exactly when the next one matters.
 *
 * <p><b>Losing the link is one event, not one per peer.</b> A cluster cut off from the mesh hears
 * nothing, so its registry ages every peer out at the same moment. Reported naively that is a toast
 * per baseline for baselines that are almost certainly fine and talking to each other - the console
 * blaming its peers for its own outage. While the link is down, peer transitions are suppressed
 * entirely for the same reason: a cut-off cluster has no evidence about anyone.
 *
 * <p><b>None of that suppression reaches this baseline's own half.</b> The mesh rules above exist
 * because a blind cluster has no evidence about its peers; it has complete evidence about itself,
 * since the baseline read never leaves this cluster. Silencing local events while the mesh is down
 * would blank the half of the screen that still works, at exactly the moment it is being read.
 *
 * @param previous the snapshot from the last poll, or undefined on the first.
 * @param current the snapshot just read.
 * @returns what changed, mesh first then local, in the order it should be read; empty when nothing
 *     did.
 */
export function transitionsBetween(
  previous: ConsoleSnapshot | undefined,
  current: ConsoleSnapshot
): Transition[] {
  if (!previous) {
    return [];
  }
  return [...meshTransitions(previous, current), ...localTransitions(previous, current)];
}

/** What changed out on the mesh, under the coalescing and blindness rules described above. */
function meshTransitions(previous: ConsoleSnapshot, current: ConsoleSnapshot): Transition[] {
  const link = linkTransition(previous, current);
  if (link) {
    return [link];
  }
  // Still cut off: nothing observed about a peer during this window is an observation.
  return current.meshLink === "down" ? [] : peerTransitions(previous.peers, current.peers);
}

/** What changed about this baseline itself - its services, then the infrastructure beneath them. */
function localTransitions(previous: ConsoleSnapshot, current: ConsoleSnapshot): Transition[] {
  return [
    ...serviceTransitions(previous.services ?? [], current.services ?? []),
    ...componentTransitions(previous.infrastructure ?? [], current.infrastructure ?? []),
  ];
}

/**
 * A service falling over or recovering.
 *
 * <p>A service absent from the previous poll yields nothing. Unlike a peer, whose arrival is a
 * discovery worth reporting, services are a configured list - a new name means the deployment
 * changed, and reporting it would announce a rollout as an incident.
 */
function serviceTransitions(before: ServiceHealth[], after: ServiceHealth[]): Transition[] {
  const previous = new Map(before.map((entry) => [entry.name, entry]));
  return after.flatMap((service): Transition[] => {
    const was = previous.get(service.name);
    if (!was || was.status === service.status) {
      return [];
    }
    return service.status === "UP"
      ? [{ kind: "service-returned", subject: service.name, message: `${service.name} came back` }]
      : [{ kind: "service-lost", subject: service.name, message: `${service.name} went down` }];
  });
}

/**
 * A component moving between its three states.
 *
 * <p>The kind is chosen by where it landed rather than where it came from: recovering from degraded
 * and recovering from down are the same news, and an operator reading the line cares what is true
 * now. The component's own `detail` rides along wherever it has one, because "degraded" alone does
 * not say which of Elasticsearch's several meanings applies.
 */
function componentTransitions(before: ComponentHealth[], after: ComponentHealth[]): Transition[] {
  const previous = new Map(before.map((entry) => [entry.name, entry]));
  return after.flatMap((component): Transition[] => {
    const was = previous.get(component.name);
    if (!was || was.status === component.status) {
      return [];
    }
    const [kind, verb] = landing(component.status);
    return [
      {
        kind,
        subject: component.name,
        message: withDetail(`${component.name} ${verb}`, component.detail),
      },
    ];
  });
}

/**
 * How a component's new state reads.
 *
 * <p>An unrecognised state reads as down rather than as healthy, matching how the rest of the
 * console treats one: baselines are versioned independently, so a console older than the gateway it
 * reads is ordinary, and guessing upward would report a component as fine on the strength of not
 * understanding it.
 */
function landing(status: ComponentHealth["status"]): [TransitionKind, string] {
  switch (status) {
    case "UP":
      return ["component-returned", "recovered"];
    case "DEGRADED":
      return ["component-degraded", "degraded"];
    default:
      return ["component-lost", "went down"];
  }
}

/** The component's own reading appended, when it sent one - never a dangling dash when it did not. */
function withDetail(message: string, detail: string | undefined): string {
  return detail ? `${message} - ${detail}` : message;
}

/**
 * The one entry that stands for this baseline gaining or losing its own view of the mesh, or
 * nothing when the link did not move.
 *
 * <p>Separate from the peer comparison because it <em>replaces</em> it. When the link drops, every
 * peer ages out at the same moment, and the per-peer story is an artefact of this cluster being
 * blind rather than anything those baselines did.
 */
function linkTransition(
  previous: ConsoleSnapshot,
  current: ConsoleSnapshot
): Transition | undefined {
  const wasCutOff = previous.meshLink === "down";
  const isCutOff = current.meshLink === "down";
  if (!wasCutOff && isCutOff) {
    return {
      kind: "mesh-lost",
      subject: "mesh",
      message: "Mesh link down - peer data is last-known",
    };
  }
  if (wasCutOff && !isCutOff) {
    return { kind: "mesh-returned", subject: "mesh", message: "Mesh link restored" };
  }
  return undefined;
}

/** What changed about each baseline, for a cluster that could actually see them. */
function peerTransitions(before: Peer[], after: Peer[]): Transition[] {
  const previous = new Map(before.map((entry) => [entry.clusterId, entry]));
  return after.flatMap((peer) => {
    const was = previous.get(peer.clusterId);
    if (!was) {
      // Never seen before, so an arrival rather than a recovery - it was not here to return.
      return [
        {
          kind: "peer-joined",
          subject: peer.clusterId,
          message: `${peer.clusterId} joined the mesh`,
        } as Transition,
      ];
    }
    return reachabilityChange(was, peer) ?? healthChange(was, peer);
  });
}

/** A baseline going quiet or coming back, or nothing when its reachability held. */
function reachabilityChange(was: Peer, now: Peer): Transition[] | undefined {
  const wasReachable = was.reachability === "REACHABLE";
  const isReachable = now.reachability === "REACHABLE";
  if (wasReachable && !isReachable) {
    return [{ kind: "peer-lost", subject: now.clusterId, message: `${now.clusterId} went quiet` }];
  }
  if (!wasReachable && isReachable) {
    return [
      { kind: "peer-returned", subject: now.clusterId, message: `${now.clusterId} came back` },
    ];
  }
  return undefined;
}

/**
 * A reachable baseline changing its rollup.
 *
 * <p>Only while reachable: a silent peer health is a memory, and reporting a memory as a change
 * would invent an event nobody observed.
 */
function healthChange(was: Peer, now: Peer): Transition[] {
  if (now.reachability !== "REACHABLE" || was.health === now.health) {
    return [];
  }
  return [
    {
      kind: "peer-health",
      subject: now.clusterId,
      message: `${now.clusterId} went ${was.health} to ${now.health}`,
    },
  ];
}
