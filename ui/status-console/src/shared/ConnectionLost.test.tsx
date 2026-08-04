import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ConnectionLost } from "./ConnectionLost.tsx";

describe("ConnectionLost", () => {
  /** A healthy read renders nothing at all, so the panel is not permanently wrapped in a shell. */
  it("renders nothing while the read is healthy", () => {
    const { container } = render(<ConnectionLost />);

    expect(container).toBeEmptyDOMElement();
  });

  /** The operator is told what failed, in the service's own words rather than a generic apology. */
  it("names what failed", () => {
    render(<ConnectionLost detail="orders did not answer" />);

    expect(screen.getByText("orders did not answer")).toBeInTheDocument();
  });

  /**
   * Says whether it is still trying and how long since the last good read, which is what separates
   * a blip from an outage while an operator waits.
   */
  it("distinguishes a blip from an outage", () => {
    render(
      <ConnectionLost
        detail="orders did not answer"
        isRetrying
        lastGoodRead={Date.now() - 60_000}
      />
    );

    expect(screen.getByText(/Retrying/)).toBeInTheDocument();
  });

  /**
   * The hold covers the screen, not the console.
   *
   * A dialog is modal over the whole viewport by default, which put its backdrop over the app bar
   * and left an operator unable to reach the one screen still working - served, as it happens, by a
   * different service entirely. Leaving is the safe action here, and it was the only one being
   * prevented. Sitting below the app bar's layer is what keeps the form and the stale list unusable
   * while the way out stays open.
   */
  it("sits below the navigation rather than over it", () => {
    render(<ConnectionLost detail="orders did not answer" />);

    const dialog = document.querySelector(".MuiDialog-root") as HTMLElement;
    // Material's app bar sits at 1100. Anything at or above that would cover the tabs.
    expect(Number(dialog.style.zIndex)).toBeLessThan(1100);
  });
});
