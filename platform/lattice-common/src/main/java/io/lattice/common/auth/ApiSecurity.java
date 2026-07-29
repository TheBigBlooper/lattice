package io.lattice.common.auth;

import io.lattice.common.RetryingGate;
import io.lattice.common.rest.Envelopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.authorization.OrAuthorization;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2Options;
import io.vertx.ext.auth.oauth2.authorization.KeycloakAuthorization;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.AuthorizationHandler;
import io.vertx.ext.web.handler.OAuth2AuthHandler;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared bearer-token guard protecting every service's {@code /api/v1} surface, built once here
 * so no service re-implements it and none can differ from another. It is mounted by
 * {@code BaseVerticle}, ahead of the routes a service contributes.
 *
 * <p><b>Bearer-only.</b> A request presents a JSON Web Token issued by <em>this</em> baseline's own
 * Keycloak realm; its signature is verified against that realm's JWKS, fetched once and cached, so no
 * call to Keycloak happens per request and a Keycloak outage does not invalidate tokens already
 * issued. A token signed by a peer baseline's realm fails the same check as a forged one - identity
 * belongs to the baseline that owns the data, so an operator working across baselines holds a grant on
 * each (see {@code docs/design/features/per_baseline_identity.md}).
 *
 * <p><b>Two roles, split on the verb.</b> The line that matters is who can change state, so reads
 * ({@code GET}, {@code HEAD}) need {@code viewer} or {@code operator} and every write verb needs
 * {@code operator}. Expressing it by verb rather than per operation means an operation added later is
 * protected by what it does, with nothing to remember.
 *
 * <p><b>The probes stay open.</b> Only {@code /api/v1} is guarded: a Kubernetes probe cannot present a
 * token, and gating {@code /health} or {@code /readiness} would take a healthy pod out of rotation on
 * a configuration mistake, trading a real availability risk for no secrecy.
 *
 * @see #create(Vertx, String, String)
 */
public final class ApiSecurity {

    private static final Logger LOG = LoggerFactory.getLogger(ApiSecurity.class);

    /** The name the OpenAPI spec gives the bearer security scheme. */
    public static final String SCHEME = "bearerAuth";

    /** The realm role granting reads: every {@code GET} under {@code /api/v1}. */
    public static final String VIEWER_ROLE = "viewer";

    /** The realm role granting reads plus every write. */
    public static final String OPERATOR_ROLE = "operator";

    /** The guarded path prefix - the whole versioned business API, and nothing else. */
    public static final String API_PATH = "/api/v1/*";

    /**
     * A placeholder client identity. Vert.x models an OAuth2 provider as a client and refuses to build
     * one without an id, but a bearer-only service is not a client: it never initiates a flow, never
     * calls the token endpoint, and never presents this value anywhere. The realm's own client id
     * belongs to the console, which is the party that actually authenticates, so inventing a service
     * setting for a value nothing reads would be config that lies about what it does.
     */
    private static final String BEARER_ONLY_CLIENT_ID = "lattice-bearer-only";

    /** The verbs treated as reads; everything else changes state and needs the operator role. */
    private static final List<HttpMethod> READ_METHODS = List.of(HttpMethod.GET, HttpMethod.HEAD);

    /** The verbs that change state. Listed rather than inferred, so a new verb is a deliberate choice. */
    private static final List<HttpMethod> WRITE_METHODS =
            List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final Vertx vertx;
    private final OAuth2Auth oauth2;
    private final String realmUrl;
    private final RetryingGate keys;

    private ApiSecurity(Vertx vertx, OAuth2Auth oauth2, String realmUrl) {
        this.vertx = vertx;
        this.oauth2 = oauth2;
        this.realmUrl = realmUrl;
        this.keys = new RetryingGate(oauth2::jWKSet);
    }

