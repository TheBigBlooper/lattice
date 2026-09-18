# Lattice - Failure Scenario Specification

Part of the [replication pack](replication_prompt.md). The build's final acceptance gate ([System Requirements Document](system_requirements.md) DEP-010 and VER-003) is that the running three-baseline stack passes a scripted set of failure scenarios; this document specifies each one precisely enough to implement without guessing: the precondition control, the exact fault, the exact expected observations, and the restore.

**This document is deliberately self-contained.** Inside the repository the canonical sources remain the [demo runbook](../tour/demo_runbook.md) (the narrated live order with measured timings) and `deploy/k8s/mesh-clusters.sh` (the implementation); if this document disagrees with them, they win.

## Cast

Three baselines on three separate local Kubernetes clusters, named here by role; the original names them `hub-central`, `hub-east`, `hub-west`.

- **A - the observer.** Where the console is watched and most assertions are read.
- **B - the subject.** The baseline that gets broken.
- **C - the third.** Needed only where two baselines cannot exercise the property (loop prevention; "every peer at once").

The three timing constants everything depends on: announce heartbeat **10 s**, peer time-to-live **30 s**, console poll **10 s**. A peer therefore takes up to about 40 s to flip after going quiet.

## Harness rules (apply to every scenario)

1. **A control comes first.** Each scenario asserts the healthy pre-state before breaking anything, and returns early ("control failed - nothing to test") if it does not hold. Without one, the scenario passes just as loudly when nothing was stopped or when it was already broken.
2. **A failed assertion does not stop the run.** It is recorded, the scenario continues into its restore, and the failure count becomes the process exit status - so the suite is a verdict a script can read.
3. **Every scenario restores what it broke and asserts the recovery.** An interrupted run is the only way to be left dirty.
4. **Faults are induced by scaling a component's controller to zero**, never by deleting a pod (the orchestrator recreates a deleted pod in seconds, which tests recovery while claiming an outage).
5. **Assert settled states, not transitions.** A recovery that may complete before the check runs is asserted at its settled state; pinning an intermediate state that may already be over is flaky by construction.
6. **Recovery waits are budgeted generously** (minutes) even where measured recovery is seconds: a budget tight enough to be an estimate fails on a slow machine and reports a healthy mesh as broken.
7. **Every API read the harness makes fetches its own fresh token**, because some waits outlast an access token's life.
8. **Scenarios that rewrite TLS material run last**, and their restore is armed as a trap before the fault is induced, so it runs exactly once whether the scenario completes, fails, or is killed.

## The seven scenarios

### 1. `peer-lost` - a peer goes quiet

The point: **gone** means aged out, marked, and still listed.

| Phase    | Specification                                                                                                                                                              |
|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Control  | A's view of B is `REACHABLE`.                                                                                                                                              |
| Fault    | Scale B's mesh-gateway to zero. It is the sole mesh participant, so B stops announcing while the rest of B keeps running.                                                  |
| Assert   | A's view of B flips to `UNREACHABLE` within a time-to-live budget; the row for B **still exists** (retained snapshot); A's own verdict is unchanged (`ready`).             |
| Display  | The retained row still shows B's last-known region, version, and health.                                                                                                   |
| Restore  | Scale the gateway back to one; assert A's view returns to `REACHABLE`. Fast, because a starting gateway announces immediately rather than waiting for its first heartbeat. |

### 2. `degraded` - one service in a baseline is down

The point: partial - still serving, not whole - **and the verdict crosses the mesh**.

| Phase    | Specification                                                                                                                                            |
|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Control  | B's own verdict is `ready`, and A's view of B is `ready`.                                                                                                |
| Fault    | Scale one domain service in B's rollup list to zero. Not the gateway, so B keeps announcing throughout.                                                  |
| Assert   | B's own `getBaseline` reads `degraded` (its readiness poll noticing, about one heartbeat); A's view of B also reads `degraded` - a second fact, proving the rollup crossed. |
| Display  | B's own breakdown names which service is missing; A never learns which, because the breakdown deliberately does not ride the announcement.               |
| Restore  | Scale the service back; assert `ready` on both sides.                                                                                                    |

The crossing is effectively free: a baseline announces immediately on a health change, so the whole delay is B noticing, none of it the mesh hop.

### 3. `baseline-down` - every service in a baseline is down

The point: **down is not gone** - it cannot serve and is still being heard.

| Phase    | Specification                                                                                                       |
|----------|------------------------------------------------------------------------------------------------------------------------|
| Control  | B's verdict `ready`; A's view of B `ready`.                                                                          |
| Fault    | Scale **every** service in B's rollup list to zero. The gateway stays up.                                            |
| Assert   | B's verdict goes to `down`; A's view of B shows health `down` while reachability **stays `REACHABLE`**.              |
| Display  | B's infrastructure card stays green throughout (the datastore is fine; the services are not).                        |
| Restore  | Scale everything back; assert `ready` on both sides.                                                                 |

Run after `peer-lost` deliberately: `REACHABLE`+`down` versus `UNREACHABLE` is the discrimination the whole design argues for.

### 4. `mesh-cut` - a baseline loses its own broker

The point: **it is us, not them** - every peer ages out at once and we keep serving.

