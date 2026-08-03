package io.lattice.inventory;

import io.lattice.common.LatticeBootstrap;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
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
     * Program entry point: builds the shared Vert.x instance ({@link LatticeBootstrap} wires the
     * logging pipeline and the metrics backend) and deploys this verticle.
     *
     * @param args ignored; configuration comes from the environment via the shared config loader.
     */
    public static void main(String[] args) {
        var vertx = LatticeBootstrap.vertx();
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
