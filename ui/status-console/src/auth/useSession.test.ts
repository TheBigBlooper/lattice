import { renderHook, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSession } from "./useSession.ts";

const realm = {
  keycloakUrl: "http://localhost:8083",
  keycloakRealm: "lattice",
  keycloakClientId: "lattice-console",
};

/** The instance the mocked adapter hands back, so a test can drive and inspect it. */
const instance = {
  init: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  updateToken: vi.fn(),
  authenticated: false,
  token: undefined as string | undefined,
  refreshToken: undefined as string | undefined,
  idToken: undefined as string | undefined,
  tokenParsed: undefined as { preferred_username?: string } | undefined,
  onTokenExpired: undefined as (() => void) | undefined,
};

// A plain function, not an arrow: the hook calls `new Keycloak(...)`, and an arrow cannot be
// constructed. Returning an object from a constructor is what hands back the shared instance.
vi.mock("keycloak-js", () => ({
  default: vi.fn(function KeycloakStub() {
    return instance;
  }),
}));

/**
 * Puts the tab in the state it is in for all but the first load: the provider has already been
 * asked whether a session exists, so nothing navigates and the hook settles on what it found.
 *
 * Tests that assert on the settled state have to say this, because the check redirects rather than
 * returning - a hook mid-redirect has no state to assert on.
 */
function alreadyChecked() {
  sessionStorage.setItem("lattice.ssoChecked", "1");
}

