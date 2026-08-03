package io.lattice.common;

import io.lattice.common.config.LatticeConfig;
import io.lattice.common.metrics.LatticeMetrics;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.micrometer.MetricsDomain;
import io.vertx.micrometer.MicrometerMetricsFactory;
import io.vertx.micrometer.MicrometerMetricsOptions;
import java.util.Set;

/**
 * Builds the {@link Vertx} instance every Lattice service runs on.
 *
 * <p>This exists because two things must happen <b>before</b> the instance is created and therefore
 * cannot live in {@link BaseVerticle}: Vert.x's logging delegate is chosen the first time Vert.x
 * initializes its logging, and its metrics backend is chosen when the instance is built. By the time
 * a verticle starts, both decisions have already been made.
 *
 * <p>Each service's {@code MainVerticle} previously repeated that setup, so the same block existed
 * three times and a fourth service would have copied it again.
 *
 * @see LatticeMetrics
 */
public final class LatticeBootstrap {

    /**
     * Families the observability design deliberately leaves off: the event bus is in-process and
     * mostly uninteresting here, and each additional family widens the scrape payload and the label
     * surface for questions nobody has asked yet.
     */
    private static final Set<String> DISABLED_FAMILIES = Set.of(
            MetricsDomain.HTTP_CLIENT.toCategory(),
            MetricsDomain.NET_CLIENT.toCategory(),
            MetricsDomain.NET_SERVER.toCategory(),
            MetricsDomain.DATAGRAM_SOCKET.toCategory(),
            MetricsDomain.EVENT_BUS.toCategory());

    private LatticeBootstrap() {}

    /**
     * Creates the Vert.x instance a service runs on, reading whether to enable metrics from the
     * environment.
     *
     * @return the instance, with metrics wired when {@code METRICS_ENABLED} is not {@code false}.
     */
    public static Vertx vertx() {
        return vertx(LatticeConfig.metricsEnabledFromEnvironment());
    }

    /**
     * Creates the instance with metrics explicitly on or off. Package-private so a test can exercise
     * both states without a process-wide environment variable.
     *
     * @param metricsEnabled whether to register meters and wire the Vert.x metrics backend.
     * @return the configured instance.
     */
    static Vertx vertx(boolean metricsEnabled) {
        // Must be set before Vert.x initializes its logging, i.e. before the instance is built.
        System.setProperty("vertx.logger-delegate-factory-class-name", "io.vertx.core.logging.SLF4JLogDelegateFactory");
        if (!metricsEnabled) {
            return Vertx.vertx();
        }
        var registry = LatticeMetrics.enable();
        var options = new VertxOptions()
                .setMetricsOptions(new MicrometerMetricsOptions()
                        .setEnabled(true)
                        // Bound by LatticeMetrics instead, so letting the binding do it as well would
                        // register every JVM meter twice.
                        .setJvmMetricsEnabled(false)
                        .setDisabledMetricsCategories(DISABLED_FAMILIES));
        return Vertx.builder()
                .with(options)
                .withMetrics(new MicrometerMetricsFactory(registry))
                .build();
    }
}
