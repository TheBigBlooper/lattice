# Mesh Envelopes - Schema + Versioning

The shared wire shapes every cluster agrees on for mesh interop, and how they version. This is the single-writer contract that lives in `platform/lattice-contract`; both sides of every cross-cluster exchange depend on it. Settles deferred question P3.

Related: [cluster_interop.md](cluster_interop.md) (how envelopes carry work), [mesh_discovery.md](mesh_discovery.md) (the announce envelope), [locked_decisions.md](../../reference/locked_decisions.md) (#18 envelopes module, #17 REST contract).

---

## Principle

An **envelope** is a self-describing message that crosses the Artemis mesh. Nothing local (no raw Elasticsearch document) ever crosses; only versioned envelopes do (see [cluster_interop.md](cluster_interop.md)). Every envelope is an immutable Java **record** in `lattice-contract`, serialized as **JSON** on the wire via Vert.x's built-in JSON (no extra serialization dependency - one engine per job, locked #15).

---

## Envelope structure

Every envelope shares a common **header** wrapping a typed **payload**:

```json
{
  "messageId": "e4f1...-uuid",
  "type": "FulfillmentHandoff",
  "schemaVersion": 1,
  "sourceClusterId": "hub-west",
  "occurredAt": "2026-07-22T20:00:00Z",
  "correlationId": "a19c...-uuid or null",
  "payload": { }
}
```

| Field             | Type              | Purpose                                                                                       |
|-------------------|-------------------|-----------------------------------------------------------------------------------------------|
| `messageId`       | UUID string       | Unique per message. The idempotency / dedup key (see [cluster_interop.md](cluster_interop.md)). |
| `type`            | string            | The envelope type, selects the payload shape (`ClusterAnnouncement`, `FulfillmentHandoff`, `HandoffAck`). |
| `schemaVersion`   | int               | Per-type payload version (starts at 1). Drives the compatibility rule below.                   |
| `sourceClusterId` | string            | The originating cluster's id (e.g. `hub-west`); also how a reply is routed back.               |
| `occurredAt`      | UTC instant       | Event time, always UTC (never host-local - core_protocol container rule).                      |
| `correlationId`   | UUID string, null | Ties a reply to its request (a `HandoffAck` sets it to the handoff's `messageId`); null for unsolicited messages. |
| `payload`         | object            | The typed body, shape selected by `type` + `schemaVersion`.                                    |

Representative record (illustrative - exact Java lands with the `lattice-contract` ticket):

```java
public record MeshEnvelope(
    String messageId,
    String type,
    int schemaVersion,
    String sourceClusterId,
    Instant occurredAt,
    String correlationId,   // nullable
    JsonObject payload) {}
```

---

## MVP envelope types

Three types are defined now (exactly what discovery #9 and handoff #10 need). Others are added later, additively, under the versioning rule.

| Type                 | Direction                         | Payload (illustrative)                                             | Settles |
|----------------------|-----------------------------------|--------------------------------------------------------------------|---------|
| `ClusterAnnouncement`| multicast to all peers            | `clusterId`, `region`, `baselineVersion`, `health`, `endpoint`     | P1      |
| `FulfillmentHandoff` | directed to a target hub's inbox  | `subjectId` (`<clusterId>:<localId>`), order/line detail, requesting hub | P2 |
| `HandoffAck`         | directed back to the sender's inbox| `outcome` (`accepted` \| `rejected`), `reason`, (`correlationId` in header) | P2 |

**Deferred (not defined yet):** `ShipmentHandoff`, `StockSignal` - added when their tickets land, as new types (no version bump to existing types).

---

## Versioning + compatibility

`schemaVersion` is a per-type integer. Peers may run different baselines (locked #13), so envelopes must be forward/backward tolerant.

- **Additive change = same `schemaVersion`.** Adding an optional field keeps the version. Consumers **ignore unknown fields**; senders **tolerate a missing optional field**. This is the common case and needs no coordination.
- **Breaking change = new `schemaVersion`.** Removing, renaming, or retyping a required field is a **new version**, coordinated across hubs. Never an in-place rewrite of an existing version.
- **Unknown higher version received:** the consumer **parses the header, skips the payload, and NACKs** ("unsupported version") rather than guessing. The header is always parseable because its shape is stable across versions.

```
v1 + new optional field        -> still v1 (compatible both ways)
v1 required field removed/retyped -> v2 (coordinated)
v1 peer receives a v2 payload  -> read header, skip payload, NACK "unsupported version"
```

A NACK for an unsupported version is surfaced the same way as any rejected handoff (see [cluster_interop.md](cluster_interop.md)).

---

## Decisions settled here (P3)

- Common header + typed payload; fields as tabled above.
- JSON wire format, immutable records in `lattice-contract`, Vert.x JSON (no new dependency).
- Additive-compatible versioning; breaking changes bump `schemaVersion`; unknown higher version is parsed at the header and NACKed.
- MVP types: `ClusterAnnouncement`, `FulfillmentHandoff`, `HandoffAck`; `ShipmentHandoff` / `StockSignal` deferred.

Promoted to locked decisions - see [locked_decisions.md](../../reference/locked_decisions.md).
