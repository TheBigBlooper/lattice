#!/usr/bin/env bash
# Stands up THREE kind clusters, one per baseline, so the mesh can be exercised across real
# cluster boundaries rather than on one flat network.
#
# WHY THIS EXISTS. Three baselines already federate, but delivery_model.md records the gap
# precisely: they sit on one flat Docker network and resolve each other by Docker DNS, so no
# announcement or federation link has ever crossed a routing boundary. The compose stack
# (deploy/docker/mesh-harness.sh) remains where the topology is exercised day to day; this is
# where ADDRESSING is proven.
#
# HOW CLUSTERS REACH EACH OTHER. Every kind node is a Docker container and they all join one
# user-defined bridge network called `kind`, which carries Docker's embedded DNS. So a pod in
# hub-east egresses through its own node and dials hub-central's node container by name, on a
# NodePort:
#
#   broker pod (hub-east) -> node hub-east -> kind bridge -> hub-central-control-plane:<nodePort>
#
# That is a genuine boundary between separate Kubernetes clusters, which is the thing that has
# never been crossed - and it needs no hosting provider, so locked #56 stays deferred rather
# than being forced.
#
# HOST PORTS ARE ALL BELOW 49152, deliberately. Windows auto-reserves blocks from the ephemeral
# range (49152-65535) during uptime, and a reserved block fails the bind with a permissions
# error on the next container recreate - intermittent, and expensive to diagnose because a
# running container keeps working indefinitely after the reservation appears.
#
#   ./deploy/k8s/mesh-clusters.sh up        create all three
#   ./deploy/k8s/mesh-clusters.sh down      delete all three
#   ./deploy/k8s/mesh-clusters.sh status    what exists, and whether the nodes can see each other

set -euo pipefail

BASELINES=(hub-central hub-east hub-west)

# NodePorts are IDENTICAL in every cluster - they are separate clusters, so there is nothing to
# collide. Only the HOST ports differ, because those share one machine.
NODEPORT_CONSOLE=30000
NODEPORT_API=30082
NODEPORT_KEYCLOAK=30083
NODEPORT_MESH=30617

# baseline -> host console, host api, host keycloak
host_ports_for() {
  case "$1" in
    hub-central) echo "3000 8082 8083" ;;
    hub-east)    echo "3010 8092 8093" ;;
    hub-west)    echo "3020 8102 8103" ;;
    *) echo "unknown baseline: $1" >&2; return 1 ;;
  esac
}

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
info() { printf '    %s\n' "$1"; }
fail() { printf '\033[1;31m!!  %s\033[0m\n' "$1" >&2; exit 1; }

require() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required and was not found on PATH."
}

# The mesh acceptor is NOT mapped to a host port. It is reached cluster-to-cluster over the kind
# bridge by node name, and publishing a broker's federation acceptor to the host would expose it
# beyond the machine for no benefit - the same reasoning the chart applies with ClusterIP.
cluster_config_for() {
  local baseline="$1" ports console api keycloak
  ports="$(host_ports_for "$baseline")"
  console="$(echo "$ports" | cut -d' ' -f1)"
  api="$(echo "$ports" | cut -d' ' -f2)"
  keycloak="$(echo "$ports" | cut -d' ' -f3)"
  cat <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: $baseline
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: $NODEPORT_CONSOLE
        hostPort: $console
        protocol: TCP
      - containerPort: $NODEPORT_API
        hostPort: $api
        protocol: TCP
      - containerPort: $NODEPORT_KEYCLOAK
        hostPort: $keycloak
        protocol: TCP
EOF
}

cmd_up() {
  require kind
  require kubectl
  local existing
  existing="$(kind get clusters 2>/dev/null || true)"

  for baseline in "${BASELINES[@]}"; do
    if printf '%s\n' "$existing" | grep -qx "$baseline"; then
      info "$baseline already exists - leaving it alone"
      continue
    fi
    step "Creating cluster $baseline"
    cluster_config_for "$baseline" | kind create cluster --config -
  done

  step "Cluster contexts"
  for baseline in "${BASELINES[@]}"; do
    info "kind-$baseline  ->  $(host_ports_for "$baseline" | awk '{print "console localhost:"$1", api localhost:"$2", keycloak localhost:"$3}')"
  done
}

cmd_down() {
  require kind
  for baseline in "${BASELINES[@]}"; do
    if kind get clusters 2>/dev/null | grep -qx "$baseline"; then
      step "Deleting cluster $baseline"
      kind delete cluster --name "$baseline"
    fi
  done
}

# Proves the property the whole design rests on: each node can reach the others by NAME over the
# shared bridge. If this fails, nothing downstream can federate and the cause is the network, not
# the brokers - which is worth separating before spending an afternoon on Artemis configuration.
cmd_status() {
  require kind
  step "Clusters"
  kind get clusters 2>/dev/null || info "none"

  step "Node addresses on the shared bridge"
  for baseline in "${BASELINES[@]}"; do
    local node="$baseline-control-plane" ip
    ip="$(docker inspect "$node" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' 2>/dev/null || true)"
    if [ -n "$ip" ]; then
      info "$node  $ip"
    else
      info "$node  (not running)"
    fi
  done

  step "Can each node resolve and reach its peers?"
  for from in "${BASELINES[@]}"; do
    for to in "${BASELINES[@]}"; do
      [ "$from" = "$to" ] && continue
      if docker exec "$from-control-plane" getent hosts "$to-control-plane" >/dev/null 2>&1; then
        info "$from -> $to  resolves"
      else
        info "$from -> $to  NO RESOLUTION"
      fi
    done
  done
}

TLS_DIR="$(cd "$(dirname "$0")/../docker/artemis/tls" && pwd)"

