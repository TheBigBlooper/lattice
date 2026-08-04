package io.lattice.common.mesh;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;

/**
 * The certificate a baseline's own broker presents, read by completing a TLS handshake against it.
 *
 * <p><b>Why the handshake rather than a file or the broker's management interface.</b> Artemis
 * exposes nothing about its own certificate through management - measured against the running
 * broker, its entire management surface carries no certificate, keystore, truststore or TLS
 * attribute - so management, which serves every other read this design makes, cannot serve this one.
 * A mounted copy would work but distributes TLS material to a second pod and can disagree with what
 * the broker actually loaded. The handshake reads the live artifact, so a broker running something
 * other than what was deployed is visible rather than silently trusted.
 *
 * <p><b>What this can and cannot tell you.</b> It reports validity dates, which is what a proactive
 * expiry warning needs. It cannot tell you a certificate has been <em>revoked</em>: revocation lives
 * in the authority's revocation list, which this baseline does not hold. An unexpired but revoked
 * certificate therefore reads as valid here, and the federation link reports neutrally rather than
 * blaming this baseline. Detail: {@code docs/design/features/federation_link_visibility.md}.
 *
 * @param notBefore when the certificate becomes valid.
 * @param notAfter when the certificate stops being valid.
 * @param subject the certificate's subject distinguished name, for logs and detail lines.
 */
public record BrokerCertificate(Instant notBefore, Instant notAfter, String subject) {

    /**
     * How long to wait for a handshake before giving up.
     *
     * <p>Matched to the health poll's own timeout rather than chosen freely: this runs on the same
     * tick as the announce, and a read that outlives the heartbeat would make a baseline go quiet
     * to learn something about a certificate. Failing fast and reporting nothing is the better
     * trade every time.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /**
     * Reads the certificate presented by a TLS listener.
     *
     * <p>The client trusts everything on purpose. It is inspecting a certificate rather than
     * relying on one, and the cases worth inspecting - expired, or signed by an authority nobody
     * trusts - are exactly the ones a validating client would refuse to complete.
     *
     * @param vertx the Vert.x instance to open the connection on.
     * @param host the broker's host.
     * @param port the broker's TLS port.
     * @return the presented certificate, or a failure if nothing could be read.
     */
    public static Future<BrokerCertificate> read(Vertx vertx, String host, int port) {
        var client = vertx.createNetClient(new NetClientOptions()
                .setSsl(true)
                .setTrustAll(true)
                // Hostname verification off for the same reason the truststore is: a certificate
                // whose name no longer matches is one of the states worth reporting, and a client
                // that refuses the handshake cannot report it.
                .setHostnameVerificationAlgorithm("")
                .setConnectTimeout((int) TIMEOUT.toMillis()));

        return client.connect(port, host)
                .compose(socket -> {
                    final Future<BrokerCertificate> read = presented(socket);
                    return socket.close().transform(closed -> read);
                })
                .eventually(() -> client.close());
    }

    /** Reads the leaf certificate off a connected socket, as a future so the failure travels. */
    private static Future<BrokerCertificate> presented(io.vertx.core.net.NetSocket socket) {
        try {
            var chain = socket.peerCertificates();
            return chain.isEmpty()
                    ? Future.failedFuture("the listener presented no certificate")
                    : Future.succeededFuture(of((X509Certificate) chain.get(0)));
        } catch (Exception failure) {
            return Future.failedFuture(failure);
        }
    }

    /** Builds the record from a parsed certificate. */
    private static BrokerCertificate of(X509Certificate certificate) {
        return new BrokerCertificate(
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                certificate.getSubjectX500Principal().getName());
    }

    /**
     * Whether the certificate is outside its validity window right now.
     *
     * @return true when it has expired or is not yet valid.
     */
    public boolean expired() {
        var now = Instant.now();
        return now.isAfter(notAfter) || now.isBefore(notBefore);
    }

    /**
     * How long until this certificate stops being valid.
     *
     * @return the remaining validity, which is zero once it has expired.
     */
    public Duration remaining() {
        var left = Duration.between(Instant.now(), notAfter);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