describe("useSession", () => {
  beforeEach(() => {
    instance.init.mockReset().mockResolvedValue(false);
    instance.login.mockReset();
    instance.logout.mockReset();
    instance.updateToken.mockReset().mockResolvedValue(true);
    instance.authenticated = false;
    instance.token = undefined;
    instance.refreshToken = undefined;
    instance.idToken = undefined;
    sessionStorage.clear();
    instance.tokenParsed = undefined;
    instance.onTokenExpired = undefined;
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  /**
   * The flow is authorization code with PKCE using S256, because no secret can be kept in a
   * browser and a plain challenge would let an interceptor replay the code. This asserts the
   * console cannot quietly fall back to a weaker mode.
   */
  it("initialises with PKCE S256", async () => {
    renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(instance.init).toHaveBeenCalled();
    });
    expect(instance.init.mock.calls[0]?.[0]).toMatchObject({ pkceMethod: "S256" });
  });

  /**
   * The console does not redirect to Keycloak on load. Signed out is a real screen an operator is
   * meant to see - especially one who followed a redirect from a peer and may have no account here
   * at all. Bouncing them straight to a login form would hide that.
   */
  it("does not force a login on load", async () => {
    alreadyChecked();

    renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(instance.init).toHaveBeenCalled();
    });
    expect(instance.init.mock.calls[0]?.[0]).not.toMatchObject({ onLoad: "login-required" });
    expect(instance.login).not.toHaveBeenCalled();
  });

  /**
   * It asks Keycloak whether a session already exists, without prompting for one.
   *
   * Every hop between baselines is a fresh page load on a new origin. Without this the adapter
   * only ever completes an in-progress redirect, so it reports signed out without contacting
   * Keycloak at all - and an operator returning to a baseline they signed into minutes earlier is
   * shown a sign-in card for a session that is alive and well. check-sso asks; prompt=none means
   * it never asks the operator.
   */
  it("checks for an existing session without prompting", async () => {
    sessionStorage.clear();
    renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(instance.init).toHaveBeenCalled();
    });
    // No onLoad. The adapter implements check-sso through its login iframe, which is disabled
    // here, so it falls back to an ORDINARY login redirect - a full credentials page for somebody
    // who only wanted to know whether a session already existed. The check is made explicitly
    // below instead.
    expect(instance.init.mock.calls[0]?.[0]).not.toMatchObject({ onLoad: "check-sso" });
    await waitFor(() => {
      expect(instance.login).toHaveBeenCalledWith(expect.objectContaining({ prompt: "none" }));
    });
  });

  /** With no session the hook reports signed out and holds no token. */
  it("reports signed out when there is no session", async () => {
    alreadyChecked();

    const { result } = renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(result.current.status).toBe("signed-out");
    });
    expect(result.current.token).toBeUndefined();
  });

  /** Returning from Keycloak with a session exposes the token and who holds it. */
  it("exposes the token and username once authenticated", async () => {
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "a-real-token";
    instance.tokenParsed = { preferred_username: "operator" };

    const { result } = renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });
    expect(result.current.token).toBe("a-real-token");
    expect(result.current.username).toBe("operator");
  });

  /** Signing in hands off to Keycloak's own login page rather than collecting credentials here. */
  it("delegates sign-in to Keycloak", async () => {
    alreadyChecked();

    const { result } = renderHook(() => useSession(realm));
    await waitFor(() => {
      expect(result.current.status).toBe("signed-out");
    });

    result.current.signIn();

    expect(instance.login).toHaveBeenCalledOnce();
  });

  /** Signing out ends the session at the provider, not just in this tab. */
  it("delegates sign-out to Keycloak", async () => {
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "a-real-token";
    const { result } = renderHook(() => useSession(realm));
    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });

    result.current.signOut();

    expect(instance.logout).toHaveBeenCalledOnce();
  });

  /**
   * An expiring token is refreshed rather than allowed to lapse. The console polls continuously, so
   * a lapsed token would turn a working dashboard into a wall of rejections while the operator sat
   * watching it.
   */
  it("refreshes a token that is about to expire", async () => {
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "first-token";

    const { result } = renderHook(() => useSession(realm));
    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });

    instance.token = "second-token";
    instance.onTokenExpired?.();

    await waitFor(() => {
      expect(instance.updateToken).toHaveBeenCalled();
    });
    await waitFor(() => {
      expect(result.current.token).toBe("second-token");
    });
  });

  /**
   * A provider that cannot be reached leaves the console signed out rather than stuck initialising,
   * so the operator sees a screen they can act on instead of a spinner that never resolves.
   */
  it("falls back to signed out when the provider cannot be reached", async () => {
    instance.init.mockRejectedValue(new Error("connection refused"));

    const { result } = renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(result.current.status).toBe("signed-out");
    });
  });

  /**
   * A refresh restores the session without a round trip to the provider.
   *
   * The adapter keeps tokens in memory, so a reload loses them and the console has to rediscover
   * a session it already had - visible as a bounce through Keycloak on every refresh. Handing the
   * stored tokens back to init lets it resume directly.
   */
  it("restores stored tokens on start", async () => {
    sessionStorage.setItem(
      "lattice.session",
      JSON.stringify({ token: "t", refreshToken: "r", idToken: "i" })
    );

    renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(instance.init).toHaveBeenCalled();
    });
    expect(instance.init.mock.calls[0]?.[0]).toMatchObject({
      token: "t",
      refreshToken: "r",
      idToken: "i",
    });
  });

  /**
   * The point of storing them: a reload lands signed in without travelling to the provider.
   *
   * This is the assertion the founder's report comes down to - not that the session is eventually
   * recovered, which it always was, but that recovering it costs no navigation.
   */
  it("resumes a stored session without contacting the provider", async () => {
    sessionStorage.setItem("lattice.session", JSON.stringify({ token: "t", refreshToken: "r" }));
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "t";

    const { result } = renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });
    expect(instance.login).not.toHaveBeenCalled();
  });

  /**
   * A restored token was stored at some arbitrary point in the past, possibly long enough ago to
   * have expired while the tab sat closed. A refusal to refresh it means the session has genuinely
   * ended, so the dead tokens go rather than being handed back on every subsequent load.
   */
  it("discards stored tokens the provider will no longer refresh", async () => {
    alreadyChecked();
    sessionStorage.setItem(
      "lattice.session",
      JSON.stringify({ token: "stale", refreshToken: "r" })
    );
    instance.init.mockResolvedValue(true);
    instance.updateToken.mockRejectedValue(new Error("refresh token expired"));

    const { result } = renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(result.current.status).toBe("signed-out");
    });
    expect(sessionStorage.getItem("lattice.session")).toBeNull();
  });

  /**
   * Signing out drops the stored tokens. Leaving them would resume, on the next load of the tab,
   * exactly the session the operator just ended.
   */
  it("drops the stored tokens on sign-out", async () => {
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "a-real-token";
    const { result } = renderHook(() => useSession(realm));
    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });
    expect(sessionStorage.getItem("lattice.session")).not.toBeNull();

    result.current.signOut();

    expect(sessionStorage.getItem("lattice.session")).toBeNull();
  });

  /** A session that authenticates is stored, so the next load can resume from it. */
  it("stores the tokens it obtains", async () => {
    sessionStorage.clear();
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "a-token";
    instance.refreshToken = "a-refresh";

    const { result } = renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });
    expect(JSON.parse(sessionStorage.getItem("lattice.session") ?? "{}")).toMatchObject({
      token: "a-token",
      refreshToken: "a-refresh",
    });
  });

  /**
   * A session that ended by expiry says so, so the signed-out screen can explain itself.
   *
   * <p>Without this the console swaps a working dashboard for a sign-in card and leaves the
   * operator to work out whether their session died or the baseline did - the same class of defect
   * as reporting a refusal as an outage.
   */
  it("reports that an expired session is why it signed out", async () => {
    alreadyChecked();
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "a-token";
    const { result } = renderHook(() => useSession(realm));
    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });

    instance.updateToken.mockRejectedValue(new Error("refresh token expired"));
    instance.onTokenExpired?.();

    await waitFor(() => {
      expect(result.current.status).toBe("signed-out");
    });
    expect(result.current.signedOutReason).toBe("expired");
  });

  /** An operator who never had a session here is not told one expired - nothing ended. */
  it("gives no reason when there was never a session to lose", async () => {
    alreadyChecked();

    const { result } = renderHook(() => useSession(realm));

    await waitFor(() => {
      expect(result.current.status).toBe("signed-out");
    });
    expect(result.current.signedOutReason).toBeUndefined();
  });

  /**
   * Exactly one adapter is created, and initialised once, however many times the effect runs.
   *
   * <p>React's StrictMode deliberately mounts, cleans up and re-mounts an effect to surface exactly
   * this bug, and the dev server runs in StrictMode. A second adapter is not merely wasteful: both
   * call `init`, and the adapter consumes the authorization code from the URL, so the first one
   * authenticates and the second finds no code and resolves unauthenticated - settling the hook to
   * signed out moments after a successful sign-in. It never reached the built container, which is
   * why it survived so long; it made signing in on the dev server intermittently fail.
   */
  it("initialises exactly once under StrictMode", async () => {
    alreadyChecked();
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "a-token";

    const { result } = renderHook(() => useSession(realm), { wrapper: StrictMode });

    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });
    expect(instance.init).toHaveBeenCalledTimes(1);
  });

  /**
   * The abandoned adapter cannot wipe the live one's tokens.
   *
   * <p>The token writes sat outside the cancellation guard, so a second adapter settling after the
   * first could clear storage the first had just filled - a resumable session lost to a race that
   * only the dev server could trigger.
   */
  it("keeps the stored session intact under StrictMode", async () => {
    alreadyChecked();
    instance.init.mockResolvedValue(true);
    instance.authenticated = true;
    instance.token = "a-token";
    instance.refreshToken = "a-refresh";

    const { result } = renderHook(() => useSession(realm), { wrapper: StrictMode });

    await waitFor(() => {
      expect(result.current.status).toBe("signed-in");
    });
    expect(JSON.parse(sessionStorage.getItem("lattice.session") ?? "{}")).toMatchObject({
      token: "a-token",
    });
  });
});
