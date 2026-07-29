import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SignedOut } from "./SignedOut.tsx";

describe("SignedOut", () => {
  /**
   * Signed out is a real screen, not an error. It says plainly what happened and offers the one
   * action that resolves it, rather than showing an empty dashboard the operator has to interpret.
   */
  it("offers a way in", () => {
    render(<SignedOut baseline="hub-central" onSignIn={() => {}} />);

    expect(screen.getByRole("button", { name: /sign in/i })).toBeInTheDocument();
  });

  /**
   * The baseline is named, and it is the largest thing on the screen. An operator arriving by
   * redirect from a peer is asking exactly one question - which baseline am I signing in to - and
   * this is where it is answered.
   */
  it("names the baseline as the destination", () => {
    render(<SignedOut baseline="hub-east" onSignIn={() => {}} />);

    expect(screen.getByRole("heading", { name: "hub-east" })).toBeInTheDocument();
  });

  /**
   * The sentence that stops the peer redirect being read as a broken federation. Realm membership
   * is deliberately unsynchronized, so a session on the baseline an operator came from genuinely
   * does not carry - and saying so before they discover it is the whole point of putting it here.
   */
  it("states that a session elsewhere does not carry", () => {
    render(<SignedOut baseline="hub-east" onSignIn={() => {}} />);

    expect(screen.getByRole("status")).toHaveTextContent(/does not carry here/i);
  });

  /**
   * The mark is decorative and hidden from assistive technology: the product is named in text
   * directly beneath it, so announcing both would say the same thing twice.
   */
  it("renders the brand mark without announcing it", () => {
    const { container } = render(<SignedOut baseline="hub-central" onSignIn={() => {}} />);

    const mark = container.querySelector("img");
    expect(mark).toHaveAttribute("src", "/android-chrome-192x192.png");
    expect(mark).toHaveAttribute("alt", "");
  });

  /**
   * Nothing claims a region or a baseline version. Both come from an endpoint that requires a
   * token, so before sign-in the console does not know them - and a screen that invented them
   * would be confidently wrong on the one page an operator has no way to check.
   */
  it("claims nothing it cannot know before sign-in", () => {
    render(<SignedOut baseline="hub-central" onSignIn={() => {}} />);

    expect(screen.queryByText(/baseline \d/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/us-east|us-west|us-central/i)).not.toBeInTheDocument();
  });

  /** The sign-in action is wired, so the screen is a way forward rather than a dead end. */
  it("starts sign-in when the action is used", async () => {
    const onSignIn = vi.fn();
    render(<SignedOut baseline="hub-central" onSignIn={onSignIn} />);

    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(onSignIn).toHaveBeenCalledOnce();
  });

  /**
   * An operator who followed a redirect and does not want to sign in here is not stranded. The
   * origin is offered only where the browser confirmed it, exactly as on the refusal screen.
   */
  it("offers a way back to a confirmed origin", () => {
    render(<SignedOut baseline="hub-east" onSignIn={() => {}} returnTo="http://localhost:3000/" />);

    expect(screen.getByRole("link", { name: /back/i })).toHaveAttribute(
      "href",
      "http://localhost:3000/"
    );
  });

  /** With nothing confirmed there is no link, rather than one pointing at a guess. */
  it("offers no way back when the origin was not confirmed", () => {
    render(<SignedOut baseline="hub-east" onSignIn={() => {}} />);

    expect(screen.queryByRole("link", { name: /back/i })).not.toBeInTheDocument();
  });

  /**
   * An expired session says so, because the operator did not choose this screen.
   *
   * <p>They were watching a dashboard and it vanished. Without a word here they cannot tell whether
   * their session ended or the baseline did - the same confusion as reporting a refusal as an
   * outage, and at a worse moment, since a session most often lapses during a long incident watch.
   */
  it("says so when the session expired", () => {
    render(<SignedOut baseline="hub-central" onSignIn={() => {}} reason="expired" />);

    expect(screen.getByText(/expired/i)).toBeInTheDocument();
  });

  /**
   * With no session to have lost, it explains the thing that arrival actually needs explaining.
   *
   * <p>An operator following a peer redirect has not lost anything - telling them a session expired
   * would be a plain falsehood about the federation working correctly.
   */
  it("explains per-baseline identity when nothing expired", () => {
    render(<SignedOut baseline="hub-central" onSignIn={() => {}} />);

    expect(screen.getByText(/a session elsewhere does not carry here/i)).toBeInTheDocument();
    expect(screen.queryByText(/expired/i)).not.toBeInTheDocument();
  });
});
