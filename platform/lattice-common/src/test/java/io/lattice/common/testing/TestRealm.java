package io.lattice.common.testing;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * A stand-in for a baseline's Keycloak realm, for tests that need real bearer tokens without a
 * Keycloak container. It generates an RSA key pair in-process, serves the matching JWKS at the same
 * path Keycloak does, and mints signed JSON Web Tokens carrying the realm roles under test.
 *
 * <p><b>What this does and does not prove.</b> Services validate a token by verifying its signature
 * against the JWKS their realm publishes, so a token signed here is indistinguishable to the service
 * from one Keycloak issued: every accept and reject path - no token, a bad signature, an expired
 * token, a token from another realm, and each role outcome - is exercised for real. What it cannot
 * prove is that Keycloak's own token and realm import behave as expected; that is what the local
 * {@code docker compose} pass against the real Keycloak covers.
 *
 * <p>Published in the shared test-jar so every service suite mints tokens the one way, rather than
 * each rebuilding a signer. Close it in teardown to release the HTTP server.
 *
 * @see #start(Vertx, String)
 */
public final class TestRealm implements AutoCloseable {

    /** The realm role granting reads (every {@code GET} under {@code /api/v1}). */
    public static final String VIEWER = "viewer";

    /** The realm role granting reads plus writes. */
    public static final String OPERATOR = "operator";

    private static final String KEY_ID = "lattice-test-key";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final Vertx vertx;
    private final HttpServer server;
    private final String realm;
    private final int port;
    private final KeyPair keyPair;

    private TestRealm(Vertx vertx, HttpServer server, String realm, KeyPair keyPair) {
        this.vertx = vertx;
        this.server = server;
        this.realm = realm;
        this.port = server.actualPort();
        this.keyPair = keyPair;
    }

