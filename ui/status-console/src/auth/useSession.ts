import Keycloak from "keycloak-js";
import { useCallback, useEffect, useRef, useState } from "react";

/** Where this baseline's identity provider lives. */
export interface RealmSettings {
  /** This baseline's Keycloak base URL. */
  keycloakUrl: string;
  /** This baseline's realm. */
  keycloakRealm: string;
  /** The public client the console authenticates as. */
  keycloakClientId: string;
}

/** Whether the console is still working out if there is a session, and what it found. */
export type SessionStatus = "initialising" | "signed-in" | "signed-out";

/** What the console knows about the operator, and what it can do about it. */
export interface Session {
  /** Where the session stands. */
  status: SessionStatus;
  /** The bearer token to present, once there is one. */
  token: string | undefined;
  /** Who holds the session, for display. */
  username: string | undefined;
  /** Hands off to this baseline's own Keycloak login page. */
  signIn: () => void;
  /** Ends the session at the provider, not only in this tab. */
  signOut: () => void;
}

/** Refresh a token with fewer than this many seconds left. */
const MIN_TOKEN_VALIDITY_SECONDS = 30;

/**
 * The operator's session with **this** baseline's Keycloak.
 *
 * The flow is authorization code with PKCE using S256. The console is a public client because no
 * secret can be kept in a browser, and PKCE is what makes that safe: without it an intercepted
 * authorization code could be exchanged by anyone. Credentials are never seen here - the operator
 * types them on Keycloak's own page, which is also what preserves its brute-force protection and
 * whatever second factor a baseline chooses to require.
 *
 * **It asks whether a session exists, and never prompts for one.** Every hop between baselines is a
 * fresh page load on a new origin, so without asking, an operator returning to a baseline they
 * signed into minutes earlier would be shown a sign-in card for a session that is alive and well.
 *
 * Asking is not the same as demanding. Signed out remains a real screen an operator is meant to see,
 * most of all one who followed a redirect from a peer baseline and may have no account there at all:
 * they arrive at it having seen no login form, and learn the thing that matters - identity belongs
 * to the baseline that owns the data, so a session elsewhere does not carry here.
 *
 * **A token nearing expiry is refreshed.** The console polls continuously, so a lapsed token would
 * turn a working dashboard into a wall of rejections while the operator sat watching it.
 *
 * @param realm this baseline's provider settings, from config.
 * @returns the session and the two actions that change it.
 */
export function useSession(realm: RealmSettings): Session {
  const [status, setStatus] = useState<SessionStatus>("initialising");
  const [token, setToken] = useState<string | undefined>(undefined);
  const [username, setUsername] = useState<string | undefined>(undefined);

  // Held in a ref rather than state: the adapter is a long-lived object with its own listeners, and
  // putting it in state would re-create it on every render, each instance racing the last.
  const keycloakRef = useRef<Keycloak | null>(null);

  useEffect(() => {
    const keycloak = new Keycloak({
      url: realm.keycloakUrl,
      realm: realm.keycloakRealm,
      clientId: realm.keycloakClientId,
    });
    keycloakRef.current = keycloak;

    let cancelled = false;

    keycloak.onTokenExpired = () => {
      keycloak
        .updateToken(MIN_TOKEN_VALIDITY_SECONDS)
        .then(() => {
          if (!cancelled) {
            setToken(keycloak.token);
          }
        })
        .catch(() => {
          // The refresh token is gone or rejected, which is a session that has genuinely ended.
          // Reporting it as signed out puts the operator back on a screen they can act on.
          if (!cancelled) {
            setStatus("signed-out");
            setToken(undefined);
          }
        });
    };

    keycloak
      .init({
        pkceMethod: "S256",
        // Ask Keycloak whether a session already exists, without ever prompting for one.
        //
        // Every hop between baselines is a fresh page load on a new origin, and without this the
        // adapter only completes a redirect already in progress - so it reports signed out without
        // contacting Keycloak at all, and an operator returning to a baseline they signed into
        // minutes earlier is shown a sign-in card for a session that is alive and well.
        //
        // check-sso redirects with prompt=none, so somebody with a session comes back signed in and
        // somebody without comes back to the signed-out screen having seen no login form. That
        // preserves the rule this used to enforce by omission - signed out is a real screen an
        // operator is meant to see, most of all one redirected from a peer who may have no account
        // here - while removing a wasted round trip through a login page they never needed.
        //
        // Full-page rather than a silent iframe: Keycloak is a different origin from the console,
        // so the iframe form depends on third-party cookie access that browsers are removing. It
        // would work today and fail quietly later, which is the worse failure.
        onLoad: "check-sso",
        checkLoginIframe: false,
      })
      .then((authenticated) => {
        if (cancelled) {
          return;
        }
        setStatus(authenticated ? "signed-in" : "signed-out");
        setToken(keycloak.token);
        setUsername(keycloak.tokenParsed?.["preferred_username"] as string | undefined);
      })
      .catch(() => {
        // A provider that cannot be reached leaves the console signed out rather than stuck
        // initialising: an operator can act on a sign-in button, not on a spinner.
        if (!cancelled) {
          setStatus("signed-out");
        }
      });

    return () => {
      cancelled = true;
      keycloak.onTokenExpired = undefined;
    };
  }, [realm.keycloakUrl, realm.keycloakRealm, realm.keycloakClientId]);

  const signIn = useCallback(() => {
    void keycloakRef.current?.login();
  }, []);

  const signOut = useCallback(() => {
    void keycloakRef.current?.logout();
  }, []);

  return { status, token, username, signIn, signOut };
}
