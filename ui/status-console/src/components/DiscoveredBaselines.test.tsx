import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Peer } from "../api/usePeers.ts";
import { DiscoveredBaselines } from "./DiscoveredBaselines.tsx";

/** The instant every age in these tests is measured against. */
const NOW = new Date("2026-07-27T00:00:00Z");

const REACHABLE: Peer = {
  clusterId: "hub-east",
  region: "us-east",
  baselineVersion: "0.1.0",
  health: "ready",
  consoleUrl: "http://hub-east:3000",
  apiBaseUrl: "http://hub-east:8082/api/v1",
  lastSeen: "2026-07-26T23:59:56Z",
  reachability: "REACHABLE",
};

const SILENT: Peer = {
  clusterId: "hub-west",
  region: "us-west",
  baselineVersion: "0.1.0",
  health: "ready",
  consoleUrl: "http://hub-west:3000",
  apiBaseUrl: "http://hub-west:8082/api/v1",
  lastSeen: "2026-07-26T23:55:48Z",
  reachability: "UNREACHABLE",
};

/**
 * The body rows of the peer table, excluding the header row.
 *
 * Peers render as a table rather than a list: the data is genuinely tabular, and a table gives a
 * screen-reader user the column name alongside each value, which a list of rows cannot.
 */
function peerRows() {
  const [, body] = screen.getAllByRole("rowgroup");
  return within(body as HTMLElement).getAllByRole("row") as HTMLElement[];
}

