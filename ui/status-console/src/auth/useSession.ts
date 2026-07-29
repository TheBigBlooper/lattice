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

/**
 * Why a session ended, when it ended rather than never having existed.
 *
 * <p>Only expiry is distinguished, because it is the only ending an operator does not already know
 * about: they performed a sign-out, and they know they never signed in. An expiry happens to them.
 */
export type SignedOutReason = "expired";

/** What the console knows about the operator, and what it can do about it. */
export interface Session {
  /** Where the session stands. */
  status: SessionStatus;
  /** The bearer token to present, once there is one. */
  token: string | undefined;
  /** Who holds the session, for display. */
  username: string | undefined;
  /**
   * The strongest realm role this operator holds here, for display.
   *
   * <p>Read from the token rather than inferred from the username. They coincide for the local demo
   * user, which is exactly the trap: a person called anything else holding the operator role would
   * otherwise be labelled with their own name where a role belongs.
   */
  role: string | undefined;
  /**
   * Why the session ended, when it ended rather than never having started.
   *
   * <p>Undefined means there was nothing to lose - an operator who has not signed in here, or one
   * who arrived by redirect from a peer. The signed-out screen reads this to say which of those it
   * is looking at, instead of showing one sentence to three different arrivals.
   */
  signedOutReason: SignedOutReason | undefined;
  /** Hands off to this baseline's own Keycloak login page. */
  signIn: () => void;
  /** Ends the session at the provider, not only in this tab. */
  signOut: () => void;
}

/**
 * The roles this console understands, strongest first.
 *
 * <p>Only these two exist: viewer reads, operator also writes. Anything else a realm grants is not
 * something this console can describe, so it reports nothing rather than a role it cannot explain.
 */
const RANKED_ROLES = ["operator", "viewer"];

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
  const [role, setRole] = useState<string | undefined>(undefined);
  const [signedOutReason, setSignedOutReason] = useState<SignedOutReason | undefined>(undefined);

  // Held in a ref rather than state: the adapter is a long-lived object with its own listeners, and
  // putting it in state would re-create it on every render, each instance racing the last.
  const keycloakRef = useRef<Keycloak | null>(null);

  // Which realm the adapter above was built for, so a genuine change of realm replaces it while a
  // re-run for any other reason does not.
  const builtForRef = useRef<string | null>(null);

  useEffect(() => {
    const key = `${realm.keycloakUrl}|${realm.keycloakRealm}|${realm.keycloakClientId}`;

    /**
     * Keeps a live session's token fresh, and reports the end of one that cannot be.
     *
     * <p>Every effect - including the storage writes - happens only while this adapter is still the
     * one the hook holds. That guard used to sit around the state updates alone, which left an
     * abandoned adapter able to clear the storage its replacement had just filled.
     */
    const attachRefresh = (adapter: Keycloak) => {
      adapter.onTokenExpired = () => {
        adapter
          .updateToken(MIN_TOKEN_VALIDITY_SECONDS)
          .then(() => {
            if (keycloakRef.current !== adapter) {
              return;
            }
            // Stored as well as held: the refreshed token is the one a reload must resume from,
            // and leaving the superseded one in storage would resume a session that has ended.
            storeTokens(adapter);
            setToken(adapter.token);
          })
          .catch(() => {
            if (keycloakRef.current !== adapter) {
              return;
            }
            // The refresh token is gone or rejected, which is a session that has genuinely ended.
            // Saying so is the point of this ticket: the operator loses the dashboard either way,
            // and the difference between a defect and an explanation is being told which happened.
            clearStoredTokens();
            setStatus("signed-out");
            setToken(undefined);
            setSignedOutReason("expired");
          });
      };
    };

    // ONE adapter per hook instance, initialised exactly once.
    //
    // StrictMode mounts, cleans up and mounts again on the same component, and refs survive that -
    // so an unconditional `new Keycloak` here builds a SECOND adapter racing the first. Both call
    // init, and the adapter consumes the authorization code from the URL: the first authenticates,
    // the second finds no code and resolves unauthenticated, settling the hook signed out moments
    // after a successful sign-in. It never reached the built container, which is why it went
    // undiagnosed for so long - it only ever broke the dev server, intermittently.
    //
    // Re-attaching the refresh handler is all a re-run needs, since the cleanup detaches it.
    const already = keycloakRef.current;
    if (already && builtForRef.current === key) {
      attachRefresh(already);
      return () => {
        already.onTokenExpired = undefined;
      };
    }

    const keycloak = new Keycloak({
      url: realm.keycloakUrl,
      realm: realm.keycloakRealm,
      clientId: realm.keycloakClientId,
    });
    keycloakRef.current = keycloak;
    builtForRef.current = key;

    /**
     * Whether this adapter is still the one the hook is using.
     *
     * <p>It replaces a per-run cancelled flag, which StrictMode defeats: that flag is set by the
     * first run's cleanup, so the first run's own init would then decline to report what it found
     * and the hook would never settle. Identity is the honest question - an adapter should stop
     * talking when it has been replaced, not when an effect happened to re-run.
     */
    const current = () => keycloakRef.current === keycloak;

    attachRefresh(keycloak);

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
      // A fresh sign-in clears any expiry reported earlier, so the card cannot go on explaining an
      // ending that has since been undone.
      if (authenticated) {
        setSignedOutReason(undefined);
      }
      setToken(keycloak.token);
      // Not a declared field on the parsed token, so it arrives through the index signature as
      // `any` and the cast is what pins it back down.
      setUsername(keycloak.tokenParsed?.preferred_username as string | undefined);
      // The strongest role, not the whole list. An operator also holds viewer, and reporting both
      // would say the weaker one about somebody who can write.
      //
      // `realm_access` IS declared, so reading it by name yields the library's own type and needs
      // no cast - the hand-written one it replaces was a weaker restatement of that type.
      const realmAccess = keycloak.tokenParsed?.realm_access;
      const held = realmAccess?.roles ?? [];
      setRole(RANKED_ROLES.find((known) => held.includes(known)));
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
        if (!current()) {
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
            if (current()) {
              settle(true);
            }
          })
          .catch(() => {
            if (!current()) {
              return;
            }
            clearStoredTokens();
            settle(false);
          });
      })
      .catch(() => {
        // A provider that cannot be reached leaves the console signed out rather than stuck
        // initialising: an operator can act on a sign-in button, not on a spinner.
        if (current()) {
          setStatus("signed-out");
        }
      });

    return () => {
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

  return { status, token, username, role, signedOutReason, signIn, signOut };
}
