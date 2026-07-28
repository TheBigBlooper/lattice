import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ServiceRow } from "./ServiceRow.tsx";

describe("ServiceRow", () => {
  /** The name and the state are both plain text, so either can be found on its own. */
  it("states the service and its readiness", () => {
    render(
      <ul>
        <ServiceRow name="orders" status="UP" />
      </ul>
    );

    expect(screen.getByText("orders")).toBeInTheDocument();
    expect(screen.getByText("up")).toBeInTheDocument();
  });

  /**
   * Anything that is not UP is down, rather than being rendered as an unknown third thing.
   *
   * <p>A readiness this console does not recognise is still not readiness, and drawing it neutrally
   * would let a broken service look merely unfamiliar.
   */
  it("treats anything that is not UP as down", () => {
    render(
      <ul>
        <ServiceRow name="artemis" status="STARTING" />
      </ul>
    );

    expect(screen.getByText("down")).toBeInTheDocument();
  });

  /**
   * The state is a word, not only a colour and a glyph.
   *
   * <p>A status surface that distinguishes states by hue alone is unreadable to a colour-blind
   * operator - and this card exists to answer "which one is wrong".
   */
  it("says the state in words as well as colour", () => {
    const { rerender } = render(
      <ul>
        <ServiceRow name="orders" status="UP" />
      </ul>
    );
    expect(screen.getByText("up")).toBeInTheDocument();

    rerender(
      <ul>
        <ServiceRow name="orders" status="DOWN" />
      </ul>
    );
    expect(screen.getByText("down")).toBeInTheDocument();
  });
});
