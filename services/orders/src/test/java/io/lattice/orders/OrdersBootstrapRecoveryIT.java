package io.lattice.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.common.testing.TestRealm;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Recovery from a startup-time Elasticsearch outage, without a restart. The index bootstrap runs once
 * at startup; if it is allowed to memoize its failure, a service that starts before Elasticsearch is
 * reachable (the ordinary Kubernetes startup race) keeps failing every request forever, even after the
 * dependency recovers. This pins the fix: the failed provisioning attempt is retried on a later
 * request, so the same running process starts serving once Elasticsearch appears.
 *
 * <p>The container is bound to a port chosen <em>before</em> it starts, so the verticle can be pointed
 * at an address nothing is listening on yet - the outage is real (connection refused), not simulated.
 * Assertions are driven from the test thread with bounded waits rather than nested callbacks, and poll
 * to the settled state rather than snapshotting a single instant.
 */
@ExtendWith(VertxExtension.class)
class OrdersBootstrapRecoveryIT {

    /**
     * This baseline's identity realm. Every service now refuses to start without one and rejects an
     * unauthenticated /api/v1 call, so a suite testing what the endpoints do runs as an operator.
     */
    private static TestRealm REALM;

    /** Starts the realm the deployed service validates tokens against. */
    @BeforeAll
    static void startRealm() {
        REALM = TestRealm.start("lattice");
    }

    /** Releases the realm. */
    @AfterAll
    static void stopRealm() {
        REALM.close();
    }

    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.19.19");

    private static final int TIMEOUT_SECONDS = 60;

    /** Reserves a port by binding and immediately releasing it, so the container can claim it later. */
    private static int reservePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static JsonObject orderBody() {
        return new JsonObject()
                .put("customerId", "customer-recovery")
                .put(
                        "lines",
                        new JsonArray().add(new JsonObject().put("sku", "sku-1").put("quantity", 1)));
    }

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * With Elasticsearch unreachable at startup the service still deploys and correctly reports 503
     * UNAVAILABLE; once Elasticsearch becomes reachable at the same address, the very same process
     * provisions its index on a later request and serves create + get successfully - proving the failed
     * bootstrap attempt was retried rather than memoized.
     */
    @Test
    void recoversWithoutRestartOnceElasticsearchAppears(Vertx vertx) throws Exception {
        int esPort = reservePort();
        var verticle = new OrdersVerticle("http://localhost:" + esPort, 0, REALM.realmUrl());

        // Deploy while nothing is listening on esPort: the startup bootstrap attempt fails.
        await(vertx.deployVerticle(verticle));
        var client = REALM.operatorClient(vertx);

        // The outage is genuine and correctly classified, not a silent failure.
        HttpResponse<Buffer> duringOutage = await(client.post(verticle.actualPort(), "localhost", "/api/v1/orders")
                .sendJsonObject(orderBody()));
        assertEquals(503, duringOutage.statusCode(), "a request during the outage is UNAVAILABLE");
        assertEquals(
                "UNAVAILABLE",
                duringOutage.bodyAsJsonObject().getJsonObject("error").getString("code"));

        // Elasticsearch appears at the address the already-running service was configured with. The
        // suppression matches the sibling suites: the Testcontainers stop() lifecycle is handled below.
        @SuppressWarnings("resource")
        var elasticsearch = new ElasticsearchContainer(IMAGE)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node");
        elasticsearch.setPortBindings(List.of(esPort + ":9200"));
        elasticsearch.start();
        try {
            // No restart, no redeploy - the same process retries provisioning on a later request. Poll
            // to the settled state: the first attempt after recovery races the container's readiness.
            HttpResponse<Buffer> created = null;
            for (int attempt = 0; attempt < 30; attempt++) {
                created = await(client.post(verticle.actualPort(), "localhost", "/api/v1/orders")
                        .sendJsonObject(orderBody()));
                if (created.statusCode() == 201) {
                    break;
                }
                TimeUnit.SECONDS.sleep(1);
            }
            assertEquals(201, created.statusCode(), "the same running service must recover without a restart");

            var orderId = created.bodyAsJsonObject().getJsonObject("data").getString("orderId");
            assertTrue(orderId != null && !orderId.isBlank(), "the recovered create returns a persisted order");

            // The write really landed: read it back through the freshly provisioned index.
            HttpResponse<Buffer> fetched =
                    await(client.get(verticle.actualPort(), "localhost", "/api/v1/orders/" + orderId)
                            .send());
            assertEquals(200, fetched.statusCode(), "the order is readable after recovery");
            assertEquals(
                    orderId, fetched.bodyAsJsonObject().getJsonObject("data").getString("orderId"));
        } finally {
            client.close();
            elasticsearch.stop();
        }
    }
}
