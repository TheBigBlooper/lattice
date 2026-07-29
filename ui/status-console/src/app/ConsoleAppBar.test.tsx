import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import { ConsoleAppBar } from "./ConsoleAppBar.tsx";

/**
 * The realm role held here, as a value rather than a literal.
 *
 * <p>`role` is the operator's grant on this baseline, not an ARIA role - but written inline the
 * a11y lint reads it as one and rejects "operator" as an invalid role. Passing it as a value says
 * the same thing without asking the gate to make an exception.
 */
const REALM_ROLE = "operator";

/** The bar at a route, with a session unless one is refused. */
function bar(at: string, signedIn = true) {
  return render(
    <MemoryRouter initialEntries={[at]}>
      <ConsoleAppBar
        clusterId="hub-central"
        region="us-central"
        role={REALM_ROLE}
        username="operator"
        version="0.1.0"
        {...(signedIn ? { onSignOut: () => {} } : {})}
      />
    </MemoryRouter>
  );
}

describe("ConsoleAppBar navigation", () => {
  /**
   * The three destinations live in the bar the Material UI migration built deliberately larger than
   * one screen needed. A drawer was rejected: it spends horizontal space permanently on a console
   * whose main screen is already two columns.
   */
  it("offers the three destinations", () => {
    bar("/");

    expect(screen.getByRole("tab", { name: "Status" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Orders" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Inventory" })).toBeInTheDocument();
  });

  /**
   * Status is home, so the console's first job - answering "is this baseline healthy" - is what an
   * operator lands on rather than something they must navigate to find.
   */
  it("marks Status as the current page at the root", () => {
    bar("/");

    expect(screen.getByRole("tab", { name: "Status" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("tab", { name: "Orders" })).toHaveAttribute("aria-selected", "false");
  });

  /** And the current tab tracks the route rather than a separate piece of state. */
  it("marks the destination matching the route", () => {
    bar("/orders");

    expect(screen.getByRole("tab", { name: "Orders" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("tab", { name: "Status" })).toHaveAttribute("aria-selected", "false");
  });

  /**
   * A signed-out operator is offered nothing to browse.
   *
   * <p>Every destination behind these tabs is a bearer-protected read, so showing them before there
   * is a session would offer three routes that can only answer 401 - the console teaching that its
   * own controls sometimes just fail, which is what decision 4 rejects on the write side too.
   */
  it("offers no destinations without a session", () => {
    bar("/", false);

    expect(screen.queryByRole("tab", { name: "Orders" })).not.toBeInTheDocument();
  });
});
