import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { components } from "../api/generated/v1.ts";
import { lightTheme } from "../theme/theme.ts";
import { InfrastructureCard } from "./InfrastructureCard.tsx";

type ComponentHealth = components["schemas"]["ComponentHealth"];

const infrastructure: ComponentHealth[] = [
  { kind: "elasticsearch", name: "elasticsearch", status: "UP" },
  { kind: "artemis", name: "artemis", status: "DOWN" },
  {
    detail: "management endpoint returned 503",
    kind: "keycloak",
    name: "keycloak",
    status: "DEGRADED",
  },
];

describe("InfrastructureCard", () => {
  /**
   * Every configured component is on screen, so an operator learns that Elasticsearch, Artemis and
   * Keycloak exist at all - which the console could not tell them before.
   */
  it("lists every component the baseline reports", () => {
    render(<InfrastructureCard components={infrastructure} />);

    const list = screen.getByRole("list", { name: /infrastructure components/i });
    expect(within(list).getAllByRole("listitem")).toHaveLength(3);
    expect(within(list).getByText("keycloak")).toBeInTheDocument();
  });

  /**
   * The rollup is what stops the list being a wall of rows: the operator is told how much of the
   * infrastructure is healthy, and reads the rows only to find out which part is not.
   */
  it("says how many components are healthy", () => {
    render(<InfrastructureCard components={infrastructure} />);

    expect(screen.getByText(/1 of 3 components healthy/i)).toBeInTheDocument();
  });

  /**
   * The rollup speaks the same three words the cluster verdict does, so the screen teaches its
   * logic once and applies it twice: all healthy reads ready, none healthy reads down, and anything
   * between reads degraded.
   */
  it.each([
    [["UP", "UP", "UP"], /ready/i],
    [["UP", "DOWN", "DEGRADED"], /degraded/i],
    [["DOWN", "DOWN", "DEGRADED"], /down/i],
  ] as const)("rolls %s up to its own one-word state", (statuses, expected) => {
    const reported = infrastructure.map((component, index) => ({
      ...component,
      status: statuses[index] as ComponentHealth["status"],
    }));

    render(<InfrastructureCard components={reported} />);

    expect(screen.getByRole("heading", { level: 3 })).toHaveTextContent(expected);
  });

  /** The rollup takes its colour from the theme palette, never from a colour written in the card. */
  it("colours the rollup from the theme", () => {
    render(<InfrastructureCard components={infrastructure} />);

    expect(screen.getByRole("heading", { level: 3 })).toHaveStyle({
      color: lightTheme.palette.warning.main,
    });
  });

  /**
   * Each component states its own coarse state in words, the middle one included. Colour is never
   * the sole indicator on this console, so a degraded datastore has to be legible as a word.
   */
  it("states each component's own state in words", () => {
    render(<InfrastructureCard components={infrastructure} />);

    const list = screen.getByRole("list", { name: /infrastructure components/i });
    expect(within(list).getByText("up")).toBeInTheDocument();
    expect(within(list).getByText("degraded")).toBeInTheDocument();
    expect(within(list).getByText("down")).toBeInTheDocument();
  });

  /** The component's own reading is shown beside it, in the language of the system that produced it. */
  it("shows the reading a component carries", () => {
    render(<InfrastructureCard components={infrastructure} />);

    expect(screen.getByText("management endpoint returned 503")).toBeInTheDocument();
  });

  /**
   * A component of a kind this console has never heard of still renders.
   *
   * <p>That is the entire point of a coarse shared state: a baseline may add a component and the
   * console renders it with no change. Branching on the kind to decide what is renderable would
   * give that away and put a console release in front of every infrastructure addition.
   */
  it("renders a component whose kind it does not know", () => {
    render(
      <InfrastructureCard
        components={[
          // Deliberately outside the contract's current union: this is the forward-compatibility
          // path, which by definition cannot be reached with a value the types allow today.
          { kind: "vault" as never, name: "secrets", status: "UP" },
        ]}
      />
    );

    expect(screen.getByText("secrets")).toBeInTheDocument();
    expect(screen.getByText("up")).toBeInTheDocument();
  });

  /**
   * Absent, not empty. A baseline that configures no infrastructure is a supported deployment, and
   * a card reading "unknown" three times would look like a fault rather than an unset option.
   */
  it("renders no card at all when nothing is reported", () => {
    const { container } = render(<InfrastructureCard components={[]} />);

    expect(container).toBeEmptyDOMElement();
  });
});
