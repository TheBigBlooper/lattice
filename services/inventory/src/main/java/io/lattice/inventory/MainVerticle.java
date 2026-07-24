package io.lattice.inventory;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The thin bootstrap for the inventory service. As a verticle it simply deploys the
 * {@link InventoryVerticle}; as a program its {@link #main(String[])} stands up a Vert.x instance and
 * deploys itself (the launch entry point the container image runs).
 */
public final class MainVerticle extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(MainVerticle.class);

    /**
     * Program entry point: routes Vert.x's own logging through SLF4J (one pipeline), creates a Vert.x
     * instance, and deploys this verticle.
     *
     * @param args ignored; configuration comes from the environment via the shared config loader.
     */
    public static void main(String[] args) {
        // Must be set before Vert.x initializes its logging, i.e. before Vertx.vertx().
        System.setProperty("vertx.logger-delegate-factory-class-name", "io.vertx.core.logging.SLF4JLogDelegateFactory");
        var vertx = Vertx.vertx();
        vertx.deployVerticle(new MainVerticle()).onFailure(err -> {
            LOG.error("inventory service failed to deploy", err);
            vertx.close();
        });
    }

    @Override
    public Future<?> start() {
        return vertx.deployVerticle(new InventoryVerticle());
    }
}
