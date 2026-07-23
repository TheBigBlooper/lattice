package io.lattice.common.mesh;

import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.contract.mesh.MeshEnvelope;
import io.vertx.core.Future;
import io.vertx.core.Handler;

/**
 * The mesh-discovery seam a cluster uses to announce itself, send directed envelopes, and consume
 * peers' messages over the Artemis-backed mesh. It is declared here so service code depends on this
 * interface (and the shared {@link io.lattice.contract.mesh contract envelopes}) rather than on any
 * broker specifics.
 *
 * <p>This is an interface only. The Artemis-backed implementation (broker connection, the multicast
 * announce address, the per-cluster durable inbox, at-least-once idempotent delivery) is added in a
 * later mesh ticket; nothing here wires a broker.
 *
 * @see ClusterAnnouncement
 * @see MeshEnvelope
 */
public interface MeshClient {

    /**
     * Broadcasts this cluster's presence to the mesh (the announce is wrapped in a
     * {@link MeshEnvelope} and published to the shared multicast announce address).
     *
     * @param announcement this cluster's current announcement (id, region, baseline, health, endpoint).
     * @return a future completing when the announcement has been published.
     */
    Future<Void> announce(ClusterAnnouncement announcement);

    /**
     * Publishes a pre-built envelope to its addressed destination (e.g. a directed handoff or ack to
     * a peer cluster's inbox).
     *
     * @param envelope the envelope to publish.
     * @return a future completing when the envelope has been published.
     */
    Future<Void> publish(MeshEnvelope envelope);

    /**
     * Subscribes to a mesh address, invoking the handler for each envelope received. The handler must
     * be idempotent, since mesh delivery is at-least-once (a duplicate is a no-op keyed on the
     * envelope's message id).
     *
     * @param address the mesh address to consume from (e.g. the announce address or this cluster's inbox).
     * @param handler the idempotent handler invoked per received envelope.
     * @return a future completing when the subscription is established.
     */
    Future<Void> subscribe(String address, Handler<MeshEnvelope> handler);
}
