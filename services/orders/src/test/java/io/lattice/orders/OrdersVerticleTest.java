package io.lattice.orders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrdersVerticle}'s failure classification helper. They pin the rule that
 * distinguishes a down dependency (an {@link IOException} anywhere in the cause chain, mapped to 503)
 * from a genuine internal error (mapped to 500), independent of the HTTP stack.
 */
class OrdersVerticleTest {

    /** A ConnectException (an IOException) at the head of the chain classifies as dependency-unavailable. */
    @Test
    void directIoExceptionIsDependencyUnavailable() {
        assertTrue(OrdersVerticle.isDependencyUnavailable(new ConnectException("connection refused")));
    }

    /** An IOException nested deeper in the cause chain still classifies as dependency-unavailable. */
    @Test
    void nestedIoExceptionIsDependencyUnavailable() {
        var wrapped = new RuntimeException("wrapping", new IOException("transport failed"));
        assertTrue(OrdersVerticle.isDependencyUnavailable(wrapped));
    }

    /** A failure whose chain holds no IOException (nor null) classifies as a genuine internal error. */
    @Test
    void nonIoFailureIsNotDependencyUnavailable() {
        var internal = new IllegalStateException("bug", new RuntimeException("cause"));
        assertFalse(OrdersVerticle.isDependencyUnavailable(internal));
    }
}
