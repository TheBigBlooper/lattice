import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ActivityToasts } from "./ActivityToasts.tsx";
import type { ActivityEntry } from "./useActivity.ts";

function toast(message: string, kind: ActivityEntry["kind"]): ActivityEntry {
  return { id: message, kind, subject: "hub-east", message, at: new Date() };
}

describe("ActivityToasts", () => {
  /** Nothing to say means nothing on screen, rather than an empty container holding space. */
  it("shows nothing when there is nothing to report", () => {
    render(<ActivityToasts onDismiss={() => {}} toasts={[]} />);

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  /**
   * Several changes stack rather than replacing one another.
   *
   * <p>Two peers can move on a single poll, and an operator told about the first but not the second
   * has been misled by the interface rather than by the mesh.
   */
  it("stacks several changes instead of replacing them", () => {
    render(
      <ActivityToasts
        onDismiss={() => {}}
        toasts={[
          toast("hub-east went quiet", "peer-lost"),
          toast("hub-west came back", "peer-returned"),
        ]}
      />
    );

    expect(screen.getByText("hub-east went quiet")).toBeInTheDocument();
    expect(screen.getByText("hub-west came back")).toBeInTheDocument();
  });

  /** Dismissing reports the one dismissed, so the log entry behind it is never touched. */
  it("reports which toast was dismissed", async () => {
    const onDismiss = vi.fn();
    render(
      <ActivityToasts onDismiss={onDismiss} toasts={[toast("hub-east went quiet", "peer-lost")]} />
    );

    await userEvent.click(screen.getByRole("button", { name: /close/i }));

    expect(onDismiss).toHaveBeenCalledWith("hub-east went quiet");
  });

  /**
   * A peer going quiet and this baseline losing its whole link are drawn at different weights.
   *
   * <p>One is a problem with a baseline; the other is a warning about what this screen can still be
   * trusted to say. Telling them apart at a glance is the point of the distinction.
   */
  it("draws a lost peer and a lost mesh link differently", () => {
    const { rerender } = render(
      <ActivityToasts onDismiss={() => {}} toasts={[toast("hub-east went quiet", "peer-lost")]} />
    );
    const peerSeverity = screen.getByRole("alert").className;

    rerender(
      <ActivityToasts onDismiss={() => {}} toasts={[toast("Mesh link down", "mesh-lost")]} />
    );

    expect(screen.getByRole("alert").className).not.toEqual(peerSeverity);
  });

  /**
   * A local kind toasts at the weight the log gives it, not at the default.
   *
   * <p>Severity used to be its own switch over kinds, and it named only the six mesh kinds - so all
   * five local ones fell through to `info`. A service going down toasted blue while the very same
   * event rendered red in the log directly beneath it. The severity now derives from the one shared
   * tone mapping, so the two surfaces cannot disagree about an event again.
   */
  it("gives a local failure the same weight the log gives it", () => {
    render(
      <ActivityToasts
        onDismiss={() => {}}
        toasts={[toast("mesh-gateway went down", "service-lost")]}
      />
    );

    expect(screen.getByRole("alert").className).toMatch(/colorError|standardError|outlinedError/);
  });

  /**
   * And a degrading component is a warning rather than an error: it is still serving, so colouring
   * it the same as "cannot serve" would spend the loudest signal on the state that stopped nothing.
   */
  it("draws a degrading component below a failure", () => {
    render(
      <ActivityToasts
        onDismiss={() => {}}
        toasts={[toast("elasticsearch degraded", "component-degraded")]}
      />
    );

    expect(screen.getByRole("alert").className).toMatch(/Warning/);
  });
});
