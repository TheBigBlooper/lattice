# Delivery model: how a baseline reaches a customer, and who runs it

Settles **P7**.

The question was recorded as "container registry + hosting" - where images are pushed and where clusters run. It turns out to be the wrong shape for this product, and answering it properly changes an assumption that had been sitting unexamined in `deploy_protocol.md` since the beginning.

---

## The decision, in one line

**Lattice is delivered to customers who run it themselves.** A baseline delivery is a set of exported image archives plus the Helm chart. There is no Lattice-hosted registry, no Lattice-hosted cluster, and no key material we hold on a customer's behalf.

---

## What that overturns

`deploy_protocol.md` stated as a key rule:

> a deployed cluster runs **images from the registry, referenced by an immutable tag** - never a `latest` tag and never a locally-built image pushed by hand

The **provenance** half of that rule is right and stays. The **registry** half assumed a vendor-hosted registry that customer clusters pull from, and that is not how this product ships. A customer's cluster pulls from wherever the customer put the images, which may be their own registry, their nodes' local image stores, or an air-gapped mirror. We do not know and should not need to.

Restated:

> A deployed cluster runs images **referenced by an immutable tag** (`<version>-<sha>`), loaded from a delivered archive. The tag is the provenance; where the image is served from is the customer's environment.

Provenance matters *more* under this model, not less. When we host the registry, "what is running" is answerable by looking at what we published. When the customer holds the artifacts, the tag is the only thread back to a commit - so a floating tag would make the question unanswerable rather than merely inconvenient.

---

## What a baseline delivery is

Exported image archives (`docker save`) plus the chart:

```
lattice-<version>/
├── images/
│   ├── orders-<version>-<sha>.tar
│   ├── inventory-<version>-<sha>.tar
│   ├── mesh-gateway-<version>-<sha>.tar
│   └── status-console-<version>-<sha>.tar
├── chart/                 the Helm chart for one baseline
└── README                 load, configure, install
```

**Archives rather than a registry pull, deliberately.** It is the only option that works air-gapped, it needs no account shared between us and the customer, and it makes the delivery a thing the customer can hold, archive, and re-install without us being reachable. The costs are real and accepted: the artifact is large, and the customer runs a load step before installing.

Third-party images (Elasticsearch, Keycloak, Artemis) are pinned by digest in the chart and are the customer's to obtain. Re-distributing other people's images is a licensing question we have no reason to take on.

---

## Hosting: deliberately deferred

**We do not host dev or prod clusters, and this decision does not pick a provider.** There is nothing yet that needs to be reachable from outside a developer's machine, and choosing a cloud before then would be paying - in money and in commitment - for a decision no work is waiting on.

What that leaves unproven is worth stating precisely, because it is narrower than it first sounds and it is easy to overstate in either direction.

**What is proven.** Three fully independent baselines federate: `hub-central`, `hub-east`, and `hub-west`, each with its own Elasticsearch, its own broker, its own Keycloak, its own services, and its own console. Between them they have demonstrated discovery, peer liveness and time-to-live expiry, federation with no edit on join, loop prevention with `max-hops="1"` measured differentially at the broker, mutual TLS with per-baseline certificates, revocation, and refusal of a certificate from an untrusted authority. That is multiple independent baselines in every sense that matters, and none of it is simulated.

**What is not.** All three sit on **one flat Docker network** and resolve each other by Docker DNS (`artemis`, `artemis-east`, `artemis-west`). No announcement or federation link has ever crossed a routing boundary, a network address translation, or a firewall, and no baseline has ever been reached at an externally routable address. The gap is about **addressing and reachability, not about the topology or the protocol** - which is exactly why all three requirements below are addressing requirements rather than design ones.

Put plainly: the mesh works, and it has never had to cross a network boundary to do it.

> ### Update: it has now crossed one
>
> Three baselines run in **three separate Kubernetes clusters** (`deploy/k8s/mesh-clusters.sh`), each with its own broker, datastore, identity provider and persistent identity database. Every baseline discovers both peers across the cluster boundary, each reporting `REACHABLE`, with federated queues on `lattice.mesh.announce` carrying the announcements between brokers.
>
> The path is real: a pod egresses through its own node, crosses the shared Docker bridge, and reaches a peer's node at a pinned NodePort, where mutual TLS authenticates the two brokers to each other. **Requirements 2 and 3 below are met** - certificates now carry the external name a peer actually dials, and each baseline advertises how peers reach it.
>
> **What this does not prove**, stated as precisely as the gap it replaces: the clusters share one Docker bridge, so the boundary crossed is between Kubernetes clusters rather than between networks. There is still no network address translation, no firewall, and no routable address beyond the machine. And the exposure is a **NodePort, not a TCP load balancer** - requirement 1's mechanism is therefore still unexercised, which is a fidelity gap recorded in the chart's own values rather than left implicit.
>
> So: the protocol and the addressing model survive a cluster boundary. Whether they survive a *network* boundary remains open, and is the part a hosting decision would settle.
>
> **Which scenarios have crossed it.** Of the six the compose harness proves, three assert *local* behaviour - a service failing changes this baseline's rollup, a broker outage changes its mesh-link state - and the boundary does not change what they assert, so re-running them across clusters would duplicate a passing test rather than test anything new. Three do depend on the boundary. Of those, **peer expiry is proven across clusters** (a peer ages out to `UNREACHABLE` on TTL, retains its last-known detail, and does not change the observer's own verdict). **Revocation and foreign-authority refusal are now proven across it too.** A revoked certificate is refused by the enforcing baseline after only THAT baseline's Secret is rewritten and its broker rolled - nothing in the revoked baseline's cluster is edited - and a well-formed certificate from an untrusted authority is refused outright. Each carries the control case its compose counterpart has, because "the handshake failed" passes just as loudly when the handshake was never attempted.

