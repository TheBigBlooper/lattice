# Lattice - Builder's Reference

Part of the [replication pack](replication_prompt.md). The [replication prompt](replication_prompt.md) lists the artifacts a rebuilder must author rather than derive; this document closes most of that gap with three things: **annotated excerpts** of the load-bearing stanzas of those artifacts (the parts that cost the original its detours), the **configuration reference** (every platform environment variable in one table), and a **worked domain-slice example** on a neutral domain, carrying the write-operation patterns the excluded demo services existed to demonstrate.

**This document is deliberately self-contained.** Inside the repository the canonical sources remain the artifacts themselves (`deploy/k8s/chart/`, `deploy/certs/`, the contract module); if this document disagrees with them, they win.

---

## 1. Annotated artifact excerpts

Excerpts, not full files: each shows the stanzas whose exact shape is load-bearing, with the reason. Everything omitted keeps the component's own default. The excerpts keep the original's `lattice` spellings (the announce address, the federation role and names, the client ids, the `LATTICE_*` variable prefix) so they stay concrete; substitute your own system name throughout, per the replication prompt's naming rule.

### 1.1 The broker configuration - the static half

One `broker.xml`, **identical in every baseline**. Everything that differs between baselines (who the peers are) lives in two included files generated per baseline, so the static file never changes as the mesh grows:

```xml
<!-- inside <core>: the baseline-specific includes -->
<xi:include href="etc/connectors.xml"/>
<xi:include href="etc/federation.xml"/>
```

**Two acceptors, not one.** Local services and peer brokers have genuinely different answers to "who may connect", and one acceptor could not require a client certificate of a peer without demanding one of every local service:

```xml
<acceptors>
   <!-- this baseline's OWN services: AMQP, username + password, plain -->
   <acceptor name="artemis">tcp://0.0.0.0:61616?protocols=CORE,AMQP;...</acceptor>

   <!-- PEER BROKERS: separate port, mutual TLS -->
   <acceptor name="federation">tcp://0.0.0.0:61617?protocols=CORE;sslEnabled=true;needClientAuth=true;keyStorePath=${artemis.instance}/tls/keystore.p12;keyStorePassword=${LATTICE_TLS_PASSWORD};trustStorePath=${artemis.instance}/tls/truststore.p12;trustStorePassword=${LATTICE_TLS_PASSWORD};crlPath=${artemis.instance}/tls/crl.pem</acceptor>
</acceptors>
```

- `needClientAuth=true` is the load-bearing word: without it, TLS encrypts the link and authenticates only this broker to the peer, leaving the peer unidentified - the shared-credential problem with better transport.
- `crlPath` is what makes revocation real: a certificate the authority has revoked is refused even though it chains to a trusted authority.
- The truststore holds **the authority only** and names no peer, which is what lets a baseline that appears later be accepted with no edit here.

**Authorization names nobody.** A generic federation role, granted the same permissions as local services, identical on every broker - this is what a joiner presents to command an existing broker to federate back, so no existing broker is edited on a join:

```xml
<security-settings>
   <security-setting match="#">
      <permission type="send"    roles="amq,lattice_federation"/>
      <permission type="consume" roles="amq,lattice_federation"/>
      <!-- ...same pairing on create/delete queue and address, browse, manage -->
   </security-setting>
</security-settings>
```

**The announce address is declared, multicast, at broker start** - never left to auto-creation. Federation's address policy matches multicast addresses only, and an address first auto-created by whichever client connected first could be created anycast, which both breaks fan-out (a queue load-balances, so each peer sees a fraction) and silently excludes the address from federation:

```xml
<addresses>
   <address name="lattice.mesh.announce"><multicast/></address>
</addresses>
```

### 1.2 The generated peer topology

Rendered per baseline from chart values. A baseline with no configured peers renders an **empty** `<federations/>` and is still complete: the generic role above is what lets a future joiner command it.