    /**
     * Builds the guard for one baseline's realm.
     *
     * <p>The issuer is pinned to the realm's own URL and validated on every token, which is what makes
     * a peer baseline's token a rejection here rather than an accepted foreign grant. No audience is
     * required: Keycloak issues a public client's token with an audience of its own choosing, and the
     * issuer plus signature already answer "was this minted by our realm".
     *
     * <p>The issuer and the signing-key address are supplied separately because in a cluster they are
     * genuinely different: a token is issued to a browser through a published address, while this
     * service fetches keys over the internal network. Trusting the issuer the token actually carries,
     * rather than the address we happen to fetch from, is what keeps the check meaningful.
     *
     * @param vertx       the Vert.x instance the JWKS fetch runs on.
     * @param realmUrl    this baseline's own realm URL as tokens claim it
     *                    ({@code <KEYCLOAK_URL>/realms/<KEYCLOAK_REALM>}); the issuer to trust.
     * @param internalUrl the realm URL this service fetches signing keys from; the same value when the
     *                    two addresses do not differ.
     * @return the configured guard.
     * @throws IllegalArgumentException if the realm URL is missing, rather than leaving {@code /api/v1}
     *     unprotected because a value was not set.
     */
    public static ApiSecurity create(Vertx vertx, String realmUrl, String internalUrl) {
        if (realmUrl == null || realmUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "keycloak is not configured: KEYCLOAK_URL and KEYCLOAK_REALM are both required, "
                            + "because /api/v1 must never be served unprotected");
        }
        var keysUrl = internalUrl == null || internalUrl.isBlank() ? realmUrl : internalUrl;
        var options = new OAuth2Options()
                .setClientId(BEARER_ONLY_CLIENT_ID)
                .setSite(keysUrl)
                .setJwkPath(keysUrl + "/protocol/openid-connect/certs")
                .setValidateIssuer(true)
                .setJWTOptions(new JWTOptions().setIssuer(realmUrl));
        return new ApiSecurity(vertx, OAuth2Auth.create(vertx, options), keysUrl);
    }

    /**
     * Loads the realm's signing keys, and arms a re-fetch for any token naming a key this service has
     * not seen.
     *
     * <p>The re-fetch is what makes the guard self-healing in both directions: a service that started
     * before Keycloak was reachable picks the keys up on the first request rather than serving 401s
     * until someone restarts the pod, and a realm that rotates its signing key is followed without a
     * redeploy. The request that triggers a re-fetch is still rejected - it is validated against the
     * keys in hand - so the recovery costs one request, not a restart.
     *
     * @return a future completing when the initial fetch settles; a failure is reported by the caller
     *     and is not terminal.
     */
    public Future<Void> loadKeys() {
        oauth2.missingKeyHandler(keyId -> {
            LOG.info("token names an unknown signing key {} - refreshing the realm's keys", keyId);
            oauth2.jWKSet()
                    .onFailure(err -> LOG.warn(
                            "refreshing the realm's signing keys from {} failed: {}", realmUrl, String.valueOf(err)));
        });
        return keys.ready();
    }

    /**
     * Mounts the guard on the router, ahead of the service's own routes: authentication for the whole
     * {@code /api/v1} surface, then the role check the verb calls for.
     *
     * <p>Mounting by path rather than per operation means a path under {@code /api/v1} that no service
     * implements is still guarded, so an unimplemented route can never answer to an anonymous caller.
     *
     * @param router the service's root router, before its own routes are added.
     */
    public void protect(Router router) {
        router.route(API_PATH).handler(this::awaitKeys);

        var authenticate = OAuth2AuthHandler.create(vertx, oauth2);
        router.route(API_PATH).handler(authenticate);

        var read = OrAuthorization.create()
                .addAuthorization(RoleBasedAuthorization.create(VIEWER_ROLE))
                .addAuthorization(RoleBasedAuthorization.create(OPERATOR_ROLE));
        for (HttpMethod method : READ_METHODS) {
            router.route(method, API_PATH).handler(roleHandler(read));
        }
        for (HttpMethod method : WRITE_METHODS) {
            router.route(method, API_PATH).handler(roleHandler(RoleBasedAuthorization.create(OPERATOR_ROLE)));
        }

        router.route(API_PATH).failureHandler(ApiSecurity::respondToAuthFailure);
    }

    /**
     * Tells a service's OpenAPI router not to enforce the spec's security requirement itself, because
     * this guard already has - mounted a level up, across the whole {@code /api/v1} path rather than
     * only the operations that service implements.
     *
     * <p>Without this the router refuses to build, since the spec declares a requirement it has no
     * handler for. The requirement in the spec is not decoration: it is what tells the generated
     * console client to attach the token and the interactive docs to offer an Authorize button, and it
     * is enforced - just not here.
     *
     * @param builder the router builder, after the service has registered its operation handlers.
     * @return the same builder, for chaining.
     */
    public static RouterBuilder enforcedByBaseVerticle(RouterBuilder builder) {
        builder.getRoutes().forEach(route -> route.setDoSecurity(false));
        return builder;
    }

    /**
     * Holds a request until the realm's signing keys are in hand, so a service that bound its port
     * before Keycloak answered validates the very first token rather than rejecting it. Once loaded the
     * gate is a settled future, so this costs nothing per request thereafter.
     *
     * <p>When the keys cannot be fetched at all, the honest answer is 503: the service cannot say
     * whether the token is good, which is a dependency being down, not a bad credential. Answering 401
     * would tell an operator holding a perfectly valid token that their token was rejected.
     *
     * @param ctx the request to gate.
     */
    private void awaitKeys(RoutingContext ctx) {
        var gate = keys.ready();

        // The overwhelmingly common case: the keys are already in hand, so there is no wait at all and
        // the request continues on this call. Kept separate from the path below so the pause/resume
        // dance is paid for only when a request genuinely has to wait.
        if (gate.succeeded()) {
            ctx.next();
            return;
        }

        // THE REQUEST MUST BE PAUSED WHILE IT WAITS, and this is not a refinement - it is the whole
        // correctness of the deferred path. Returning from a handler without calling ctx.next() leaves
        // nothing consuming the inbound stream, so the body arrives with no reader and is gone by the
        // time the keys land: the route then sees an empty body, and a validating router reports
        // "Request has already been read" as a 500.
        //
        // It surfaced only on the FIRST request after a cold start - once the keys are loaded the
        // branch above returns synchronously and there is no window - which is why it read in a
        // cluster as one flaky 500 rather than as a startup ordering problem.
        ctx.request().pause();
        gate.onComplete(loaded -> {
            ctx.request().resume();
            if (loaded.succeeded()) {
                ctx.next();
                return;
            }
            LOG.warn(
                    "cannot validate tokens - the realm's signing keys are unavailable at {}: {}",
                    realmUrl,
                    String.valueOf(loaded.cause()));
            ctx.response()
                    .setStatusCode(503)
                    .putHeader("content-type", "application/json")
                    .end(Envelopes.error("UNAVAILABLE", "Identity is currently unavailable.", null)
                            .encode());
        });
    }

    /** Builds the role check as a handler, reading the realm roles out of the validated token. */
    private static AuthorizationHandler roleHandler(io.vertx.ext.auth.authorization.Authorization required) {
        return AuthorizationHandler.create(required).addAuthorizationProvider(KeycloakAuthorization.create());
    }

    /**
     * Renders an authentication or authorization failure as the standard error envelope, so a rejected
     * request is shaped like every other failure the API returns rather than as a bare status line.
     * Anything else is passed along to the service's own failure handling.
     *
     * @param ctx the failed routing context.
     */
    private static void respondToAuthFailure(RoutingContext ctx) {
        var status = ctx.statusCode();
        if (status != 401 && status != 403) {
            ctx.next();
            return;
        }
        // Both are ordinary, expected outcomes of an exposed API - a caller without a token, or with
        // the wrong role - so they are recorded at DEBUG. Logging them louder would turn routine
        // traffic into noise that buries a real failure.
        LOG.debug(
                "rejected {} {} with {}", ctx.request().method(), ctx.request().path(), status);
        var body = status == 401
                ? Envelopes.error("UNAUTHORIZED", "A valid bearer token is required.", null)
                : Envelopes.error("FORBIDDEN", "This token's role does not permit that operation.", null);
        ctx.response()
                .setStatusCode(status)
                .putHeader("content-type", "application/json")
                .end(body.encode());
    }
}
