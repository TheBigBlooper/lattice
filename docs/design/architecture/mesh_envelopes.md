# Mesh Envelopes - Schema + Versioning

The shared wire shape every cluster agrees on for the mesh, and how it versions. This is the single-writer contract that lives in `platform/lattice-contract`; both sides of every cross-cluster exchange depend on it. Settles the envelope schema and how it versions.

Related: [mesh_discovery.md](mesh_discovery.md) (the announce mechanism), [cluster_interop.md](cluster_interop.md) (Shape A federation: what the mesh carries), [locked_decisions.md](../../reference/locked_decisions.md) (#18 envelopes module, #17 REST contract, #37 Shape A federation).

---

## Principle

An **envelope** is a self-describing message that crosses the Artemis mesh. Under Shape A federation (locked #37) the mesh is a **discovery phone book**: the only thing that crosses it is a cluster announcing its presence + endpoints. No local document and no work ever crosses. Every envelope is an immutable Java **record** in `lattice-contract`, serialized as **JSON** on the wire via Vert.x's built-in JSON (no extra serialization dependency - one engine per job, locked #15).

---

## Envelope structure

Every envelope shares a common **header** wrapping a typed **payload**:

```json
{
  "messageId": "e4f1...-uuid",
  "type": "ClusterAnnouncement",
  "schemaVersion": 1,
  "sourceClusterId": "hub-west",
  "occurredAt": "2026-07-22T20:00:00Z",
  "correlationId": null,
  "payload": { }
}
```

| Field             | Type              | Purpose                                                                                       |
|-------------------|-------------------|-----------------------------------------------------------------------------------------------|
| `messageId`       | UUID string       | Unique per message.                                                                            |
| `type`            | string            | The envelope type, selects the payload shape (`ClusterAnnouncement`).                          |
| `schemaVersion`   | int               | Per-type payload version (starts at 1). Drives the compatibility rule below.                   |
| `sourceClusterId` | string            | The announcing cluster's id (e.g. `hub-west`).                                                 |
| `occurredAt`      | UTC instant       | Event time, always UTC (never host-local - core_protocol container rule).                      |
| `correlationId`   | UUID string, null | Reserved for a future request/reply pairing; null for unsolicited messages (all MVP messages). |
| `payload`         | object            | The typed body, shape selected by `type` + `schemaVersion`.                                    |

The header shape is kept general (it already supports a nullable `correlationId`) so a future directed type can be added additively without reworking the wrapper. Representative record (exact Java lives in `lattice-contract`):

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

Under Shape A the mesh carries **discovery only**, so there is exactly one type. Others are added later, additively, under the versioning rule.

| Type                 | Direction              | Payload                                                                | Settles |
|----------------------|------------------------|-----------------------------------------------------------------------|---------|
| `ClusterAnnouncement`| multicast to all peers | `clusterId`, `region`, `baselineVersion`, `health`, `consoleUrl`, `apiBaseUrl` | locked #29 |

`consoleUrl` and `apiBaseUrl` are what make Shape A work: peers learn where to **redirect** an operator (`consoleUrl`) and where the **unified view reads a peer live** (`apiBaseUrl`). See [mesh_discovery.md](mesh_discovery.md) and [cluster_interop.md](cluster_interop.md).

**Removed (Shape A):** `FulfillmentHandoff`, `HandoffAck`, and the design-only `AvailabilityQuery` / `AvailabilityResponse`. No work or directed request/response crosses the mesh, so these directed types are gone (not deferred).

---

## Versioning + compatibility

`schemaVersion` is a per-type integer. Peers may run different baselines (locked #13), so the announcement must be forward/backward tolerant.

- **Additive change = same `schemaVersion`.** Adding an optional field keeps the version. Consumers **ignore unknown fields**; consumers **tolerate a missing optional field**. This is the common case and needs no coordination - and for a single unsolicited multicast type, it is sufficient on its own.
- **Breaking change = new `schemaVersion`.** Removing, renaming, or retyping a required field is a **new version**, coordinated across hubs. Never an in-place rewrite of an existing version.
- **Unknown higher version received:** the consumer **reads the stable header and skips the payload** rather than guessing. For an unsolicited announcement this is a silent skip (there is no request to reject); the peer simply is not added/refreshed from that message until it is understood.

```
v1 + new optional field          -> still v1 (compatible both ways)
v1 required field removed/retyped -> v2 (coordinated)
v1 peer receives a v2 payload     -> read header, skip payload (announcement ignored)
```

> **Note (Shape A).** The former **version-negotiation** design (a sender reading a peer's `supportedEnvelopeVersions` and down-emitting the highest common version, with a NACK backstop) existed to make **directed handoffs** degrade gracefully across a breaking change. With the directed layer removed and only an unsolicited, multicast `ClusterAnnouncement` on the mesh, negotiation has no exchange to serve - additive forward-compatibility above fully covers announcement evolution. `supportedEnvelopeVersions` is therefore no longer advertised. If a directed request/reply type is ever added, negotiation can be reintroduced with it.

---

## Decisions settled here (Shape A)

- Common header + typed payload; fields as tabled above (nullable `correlationId` retained for a future directed type).
- JSON wire format, immutable records in `lattice-contract`, Vert.x JSON (no new dependency).
- Additive-compatible versioning; breaking changes bump `schemaVersion`; an unknown higher version is read at the header and its payload skipped.
- MVP type: `ClusterAnnouncement` only (advertising `consoleUrl` + `apiBaseUrl`); directed handoff/availability types removed under Shape A; version negotiation retired with them.

Promoted to locked decisions - see [locked_decisions.md](../../reference/locked_decisions.md).
