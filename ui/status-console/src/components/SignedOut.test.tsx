import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { lightPalette } from "../theme/tokens.ts";
import { ClusterVerdict } from "./ClusterVerdict.tsx";
import { SignedOut } from "./SignedOut.tsx";

describe("SignedOut", () => {
  /**
   * Signed out is a real screen, not an error. It says plainly what happened and offers the one
   * action that resolves it, rather than showing an empty dashboard the operator has to interpret.
   */
  it("says the operator is signed out and offers a way in", () => {
    render(<SignedOut baseline="hub-local" palette={lightPalette} onSignIn={() => {}} />);

    expect(screen.getByRole("status")).toHaveTextContent(/signed out/i);
    expect(screen.getByRole("button", { name: /sign in/i })).toBeInTheDocument();
  });

  /** The baseline is named, because an operator working across several needs to know which one. */
  it("names the baseline being signed in to", () => {
    render(<SignedOut baseline="hub-east" palette={lightPalette} onSignIn={() => {}} />);

    expect(screen.getByRole("status")).toHaveTextContent(/hub-east/);
  });

  /** The sign-in action is wired, so the screen is a way forward rather than a dead end. */
  it("starts sign-in when the action is used", async () => {
    const onSignIn = vi.fn();
    render(<SignedOut baseline="hub-local" palette={lightPalette} onSignIn={onSignIn} />);

    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(onSignIn).toHaveBeenCalledOnce();
  });

  /**
   * The load-bearing one. Signed out occupies the same block, at the same size, as the verdict it
   * replaces - so signing in does not reflow the page. A layout that jumps on sign-in reads as a
   * page that broke and then recovered, which is exactly the wrong first impression of a status
   * console.
   */
  it("occupies the same block as the verdict it replaces", () => {
    const { unmount } = render(
      <ClusterVerdict health="ready" services={[]} palette={lightPalette} />
    );
    const verdictHeight = screen.getByRole("status").style.minHeight;
    unmount();

    render(<SignedOut baseline="hub-local" palette={lightPalette} onSignIn={() => {}} />);
    const signedOutHeight = screen.getByRole("status").style.minHeight;

    expect(signedOutHeight).toBe(verdictHeight);
    expect(verdictHeight).not.toBe("");
  });
});
