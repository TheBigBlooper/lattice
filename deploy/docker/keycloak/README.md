# Keycloak - this baseline's identity

One Keycloak per baseline, including locally. A shared local Keycloak would repeat the shared-broker
mistake the per-baseline broker work removed: it makes one baseline privileged, and it stops the local
stack modelling the topology it is supposed to prove.

## The realm import

The realm is the whole thing: the `viewer` and `operator` roles, the `viewers` and `operators`
groups, the console's public client, and two local demo users. It is committed, so it is reviewable
in a diff and identical on every run - unlike clicking through the admin console, which is the
reliable way for two baselines to end up subtly different.

**It lives at [`deploy/k8s/chart/files/lattice-realm.json`](../../k8s/chart/files/lattice-realm.json), not here**, and compose mounts it from
there. That direction is deliberate (locked #54): the cluster's realm ConfigMap is generated from
this same file, and Helm can only read files inside the chart - so a realm kept in this directory
would make the chart unpackageable, and `helm package` would ship a tarball that installs an empty
realm. One file, two consumers, no second copy to drift.

**Keycloak runs in dev mode with no persistence here, and that is deliberate.** The import runs only
when the realm does not already exist. A persisted database would silently ignore every later edit to
this file - exactly the trap the Artemis `etc-override` config hit, where a persisted broker instance
kept ignoring config changes until someone thought to run `down -v`.

## Local demo users

| Username   | Password   | Group       | Can |
|------------|------------|-------------|-----|
| `operator` | `operator` | `operators` | Read and write every `/api/v1` operation |
| `viewer`   | `viewer`   | `viewers`   | Read (`GET`) only; a write returns 403 FORBIDDEN |

Local development only. A deployed environment manages its own users, and its Keycloak needs a real
database (production persistence and high availability are a deploy concern, not settled here).

## Getting a token by hand

The console uses authorization code with PKCE. For a QA pass from a shell, the direct grant is enabled
on the console client so a token can be fetched in one call:

```bash
curl -s -d 'client_id=lattice-console' -d 'username=operator' -d 'password=operator' -d 'grant_type=password' http://localhost:8083/realms/lattice/protocol/openid-connect/token
```

Take `access_token` out of the response and send it as `Authorization: Bearer <token>`. Swap the
username and password for `viewer`/`viewer` to see a write rejected with 403.

For the peer baseline, the same call against port `8084` - its realm is separate, so a token from one
is not accepted by the other. That is the point: identity belongs to the baseline that owns the data.
