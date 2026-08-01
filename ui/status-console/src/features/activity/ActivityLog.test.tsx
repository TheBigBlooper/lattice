import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { ActivityLog } from "./ActivityLog.tsx";
import type { ActivityEntry } from "./useActivity.ts";

function entry(message: string, kind: ActivityEntry["kind"], at: string): ActivityEntry {
  return { id: message, kind, subject: "hub-east", message, at: new Date(at) };
}

describe("ActivityLog", () => {
  /**
   * An empty log says why it is empty.
   *
   * <p>A blank panel on a status screen reads as something that failed to load. Saying nothing has
   * changed *since this page was opened* also states the limit in the same breath: the log is blind
   * to anything that happened before the tab existed.
   */
  it("says nothing has changed rather than showing an empty panel", () => {
    render(<ActivityLog entries={[]} />);

    expect(screen.getByText(/nothing has changed since this page was opened/i)).toBeInTheDocument();
  });

  /** Newest first, so the thing that just happened is the thing at the top. */
  it("lists entries newest first", () => {
    render(
      <ActivityLog
        entries={[
          entry("hub-east came back", "peer-returned", "2026-07-28T18:44:00Z"),
          entry("hub-east went quiet", "peer-lost", "2026-07-28T18:41:00Z"),
        ]}
      />
    );

    const rows = within(screen.getByRole("list", { name: /^activity$/i })).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("hub-east came back");
    expect(rows[1]).toHaveTextContent("hub-east went quiet");
  });

  /**
   * Local and mesh entries share one stream and are told apart by a word on the line.
   *
   * <p>This is the whole reason one timeline was chosen over two panels: an operator reading a
   * service failure next to a peer going quiet has to be able to see whose problem each one is
   * without comparing a line against its neighbours. A tag carried only by colour or column
   * position would not survive that reading.
   */
  it("tags each line with the half of the system it happened in", () => {
    render(
      <ActivityLog
        entries={[
          entry("orders went down", "service-lost", "2026-07-28T18:44:00Z"),
          entry("hub-east went quiet", "peer-lost", "2026-07-28T18:41:00Z"),
        ]}
      />
    );

    const rows = within(screen.getByRole("list", { name: /^activity$/i })).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent(/this baseline/i);
    expect(rows[1]).toHaveTextContent(/mesh/i);
  });

  /**
   * Each line carries words, not only a colour.
   *
   * <p>A status surface that distinguishes states by hue alone is unreadable to a colour-blind
   * operator, so the dot is an addition to the sentence rather than a substitute for it.
   */
  it("states what happened in words", () => {
    render(
      <ActivityLog entries={[entry("hub-west went quiet", "peer-lost", "2026-07-28T18:41:00Z")]} />
    );

    expect(screen.getByText("hub-west went quiet")).toBeInTheDocument();
  });

  /**
   * The panel says it is session-scoped.
   *
   * <p>The log cannot see anything from before the tab opened, misses a peer that drops and returns
   * between two polls, and disagrees with a second tab. An operator who believed it was a complete
   * record would draw a confident conclusion from a partial one.
   */
  it("says the record is only this session", async () => {
    // Asserted through the panel help rather than a caption in the header. The caption said it in
    // four words an operator could not act on, and only some panels carried one - so the headers
    // read as inconsistent before they read as informative. The property is unchanged and is now
    // stated properly, which is what this checks.
    render(
      <ActivityLog
        entries={[entry("hub-east came back", "peer-returned", "2026-07-28T18:44:00Z")]}
      />
    );

    await userEvent.click(screen.getByRole("button", { name: /about activity/i }));

    expect(screen.getByRole("dialog")).toHaveTextContent(/since this page was opened/i);
  });
});
