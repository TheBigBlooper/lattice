package io.lattice.common.mesh;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Verifies how a broker's queue listing is read as per-peer federation link state.
 *
 * <p>The parse is the load-bearing part and the fragile one: Artemis names its federated queues from
 * the federation and upstream names, which is a broker implementation detail rather than a contract.
 * These tests pin both the naming this depends on and - more importantly - what happens when it
 * stops matching, because a signal that fails to "healthy" is worse than no signal at all.
 */
class BrokerFederationTest {

    /** A federated queue exactly as a running broker names it. */
    private static JsonObject federatedQueue(String peer, String us, int consumers) {
        return new JsonObject()
                .put(
                        "name",
                        "federated.lattice-mesh-%s.%s-from-%s.topic://lattice.mesh.announce.multicast"
                                .formatted(peer, peer, us))
                .put("consumerCount", consumers);
    }

    /**
     * Verifies a peer whose broker is consuming reads as carrying, and one with no consumer reads as
     * not carrying - the distinction measured against a running broker, where a healthy link shows
     * one consumer and a broken one shows zero while the queue itself remains.
     */
    @Test
    void readsEachPeerFromItsFederatedQueuesConsumerCount() {
        var queues = new JsonArray()
                .add(federatedQueue("hub-east", "hub-central", 1))
                .add(federatedQueue("hub-west", "hub-central", 0));

        var links = BrokerFederation.linksFrom(queues);

        assertThat(links).containsEntry("hub-east", true).containsEntry("hub-west", false);
    }

    /** Verifies the broker's own non-federated queues are not mistaken for peers. */
    @Test
    void ignoresQueuesThatAreNotFederationLinks() {
        var queues = new JsonArray()
                .add(new JsonObject().put("name", "DLQ").put("consumerCount", 0))
                .add(new JsonObject().put("name", "ExpiryQueue").put("consumerCount", 0))
                .add(new JsonObject().put("name", "$sys.mqtt.sessions").put("consumerCount", 0))
                .add(federatedQueue("hub-east", "hub-central", 1));

        var links = BrokerFederation.linksFrom(queues);

        assertThat(links).hasSize(1).containsEntry("hub-east", true);
    }

    /**
     * Verifies that naming this parse does not recognise yields <em>nothing</em> rather than a
     * healthy reading.
     *
     * <p>This is the test that matters. If Artemis renames its federated queues on an upgrade, the
     * parse stops matching - and the only acceptable outcome is that the baseline reports no
     * federation state at all, so the console shows nothing rather than a confident "up". Failing to
     * absent is recoverable; failing to healthy is a signal that lies exactly when it is needed.
     */
    @Test
    void reportsNothingRatherThanHealthyWhenTheNamingStopsMatching() {
        var renamed = new JsonArray()
                .add(new JsonObject()
                        .put("name", "fed-link.lattice-mesh-hub-east.something-else")
                        .put("consumerCount", 1));

        var links = BrokerFederation.linksFrom(renamed);

        assertThat(links).isEmpty();
    }

    /** Verifies a broker with no peers configured reads as no links rather than as a failure. */
    @Test
    void readsNoLinksWhenNothingIsFederated() {
        var links = BrokerFederation.linksFrom(new JsonArray());

        assertThat(links).isEmpty();
    }

    /**
     * Verifies that two links to the same peer collapse to carrying when either carries.
     *
     * <p>A peer is reachable if any federated queue to it has a consumer: the pair of directions a
     * join creates can settle at different moments, and reporting a peer down because one of two
     * links has not finished connecting would flap on every restart.
     */
    @Test
    void treatsAPeerAsCarryingWhenAnyOfItsLinksIs() {
        var queues = new JsonArray()
                .add(federatedQueue("hub-east", "hub-central", 0))
                .add(new JsonObject()
                        .put("name", "federated.lattice-mesh-hub-east.hub-east-from-hub-central.topic://other")
                        .put("consumerCount", 1));

        var links = BrokerFederation.linksFrom(queues);

        assertThat(links).containsEntry("hub-east", true);
    }
}