```xml
<!-- connectors.xml: how peers are dialed, all over mutual TLS -->
<connectors>
   <!-- "self" is how a PEER reaches THIS broker: an externally resolvable
        host, and an ADVERTISED port that may differ from the listen port
        (NodePort or load balancer in front). A peer dials this verbatim. -->
   <connector name="A-self">tcp://ADVERTISED_HOST:ADVERTISED_PORT?sslEnabled=true;...</connector>
   <connector name="B">tcp://B_HOST:B_PORT?sslEnabled=true;...</connector>
</connectors>

<!-- federation.xml: the JOINER declares BOTH directions per peer -->
<federations>
   <!-- the federation NAME must be unique across the mesh: a broker keys
        arriving federations by name and silently DISCARDS a duplicate,
        so a shared name loses a joiner. Name it for the owning baseline. -->
   <federation name="lattice-mesh-A">
      <upstream name="A-from-B">
         <static-connectors><connector-ref>B</connector-ref></static-connectors>
         <policy ref="mesh-announce"/>
      </upstream>
      <downstream name="A-to-B">
         <static-connectors><connector-ref>B</connector-ref></static-connectors>
         <policy ref="mesh-announce"/>
         <!-- how B dials back: A's own advertised connector -->
         <upstream-connector-ref>A-self</upstream-connector-ref>
      </downstream>
      <address-policy name="mesh-announce" max-hops="1">
         <include address-match="lattice.mesh.announce"/>
      </address-policy>
   </federation>
</federations>
```

- The `downstream` element is the no-edit-on-join mechanism: the joiner commands the peer's broker to federate back, paying the whole cost itself.
- `max-hops="1"` on the address policy is the loop prevention the `loop-check` scenario measures.
- There is **no** `downstream-authorization` attribute in the Artemis 2.44 schema; a broker configured with one fails validation and does not start. The credential the joiner presents is the generic role's, and the policy scope is the one announce address.

### 1.3 The realm import file

A **template**, rendered per baseline, not static JSON - and only the URL lists are computed. Roles, groups, and client flags stay literal, because they are fixed and more reviewable as JSON:

- The console client's `redirectUris` and `webOrigins` **derive from the configured console address** (plus any explicitly added developer origins). This is the derive-never-redeclare rule at its sharpest: hand-pinned lists were the source of `Invalid parameter: redirect_uri` refusals pointing at a chart value.
- The docs client's redirects derive from the per-service browser addresses, one `/docs/oauth2-redirect.html` per service; empty is correct where services are not browser-exposed.

The fixed content, in shape:

```json
{
  "realm": "<per baseline>",
  "roles":  { "realm": [
      { "name": "viewer" },
      { "name": "operator", "composite": true, "composites": { "realm": ["viewer"] } } ] },
  "groups": [ { "name": "viewers",   "realmRoles": ["viewer"] },
              { "name": "operators", "realmRoles": ["operator"] } ],
  "clients": [
    { "clientId": "lattice-console", "publicClient": true, "standardFlowEnabled": true,
      "attributes": { "pkce.code.challenge.method": "S256" },
      "redirectUris": "<derived>", "webOrigins": "<derived>" },
    { "clientId": "lattice-docs", "publicClient": true, "standardFlowEnabled": true,
      "attributes": { "pkce.code.challenge.method": "S256" },
      "redirectUris": "<derived per service>", "webOrigins": "<derived per service>" } ]
}
```

`operator` is composite over `viewer`, so one grant carries both; the docs page gets its **own** public client so the console's redirect list stays narrow. Local development ships two seeded users (`operator`, `viewer`) with obvious passwords; a deployed realm does not.

### 1.4 The chart shape

An umbrella chart installs one baseline as **one release**: baseline-level values under `global`, a subchart per component (each with an `enabled` flag), and a mandatory library chart holding the shared helpers plus **one templated service definition** every platform-service subchart instantiates - so a service subchart is little more than its values.

The two derivations worth copying exactly:

