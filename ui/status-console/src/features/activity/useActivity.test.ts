import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Peer } from "../../api/usePeers.ts";
import type { ConsoleSnapshot } from "./activity.ts";
import { useActivity } from "./useActivity.ts";

function peer(clusterId: string, reachability: "REACHABLE" | "UNREACHABLE"): Peer {
  return {
    clusterId,
    region: "us-test",
    baselineVersion: "0.1.0",
    health: "ready",
    consoleUrl: `http://${clusterId}:3000`,
    apiBaseUrl: `http://${clusterId}:8082/api/v1`,
    lastSeen: "2026-07-28T00:00:00Z",
    reachability,
  };
}

const up: ConsoleSnapshot = { peers: [peer("hub-east", "REACHABLE")], meshLink: "up" };
const quiet: ConsoleSnapshot = { peers: [peer("hub-east", "UNREACHABLE")], meshLink: "up" };

describe("useActivity", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** The first read establishes a baseline to compare against and announces nothing. */
  it("is silent on the first snapshot", () => {
    const { result } = renderHook(({ snapshot }) => useActivity(snapshot), {
      initialProps: { snapshot: up },
    });

    expect(result.current.entries).toEqual([]);
    expect(result.current.toasts).toEqual([]);
  });

  /** A change raises both a toast and a log entry - one announces, the other remembers. */
  it("records a transition in the log and raises a toast", () => {
    const { result, rerender } = renderHook(({ snapshot }) => useActivity(snapshot), {
      initialProps: { snapshot: up },
    });

    rerender({ snapshot: quiet });

    expect(result.current.entries.map((entry) => entry.message)).toEqual(["hub-east went quiet"]);
    expect(result.current.toasts).toHaveLength(1);
  });

  /**
   * The toast retires; the entry does not.
   *
   * <p>That difference is the whole point of keeping both. A toast is an interruption and has to end;
   * the log is what an operator arriving two minutes later reads to find out what they missed.
   */
  it("retires the toast but keeps the log entry", () => {
    const { result, rerender } = renderHook(({ snapshot }) => useActivity(snapshot), {
      initialProps: { snapshot: up },
    });
    rerender({ snapshot: quiet });

    act(() => {
      vi.advanceTimersByTime(6000);
    });

    expect(result.current.toasts).toEqual([]);
    expect(result.current.entries).toHaveLength(1);
  });

  /** Dismissing a toast leaves its entry alone, for the same reason. */
  it("dismisses a toast without forgetting it happened", () => {
    const { result, rerender } = renderHook(({ snapshot }) => useActivity(snapshot), {
      initialProps: { snapshot: up },
    });
    rerender({ snapshot: quiet });

    act(() => {
      result.current.dismissToast(result.current.toasts[0]?.id ?? "");
    });

    expect(result.current.toasts).toEqual([]);
    expect(result.current.entries).toHaveLength(1);
  });

  /** Newest first, so the thing that just happened is the thing at the top. */
  it("keeps the log newest first", () => {
    const { result, rerender } = renderHook(({ snapshot }) => useActivity(snapshot), {
      initialProps: { snapshot: up },
    });

    rerender({ snapshot: quiet });
    rerender({ snapshot: up });

    expect(result.current.entries.map((entry) => entry.message)).toEqual([
      "hub-east came back",
      "hub-east went quiet",
    ]);
  });

  /** A poll where nothing moved adds nothing, however many times it is repeated. */
  it("adds nothing when a poll reports no change", () => {
    const { result, rerender } = renderHook(({ snapshot }) => useActivity(snapshot), {
      initialProps: { snapshot: up },
    });

    rerender({ snapshot: { peers: [peer("hub-east", "REACHABLE")], meshLink: "up" } });
    rerender({ snapshot: { peers: [peer("hub-east", "REACHABLE")], meshLink: "up" } });

    expect(result.current.entries).toEqual([]);
  });
});