# Everything a baseline needs before Helm runs. The chart NAMES these and never carries them: two
# hold private key material and one holds a database password.
create_secrets() {
  local baseline="$1" ctx="kind-$baseline"
  kubectl --context "$ctx" create namespace lattice >/dev/null 2>&1 || true

  # ONE shared credential across every baseline, deliberately - locked #45. A downstream federation
  # command authenticates against the PEER's broker, so a per-baseline password fails on arrival
  # and the mesh silently never forms. Per-baseline identity is the CERTIFICATE (locked #50);
  # authorization stays a single generic role, because per-peer authorization would mean naming
  # each peer in every broker, which is edit-on-join by another route (locked #44).
  kubectl --context "$ctx" -n lattice create secret generic artemis-credentials \
    --from-literal=username="${ARTEMIS_USER:-artemis}" \
    --from-literal=password="${ARTEMIS_PASSWORD:-artemis}" \
    --dry-run=client -o yaml | kubectl --context "$ctx" apply -f - >/dev/null

  # This baseline's OWN keystore, the SHARED truststore, and the revocation list. The truststore is
  # what makes no-edit-on-join work: it holds the authority, not any peer, so a joiner's
  # certificate is accepted with no edit anywhere (locked #50).
  kubectl --context "$ctx" -n lattice create secret generic artemis-tls \
    --from-file=keystore.p12="$TLS_DIR/$baseline/keystore.p12" \
    --from-file=truststore.p12="$TLS_DIR/truststore.p12" \
    --from-file=crl.pem="$TLS_DIR/ca/crl.pem" \
    --from-literal=password="${LATTICE_TLS_PASSWORD:-lattice}" \
    --dry-run=client -o yaml | kubectl --context "$ctx" apply -f - >/dev/null

  kubectl --context "$ctx" -n lattice create secret generic keycloak-db-credentials \
    --from-literal=username=keycloak --from-literal=password="kc-$baseline" \
    --from-literal=rootPassword="root-$baseline" \
    --dry-run=client -o yaml | kubectl --context "$ctx" apply -f - >/dev/null
}

# The two peers of a baseline, as chart values. Every baseline names both others: they come up
# together here, so there is no joiner to be the only one configured.
peer_values_for() {
  local baseline="$1" i=0 out=""
  for peer in "${BASELINES[@]}"; do
    [ "$peer" = "$baseline" ] && continue
    out="$out --set artemis.peers[$i].name=$peer"
    out="$out --set artemis.peers[$i].host=$peer-control-plane"
    out="$out --set artemis.peers[$i].port=$NODEPORT_MESH"
    i=$((i + 1))
  done
  printf '%s' "$out"
}

cmd_deploy() {
  require kubectl
  require helm
  [ -f "$TLS_DIR/truststore.p12" ] || fail "no broker certificates - run deploy/docker/artemis/tls/issue-certs.sh first."

  local chart; chart="$(cd "$(dirname "$0")/chart" && pwd)"

  for baseline in "${BASELINES[@]}"; do
    local ports console api keycloak
    ports="$(host_ports_for "$baseline")"
    console="$(echo "$ports" | cut -d' ' -f1)"
    api="$(echo "$ports" | cut -d' ' -f2)"
    keycloak="$(echo "$ports" | cut -d' ' -f3)"

    step "Deploying $baseline"
    create_secrets "$baseline"

    # advertisedHost is the kind node's container name, which is exactly the name added to this
    # baseline's certificate SANs. If the two ever drift, the peer's host verification refuses the
    # connection before federation begins - so they are derived from the same convention here.
    # shellcheck disable=SC2046
    helm --kube-context "kind-$baseline" upgrade --install "$baseline" "$chart" \
      --namespace lattice --create-namespace \
      --set baseline.clusterId="$baseline" \
      --set baseline.consoleUrl="http://localhost:$console" \
      --set baseline.apiBaseUrl="http://localhost:$api/api/v1" \
      --set keycloak.hostname="http://localhost:$keycloak" \
      --set keycloak.devMode=false \
      --set image.pullPolicy=Never \
      --set artemis.meshServiceType=NodePort \
      --set artemis.meshNodePort="$NODEPORT_MESH" \
      --set artemis.advertisedHost="$baseline-control-plane" \
      --set artemis.advertisedPort="$NODEPORT_MESH" \
      --set statusConsole.serviceType=NodePort \
      --set statusConsole.nodePort="$NODEPORT_CONSOLE" \
      --set keycloak.serviceType=NodePort \
      --set keycloak.nodePort="$NODEPORT_KEYCLOAK" \
      --set apiNodePort="$NODEPORT_API" \
      $(peer_values_for "$baseline") >/dev/null
    info "$baseline installed"
  done
}

# Docker Desktop cannot answer "what is running in each cluster": it lists the three NODE
# containers and nothing else, because the pods run under containerd INSIDE those nodes and the
# Docker daemon does not own them. This is the equivalent view.
cmd_pods() {
  require kubectl
  for baseline in "${BASELINES[@]}"; do
    step "$baseline"
    kubectl --context "kind-$baseline" get pods -A \
      --field-selector metadata.namespace!=kube-system \
      --no-headers 2>/dev/null \
      | awk '{printf "    %-14s %-46s %-8s %s\n", $1, $2, $3, $4}' \
      || info "(cluster not reachable)"
  done
}

case "${1:-}" in
  up)     cmd_up ;;
  down)   cmd_down ;;
  status) cmd_status ;;
  pods)   cmd_pods ;;
  deploy) cmd_deploy ;;
  *)
    printf 'usage: %s {up|down|deploy|status|pods}\n' "$0" >&2
    exit 2
    ;;
esac