- **The umbrella authors the service list**, and the gateway's rollup poll list (`CLUSTER_SERVICES`) is **rendered from it** - one entry `name=http://<release>-<name>:8080` per non-gateway service. Split across subcharts with no umbrella, no chart would know the full set and the rollup would have no author.
- **Identity is declared once** (`global.baseline`: cluster id, region, version, console and API addresses) and every consumer derives: the image tag and version label from `version`, the realm redirect lists from `consoleUrl`, the announcement fields from all of it. Two baselines differ in `global.baseline` and almost nowhere else - which is the point of the topology.

Infrastructure subcharts (datastore, broker, identity, its database) follow the same pattern with their own vendor configuration, each disableable so a customer can point at a component the environment already runs.

### 1.5 The scenario harness

Specified in full, with an implementation skeleton, in the [failure scenario specification](failure_scenarios.md). The shape to preserve: a control before every fault, faults induced by scaling controllers to zero, failures recorded and turned into the exit status, restores asserted, and the certificate scenario's restore armed as a trap.

---

## 2. Configuration reference

Every platform environment variable, its reader, and its default. The rule behind the table: each variable is declared, with a description, in the chart values of the component that reads it, and anything needed twice is derived from one declaration. Infrastructure components additionally carry their own vendor variables (Keycloak's `KC_*`, MySQL's `MYSQL_*`, Elasticsearch's `ES_JAVA_OPTS`), declared in their subcharts and not listed here.

### Every service (via the shared base)

| Variable               | Default        | Meaning                                                                                          |
|------------------------|----------------|--------------------------------------------------------------------------------------------------|
| `CLUSTER_ID`           | (required)     | This baseline's id. Given to every service: a service that cannot name its baseline cannot say so in anything it serves or logs. |
| `REGION`               | (required)     | This baseline's region label.                                                                    |
| `BASELINE_VERSION`     | (required)     | The baseline version. Also the default image tag and the version label, derived, so the three cannot disagree. |
| `HTTP_PORT`            | `8080`         | The API port.                                                                                    |
| `ELASTICSEARCH_URL`    | (required*)    | The baseline's own datastore. *Data-owning services and the gateway's probe.                     |
| `KEYCLOAK_URL`         | (required)     | The realm base the browser sees (token issuer).                                                  |
| `KEYCLOAK_INTERNAL_URL`| (optional)     | The in-cluster address services fetch the key set from, when it differs from the browser-facing one. |
| `KEYCLOAK_REALM`       | (required)     | This baseline's realm name.                                                                      |
| `CORS_ALLOWED_ORIGINS` | (required)     | The console origin(s) allowed cross-origin, reads and writes both.                               |
| `API_DOCS_ENABLED`     | unset = on     | The docs pages. Unset means on, so a value nobody set never silently withdraws the contract in development; production turns it off explicitly. |
| `METRICS_ENABLED`      | `true`         | The management-port metrics server.                                                              |
| `METRICS_PORT`         | `9090`         | The management port.                                                                             |

### The mesh-gateway only

| Variable                 | Default       | Meaning                                                                                        |
|--------------------------|---------------|-------------------------------------------------------------------------------------------------|
| `CONSOLE_URL`            | (required)    | Advertised in the announcement: where a peer redirects an operator.                            |
| `API_BASE_URL`           | (required)    | Advertised in the announcement; recorded by peers, never read from a browser.                  |
| `ARTEMIS_URL`            | (required)    | This baseline's **own** broker, single-valued by design (`tcp://<broker>:61616`). Peer brokers are reached by federation, never by services. |
| `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | (secret) | The local acceptor credential.                                                        |
| `ARTEMIS_TLS_PORT`       | `61617`       | Where the broker presents its certificate (the mutual-TLS acceptor). Read by TLS handshake for the expiry check, and derived from the broker subchart's federation port so the two cannot drift. |
| `CLUSTER_SERVICES`       | empty         | The rollup poll list, `name=url` comma-separated. **Authored by the umbrella**, rendered from the service list. |
| `CLUSTER_INFRASTRUCTURE` | empty         | The infrastructure report list, `name:kind=url` comma-separated. The broker entry carries **no URL** on purpose (its state is the gateway's own broker connection); the identity entry points at the **management** endpoint, which stays reachable when not ready. Unset yields no card plus a startup warning. |
| `HEARTBEAT_INTERVAL`     | `10s`         | Announce cadence. Parse-forgiving: a bad value falls back to the default rather than stopping the service. |
| `PEER_TTL`               | `30s`         | Peer liveness time-to-live (three missed heartbeats). Same forgiving parse.                    |

### The broker and the data jobs

| Variable                 | Default    | Meaning                                                                                             |
|--------------------------|------------|------------------------------------------------------------------------------------------------------|
| `LATTICE_TLS_PASSWORD`   | (secret)   | Protects the broker's keystore and truststore; referenced inside acceptor and connector strings.    |
| `LATTICE_ENV`            | (required by jobs) | Names the environment a data job is pointed at. Unset means the job refuses outright: an unnamed cluster is an unknown one. |
| `LATTICE_ALLOW_DATA_JOBS`| `false`    | The explicit opt-in for data jobs. Even set true, seed and reset still refuse against production.  |

### The console (build-time)

| Variable                  | Meaning                                                                                       |
|---------------------------|------------------------------------------------------------------------------------------------|
| `VITE_*_BASE_URL` family  | The per-service API addresses, **baked in at image build** - which is why the console image is rebuilt per baseline. |

---

## 3. A worked domain slice

The demonstration domain is excluded from this pack, but the write-operation patterns it existed to demonstrate must survive into whatever domain a replica chooses. This example carries all of them on a neutral domain - a venue-booking service with two resources, `slots` (capacity at a venue) and `bookings` (a customer holding capacity in a slot). Build your first domain service to these patterns, whatever it models.

**The contract slice** (all under `/api/v1`, all enveloped, all bearer-guarded; writes need `operator`):

| Operation                                | Pattern it carries                                                                             |
|------------------------------------------|-------------------------------------------------------------------------------------------------|
| `POST /bookings`                         | Strict create: server-minted id and UTC timestamp, no client-supplied id, 201 with the created record returned in place. |
| `GET /bookings/{bookingId}`              | Read by id; `NOT_FOUND` envelope when absent.                                                  |
| `GET /bookings?page&size`                | Paged list, newest first (a feed is read from the top), no filters, counts in `meta.pagination`. |
| `GET /slots?page&size`                   | Paged list sorted by natural key (a catalogue is scanned for a known item).                    |
| `PUT /slots/{slotId}`                    | **Absolute-set write**: sets capacity to the given value, creating the slot when absent. `CONFLICT` when the new capacity would fall below what is already booked. Written under optimistic concurrency. The destructive one, so the console confirms it before sending. |
| `GET /slots/{slotId}`                    | Availability read: booked and capacity stored, `available` **computed at read, never stored**. |

**The idempotent, contention-safe write** - the pattern that matters most:

`POST /bookings` books `quantity` in `slotId` for `customerId`, and is **idempotent by the natural key `(slotId, customerId)`**:

- Unknown slot: `NOT_FOUND`.
- Insufficient availability: `CONFLICT` (oversell-safe under optimistic concurrency - a concurrent write retries or conflicts, never double-books).
- Repeat with the **same** quantity: returns the existing booking, no second increment, still 2xx.
- Repeat with a **different** quantity: `CONFLICT` - it is neither the same request nor a new one.

**The data design**: index-per-entity (`slots`, `bookings`), read and write aliases from day one, committed mappings, snake_case fields, bootstrap on startup. The uniqueness of `(slotId, customerId)` is the document id of the booking-hold record, which is what makes the idempotency check a get rather than a search.

**The test slice, written first**: a route contract test asserting the success envelope, the `VALIDATION_ERROR` shape for an unknown body key, and the 401/403 pair; an integration test against real Elasticsearch asserting the oversell `CONFLICT` under concurrent writes and the idempotent repeat; and a contract unit test pinning every request and response shape.

What this example deliberately does **not** carry: cross-resource transactions (there are none in the platform - each write is one document under optimistic concurrency), free-text search (a shared query contract would commit every baseline to identical query semantics), and filters (added later, additively, once a screen has been used).