The local three-baseline stack (`deploy/docker`) therefore remains where the topology is exercised, and it is not a temporary stand-in.

### What a customer deployment will need, when there is one

Recorded now because it is known, and because two pieces of it are already built in a way that assumes otherwise:

1. **The federation link is raw TCP with mutual TLS, not HTTP.** An HTTP ingress cannot carry it. It needs a TCP-level `LoadBalancer` or an equivalent, and nothing in the path may terminate or re-originate TLS - the two brokers authenticate *each other*, and a middlebox that terminates would break the per-baseline identity that is the whole point of locked #50.

2. **Certificates currently vouch for internal names only.** `issue-certs.sh` writes subject alternative names of `DNS:<baseline>`, the compose service name, `artemis.<baseline>.svc.cluster.local`, and `localhost`. Every one is compose- or cluster-internal. A peer in another cluster dialling a routable address would fail host verification. The issuing tool has to learn each baseline's externally reachable name before any cross-cluster mesh can work.

3. **A baseline advertises how peers reach it.** The chart already has `artemis.advertisedHost` for exactly this, defaulting to the in-cluster Service. It becomes a routable address in a real deployment.

---

## Environments: separate clusters, separate authority

Dev and prod are **separate clusters with separate certificate authorities**.

Separate clusters is the ordinary isolation argument. The separate *authority* is the interesting half: `deploy_protocol.md` calls a dev broker federating onto a prod broker "a serious cross-environment leak", and until now the only thing preventing it was configuration - pointing a connector at the wrong host. With per-baseline certificates and one authority per environment, a dev baseline's certificate is signed by an authority prod does not trust, so the connection fails in the TLS handshake.

That converts a leak you have to remember not to cause into one that cannot be caused. It is the same reasoning as gating `/api/v1` in `BaseVerticle` rather than per service: put the guarantee where forgetting is impossible.

---

## The trust root: one authority per customer deployment

**Each customer deployment is its own trust domain, and creates its own authority.** `issue-certs.sh` ships as the tool they run.

The alternative - a Lattice root signing per-customer intermediates - was considered and rejected. It would give us a revocation lever over an entire customer, which sounds useful until it is inverted: it means **we hold key material that is load-bearing for someone else's production mesh**, and an expiry or a compromise on our side becomes their outage. A customer's mesh should not be able to fail because of something in our custody.

Consequences:

- **We never hold a customer's private key.** The authority is created on their side, by them.
- **No customer's baseline can federate into another customer's.** Their brokers trust different authorities, so the mutual-TLS handshake fails in both directions before any federation command is exchanged. This needs no separate mechanism - it is a consequence of the trust topology, the same property that separates dev from prod in #57. In practice network isolation is the first barrier; this is what still holds if it is not. Exercised by the harness's `foreign-authority` scenario, which mints a certificate carrying a legitimate-looking distinguished name from an authority nobody trusts and shows it refused.
- **The `issue-certs.sh` header is now wrong.** It describes a "development" authority for the local stack. Under this decision it is also the tool a customer runs to create a real one, and it needs to say so - along with the external-name gap in point 2 above.
- **Revocation is the customer's**, using the same `revoke` path already built and exercised by the harness.

---

## Decisions settled here

- **Lattice is delivered, not hosted.** A baseline delivery is exported image archives plus the Helm chart; there is no Lattice-hosted registry or cluster. Images are still referenced by an immutable `<version>-<sha>` tag - the provenance rule survives, the registry assumption does not.
- **Hosting is deferred, with the cross-cluster mesh explicitly unproven** and the three requirements above recorded for when it is not.
- **Dev and prod are separate clusters with separate certificate authorities**, so cross-environment federation fails cryptographically rather than being merely discouraged.
- **One certificate authority per customer deployment**; we hold no key material that a customer's mesh depends on.

Promoted to [locked_decisions.md](../../reference/locked_decisions.md) #55-#58.
