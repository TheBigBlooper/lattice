package io.lattice.contract.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the three MVP mesh envelope payload types
 * (ClusterAnnouncement, FulfillmentHandoff, HandoffAck) per mesh_envelopes.md
 * + locked decision 31. Each payload round-trips through JSON and declares its
 * type name + schema version; the envelope factories stamp the header.
 */
class EnvelopePayloadsTest {

  private static final Instant WHEN = Instant.parse("2026-07-22T20:00:00Z");

  @Test
  void clusterAnnouncementRoundTrips() {
    var a = new ClusterAnnouncement("hub-west", "us-west", "1.0.0", "ready", "https://west.svc:8080");
    var restored = ClusterAnnouncement.fromJson(new JsonObject(a.toJson().encode()));
    assertEquals(a, restored);
    assertEquals("ClusterAnnouncement", ClusterAnnouncement.TYPE);
    assertEquals(1, ClusterAnnouncement.SCHEMA_VERSION);
  }

  @Test
  void fulfillmentHandoffRoundTrips() {
    var h = new FulfillmentHandoff("hub-west:order-123", "hub-west", "sku-42", 3);
    var restored = FulfillmentHandoff.fromJson(new JsonObject(h.toJson().encode()));
    assertEquals(h, restored);
    assertEquals("hub-west:order-123", restored.subjectId());
    assertEquals("FulfillmentHandoff", FulfillmentHandoff.TYPE);
    assertEquals(1, FulfillmentHandoff.SCHEMA_VERSION);
  }

  @Test
  void handoffAckRoundTripsWithNullableReason() {
    var accepted = new HandoffAck(HandoffOutcome.ACCEPTED, null);
    var restored = HandoffAck.fromJson(new JsonObject(accepted.toJson().encode()));
    assertEquals(HandoffOutcome.ACCEPTED, restored.outcome());
    assertNull(restored.reason());

    var rejected = new HandoffAck(HandoffOutcome.REJECTED, "out of stock");
    assertEquals("out of stock", HandoffAck.fromJson(rejected.toJson()).reason());
    assertEquals("HandoffAck", HandoffAck.TYPE);
  }

  @Test
  void announcementEnvelopeFactoryStampsHeader() {
    var a = new ClusterAnnouncement("hub-west", "us-west", "1.0.0", "ready", "https://west.svc:8080");
    var env = MeshEnvelope.announce("m-1", "hub-west", WHEN, a);

    assertEquals("ClusterAnnouncement", env.type());
    assertEquals(1, env.schemaVersion());
    assertEquals("hub-west", env.sourceClusterId());
    assertNull(env.correlationId());
    assertEquals("hub-west", ClusterAnnouncement.fromJson(env.payload()).clusterId());
  }

  @Test
  void handoffEnvelopeFactoryStampsHeader() {
    var h = new FulfillmentHandoff("hub-west:order-123", "hub-west", "sku-42", 3);
    var env = MeshEnvelope.handoff("m-1", "hub-west", WHEN, h);

    assertEquals("FulfillmentHandoff", env.type());
    assertEquals(1, env.schemaVersion());
    assertNull(env.correlationId());
    assertEquals("sku-42", FulfillmentHandoff.fromJson(env.payload()).itemSku());
  }

  @Test
  void ackEnvelopeFactoryLinksCorrelationIdToTheHandoff() {
    // A HandoffAck replies to a FulfillmentHandoff: correlationId = the handoff's messageId.
    var ack = new HandoffAck(HandoffOutcome.ACCEPTED, null);
    var env = MeshEnvelope.ack("m-2", "hub-central", WHEN, "m-1-handoff", ack);

    assertEquals("HandoffAck", env.type());
    assertEquals("m-1-handoff", env.correlationId());
    assertEquals(HandoffOutcome.ACCEPTED, HandoffAck.fromJson(env.payload()).outcome());
  }
}
