package io.lattice.common.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.contract.mesh.MeshLinkState;
import io.vertx.amqp.AmqpClient;
import io.vertx.amqp.AmqpConnection;
import io.vertx.amqp.AmqpSender;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * How the mesh client handles a connection it has abandoned.
 *
 * <p>Both failure notifications - the transport exception handler and the close future - are
 * registered per connection, and each one used to act on whatever connection was current rather
 * than on the one it was reporting about. A reconnect can complete before a dead connection has
 * finished reporting its own death, so a late notification could tear down the connection that had
 * just replaced it.
 *
 * <p>The transport is stubbed rather than driven against a broker, because the property under test
 * is the ordering of two callbacks and a real broker cannot be asked to produce that order.
 */
class AmqpMeshClientReconnectTest {

    /** One stubbed connection, with the handlers it was given and whether it was closed. */
    private static final class StubConnection {
        private final List<Handler<Throwable>> exceptionHandlers = new ArrayList<>();
        private final Promise<Void> closeFuture = Promise.promise();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AmqpConnection connection;

        StubConnection() {
            var sender = stub(AmqpSender.class, Map.of());
            connection = stub(
                    AmqpConnection.class,
                    Map.of(
                            "exceptionHandler",
                            (Answer) args -> {
                                exceptionHandlers.add(cast(args[0]));
                                return null;
                            },
                            "closeFuture",
                            (Answer) args -> closeFuture.future(),
                            "createSender",
                            (Answer) args -> Future.succeededFuture(sender),
                            "close",
                            (Answer) args -> {
                                closed.set(true);
                                return Future.succeededFuture();
                            }));
        }

        /** Reports a transport error, which does not imply the connection has closed. */
        void failTransport() {
            exceptionHandlers.forEach(handler -> handler.handle(new IllegalStateException("link error")));
        }

        /** Reports the close, which is what a graceful broker restart produces. */
        void reportClosed() {
            closeFuture.tryComplete();
        }
    }

    /** What a stubbed method does with its arguments. */
    private interface Answer {
        Object apply(Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    /**
     * A stub of a Vert.x interface, answering only the methods a test names.
     *
     * <p>A proxy rather than 27 hand-written methods across three interfaces: the ones that matter
     * here are four, and the rest exist only so the type can be implemented at all.
     */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Answer> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            var answer = answers.get(method.getName());
            if (answer != null) {
                var result = answer.apply(args == null ? new Object[0] : args);
                return result == null && method.getReturnType().isInstance(proxy) ? proxy : result;
            }
            // Fluent setters return the object itself; everything else is unused by this test.
            return method.getReturnType().isInstance(proxy) ? proxy : null;
        });
    }

    private static ClusterAnnouncement announcement() {
        return new ClusterAnnouncement(
                "hub-central", "us-east", "1.0.0", "ready", "https://console:3000", "https://svc:8080/api/v1");
    }

    /**
     * A late close from a replaced connection leaves the live one alone.
     *
     * <p>This is the ordering that used to break the mesh quietly: the client would report itself
     * disconnected while holding a working connection, and only reconnect on the next heartbeat.
     */
    @Test
    void aLateCloseFromAReplacedConnectionDoesNotTearDownTheLiveOne() {
        var first = new StubConnection();
        var second = new StubConnection();
        Deque<StubConnection> connections = new ArrayDeque<>(List.of(first, second));
        var client = stub(AmqpClient.class, Map.of("connect", (Answer)
                args -> Future.succeededFuture(connections.removeFirst().connection)));

        var mesh = new AmqpMeshClient("hub-central", Clock.systemUTC(), client);
        mesh.announce(announcement());
        assertEquals(MeshLinkState.UP, mesh.linkState());

        // The first connection dies and is replaced.
        first.failTransport();
        assertEquals(MeshLinkState.DOWN, mesh.linkState());
        mesh.announce(announcement());
        assertEquals(MeshLinkState.UP, mesh.linkState(), "the replacement should be live");

        // The first connection now finishes reporting its own death, late.
        first.reportClosed();

        assertEquals(
                MeshLinkState.UP,
                mesh.linkState(),
                "a notification from a replaced connection must not tear down its replacement");
    }

    /**
     * An abandoned connection is closed rather than merely dropped.
     *
     * <p>A transport error does not mean the connection is closed, and an abandoned one keeps its
     * receiver attached at the broker - which would deliver every announcement once more for each
     * abandoned connection still holding a link.
     */
    @Test
    void anAbandonedConnectionIsClosed() {
        var first = new StubConnection();
        var second = new StubConnection();
        Deque<StubConnection> connections = new ArrayDeque<>(List.of(first, second));
        var client = stub(AmqpClient.class, Map.of("connect", (Answer)
                args -> Future.succeededFuture(connections.removeFirst().connection)));

        var mesh = new AmqpMeshClient("hub-central", Clock.systemUTC(), client);
        mesh.announce(announcement());
        first.failTransport();

        assertTrue(first.closed.get(), "the abandoned connection should have been closed");
    }

    /**
     * A client that has never connected reports NO federation links, rather than an empty-but-healthy
     * reading.
     *
     * <p>The distinction is the whole discipline of this signal: absent means "not measured", so a
     * console renders nothing instead of an all-clear no broker was ever asked for.
     */
    @Test
    void reportsNoFederationLinksWhenNeverConnected() throws Exception {
        // Never connected: no connect() is ever called, so there is no broker to ask.
        var client = stub(AmqpClient.class, Map.of());
        var mesh = new AmqpMeshClient("hub-central", Clock.systemUTC(), client);

        var links =
                mesh.federationLinks().toCompletionStage().toCompletableFuture().get();

        assertTrue(links.isEmpty(), "nothing was asked, so nothing is claimed");
    }
}
