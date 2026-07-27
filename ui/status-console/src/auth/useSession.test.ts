import { renderHook, waitFor } from "@testing-library/react";
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

describe("useSession", () => {
  beforeEach(() => {
    instance.init.mockReset().mockResolvedValue(false);
    instance.login.mockReset();
    instance.logout.mockReset();
    instance.updateToken.mockReset().mockResolvedValue(true);
    instance.authenticated = false;
    instance.token = undefined;
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
});