| Phase    | Specification                                                                                                                                                                                          |
|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Control  | A's mesh link is `up` and both peers `REACHABLE`.                                                                                                                                                      |
| Fault    | Scale **A's own broker** to zero (the observer's - the fault is at home).                                                                                                                               |
| Assert   | A's own verdict stays `ready`; A's `/readiness` still returns 200 (deliberately - the gateway must not fail with the broker); **both** peers age to `UNREACHABLE`; after restore, the gateway rejoins **with no restart** and both peers return to `REACHABLE`. |
| Display  | The mesh-link banner ("broker link down", with the pinned snapshot time) - the single most important thing on the screen, and deliberately display-only.                                               |
| Restore  | Scale the broker back to one; wait out reconnect plus a time-to-live.                                                                                                                                  |

Recovery here is structurally slower than scenario 1's: the returning thing is the broker underneath a gateway that never went anywhere, so the gateway must notice, reconnect, re-federate, and then wait to be told about peers it already aged out - nothing announces on your behalf when you were the one who went deaf.

### 5. `loop-check` - loop prevention, measured

The point: with three federated brokers, a message could arrive both directly and by way of the third; `max-hops="1"` means it must not.

| Phase    | Specification                                                                                                                                                          |
|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Control  | All three baselines announcing (three brokers federated).                                                                                                              |
| Measure  | Count announcements **delivered at A's broker** over a fixed window (90 s) with all three announcing; silence C's gateway; wait out its expiry plus a settle; count again over an equal window. The difference is C's contribution. |
| Assert   | At a 10-second cadence over 90 seconds, C's contribution is one copy's worth: **between 6 and 12** messages. Above the band means duplicate delivery (a loop); the scenario fails loudly. |
| Restore  | Scale C's gateway back.                                                                                                                                                |

Two properties are load-bearing: it needs **three** baselines, because two brokers cannot form a loop; and it measures **at the broker**, not at the peer registry, because the registry deduplicates by cluster id and would hide a duplicate completely. This scenario has no console surface at all.

### 6. `revoked-<subject>` - a revoked certificate is refused, with no edit to the revoked baseline

The point: trusting the authority buys per-baseline revocation with nobody else edited - and the refused baseline can see the problem on its own console.

| Phase    | Specification                                                                                                                                                                                                                    |
|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Control  | B's broker completes a mutual-TLS handshake with A's federation acceptor across the cluster boundary, successfully. This control is essential: "the handshake was refused" is also what a wrong address or a restarting pod reports. |
| Fault    | Revoke B's certificate at the authority; refresh the revocation list; update **only A's** TLS secret; roll **only A's** broker. No command touches B.                                                                             |
| Assert   | The same handshake is now refused; B's own `getPeers` shows federation `down` toward A while B's own broker link stays `up`; after restore the mesh has re-formed (settled state - see harness rule 5).                            |
| Display  | The Federation column reading `Down` on B's console. `Down` rather than `Refused` is the design: the certificate is revoked, not expired, and revocation lives in the authority's list, which a baseline does not hold - only expiry can be blamed locally. |
| Restore  | Armed as a trap before the revocation: re-issue B's certificate, update **both** A's and B's secrets (A holds the revocation list), roll both brokers, wait for `REACHABLE`. Brokers read their truststore and revocation list at start, so the roll is not optional. |

The re-issued certificate carries a new serial, so the revocation list still naming the old one is correct: revocation is per certificate, not per baseline.

### 7. `foreign-authority` - a certificate from an authority nobody trusts

The point: the truststore is the gate, not the certificate's contents - and this is the shape a second customer's mesh would present, so two customers' meshes cannot federate by accident.

| Phase    | Specification                                                                                                                                       |
|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| Control  | The genuine handshake succeeds.                                                                                                                     |
| Fault    | Mint a well-formed certificate with a legitimate-looking name from a **different** authority; stage it inside B's broker pod; attempt the handshake with it. |
| Assert   | The handshake is refused despite the certificate being well-formed and correctly named.                                                             |
| Restore  | Remove the staged material from the pod; assert the genuine certificate still works.                                                                |

This isolates the trust anchor itself, which the other two certificate cases do not: a missing certificate and a revoked one could each fail for other reasons.

## Implementation skeleton

The shape of a scenario as the original implements it (shell, against the local stack):

```bash
FAILURES=0
record_fail() { printf '[FAIL] %s\n' "$1" >&2; FAILURES=$((FAILURES + 1)); }

scenario_peer_lost() {
  step "Scenario: a peer baseline goes quiet"

  # control - or there is nothing to test
  wait_until "A's view of B" REACHABLE 60 peer_view A B reachability \
    || { info "control failed: B was not REACHABLE to begin with"; return 1; }

  scale_component B mesh-gateway 0                      # the fault

  wait_until "A's view of B" UNREACHABLE "$TTL_WAIT" \
    peer_view A B reachability || true                  # assert the flip

  [ "$(health_of A)" = "ready" ] \
    || record_fail "A's own health changed because a peer went away"

  scale_component B mesh-gateway 1                      # restore
  wait_until "A's view of B" REACHABLE 180 peer_view A B reachability || true
}

# ... one function per scenario; a dispatcher maps names to functions; and
# the run ends by turning the count into the verdict:
exit_with_verdict() { [ "$FAILURES" -gt 0 ] && exit 1 || exit 0; }
```

Helpers a replica needs: `scale_component <baseline> <component> <replicas>`, `health_of <baseline>` (reads `getBaseline`), `peer_view <observer> <peer> <field>` (reads `getPeers`), `wait_until <label> <expected> <budget-seconds> <command...>` (polls, prints `(after Ns)` when it settles), and a per-read token fetch.

## Acceptance

The suite passes when all seven scenarios run green in one invocation (`scenario all` in the original), certificate scenarios last, exit status zero, and the stack is left healthy: every verdict `ready`, mesh link `up`, every peer `REACHABLE`, with no manual repair.
