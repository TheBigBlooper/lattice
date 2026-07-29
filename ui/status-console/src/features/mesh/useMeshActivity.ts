import { useEffect, useRef, useState } from "react";
import { type MeshSnapshot, type Transition, transitionsBetween } from "./activity.ts";

/** One thing that happened, stamped with when this console noticed it. */
export interface ActivityEntry extends Transition {
  /** A stable key, since two identical transitions can occur minutes apart. */
  id: string;
  /** When this console observed it, which is the poll it was noticed on, not when it happened. */
  at: Date;
}

/** What the status view needs to show activity. */
export interface MeshActivity {
  /** Everything noticed this session, newest first. */
  entries: ActivityEntry[];
  /** The few still worth interrupting for, newest last. */
  toasts: ActivityEntry[];
  /** Dismisses one toast, leaving its log entry alone. */
  dismissToast: (id: string) => void;
}

/**
 * How many entries the log keeps.
 *
 * <p>Enough to cover an incident an operator walks into, short enough that the panel never becomes
 * something to scroll. The log is a recent history, not an archive - a gateway-served event log is
 * the answer for anything longer, and is deliberately not this.
 */
const LOG_LIMIT = 20;

/** How many toasts may stack before the oldest is pushed out, so a burst cannot cover the page. */
const TOAST_LIMIT = 3;

/** How long a toast lives. Long enough to read a short sentence, short enough not to linger. */
const TOAST_MS = 6000;

/**
 * Watches the polled mesh and reports what changed.
 *
 * <p><b>Session-scoped, deliberately.</b> The log does not survive a refresh and is not shared
 * between tabs. It is also blind to anything that happened while the tab was closed, and to a peer
 * that drops and returns between two polls. Persisting it would dress a record with those holes in
 * it up as a complete one; the honest fix is a gateway-served event log, which is a contract change
 * and a retention decision rather than a storage call made here.
 *
 * @param snapshot what the console can currently see, or undefined before the first read lands.
 * @returns the log, the live toasts, and a way to dismiss one.
 */
export function useMeshActivity(snapshot: MeshSnapshot | undefined): MeshActivity {
  const [entries, setEntries] = useState<ActivityEntry[]>([]);
  const [toasts, setToasts] = useState<ActivityEntry[]>([]);

  // The previous snapshot lives in a ref rather than state: it is an input to the comparison, not
  // something the screen renders, and holding it in state would re-run this effect on every poll
  // whether or not anything changed.
  const previous = useRef<MeshSnapshot | undefined>(undefined);
  const sequence = useRef(0);

  useEffect(() => {
    if (!snapshot) {
      return;
    }

    const changes = transitionsBetween(previous.current, snapshot);
    previous.current = snapshot;
    if (changes.length === 0) {
      return;
    }

    const noticed = changes.map((change) => ({
      ...change,
      // A counter rather than the message or a timestamp: the same transition recurs, and two can
      // land on one poll, so neither is unique enough to be a key.
      id: `${Date.now()}-${sequence.current++}`,
      at: new Date(),
    }));

    setEntries((held) => [...noticed].reverse().concat(held).slice(0, LOG_LIMIT));
    setToasts((held) => held.concat(noticed).slice(-TOAST_LIMIT));
  }, [snapshot]);

  // Each toast retires on its own timer, so a later one does not extend an earlier one's life.
  useEffect(() => {
    if (toasts.length === 0) {
      return;
    }
    const timers = toasts.map((toast) =>
      setTimeout(() => setToasts((held) => held.filter((held_) => held_.id !== toast.id)), TOAST_MS)
    );
    return () => timers.forEach(clearTimeout);
  }, [toasts]);

  return {
    entries,
    toasts,
    dismissToast: (id) => setToasts((held) => held.filter((toast) => toast.id !== id)),
  };
}
