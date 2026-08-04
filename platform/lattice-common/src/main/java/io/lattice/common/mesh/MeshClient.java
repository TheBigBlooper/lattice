package io.lattice.common.mesh;

import io.lattice.contract.mesh.ClusterAnnouncement;
import io.lattice.contract.mesh.MeshEnvelope;
import io.lattice.contract.mesh.MeshLinkState;
import io.vertx.core.Future;
import io.vertx.core.Handler;

/**
 * The mesh seam a cluster uses to announce itself and to hear its peers. Service code depends on this
 * interface (and the shared {@link io.lattice.contract.mesh contract envelopes}) rather than on any
 * broker specifics, so the transport can change without touching callers.
 *
 * <p><b>Discovery only.</b> Under Shape A federation (locked #37) announcing presence is the mesh's
 * entire job - it is a phone book. There is no directed peer-to-peer traffic and no per-cluster
 * inbox: an operator acts on a baseline by being redirected to its own console, and the unified view
 * reads each peer live over its advertised REST API. So this interface deliberately offers only a
 * broadcast and a subscribe; there is nothing to send *to* a specific peer.
 *
 * @see ClusterAnnouncement
 * @see MeshEnvelope
 */
public interface MeshClient {

    /** The multicast address every cluster announces on and subscribes to (mesh_discovery.md). */
    String ANNOUNCE_ADDRESS = "lattice.mesh.announce";

    /**
     * Broadcasts this cluster's presence to the mesh, wrapped in a {@link MeshEnvelope} and published
     * to the shared multicast {@link #ANNOUNCE_ADDRESS}.
     *
     * @param announcement this cluster's current announcement (id, region, baseline, health, and the
     *                     console + API endpoints peers use to reach it).
     * @return a future completing when the announcement has been published.
     */
    Future<Void> announce(ClusterAnnouncement announcement);

    /**
     * Subscribes to a mesh address, invoking the handler for each envelope received. The handler must
     * be idempotent: delivery is at-least-once, and announcements are repeated on every heartbeat, so
     * receiving the same information twice is the normal case rather than an error.
     *
     * @param address the mesh address to consume from (normally {@link #ANNOUNCE_ADDRESS}).
     * @param handler the idempotent handler invoked per received envelope.
     * @return a future completing when the subscription is established.
     */
    Future<Void> subscribe(String address, Handler<MeshEnvelope> handler);

    /**
     * Whether this cluster currently holds a usable link to the mesh.
     *
     * <p>Reported rather than inferred, and the distinction is the reason this exists (locked #46).
     * A cluster whose link is down hears nothing, so its registry ages <em>every</em> peer out at
     * once - indistinguishable, from the registry alone, from the entire mesh going away. Asking the
     * client directly is the only way to tell "we are cut off" from "they are gone".
     *
     * <p>It answers about this cluster's own connection and nothing else. It is not a view of the
     * mesh's health, and it never travels on the mesh.
     *
     * @return {@link MeshLinkState#UP} while a usable connection is held, otherwise
     *         {@link MeshLinkState#DOWN} - including before the first connection is established.
     */
    MeshLinkState linkState();

    /**
     * The state of this broker's federation links to its peers, one entry per peer.
     *
     * <p><b>A different link from {@link #linkState()}, and the distinction is the point.</b> That
     * one is this cluster's connection to its <em>own</em> broker; this is that broker's connections
     * to <em>peer</em> brokers. One reads healthy while the other is dead whenever a certificate is
     * refused or expires, which is the case where a broken mesh would otherwise read as a quiet one
     * (locked #80).
     *
     * <p>Answered from the broker rather than inferred from peer silence, so it is per peer and
     * available a full liveness time-to-live before the peers it affects age out.
     *
     * <p><b>Empty means "could not read", never "nothing is wrong."</b> A client with no way to ask,
     * or whose broker did not answer, reports nothing rather than a set of confident healthy links -
     * so a consumer renders no state instead of an all-clear it has not earned.
     *
     * @return peer cluster id to whether that link is carrying; empty when it could not be read.
     */
    default Future<java.util.Map<String, Boolean>> federationLinks() {
        return Future.succeededFuture(java.util.Map.of());
    }

    /**
     * Releases the broker connection and any subscriptions. Safe to call when never connected.
     *
     * @return a future completing when the client has released its resources.
     */
    Future<Void> close();
}