    /**
     * Generates a key pair and starts the JWKS endpoint on an ephemeral port, so two realms can run
     * side by side in one test process (which is what lets a "token from a peer baseline" case be
     * written honestly rather than simulated).
     *
     * <p>The realm owns the Vert.x instance serving it, rather than borrowing the one under test. That
     * keeps it startable from {@code @BeforeAll} - the identity provider a service talks to is external
     * to that service, and modelling it as external is also what stops a suite's own Vert.x lifecycle
     * from having to be sequenced around it.
     *
     * @param realm the realm name, appearing in the served path and in every token's issuer claim.
     * @return the started realm.
     */
    public static TestRealm start(String realm) {
        KeyPair keyPair;
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("generating the test realm's key pair failed", e);
        }
        var vertx = Vertx.vertx();
        var router = Router.router(vertx);
        router.get("/realms/" + realm + "/protocol/openid-connect/certs")
                .handler(ctx -> ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(jwks((RSAPublicKey) keyPair.getPublic()).encode()));
        var server = vertx.createHttpServer()
                .requestHandler(router)
                .listen(0)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        return new TestRealm(vertx, server, realm, keyPair);
    }

    /**
     * Returns the base URL a service points {@code KEYCLOAK_URL} at.
     *
     * @return this realm's Keycloak-equivalent base URL.
     */
    public String keycloakUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Returns the realm name a service points {@code KEYCLOAK_REALM} at.
     *
     * @return this realm's name.
     */
    public String realm() {
        return realm;
    }

    /**
     * Returns the composed realm URL a service's guard validates against - the same value
     * {@code BaseVerticle} builds from {@code KEYCLOAK_URL} and {@code KEYCLOAK_REALM}, and the issuer
     * every token minted here claims.
     *
     * @return this realm's URL.
     */
    public String realmUrl() {
        return keycloakUrl() + "/realms/" + realm;
    }

    /**
     * Mints a valid token carrying the given realm roles, shaped the way Keycloak shapes one (roles
     * under {@code realm_access.roles}).
     *
     * @param roles the realm roles to grant the token.
     * @return the signed compact JSON Web Token.
     */
    public String token(String... roles) {
        return token(Instant.now().plusSeconds(300), List.of(roles));
    }

    /** @return a token with the {@link #VIEWER} role, valid for five minutes. */
    public String viewerToken() {
        return token(VIEWER);
    }

    /**
     * @return a token with both realm roles, valid for five minutes. Keycloak expands the composite
     *     {@code operator} role, so a real operator's token carries {@code viewer} too.
     */
    public String operatorToken() {
        return token(OPERATOR, VIEWER);
    }

    /**
     * Builds a web client that presents an operator token on every request, so a suite testing what an
     * endpoint <em>does</em> is not rewritten to thread a header through each call. A suite testing the
     * guard itself sets the header per request instead.
     *
     * @param vertx the Vert.x instance the client runs on (the one under test, not this realm's).
     * @return a client carrying an operator bearer token.
     */
    public WebClient operatorClient(Vertx vertx) {
        return clientWith(vertx, operatorToken());
    }

    /**
     * Builds a web client that presents a viewer token on every request.
     *
     * @param vertx the Vert.x instance the client runs on.
     * @return a client carrying a viewer bearer token.
     */
    public WebClient viewerClient(Vertx vertx) {
        return clientWith(vertx, viewerToken());
    }

    /** Wraps a web client so every request carries the given bearer token. */
    private static WebClient clientWith(Vertx vertx, String token) {
        return WebClientSession.create(WebClient.create(vertx)).addHeader("Authorization", "Bearer " + token);
    }

    /**
     * Mints a token that expired five minutes ago, for the rejection path.
     *
     * @param roles the realm roles to grant the (already expired) token.
     * @return the signed compact JSON Web Token.
     */
    public String expiredToken(String... roles) {
        return token(Instant.now().minusSeconds(300), List.of(roles));
    }

    /** Signs a token with the given expiry and roles. */
    private String token(Instant expiry, List<String> roles) {
        var header = new JsonObject().put("alg", "RS256").put("typ", "JWT").put("kid", KEY_ID);
        var payload = new JsonObject()
                .put("iss", realmUrl())
                .put("sub", "00000000-0000-0000-0000-000000000001")
                .put("preferred_username", "test-operator")
                .put("iat", Instant.now().minusSeconds(5).getEpochSecond())
                .put("exp", expiry.getEpochSecond())
                .put("realm_access", new JsonObject().put("roles", new JsonArray(roles)));
        var signingInput = encode(header.encode()) + "." + encode(payload.encode());
        return signingInput + "." + BASE64_URL.encodeToString(sign(signingInput));
    }

    /** Signs the token's signing input with this realm's private key (RS256). */
    private byte[] sign(String signingInput) {
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign((RSAPrivateKey) keyPair.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            // A fixed algorithm over a key this class generated cannot fail in practice; surfacing it
            // as an unchecked failure keeps the signing helpers usable inline in a test.
            throw new IllegalStateException("signing the test token failed", e);
        }
    }

    /** Renders the public half as the JWKS document Keycloak serves. */
    private static JsonObject jwks(RSAPublicKey publicKey) {
        var key = new JsonObject()
                .put("kty", "RSA")
                .put("kid", KEY_ID)
                .put("use", "sig")
                .put("alg", "RS256")
                .put("n", BASE64_URL.encodeToString(unsigned(publicKey.getModulus())))
                .put("e", BASE64_URL.encodeToString(unsigned(publicKey.getPublicExponent())));
        return new JsonObject().put("keys", new JsonArray().add(key));
    }

    /**
     * Renders a positive {@link BigInteger} as the unsigned big-endian bytes a JWK expects, dropping
     * the sign byte {@code toByteArray} prepends when the high bit is set (which it is for roughly
     * half of all generated moduli - leaving it in yields a key the consumer cannot parse).
     */
    private static byte[] unsigned(BigInteger value) {
        var bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            var trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static String encode(String json) {
        return BASE64_URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stops the Vert.x instance serving this realm, which closes its HTTP server with it.
     *
     * <p>Closed in one call from the caller's thread rather than by chaining onto the server's close:
     * that chain would run the {@code vertx.close()} on this instance's own event loop, which waits on
     * the very thread it is running on and never returns.
     */
    @Override
    public void close() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }
}
