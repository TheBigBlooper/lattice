import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LoadingScreen } from "./LoadingScreen.tsx";

describe("LoadingScreen", () => {
  /**
   * A spinner says nothing to a screen reader, so what it is waiting on has to be stated as its
   * accessible name. It is deliberately not visible text: on a first paint the words are gone
   * before they can be read and register as a fault rather than as information.
   */
  it("names what it is waiting on without showing the words", () => {
    render(<LoadingScreen label="Reading this baseline" />);

    expect(screen.getByRole("progressbar", { name: "Reading this baseline" })).toBeInTheDocument();
    expect(screen.queryByText("Reading this baseline")).not.toBeInTheDocument();
  });

  /**
   * The load-bearing guarantee. Starting the console runs two waits back to back - the session
   * check, then the first read - and if each sized its own container the spinner would jump from
   * one position to another between them, which reads as the page flinching rather than loading.
   * Both waits render this component, so the position is the same by construction.
   */
  it("reserves the same screen position whatever it is waiting on", () => {
    const { container: session } = render(<LoadingScreen label="Checking your session" />);
    const { container: read } = render(<LoadingScreen label="Reading this baseline" />);

    const height = (root: HTMLElement) => (root.firstElementChild as HTMLElement).style.minHeight;
    expect(height(session)).toBe(height(read));
    expect(height(session)).not.toBe("");
  });
});