describe("DiscoveredBaselines", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * The mesh's own rollup, which is the question this panel exists to answer. Counting rows is what
   * it removes, so the count has to be stated rather than implied by the list beneath it.
   */
  it("leads with how many peers are reachable", () => {
    render(<DiscoveredBaselines peers={[REACHABLE, SILENT]} />);

    expect(screen.getByText("1 of 2 peers reachable")).toBeInTheDocument();
  });

  /** Each discovered baseline is one row carrying its identity and where it runs. */
  it("renders a row per discovered baseline", () => {
    render(<DiscoveredBaselines peers={[REACHABLE, SILENT]} />);

    const rows = peerRows();
    expect(rows).toHaveLength(2);
    expect(within(rows[0] as HTMLElement).getByText("hub-east")).toBeInTheDocument();
    expect(within(rows[0] as HTMLElement).getByText("us-east")).toBeInTheDocument();
  });

  /**
   * A reachable peer reports the health it last announced, as a word. The word is the point: an
   * operator who cannot tell the colours apart must still be able to read the state.
   */
  it("states a reachable peer's health in words", () => {
    render(<DiscoveredBaselines peers={[REACHABLE]} />);

    const row = peerRows()[0] as HTMLElement;
    expect(within(row).getByText("ready")).toBeInTheDocument();
  });

  /**
   * A peer past its liveness window is retained rather than removed, and says so. Dropping the row
   * would render "this baseline went quiet" as "this baseline never existed", which is the single
   * most misleading thing this panel could do.
   */
  it("retains a silent peer, marked unreachable with its last-known health", () => {
    render(<DiscoveredBaselines peers={[SILENT]} />);

    const row = peerRows()[0] as HTMLElement;
    expect(within(row).getByText("hub-west")).toBeInTheDocument();
    expect(within(row).getByText(/unreachable/i)).toBeInTheDocument();
    expect(within(row).getByText(/last known: ready/i)).toBeInTheDocument();
  });

  /** How long ago the announcement was heard, so a stale row cannot be mistaken for a fresh one. */
  it("reports how long ago each peer was last heard", () => {
    render(<DiscoveredBaselines peers={[REACHABLE, SILENT]} />);

    const rows = peerRows();
    expect(within(rows[0] as HTMLElement).getByText("4s")).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getByText("4m 12s")).toBeInTheDocument();
  });

  /**
   * A baseline that has discovered nobody is a normal cold-start state, not a failure. It is stated
   * plainly so an empty panel cannot be read as a panel that failed to load.
   */
  it("says so plainly when no peer has announced", () => {
    render(<DiscoveredBaselines peers={[]} />);

    expect(screen.getByText(/no peers discovered/i)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  /**
   * A long-silent peer coarsens to hours and minutes. At that age the seconds are noise, and a
   * peer quiet for hours is precisely the row an operator must not misread as recent.
   */
  it("coarsens a long silence to hours", () => {
    render(<DiscoveredBaselines peers={[{ ...SILENT, lastSeen: "2026-07-26T20:53:00Z" }]} />);

    const row = peerRows()[0] as HTMLElement;
    expect(within(row).getByText("3h 7m")).toBeInTheDocument();
  });

  /** With every peer silent the rollup reads zero, rather than being hidden. */
  it("reports zero reachable when the whole mesh has gone quiet", () => {
    render(<DiscoveredBaselines peers={[SILENT]} />);

    expect(screen.getByText("0 of 1 peers reachable")).toBeInTheDocument();
  });

  /**
   * Under Shape A an operator does not drive a peer from here - they travel to it. The action
   * carries the origin they came from, so the peer can offer a way back, and it is a real link so
   * the browser treats it as the navigation it is.
   */
  it("offers a way to the peer own console", () => {
    render(<DiscoveredBaselines peers={[REACHABLE]} />);

    const go = screen.getByRole("link", { name: /hub-east/i });
    expect(go).toHaveAttribute("href", expect.stringContaining("http://hub-east:3000"));
    expect(go).toHaveAttribute("href", expect.stringContaining("from="));
  });

  /**
   * A peer that has gone silent is not offered as a destination. The redirect would fail at the
   * browser like any unreachable site, and presenting it as available invites an operator to
   * diagnose their own browser rather than read the row telling them the baseline is quiet.
   */
  it("does not offer a silent peer as a destination", () => {
    render(<DiscoveredBaselines peers={[SILENT]} />);

    expect(screen.queryByRole("link", { name: /hub-west/i })).not.toBeInTheDocument();
  });

  /**
   * The control is an icon with no text, so without a tooltip the only clue to where it goes is the
   * URL the browser prints in its status bar - which asks an operator to read an origin to find out
   * what a button does. The accessible name already said this; sighted operators could not see it.
   */
  it("names where the peer control goes on hover", () => {
    render(<DiscoveredBaselines peers={[REACHABLE]} />);

    // fireEvent rather than userEvent, and the clock advanced by hand: this file installs fake
    // timers for age formatting, and userEvent waits on real ones that never tick, so it hangs
    // instead of failing. The tooltip opens on an enter delay, which is what is being advanced past.
    fireEvent.mouseOver(screen.getByRole("link", { name: /go to hub-east/i }));
    act(() => {
      vi.advanceTimersByTime(1000);
    });

    expect(screen.getByRole("tooltip")).toHaveTextContent(/go to hub-east/i);
  });

  /**
   * A cut-off baseline stops speaking for the mesh.
   *
   * <p>When this baseline loses its own broker link it hears nothing, so the registry ages every
   * peer out at once. Rendering that as a row of unreachable chips asserts something this console
   * has no evidence for - those baselines are most likely up and talking to each other. The panel
   * falls back to what was actually last observed and says the link is down.
   */
  it("reports its own link as down rather than calling every peer unreachable", () => {
    render(<DiscoveredBaselines meshLink="down" peers={[REACHABLE, SILENT]} />);

    expect(screen.getByText(/mesh link down/i)).toBeInTheDocument();
    expect(screen.queryByText(/peers reachable/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/^unreachable$/i)).not.toBeInTheDocument();
  });

  /** The column stops claiming current state and says what it is: the last thing heard. */
  it("restates the state column as last known while cut off", () => {
    render(<DiscoveredBaselines meshLink="down" peers={[REACHABLE]} />);

    expect(screen.getByRole("columnheader", { name: /last known state/i })).toBeInTheDocument();
    expect(within(peerRows()[0] as HTMLElement).getByText("ready")).toBeInTheDocument();
  });

  /**
   * How stale the snapshot is, stated rather than left to arithmetic on a relative age. An
   * operator deciding whether to act needs to know when this was true, not how many seconds have
   * elapsed since.
   */
  it("stamps the snapshot with when it was taken", () => {
    render(<DiscoveredBaselines meshLink="down" peers={[REACHABLE]} />);

    expect(screen.getByText(/as of /i)).toBeInTheDocument();
  });

  /** With the link up nothing changes: the rollup counts, and the column is current state. */
  it("counts reachable peers while the link is up", () => {
    render(<DiscoveredBaselines meshLink="up" peers={[REACHABLE, SILENT]} />);

    expect(screen.getByText("1 of 2 peers reachable")).toBeInTheDocument();
    expect(screen.queryByText(/mesh link down/i)).not.toBeInTheDocument();
  });
});
