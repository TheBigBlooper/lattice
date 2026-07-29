import type { components } from "../../api/generated/v1.ts";
import type { Peer } from "../../api/usePeers.ts";

/** What this baseline could see of the mesh at one poll. */
export interface MeshSnapshot {
  /** The peers its registry held. */
  peers: Peer[];
  /** Whether it could reach the mesh at all. Absent is read as reachable. */
  meshLink?: components["schemas"]["MeshLinkState"];
}

/** The kinds of change worth telling an operator about. */
export type TransitionKind =
  | "peer-lost"
  | "peer-returned"
  | "peer-joined"
  | "peer-health"
  | "mesh-lost"
  | "mesh-returned";

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
 * @param previous the snapshot from the last poll, or undefined on the first.
 * @param current the snapshot just read.
 * @returns what changed, in the order it should be read; empty when nothing did.
 */
export function transitionsBetween(
  previous: MeshSnapshot | undefined,
  current: MeshSnapshot
): Transition[] {
  if (!previous) {
    return [];
  }
  const link = linkTransition(previous, current);
  if (link) {
    return [link];
  }
  // Still cut off: nothing observed about a peer during this window is an observation.
  return current.meshLink === "down" ? [] : peerTransitions(previous.peers, current.peers);
}

/**
 * The one entry that stands for this baseline gaining or losing its own view of the mesh, or
 * nothing when the link did not move.
 *
 * <p>Separate from the peer comparison because it <em>replaces</em> it. When the link drops, every
 * peer ages out at the same moment, and the per-peer story is an artefact of this cluster being
 * blind rather than anything those baselines did.
 */
function linkTransition(previous: MeshSnapshot, current: MeshSnapshot): Transition | undefined {
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
