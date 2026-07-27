import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { lightTheme } from "../theme/theme.ts";
import { ClusterVerdict } from "./ClusterVerdict.tsx";

const services = [
  { name: "orders", status: "UP" },
  { name: "inventory", status: "UP" },
  { name: "mesh-gateway", status: "DOWN" },
] as const;

describe("ClusterVerdict", () => {
  /**
   * The verdict is the largest thing on the screen and reads as a word. This is the whole point of
   * the chosen direction: the operator is told the cluster's state rather than deriving it by
   * scanning rows.
   */
  it("states the cluster's verdict in words", () => {
    render(<ClusterVerdict health="ready" services={[...services]} />);

    expect(screen.getByRole("status")).toHaveTextContent(/ready/i);
  });

  /**
   * The count is what stops the verdict being a black box: an operator seeing "degraded" learns
   * how degraded in the same glance, without navigating anywhere.
   */
  it("says how many services are ready", () => {
    render(<ClusterVerdict health="degraded" services={[...services]} />);

    expect(screen.getByRole("status")).toHaveTextContent(/2 of 3 services ready/i);
  });

  /** Every service appears in the breakdown, so the cause of a degraded verdict is on screen. */
  it("lists every service beneath the verdict", () => {
    render(<ClusterVerdict health="degraded" services={[...services]} />);

    const breakdown = screen.getByRole("list", { name: /services/i });
    expect(within(breakdown).getAllByRole("listitem")).toHaveLength(3);
    expect(within(breakdown).getByText("mesh-gateway")).toBeInTheDocument();
  });

  /** Each verdict maps to its own theme colour, and never to a colour written inline. */
  it.each([
    ["ready", lightTheme.palette.success.main],
    ["degraded", lightTheme.palette.warning.main],
    ["down", lightTheme.palette.error.main],
  ] as const)("colours the %s verdict from the theme", (health, expected) => {
    render(<ClusterVerdict health={health} services={[...services]} />);

    expect(screen.getByRole("status")).toHaveStyle({ color: expected });
  });

  /**
   * A health value this console does not know about renders in the neutral tone instead of
   * crashing or picking a misleading colour. The contract can add a state, and a console deployed
   * against a newer baseline than itself is the ordinary case in a system of independently
   * versioned baselines - so the unknown value is shown as the word it is.
   */
  it("renders an unrecognised health value neutrally", () => {
    render(
      <ClusterVerdict
        // Deliberately outside the contract's current union: this is the forward-compatibility
        // path, which by definition cannot be reached with a value the types allow today.
        health={"recovering" as never}
        services={[]}
      />
    );

    expect(screen.getByRole("status")).toHaveTextContent(/recovering/i);
    expect(screen.getByRole("status")).toHaveStyle({ color: lightTheme.palette.text.secondary });
  });

  /**
   * A cluster that reports down has nothing reachable to break down, and rendering "0 of 0" would
   * be noise. The verdict still stands on its own.
   */
  it("omits the count when there are no services to report", () => {
    render(<ClusterVerdict health="down" services={[]} />);

    expect(screen.getByRole("status")).toHaveTextContent(/down/i);
    expect(screen.getByRole("status")).not.toHaveTextContent(/services ready/i);
  });
});
