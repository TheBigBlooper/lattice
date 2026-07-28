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

/** Marks that this tab has already asked the provider whether a session exists. */
const SSO_CHECKED_KEY = "lattice.ssoChecked";

/** Where this tab keeps the tokens it holds, so a reload can resume from them. */
const SESSION_KEY = "lattice.session";

/** What the adapter needs handed back to resume a session without contacting the provider. */
interface StoredTokens {
  /** The bearer token. */
  token?: string;
  /** The token that buys a new bearer token when it expires. */
  refreshToken?: string;
  /** The identity token, needed for a logout that ends the provider session too. */
  idToken?: string;
}

/**
 * The tokens this tab stored on a previous load, if any.
 *
 * **Why they are stored at all.** The adapter holds tokens in memory, so a refresh loses them and
 * the console has to rediscover a session it already had - a visible bounce through Keycloak on
 * every reload. Handing them back lets it resume where it was.
 *
 * **Why sessionStorage rather than localStorage.** It is scoped to this tab and cleared when the
 * tab closes, so a token cannot outlive the window that obtained it or be read by another tab.
 * Both are readable by script in this origin: storing a token is a real widening of what an
 * injected script could take, and the mitigation is keeping script out (the console ships no
 * user-authored HTML and no third-party bundles), not the choice of store.
 *
 * A malformed or unreadable value is treated as no value rather than thrown: a corrupt entry
 * should cost a sign-in, not a console that refuses to start.
 */
function readStoredTokens(): StoredTokens {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as StoredTokens) : {};
  } catch {
    return {};
  }
}

/** Records the tokens currently held, so the next load of this tab can resume from them. */
function storeTokens(keycloak: Keycloak): void {
  sessionStorage.setItem(
    SESSION_KEY,
    JSON.stringify({
      token: keycloak.token,
      refreshToken: keycloak.refreshToken,
      idToken: keycloak.idToken,
    } satisfies StoredTokens)
  );
}

/** Drops the stored tokens, so a session that has ended is not resumed on the next load. */
function clearStoredTokens(): void {
  sessionStorage.removeItem(SESSION_KEY);
}

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
 * **A refresh resumes the session it already had.** The adapter keeps tokens in memory only, so a
 * reload would otherwise start from nothing and rediscover the session through the provider - a
 * bounce an operator sees on every refresh of a console they are already signed into. The tokens
 * are kept in tab-scoped storage and handed back on start; see {@link readStoredTokens} for what
 * that costs.
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
          // Stored as well as held: the refreshed token is the one a reload must resume from, and
          // leaving the superseded one in storage would resume a session that no longer exists.
          storeTokens(keycloak);
          if (!cancelled) {
            setToken(keycloak.token);
          }
        })
        .catch(() => {
          // The refresh token is gone or rejected, which is a session that has genuinely ended.
          // Reporting it as signed out puts the operator back on a screen they can act on.
          clearStoredTokens();
          if (!cancelled) {
            setStatus("signed-out");
            setToken(undefined);
          }
        });
    };

    /** Records an established session, or decides what to do about the absence of one. */
    const settle = (authenticated: boolean) => {
      if (!(authenticated || sessionStorage.getItem(SSO_CHECKED_KEY))) {
        // Ask the provider whether a session already exists, without ever asking the operator.
        //
        // Every hop between baselines is a fresh page load, and the adapter alone only completes
        // a redirect already in progress - so a return to a baseline signed into minutes earlier
        // reports signed out without contacting Keycloak at all. prompt=none answers that: a live
        // session comes back authenticated, and no session comes back refused, having shown the
        // operator nothing.
        //
        // The adapter's own check-sso is deliberately not used: it works through the login
        // iframe, and with that disabled it falls back to an ordinary login redirect - a full
        // credentials page for somebody who only wanted the question answered.
        //
        // The flag is set BEFORE redirecting and is what stops a loop: a refusal comes back here
        // unauthenticated, and without it the same check would fire again forever.
        sessionStorage.setItem(SSO_CHECKED_KEY, "1");
        void keycloak.login({ prompt: "none" });
        return;
      }

      if (authenticated) {
        storeTokens(keycloak);
      } else {
        clearStoredTokens();
      }

      setStatus(authenticated ? "signed-in" : "signed-out");
      setToken(keycloak.token);
      setUsername(keycloak.tokenParsed?.["preferred_username"] as string | undefined);
    };

    // Whatever this tab already held, handed straight back. This is the whole of what makes a
    // refresh land on the signed-in screen instead of travelling to the provider to be told
    // something the tab knew before it reloaded.
    const stored = readStoredTokens();

    keycloak
      .init({
        pkceMethod: "S256",
        checkLoginIframe: false,
        ...stored,
      })
      .then((authenticated) => {
        if (cancelled) {
          return;
        }
        if (!(authenticated && stored.token)) {
          settle(authenticated);
          return;
        }

        // A restored token is only as good as its remaining life, and it was stored at some
        // arbitrary point in the past - possibly long enough ago to have expired while the tab sat
        // closed. Refreshing before trusting it is what stops a resumed session from presenting a
        // dead token to every poll; it costs nothing when the token is still fresh, because the
        // adapter skips the network call. A refusal means the session really has ended, so the
        // stored tokens go and the ordinary no-session path takes over.
        keycloak
          .updateToken(MIN_TOKEN_VALIDITY_SECONDS)
          .then(() => {
            if (!cancelled) {
              settle(true);
            }
          })
          .catch(() => {
            clearStoredTokens();
            if (!cancelled) {
              settle(false);
            }
          });
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
    // Cleared before handing off, not after: logout navigates away, so anything left until the
    // redirect returns is a token that outlived the session it belonged to and would be resumed
    // by the next load of this tab.
    clearStoredTokens();
    void keycloakRef.current?.logout();
  }, []);

  return { status, token, username, signIn, signOut };
}
