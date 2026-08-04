import { render, screen, waitForElementToBeRemoved } from "@testing-library/react";
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
    // Both at once rather than one replacing the other: a toast that leaves stays mounted while it
    // animates out, so a rerender would briefly hold two and "the alert" would be ambiguous.
    render(
      <ActivityToasts
        onDismiss={() => {}}
        toasts={[toast("hub-east went quiet", "peer-lost"), toast("Broker link down", "mesh-lost")]}
      />
    );

    const peer = screen.getByText("hub-east went quiet").closest('[role="alert"]');
    const mesh = screen.getByText("Broker link down").closest('[role="alert"]');

    expect(peer?.className).not.toEqual(mesh?.className);
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
  /**
   * A dismissed toast leaves rather than vanishing.
   *
   * <p>The hook that owns the toasts drops one the instant it is dismissed, so without this the
   * element unmounted mid-air and the exit animation had nowhere to run. It is held in place - in
   * its own position, so nothing below it jumps up before it has gone - and removed on a timer
   * rather than on the animation ending, because an animation turned off by reduced motion never
   * fires that event and the toast would stay forever.
   */
  it("holds a departed toast long enough for it to leave", async () => {
    const { rerender } = render(
      <ActivityToasts onDismiss={() => {}} toasts={[toast("hub-east went quiet", "peer-lost")]} />
    );

    rerender(<ActivityToasts onDismiss={() => {}} toasts={[]} />);

    expect(screen.getByText("hub-east went quiet")).toBeInTheDocument();
    await waitForElementToBeRemoved(() => screen.queryByText("hub-east went quiet"));
  });

  /**
   * The toasts stack downwards, asserted on the style that is emitted rather than the prop asked for.
   *
   * <p><b>The prop was the trap.</b> Snackbar injects a `direction` of `up` or `down` for its Slide
   * transition, and Material forwards props it does not consume to the child element - so a Stack
   * asking for a column had that column overwritten and emitted `flex-direction: up`. That is not a
   * value, so the browser discarded it and fell back to `row`: the toasts laid out along the bottom
   * edge and the third was clipped off screen with its dismiss control out of reach.
   *
   * <p>Writing `direction="column"` did not fix it and could not, because the injected prop wins.
   * That is exactly why this asserts the rendered rule instead: a test on the prop would have passed
   * throughout, which is what let the defect survive being fixed twice.
   */
  it("stacks downwards rather than along the bottom edge", () => {
    render(
      <ActivityToasts
        onDismiss={() => {}}
        toasts={[
          toast("hub-east came back", "peer-returned"),
          toast("hub-west came back", "peer-returned"),
        ]}
      />
    );

    const column = document.querySelector(".MuiSnackbar-root")?.firstElementChild;
    const css = Array.from(document.querySelectorAll("style"))
      .map((tag) => tag.textContent)
      .join("\n");
    const rule = String(column?.className ?? "")
      .split(" ")
      .filter(Boolean)
      .map((cls) => css.match(new RegExp(`\\.${cls}\\{[^}]*\\}`)))
      .find(Boolean);

    expect(rule?.[0]).toContain("flex-direction:column");
  });
});
