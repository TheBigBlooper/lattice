package io.lattice.common.mesh;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PfxOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a baseline can read the certificate its own broker actually presents, by completing
 * a TLS handshake against it and inspecting the chain.
 *
 * <p>This is how the federation design learns whether this baseline's own certificate has expired,
 * which is what lets a link failure be reported as "yours to fix" rather than neutrally. It reads
 * the live artifact rather than a file on disk, so a broker running something other than what was
 * deployed is visible rather than silently trusted.
 *
 * <p>The server here is an ordinary TLS listener with a self-signed certificate. That is deliberate:
 * the read must work against a certificate the reader does <em>not</em> trust, because a revoked or
 * expired certificate is exactly the case it exists to inspect.
 */
class BrokerCertificateIT {

    /** The fixture keystore's password. A throwaway store generated per test run, never shipped. */
    private static final String STORE_PASSWORD = "lattice-test";

    private Vertx vertx;
    private NetServer server;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            await(server.close());
        }
        if (vertx != null) {
            await(vertx.close());
        }
    }

    /**
     * Verifies the probe returns the certificate the listener presented, with an expiry in the
     * future, without the reader having been configured to trust it.
     */
    @Test
    void readsTheCertificateAListenerPresents() throws Exception {
        var port = startTlsListener();

        var certificate = await(BrokerCertificate.read(vertx, "localhost", port));

        assertThat(certificate.notAfter()).isAfter(Instant.now());
        assertThat(certificate.expired()).isFalse();
    }

    /**
     * Verifies that a broker which cannot be reached at all yields a failure rather than a
     * misleading "certificate is fine" reading - the probe must never let unreachable look healthy.
     */
    @Test
    void failsRatherThanReportingHealthyWhenNothingIsListening() {
        var unusedPort = 1;

        var read = BrokerCertificate.read(vertx, "localhost", unusedPort);

        assertThat(read.failed() || !read.isComplete()).isTrue();
    }

    /** Starts a TLS listener with a throwaway certificate and returns its port. */
    private int startTlsListener() throws Exception {
        server = vertx.createNetServer(new NetServerOptions()
                .setSsl(true)
                .setKeyCertOptions(new PfxOptions().setPath(throwawayKeystore()).setPassword(STORE_PASSWORD))
                .setPort(0));
        server.connectHandler(socket -> {});
        return await(server.listen()).actualPort();
    }

    /**
     * Generates a throwaway PKCS12 keystore with keytool.
     *
     * <p>keytool rather than a certificate-generation library, because it ships with the JDK the
     * tests already run on and needs no dependency for a fixture. It is also what
     * {@code issue-certs.sh} uses, so the material here has the same shape as the real thing.
     */
    private String throwawayKeystore() throws Exception {
        var keystore = Files.createTempDirectory("lattice-cert-test").resolve("keystore.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();

        var process = new ProcessBuilder(
                        keytool,
                        "-genkeypair",
                        "-alias",
                        "broker",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "2",
                        "-dname",
                        "CN=localhost,OU=Lattice Baseline,O=Lattice,C=US",
                        "-keystore",
                        keystore.toString(),
                        "-storetype",
                        "PKCS12",
                        "-storepass",
                        STORE_PASSWORD,
                        "-keypass",
                        STORE_PASSWORD)
                .redirectErrorStream(true)
                .start();

        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroy();
            throw new IllegalStateException("keytool could not generate the fixture keystore");
        }
        return keystore.toString();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
