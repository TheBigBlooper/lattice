import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client.ts";
import type { Session } from "../auth/useSession.ts";
import type { ConsoleConfig } from "../config.ts";
import { ConsoleScreen } from "./ConsoleScreen.tsx";

const config: ConsoleConfig = {
  apiBaseUrl: "http://localhost:8082/api/v1",
  clusterId: "hub-central",
  region: "us-central",
  baselineVersion: "0.1.0",
  keycloakUrl: "http://localhost:8083",
  keycloakRealm: "lattice",
  keycloakClientId: "lattice-console",
};

/** A signed-in operator, since every failed-read case is reached from one. */
const signedIn: Session = {
  status: "signed-in",
  token: "a-token",
  username: "operator",
  role: "operator",
  signedOutReason: undefined,
  signIn: () => {},
  signOut: () => {},
};

/** A read that failed for a reason that is not a refusal, which has its own screen. */
const unreachable = new ApiError("UNAVAILABLE", 0, "connection refused");

/** The screen as it stands when a poll has failed, with the retry state under test. */
function failedRead(overrides: { isRetrying: boolean; lastGoodRead?: number }) {
  return (
    <ConsoleScreen
      activity={[]}
      config={config}
      error={unreachable}
      isPending={false}
      peers={[]}
      session={signedIn}
      {...overrides}
    />
  );
}

describe("ConsoleScreen - a read that failed", () => {
  /**
   * A retry in flight is visible, because a blip and a dead baseline are otherwise identical.
   *
   * <p>The console keeps polling and the query layer keeps retrying underneath, but none of that
   * reached the screen - so an operator watching an incident could not tell recovering from gone,
   * which is the one distinction that matters at that moment.
   */
  it("says a retry is in flight", () => {
    render(failedRead({ isRetrying: true }));

    expect(screen.getByText(/retrying/i)).toBeInTheDocument();
  });

  /**
   * A settled failure does not claim to be trying.
   *
   * <p>Saying "retrying" when nothing is in flight is worse than saying nothing: it invites an
   * operator to keep waiting through an outage that is not being worked on.
   */
  it("does not claim a retry when nothing is in flight", () => {
    render(failedRead({ isRetrying: false }));

    expect(screen.queryByText(/retrying/i)).not.toBeInTheDocument();
  });

  /**
   * How long since the last good read, which is what separates a blip from an outage.
   *
   * <p>"Retrying" reads the same at ten seconds and at forty minutes. The age is the number an
   * operator actually reasons about, and it is read from the query layer rather than timed here.
   */
  it("says how long since the last successful read", () => {
    render(failedRead({ isRetrying: true, lastGoodRead: Date.now() - 4 * 60 * 1000 }));

    expect(screen.getByText(/4m ago/i)).toBeInTheDocument();
  });

  /**
   * A baseline that has never answered says nothing about an age it does not have.
   *
   * <p>A first read failing leaves no last-good moment, and rendering one from a zero would date
   * the outage to 1970.
   */
  it("gives no age when there has never been a successful read", () => {
    render(failedRead({ isRetrying: true }));

    expect(screen.queryByText(/ago/i)).not.toBeInTheDocument();
  });
});
