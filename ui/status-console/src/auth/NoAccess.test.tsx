import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { NoAccess } from "./NoAccess.tsx";

describe("NoAccess", () => {
  /**
   * The load-bearing assertion of this whole screen. A 403 previously fell through to "Cannot reach
   * this baseline", which told an operator the federation was broken when the baseline was serving
   * perfectly and only their grant was missing. Naming the baseline as reachable is the difference.
   */
  it("says the baseline is reachable and only access is missing", () => {
    render(<NoAccess baseline="hub-east" onSignOut={() => {}} />);

    expect(screen.getByRole("status")).toHaveTextContent(/no access on hub-east/i);
    expect(screen.getByText(/reachable/i)).toBeInTheDocument();
  });

  /**
   * The operator is told why, at the moment the identity model first affects them. Role names are
   * standard across baselines but membership is deliberately unsynchronised, so a grant elsewhere
   * genuinely does not carry - and that is the system working, not a fault to report.
   */
  it("explains that access is granted per baseline", () => {
    render(<NoAccess baseline="hub-east" onSignOut={() => {}} />);

    expect(screen.getByRole("status")).toHaveTextContent(/per baseline/i);
  });

  /** Arriving somewhere useless is not a dead end when the origin is known. */
  it("offers a way back to a confirmed origin", () => {
    render(<NoAccess baseline="hub-east" onSignOut={() => {}} returnTo="http://localhost:3000/" />);

    const back = screen.getByRole("link", { name: /back/i });
    expect(back).toHaveAttribute("href", "http://localhost:3000/");
  });

  /**
   * With no confirmed origin there is no button at all, rather than one pointing at a guess. The
   * browser's own back navigation still works; what is withheld is a link this console cannot
   * vouch for.
   */
  it("offers no way back when the origin was not confirmed", () => {
    render(<NoAccess baseline="hub-east" onSignOut={() => {}} />);

    expect(screen.queryByRole("link", { name: /back/i })).not.toBeInTheDocument();
  });

  /** Signing out is always available, so an operator can arrive as somebody else. */
  it("offers a sign out", async () => {
    const onSignOut = vi.fn();
    render(<NoAccess baseline="hub-east" onSignOut={onSignOut} />);

    await userEvent.click(screen.getByRole("button", { name: /sign out/i }));

    expect(onSignOut).toHaveBeenCalledOnce();
  });
});
