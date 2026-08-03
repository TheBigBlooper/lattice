#!/usr/bin/env bash
# Three kind clusters, one per baseline: the only local stack, and where addressing across a real
# cluster boundary is proven (locked #75). Every kind node is a container on one bridge with Docker's
# embedded DNS, so a pod egresses through its own node and dials a peer's node by name on a NodePort.
# Host ports all sit below 49152, because Windows reserves blocks from the ephemeral range while it
# is up and a reserved block fails the bind on the next container recreate.
#
#   ./deploy/k8s/mesh-clusters.sh up        create all three
#   ./deploy/k8s/mesh-clusters.sh down      delete all three
#   ./deploy/k8s/mesh-clusters.sh status    what exists, and whether the nodes can see each other

set -euo pipefail

BASELINES=(hub-central hub-east hub-west)

# NodePorts are IDENTICAL in every cluster - they are separate clusters, so there is nothing to
# collide. Only the HOST ports differ, because those share one machine.
NODEPORT_CONSOLE=30000
NODEPORT_ORDERS=30080
NODEPORT_INVENTORY=30081
NODEPORT_API=30082
NODEPORT_KEYCLOAK=30083
NODEPORT_MESH=30617

# baseline -> host ports: console orders inventory api keycloak. The console calls orders,
# inventory and the gateway DIRECTLY from the browser, so each needs its own host address.
host_ports_for() {
  case "$1" in
    # Not free choices: the committed realm already permits these exact ports, and a console served
    # anywhere else is refused with "Invalid parameter: redirect_uri". Aligning to the realm beats
    # widening it, since one realm definition (locked #48) is imported by every deployment.
    hub-central) echo "3000 8080 8081 8082 8083" ;;
    hub-east)    echo "3001 8090 8091 8092 8093" ;;
    hub-west)    echo "3002 8100 8101 8102 8103" ;;
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
  local baseline="$1"
  read -r console orders inventory api keycloak <<<"$(host_ports_for "$baseline")"
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
      - containerPort: $NODEPORT_ORDERS
        hostPort: $orders
        protocol: TCP
      - containerPort: $NODEPORT_INVENTORY
        hostPort: $inventory
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
    info "kind-$baseline  ->  $(host_ports_for "$baseline" | awk '{print "console :"$1"  orders :"$2"  inventory :"$3"  api :"$4"  keycloak :"$5}')"
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

TLS_DIR="$(cd "$(dirname "$0")/../certs" && pwd)"

# Everything a baseline needs before Helm runs. The chart NAMES these and never carries them: two
# hold private key material and one holds a database password.
create_secrets() {
  # Two statements, not one: bash expands every word of a `local` before binding any of them, so
  # `local a="$1" b="$a"` leaves b unbound under `set -u`. It only ever worked here because a
  # caller happened to have a global loop variable of the same name.
  local baseline="$1"
  local ctx="kind-$baseline"
  kubectl --context "$ctx" create namespace lattice >/dev/null 2>&1 || true

  # One shared credential across every baseline (locked #45): a downstream federation command
  # authenticates against the peer's broker, so a per-baseline password fails on arrival and the
  # mesh silently never forms. Per-baseline identity is the certificate instead (locked #50).
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

# A baseline's peers, as chart values: only the ones earlier in the list, which models the join.
#
# Defect note. Symptom: every peer announcement is delivered twice - measured at hub-central over
# 60 seconds, its own announcements arriving 6 times against each peer's 12 to 14.
#
# Naming a peer builds both an upstream and a downstream link, so both sides naming each other makes
# two links per pair carrying the same address. The registry dedupes by cluster id, so only the
# broker can show it, which is why loop-check reads the broker. Declaring each pair from one side
# only is also how a real join works: the joiner declares both directions (locked #44).
peer_values_for() {
  local baseline="$1" i=0 out=""
  for peer in "${BASELINES[@]}"; do
    [ "$peer" = "$baseline" ] && break
    out="$out --set artemis.peers[$i].name=$peer"
    out="$out --set artemis.peers[$i].host=$peer-control-plane"
    out="$out --set artemis.peers[$i].port=$NODEPORT_MESH"
    i=$((i + 1))
  done
  printf '%s' "$out"
}

# Per-baseline console images. The console is a static bundle with its API addresses inlined at
# build time, so one shared image points every baseline at whichever addresses it was built with -
# which once had two peer consoles reading hub-central's data. Three baselines, three images.
SERVICES=(orders inventory mesh-gateway)
IMAGE_TAG=0.1.0-SNAPSHOT

repo_root() { cd "$(dirname "$0")/../.." && pwd; }

# Compiles the fat jars the service images COPY.
#
# DEFECT NOTE - "running the new build" while serving old code. The image build is a plain
# `docker build`, and the Dockerfile only COPYs target/<name>-fat.jar. So it ships whatever Maven
# last happened to leave there: an edit compiled by nothing more than `mvnw test` is absent from the
# jar, the pod really is new, the image really was rebuilt, and the script reports success. This is
# the stale-image trap one level down - the usual warning is about a pod not being restarted, and a
# restart does not help here. Compiling here is what makes the report true.
build_service_jars() {
  (cd "$(repo_root)" && ./mvnw -q -pl "$1" -am package -DskipTests -DskipITs) \
    || fail "maven package failed for $1 - the image would have shipped a stale jar"
}

build_service_image() {
  docker build -q -t "lattice/$1:$IMAGE_TAG" "$(repo_root)/services/$1" >/dev/null
  info "built lattice/$1"
}

# The console's API addresses are inlined at BUILD time, so the baseline is an input to the build
# rather than a runtime setting - which is why there is an image per baseline and why this takes one.
build_console_image() {
  local baseline="$1" console orders inventory api keycloak
  read -r console orders inventory api keycloak <<<"$(host_ports_for "$baseline")"
  docker build -q -t "lattice/status-console:$baseline" "$(repo_root)/ui/status-console" \
    --build-arg VITE_API_BASE_URL="http://localhost:$api/api/v1" \
    --build-arg VITE_ORDERS_BASE_URL="http://localhost:$orders/api/v1" \
    --build-arg VITE_INVENTORY_BASE_URL="http://localhost:$inventory/api/v1" \
    --build-arg VITE_KEYCLOAK_URL="http://localhost:$keycloak" \
    --build-arg VITE_KEYCLOAK_REALM=lattice \
    --build-arg VITE_KEYCLOAK_CLIENT_ID=lattice-console \
    --build-arg VITE_CLUSTER_ID="$baseline" \
    --build-arg VITE_REGION=local \
    --build-arg VITE_BASELINE_VERSION="$IMAGE_TAG" >/dev/null
  info "built lattice/status-console:$baseline"
}

cmd_images() {
  require kind

  step "Compiling service jars"
  build_service_jars "$(printf 'services/%s,' "${SERVICES[@]}" | sed 's/,$//')"
  info "jars are current"

  step "Building service images"
  for s in "${SERVICES[@]}"; do build_service_image "$s"; done

  for baseline in "${BASELINES[@]}"; do
    step "Building the console for $baseline"
    build_console_image "$baseline"
  done

  step "Loading images into each cluster"
  for baseline in "${BASELINES[@]}"; do
    for s in "${SERVICES[@]}"; do
      kind load docker-image "lattice/$s:$IMAGE_TAG" --name "$baseline" >/dev/null 2>&1
    done
    kind load docker-image "lattice/status-console:$baseline" --name "$baseline" >/dev/null 2>&1
    info "$baseline loaded"
  done
}

# Rebuild one component and roll it, in one baseline; the redeploy cost table is in core_protocol.md.
# The rollout restart is load-bearing: images are side-loaded with imagePullPolicy: Never and the tag
# does not change, so without it everything reports healthy while serving the old build.
cmd_redeploy() {
  require kind
  require kubectl
  [ $# -eq 2 ] || fail "usage: $0 redeploy <baseline> <service>   (services: ${SERVICES[*]} status-console)"
  local baseline="$1" component="$2"
  host_ports_for "$baseline" >/dev/null || fail "unknown baseline: $baseline (expected one of: ${BASELINES[*]})"

  local image
  case "$component" in
    status-console)
      step "Rebuilding the console for $baseline"
      build_console_image "$baseline"
      image="lattice/status-console:$baseline"
      ;;
    orders|inventory|mesh-gateway)
      step "Rebuilding $component"
      build_service_jars "services/$component"
      build_service_image "$component"
      image="lattice/$component:$IMAGE_TAG"
      ;;
    *)
      fail "unknown service: $component (expected one of: ${SERVICES[*]} status-console)"
      ;;
  esac

  step "Loading into $baseline and rolling it"
  kind load docker-image "$image" --name "$baseline" >/dev/null 2>&1
  kubectl --context "kind-$baseline" -n lattice rollout restart "deploy/$baseline-lattice-$component" >/dev/null
  kubectl --context "kind-$baseline" -n lattice rollout status "deploy/$baseline-lattice-$component" --timeout=180s >/dev/null \
    || fail "$component did not become ready on $baseline - check its logs."
  info "$baseline/$component is running the new build"
}

chart_dir() { cd "$(dirname "$0")/chart" && pwd; }

# Every value that makes a baseline this baseline, in one place - `render` and `check` need the same
# values `deploy` uses, and a check run against a different set can pass while the install fails.
# advertisedHost follows the certificate's subjectAltName convention, or host verification refuses.
helm_values_for() {
  local baseline="$1"
  # Positional, and destructured the same way everywhere that reads them. Extracting by index
  # separately is how this once handed Keycloak the inventory port: the list grew from three entries
  # to five and only two of the three readers were updated.
  local console orders inventory api keycloak
  read -r console orders inventory api keycloak <<<"$(host_ports_for "$baseline")"

  # BASELINE-WIDE values go under `global`, component values under that component's subchart name.
  # That split is the umbrella's contract, not a style choice: a subchart only ever sees its own
  # section plus `global`, so a baseline fact set anywhere else silently renders empty.
  printf '%s' "\
 --set global.baseline.clusterId=$baseline\
 --set global.baseline.consoleUrl=http://localhost:$console\
 --set global.baseline.apiBaseUrl=http://localhost:$api/api/v1\
 --set global.keycloak.hostname=http://localhost:$keycloak\
 --set global.keycloak.devMode=false\
 --set global.image.pullPolicy=Never\
 --set global.serviceNodePorts.orders=$NODEPORT_ORDERS\
 --set global.serviceNodePorts.inventory=$NODEPORT_INVENTORY\
 --set global.serviceNodePorts.mesh-gateway=$NODEPORT_API\
 --set global.baseline.serviceUrls.orders=http://localhost:$orders\
 --set global.baseline.serviceUrls.inventory=http://localhost:$inventory\
 --set global.baseline.serviceUrls.mesh-gateway=http://localhost:$api\
 --set artemis.meshServiceType=NodePort\
 --set artemis.meshNodePort=$NODEPORT_MESH\
 --set artemis.advertisedHost=$baseline-control-plane\
 --set artemis.advertisedPort=$NODEPORT_MESH\
 --set status-console.serviceType=NodePort\
 --set status-console.nodePort=$NODEPORT_CONSOLE\
 --set status-console.imageTag=$baseline\
 --set keycloak.serviceType=NodePort\
 --set keycloak.nodePort=$NODEPORT_KEYCLOAK"
  peer_values_for "$baseline"
}

cmd_deploy() {
  require kubectl
  require helm
  [ -f "$TLS_DIR/truststore.p12" ] || fail "no broker certificates - run deploy/certs/issue-certs.sh first."

  local chart; chart="$(chart_dir)"

  for baseline in "${BASELINES[@]}"; do
    step "Deploying $baseline"
    create_secrets "$baseline"

    # shellcheck disable=SC2046
    helm --kube-context "kind-$baseline" upgrade --install "$baseline" "$chart" \
      --namespace lattice --create-namespace $(helm_values_for "$baseline") >/dev/null
    info "$baseline installed"
  done
}

# Renders a baseline exactly as `deploy` would install it, without a cluster. This is what makes the
# chart checkable on a machine holding no kind clusters at all, and what a restructure is diffed
# against - "identical to today's baseline" is a claim you can only make by rendering both.
cmd_render() {
  require helm
  [ $# -eq 1 ] || fail "usage: $0 render <baseline>"
  host_ports_for "$1" >/dev/null || return 1
  # shellcheck disable=SC2046
  helm template "$1" "$(chart_dir)" --namespace lattice $(helm_values_for "$1")
}

# Renders every baseline and asserts no container declares the same environment key twice. Needs no
# cluster. The assertion lives in chart-lint.awk, which states what it does and does not cover.
cmd_check() {
  local lint here failures=0
  here="$(cd "$(dirname "$0")" && pwd)"
  lint="$here/chart-lint.awk"

  # The check checks ITSELF first, against committed fixtures. A lint that silently stopped matching
  # - an awk change, a different awk - would otherwise pass everything forever and read as health,
  # which is the failure mode this whole ticket is about.
  step "Self-test"
  if awk -f "$lint" < "$here/testdata/duplicate-env.yaml" 2>/dev/null; then
    fail "the lint did NOT flag testdata/duplicate-env.yaml - it is broken, and every pass below is meaningless."
  fi
  info "[pass] flags a planted duplicate"
  if awk -f "$lint" < "$here/testdata/legal-env.yaml"; then
    info "[pass] no false positive on the shapes that only look like duplicates"
  else
    fail "the lint flagged testdata/legal-env.yaml, which is legal - it would block a correct chart."
  fi
  if awk -f "$lint" < "$here/testdata/missing-security-context.yaml" 2>/dev/null; then
    fail "the lint did NOT flag a container with no securityContext - that assertion is broken."
  fi
  info "[pass] flags a container declaring no securityContext"
  # Both batch kinds, because the exemption that used to live here was keyed on the kind. A Job is
  # judged like anything else now, and the data jobs are CronJobs whose containers sit two levels
  # deeper - the depth a check written against Deployments would quietly stop reading at.
  if awk -f "$lint" < "$here/testdata/job-missing-security-context.yaml" 2>/dev/null; then
    fail "the lint did NOT flag a Job with no securityContext - the exemption is back, and a Job is the shape a context-less container is most likely to take."
  fi
  info "[pass] flags a Job, which is no longer exempt"
  if awk -f "$lint" < "$here/testdata/cronjob-missing-security-context.yaml" 2>/dev/null; then
    fail "the lint did NOT flag a CronJob container with no securityContext - the data jobs are that shape, so they would be unscanned."
  fi
  info "[pass] flags a CronJob container, two levels deeper than a Deployment's"

  require helm

  # The chart must render on its own defaults. Every other render passes the harness's --set list, so
  # a template that only works because the harness set something stays clean here and breaks a
  # customer's first `helm install` (locked #55). It happened once, to `lattice.image`.
  step "Default values"
  if ! helm template defaults "$(chart_dir)" --namespace lattice >/dev/null 2>&1; then
    helm template defaults "$(chart_dir)" --namespace lattice >/dev/null || true
    fail "the chart does not render on its defaults - that is what a customer installs, with none of the --set list below."
  fi
  # Linted as well as rendered, because the defaults enable a different set of components than the
  # local run does, and a container reachable only that way would otherwise go unread.
  if helm template defaults "$(chart_dir)" --namespace lattice | awk -f "$lint"; then
    info "[pass] renders and lints with no baseline values set"
  else
    fail "the chart renders on its defaults but does not pass the lint."
  fi

  for baseline in "${BASELINES[@]}"; do
    step "Rendering $baseline"
    if cmd_render "$baseline" | awk -f "$lint"; then
      info "[pass] no duplicate environment keys, and every container declares a securityContext"
    else
      failures=$((failures + 1))
    fi
  done

  [ "$failures" -eq 0 ] || fail "$failures baseline(s) render a manifest Helm 4 will reject."
  step "Chart renders clean"
}

# Seeds each baseline's Elasticsearch, without which Orders and Inventory open empty. Arming the
# chart's jobs through values would arm reset too, which would destroy what seed just wrote - so
# this runs the seed job alone, as a one-off pod. The guard still refuses against prod when armed.
#
# Defect note. Symptom: all three baselines seed identical data - 130 orders, 60 items, skus from
# SKU-9100 - when each was supposed to differ.
#
# This pod builds its own environment and so does not inherit the chart's, which is where CLUSTER_ID
# was added. Without it the seed cannot tell which baseline it is against and every one falls through
# to the default profile. It looked like a data bug and was a wiring one: the mapping diverged
# correctly on hub-west, because the SERVICE reads the chart's environment and only this pod does not.
cmd_seed() {
  require kubectl
  local unseeded=0
  for baseline in "${BASELINES[@]}"; do
    local ctx="kind-$baseline" pod="data-seed-$$"
    step "Seeding $baseline"

    # Waits for the datastore AND the services that own the indices. `deploy` returns as soon as Helm
    # has applied, and two cold-start races follow: Elasticsearch not yet serving ("Connection
    # refused"), then serving but empty ("no such index"), because each service creates its own.
    local ready=1
    if ! kubectl --context "$ctx" -n lattice rollout status \
        "statefulset/$baseline-lattice-elasticsearch" --timeout=300s >/dev/null 2>&1; then
      info "[FAIL] Elasticsearch never became ready on $baseline"
      ready=0
    fi
    for owner in orders inventory; do
      if ! kubectl --context "$ctx" -n lattice rollout status \
          "deploy/$baseline-lattice-$owner" --timeout=300s >/dev/null 2>&1; then
        info "[FAIL] $owner never became ready on $baseline, so its indices do not exist"
        ready=0
      fi
    done
    if [ "$ready" -eq 0 ]; then
      info "not seeding $baseline"
      unseeded=$((unseeded + 1))
      continue
    fi

    kubectl --context "$ctx" -n lattice delete pod "$pod" --ignore-not-found >/dev/null 2>&1

    kubectl --context "$ctx" -n lattice run "$pod" \
      --image="lattice/orders:0.1.0-SNAPSHOT" \
      --image-pull-policy=Never \
      --restart=Never \
      --env="ELASTICSEARCH_URL=http://$baseline-lattice-elasticsearch:9200" \
      --env="LATTICE_ENV=local" \
      --env="LATTICE_ALLOW_DATA_JOBS=true" \
      --env="CLUSTER_ID=$baseline" \
      --command -- java -cp app.jar io.lattice.common.data.DataJobRunner seed >/dev/null

    if kubectl --context "$ctx" -n lattice wait --for=condition=Ready=false \
        --for=jsonpath='{.status.phase}'=Succeeded pod/"$pod" --timeout=180s >/dev/null 2>&1; then
      info "seeded"
    else
      info "[FAIL] seed did not succeed on $baseline:"
      info "$(kubectl --context "$ctx" -n lattice logs "$pod" 2>&1 | tail -3)"
      unseeded=$((unseeded + 1))
    fi
    kubectl --context "$ctx" -n lattice delete pod "$pod" --ignore-not-found >/dev/null 2>&1
  done

  # A FAILED SEED HAS TO FAIL THE COMMAND. It used to print the job's log and return 0, so a cold
  # bring-up reported success with a baseline holding no data - and the symptom surfaces much later,
  # as operational views that open empty, which reads as a console fault rather than a missing seed.
  [ "$unseeded" -eq 0 ] || fail "$unseeded baseline(s) were not seeded - see the errors above."
}

# --- Stopping and starting one component ---------------------------------------------------------
# `kubectl scale` does this already; what it does not do is stop you scaling the wrong cluster, since
# every baseline shares a namespace and near-identical names. Deleting a pod is not the same thing:
# the controller recreates it in seconds, so that tests recovery rather than an outage.

# Which workload kind owns a component. The three with state are StatefulSets, because their volumes
# are not interchangeable between pods; the rest are Deployments.
workload_for() {
  case "$1" in
    artemis|elasticsearch|keycloak-db) echo "statefulset" ;;
    orders|inventory|mesh-gateway|keycloak|status-console) echo "deploy" ;;
    *) return 1 ;;
  esac
}

scale_component() {
  local baseline="$1" component="$2" replicas="$3" kind
  host_ports_for "$baseline" >/dev/null || fail "unknown baseline: $baseline (expected one of: ${BASELINES[*]})"
  kind="$(workload_for "$component")" \
    || fail "unknown component: $component (expected one of: orders inventory mesh-gateway keycloak status-console artemis elasticsearch keycloak-db)"

  kubectl --context "kind-$baseline" -n lattice scale \
    "$kind/$baseline-lattice-$component" --replicas="$replicas" >/dev/null
  info "$baseline/$component -> replicas=$replicas"
}

cmd_stop() {
  require kubectl
  [ $# -eq 2 ] || fail "usage: $0 stop <baseline> <component>"
  step "Stopping $2 on $1"
  scale_component "$1" "$2" 0
  case "$2" in
    mesh-gateway) info "peers should age this baseline to UNREACHABLE in ~25-30s (PEER_TTL)" ;;
    keycloak-db)  info "Keycloak readiness should fail in ~15s, naming its database check" ;;
    artemis)      info "this baseline's mesh-link state goes down; the gateway keeps serving and rejoins on its own" ;;
  esac
}

cmd_start() {
  require kubectl
  [ $# -eq 2 ] || fail "usage: $0 start <baseline> <component>"
  step "Starting $2 on $1"
  scale_component "$1" "$2" 1
  [ "$2" = "keycloak" ] && info "allow ~2 minutes: production mode rebuilds and re-checks its schema on start"
  return 0
}

# --- Scenarios ---------------------------------------------------------------------------------
# Everything the mesh claims is proven here, because there is nowhere else left. Every scenario
# opens with a control asserting the healthy pre-state: without one, "the baseline reported
# degraded" passes just as loudly when nothing was stopped, or when it already was.

TTL_WAIT=75

# Scenario failures are counted, and the count becomes the exit status. One that prints [FAIL] and
# exits 0 reports success to anything reading the status - a person catches that, a script never
# does, and "every scenario passed" has to be a claim something other than attention can make.
FAILURES=0

record_fail() {
  FAILURES=$((FAILURES + 1))
  info "[FAIL] $1"
}

# The host ports a scenario talks to, DERIVED from the one list that already defines them rather
# than repeated as literals. Inline 8082/8083 is how the port list ended up read by index in three
# places and handed Keycloak the inventory port when the list grew.
api_port_for()      { host_ports_for "$1" | awk '{print $4}'; }
keycloak_port_for() { host_ports_for "$1" | awk '{print $5}'; }

# A token from a baseline's own realm. Every /api/v1 read below needs one, and each baseline issues
# its own - there is no shared session, deliberately (locked #49).
kc_token() {
  local port="$1"
  curl -s -m 10 -X POST "http://localhost:$port/realms/lattice/protocol/openid-connect/token" \
    -d "client_id=lattice-console" -d "grant_type=password" \
    -d "username=operator" -d "password=operator" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
}

# One field of what a baseline currently believes about a named peer. The payload is a single JSON
# array, so splitting on '{' is what puts each peer on its own line and keeps the field read off the
# right one.
peer_field() {
  local gateway="$1" tok="$2" peer="$3" field="$4"
  curl -s -m 10 -H "Authorization: Bearer $tok" "http://localhost:$gateway/api/v1/peers" \
    | tr '{' '\n' | grep "\"clusterId\":\"$peer\"" \
    | grep -o "\"$field\":\"[^\"]*\"" | cut -d'"' -f4
}

# A baseline's own verdict, read from its own API. The per-service breakdown behind it is served
# here and never announced (locked #43).
baseline_health() {
  local gateway="$1" tok="$2"
  curl -s -m 10 -H "Authorization: Bearer $tok" "http://localhost:$gateway/api/v1/baseline" \
    | grep -o '"health":"[^"]*"' | head -1 | cut -d'"' -f4
}

# The two reads every scenario actually makes, each fetching its OWN token rather than reusing one
# for the length of a scenario. Some waits below run longer than an access token lives, and a shared
# token turns a slow but correct recovery into a 401 reported as a failed assertion.
health_of() {
  local baseline="$1" tok
  tok="$(kc_token "$(keycloak_port_for "$baseline")")"
  [ -n "$tok" ] || return 1
  baseline_health "$(api_port_for "$baseline")" "$tok"
}

peer_view() {
  local baseline="$1" peer="$2" field="$3" tok
  tok="$(kc_token "$(keycloak_port_for "$baseline")")"
  [ -n "$tok" ] || return 1
  peer_field "$(api_port_for "$baseline")" "$tok" "$peer" "$field"
}

http_code() {
  curl -s -o /dev/null -m 10 -w '%{http_code}' "$1"
}

# Polls to a settled state rather than snapshotting one instant: peer liveness is TTL-driven and a
# rollout takes as long as it takes, so an immediate check would assert on a transition that has not
# happened yet.
wait_until() {
  local what="$1" want="$2" budget="$3"; shift 3
  local waited=0 got
  while [ "$waited" -lt "$budget" ]; do
    got="$("$@" || true)"
    if [ "$got" = "$want" ]; then
      info "[pass] $what is $want (after ${waited}s)"
      return 0
    fi
    sleep 5; waited=$((waited + 5))
  done
  record_fail "$what never became $want (last: ${got:-none}, waited ${waited}s)"
  return 1
}

# A peer going quiet must read as a peer that went quiet - retained and marked UNREACHABLE - rather
# than one that silently vanished, and it must not change the local baseline's own verdict.
scenario_peer_lost() {
  step "Scenario: a peer baseline goes quiet, across a cluster boundary"

  wait_until "hub-central's view of hub-east" REACHABLE 60 peer_view hub-central hub-east reachability \
    || { info "control failed: hub-east was not REACHABLE to begin with - nothing to test"; return 1; }
  info "[pass] control: hub-central can hear hub-east before anything is stopped"

  info "scaling hub-east's gateway to zero (it is the sole mesh participant, locked #42)"
  scale_component hub-east mesh-gateway 0

  wait_until "hub-central's view of hub-east" UNREACHABLE "$TTL_WAIT" \
    peer_view hub-central hub-east reachability || true

  local health
  health="$(health_of hub-central || true)"
  if [ "$health" = "ready" ]; then
    info "[pass] hub-central still reports itself ready - a lost PEER is not a local outage"
  else
    record_fail "hub-central's own health changed because a peer went away (got: ${health:-none})"
  fi

  info "restoring hub-east"
  scale_component hub-east mesh-gateway 1
  wait_until "hub-central's view of hub-east" REACHABLE 180 \
    peer_view hub-central hub-east reachability || true
  info "recovered with no restart anywhere else"
}

# --- Local-behaviour scenarios -------------------------------------------------------------------
# Ported from the retired compose harness; what they assert is unchanged, only the mechanism. Compose
# stopped a container, and here a component is absent only when its controller is scaled to zero -
# deleting the pod would have it recreated in seconds, testing recovery while claiming an outage.

# One service down is not the whole baseline down. The distinction is the whole point of a rollup:
# a peer deciding whether to redirect an operator needs "still serving, but not whole" to read
# differently from "cannot serve".
scenario_degraded() {
  step "Scenario: one service in a baseline is down"

  wait_until "hub-east's own verdict" ready 120 health_of hub-east \
    || { info "control failed: hub-east was not ready to begin with - nothing to test"; return 1; }
  wait_until "hub-central's view of hub-east" ready 90 peer_view hub-central hub-east health \
    || { info "control failed: hub-central did not already see hub-east as ready"; return 1; }
  info "[pass] control: hub-east is whole, and its peer says so"

  info "stopping orders on hub-east - one service of the rollup, not its gateway"
  scale_component hub-east orders 0

  wait_until "hub-east's own verdict" degraded 120 health_of hub-east || true

  # The second assertion is not a duplicate: hub-east computing 'degraded' proves the rollup,
  # hub-central holding the same word proves it travelled. Under locked #43 the rollup is the only
  # thing a peer ever learns about hub-east's services.
  wait_until "hub-central's view of hub-east" degraded 120 peer_view hub-central hub-east health || true

  info "restoring orders on hub-east"
  scale_component hub-east orders 1
  wait_until "hub-east's own verdict" ready 240 health_of hub-east || true
  wait_until "hub-central's view of hub-east" ready 120 peer_view hub-central hub-east health || true
  info "recovered with no restart anywhere else"
}

# A baseline that cannot serve is still a baseline that can be heard. The gateway is the sole mesh
# participant (locked #42), so with every other service stopped it keeps announcing - and 'down'
# reaching a peer is exactly what distinguishes a cluster that cannot serve from one nobody can hear.
scenario_baseline_down() {
  step "Scenario: every service in a baseline is down"

  wait_until "hub-east's own verdict" ready 120 health_of hub-east \
    || { info "control failed: hub-east was not ready to begin with - nothing to test"; return 1; }
  wait_until "hub-central's view of hub-east" REACHABLE 90 peer_view hub-central hub-east reachability \
    || { info "control failed: hub-central could not hear hub-east to begin with"; return 1; }
  info "[pass] control: hub-east is whole and audible"

  info "stopping orders and inventory on hub-east - everything the rollup covers"
  scale_component hub-east orders 0
  scale_component hub-east inventory 0

  wait_until "hub-east's own verdict" down 150 health_of hub-east || true

  local reach
  reach="$(peer_view hub-central hub-east reachability || true)"
  if [ "$reach" = "REACHABLE" ]; then
    info "[pass] hub-central still hears hub-east - down is not the same as gone"
  else
    record_fail "hub-central saw '${reach:-none}' - a down baseline that is still announcing must stay REACHABLE"
  fi

  info "restoring both services on hub-east"
  scale_component hub-east orders 1
  scale_component hub-east inventory 1
  wait_until "hub-east's own verdict" ready 300 health_of hub-east || true
  info "recovered with no restart anywhere else"
}

# Every baseline runs its own broker (locked #44), so stopping one cuts THAT baseline off the mesh
# and leaves its peers' brokers untouched. What must survive the cut is the baseline's own service:
# a mesh outage degrades discovery, not the ability to answer for your own data.
scenario_mesh_cut() {
  step "Scenario: a baseline loses its own broker"

  wait_until "hub-central's view of hub-east" REACHABLE 90 peer_view hub-central hub-east reachability \
    || { info "control failed: hub-central could not hear hub-east to begin with - nothing to cut"; return 1; }
  info "[pass] control: the mesh is carrying announcements before the broker is stopped"

  info "stopping hub-central's own broker"
  scale_component hub-central artemis 0

  local health
  health="$(health_of hub-central || true)"
  if [ "$health" = "ready" ]; then
    info "[pass] hub-central still serves its own baseline with no broker"
  else
    record_fail "hub-central reported '${health:-none}' with its broker down - it should still serve"
  fi

  # Readiness is asserted separately from health: health is what an operator reads, readiness is what
  # Kubernetes acts on, and either can regress alone. Locked #42 keeps the gateway UP on broker loss
  # so an orchestrator does not pull a pod that is serving perfectly well.
  if [ "$(http_code "http://localhost:$(api_port_for hub-central)/readiness")" = "200" ]; then
    info "[pass] readiness stays UP, so an orchestrator does not pull a serving pod out of rotation"
  else
    record_fail "readiness went down when the broker did"
  fi

  # Waiting out the peer time-to-live is what makes this scenario mean anything. Checking straight
  # after stopping the broker would pass while the mesh was still perfectly healthy - the peer has
  # simply not aged out yet - and the recovery assertion afterwards would then prove nothing either.
  info "waiting out the peer time-to-live, so the cut is real rather than merely recent"
  wait_until "hub-central's view of hub-east" UNREACHABLE "$TTL_WAIT" \
    peer_view hub-central hub-east reachability || true
  info "hub-east's own broker was never touched, which is the point of one per baseline"

  info "restoring hub-central's broker"
  scale_component hub-central artemis 1
  wait_until "hub-central's view of hub-east" REACHABLE 300 \
    peer_view hub-central hub-east reachability || true
  info "the gateway rejoined on its own, with no restart"
}

# --- Certificate scenarios -----------------------------------------------------------------------
# These two manipulate the broker's TLS material, which here is a Secret. The acceptor reads its
# truststore and revocation list at start, so the enforcing broker has to be rolled - a Secret
# update plus a rollout in that baseline's own cluster.

# Rewrites one baseline's artemis-tls Secret from whatever issue-certs.sh currently holds on disk.
update_tls_secret() {
  # Two statements, not one: bash expands every word of a `local` before binding any of them, so
  # `local a="$1" b="$a"` leaves b unbound under `set -u`. It only ever worked here because a
  # caller happened to have a global loop variable of the same name.
  local baseline="$1"
  local ctx="kind-$baseline"
  kubectl --context "$ctx" -n lattice create secret generic artemis-tls \
    --from-file=keystore.p12="$TLS_DIR/$baseline/keystore.p12" \
    --from-file=truststore.p12="$TLS_DIR/truststore.p12" \
    --from-file=crl.pem="$TLS_DIR/ca/crl.pem" \
    --from-literal=password="${LATTICE_TLS_PASSWORD:-lattice}" \
    --dry-run=client -o yaml | kubectl --context "$ctx" apply -f - >/dev/null
}

roll_broker() {
  # Two statements, not one: bash expands every word of a `local` before binding any of them, so
  # `local a="$1" b="$a"` leaves b unbound under `set -u`. It only ever worked here because a
  # caller happened to have a global loop variable of the same name.
  local baseline="$1"
  local ctx="kind-$baseline"
  kubectl --context "$ctx" -n lattice rollout restart "statefulset/$baseline-lattice-artemis" >/dev/null
  kubectl --context "$ctx" -n lattice rollout status "statefulset/$baseline-lattice-artemis" --timeout=180s >/dev/null 2>&1
}

# Asks hub-east's broker to complete a mutual-TLS handshake with hub-central's acceptor across the
# boundary, and returns 0 when it was REFUSED. Direct rather than watching the mesh go quiet, since
# the federation link retries on its own schedule and "peers disappeared" is a muddier signal.
tls_handshake_refused() {
  local keystore="${1:-/var/lib/artemis-instance/tls/keystore.p12}"
  local pass="${LATTICE_TLS_PASSWORD:-lattice}"
  local url="tcp://hub-central-control-plane:$NODEPORT_MESH?sslEnabled=true"
  url="$url;keyStorePath=$keystore;keyStoreType=PKCS12;keyStorePassword=$pass"
  url="$url;trustStorePath=/var/lib/artemis-instance/tls/truststore.p12"
  url="$url;trustStoreType=PKCS12;trustStorePassword=$pass"

  MSYS_NO_PATHCONV=1 kubectl --context kind-hub-east -n lattice exec hub-east-lattice-artemis-0 -- \
    sh -c "timeout 30 /var/lib/artemis-instance/bin/artemis check node --up --url '$url'" >/dev/null 2>&1
  # Non-zero means the broker would not talk to us: the handshake failed, or it timed out waiting for
  # one that never completed. Both are the acceptor refusing the certificate.
  [ $? -ne 0 ]
}

# Revoking one baseline must need NO edit to any peer's configuration - that is the property locked
# #50 buys by trusting the AUTHORITY rather than individual peers, and it is what makes no-edit-on-join
# survivable in reverse.
scenario_revoked() {
  step "Scenario: a peer's certificate is revoked, across a cluster boundary"
  [ -f "$TLS_DIR/ca/ca.crt" ] || fail "no certificate authority - run deploy/certs/issue-certs.sh"

  # The control comes FIRST and is not optional. Without it, "the handshake was refused" is also what
  # a wrong URL, a restarting pod or a typo reports - so the scenario would pass most loudly exactly
  # when it was broken.
  if tls_handshake_refused; then
    record_fail "hub-east could not handshake even BEFORE revocation - the check is broken, not the certificate"
    return 1
  fi
  info "[pass] control: hub-east's valid certificate is accepted across the boundary"

  info "revoking hub-east at the authority and refreshing the revocation list"
  (cd "$TLS_DIR" && ./issue-certs.sh revoke hub-east >/dev/null 2>&1) || fail "could not revoke hub-east"

  # Only the ENFORCER is touched. hub-east keeps its now-worthless certificate and is not edited.
  info "updating and rolling ONLY hub-central's broker - hub-east is not touched"
  update_tls_secret hub-central
  roll_broker hub-central
  sleep 15

  if tls_handshake_refused; then
    info "[pass] hub-central refuses hub-east's revoked certificate"
    info "[pass] nothing in hub-east's cluster was edited to revoke it"
  else
    record_fail "hub-central still accepted a revoked certificate"
  fi

  info "re-issuing hub-east and restoring the mesh"
  (cd "$TLS_DIR" && ./issue-certs.sh issue hub-east >/dev/null 2>&1)
  update_tls_secret hub-east
  update_tls_secret hub-central
  roll_broker hub-east
  roll_broker hub-central
  sleep 20

  # Asserts the SETTLED state, and often passes immediately because the transition has finished by
  # the time the re-issue and both rolls are done - measured, it is real. Pinning the intermediate
  # UNREACHABLE would be flaky by construction, which core_protocol.md rules out.
  wait_until "hub-central's view of hub-east" REACHABLE 180 \
    peer_view hub-central hub-east reachability || true
  info "[pass] re-issuing is the joiner's own cost - no peer was edited to accept the new certificate"
}

# The other certificate cases fail for reasons OTHER than the authority - one tests a missing
# certificate, one a revoked one. This isolates the trust anchor itself, which is the thing locked
# #50 actually rests on, and is the shape a second customer's baseline would present (locked #58).
scenario_foreign_authority() {
  step "Scenario: a certificate from an authority nobody trusts, across a cluster boundary"
  [ -f "$TLS_DIR/ca/ca.crt" ] || fail "no certificate authority - run deploy/certs/issue-certs.sh"

  if tls_handshake_refused; then
    record_fail "hub-east's genuine certificate was refused BEFORE the test - the check is broken, not the trust anchor"
    return 1
  fi
  info "[pass] control: a certificate from the real authority is accepted"

  info "minting a certificate with a legitimate-looking name from a different authority"
  (cd "$TLS_DIR" && ./issue-certs.sh foreign hub-east >/dev/null 2>&1) || fail "could not mint the foreign certificate"

  # Copied into the pod rather than mounted, because teaching the chart to carry an untrusted
  # keystore is worse than the test is worth. From INSIDE the tls directory with a relative source:
  # kubectl cp splits on the first colon, so `D:/...` makes `D` look like a pod name.
  ( cd "$TLS_DIR" && MSYS_NO_PATHCONV=1 kubectl --context kind-hub-east -n lattice cp \
      foreign/keystore.p12 hub-east-lattice-artemis-0:/tmp/foreign-keystore.p12 >/dev/null 2>&1 ) \
    || fail "could not stage the foreign keystore"

  if tls_handshake_refused /tmp/foreign-keystore.p12; then
    info "[pass] hub-central refuses a well-formed certificate signed by an authority it does not trust"
  else
    record_fail "a foreign authority's certificate was ACCEPTED - the truststore is not the gate"
  fi

  MSYS_NO_PATHCONV=1 kubectl --context kind-hub-east -n lattice exec hub-east-lattice-artemis-0 -- \
    rm -f /tmp/foreign-keystore.p12 >/dev/null 2>&1 || true
  info "[pass] the genuine certificate still works - nothing was left behind"
}

SCENARIOS=(peer-lost degraded baseline-down mesh-cut loop-check revoked-east foreign-authority)

# A scenario returning non-zero means an assertion failed, never that the name was wrong - so the
# name is validated separately. Conflating the two reports a broken mesh as a typo.
run_scenario() {
  case "$1" in
    peer-lost)         scenario_peer_lost ;;
    degraded)          scenario_degraded ;;
    baseline-down)     scenario_baseline_down ;;
    mesh-cut)          scenario_mesh_cut ;;
    loop-check)        scenario_loop_check ;;
    revoked-east)      scenario_revoked ;;
    foreign-authority) scenario_foreign_authority ;;
  esac
}

usage_scenarios() {
  printf 'scenarios: %s all\n' "${SCENARIOS[*]}" >&2
  exit 2
}

# Counts announcements delivered on the announce topic at one baseline's own broker. Read from the
# broker rather than the registry, which dedupes by cluster id and so cannot show a re-forward. The
# awk program is single-quoted: its fields would otherwise expand as shell positional parameters.
announcements_delivered() {
  local baseline="$1"
  MSYS_NO_PATHCONV=1 kubectl --context "kind-$baseline" -n lattice exec \
    "$baseline-lattice-artemis-0" -- /var/lib/artemis-instance/bin/artemis queue stat \
    --user "${ARTEMIS_USER:-artemis}" --password "${ARTEMIS_PASSWORD:-artemis}" \
    --url tcp://localhost:61616 2>/dev/null \
    | sed 's/|/ /g' \
    | awk '$2 ~ /^topic:/ && $1 !~ /^federated/ && $1 !~ /^ / {print $5}' | head -1
}

# Loop prevention, measured rather than asserted, and it needs all three baselines: two brokers
# cannot form a loop. Measured as a DIFFERENCE because cadence and sampling are not synchronised,
# silencing hub-west's gateway rather than its broker. Slow: two 90-second windows plus a settle.
scenario_loop_check() {
  step "Scenario: a third baseline adds one copy of its announcements, not two"

  wait_until "hub-central's view of hub-west" REACHABLE 90 peer_view hub-central hub-west reachability \
    || { info "control failed: hub-west was not federating to begin with - nothing to measure"; return 1; }
  info "[pass] control: all three baselines are federating"

  local window=90 three_start three_end two_start two_end with_three with_two contributed

  three_start="$(announcements_delivered hub-central)"
  info "measuring with three baselines announcing (${window}s)..."
  sleep "$window"
  three_end="$(announcements_delivered hub-central)"
  with_three=$((three_end - three_start))

  info "silencing hub-west's gateway and letting it age out"
  scale_component hub-west mesh-gateway 0
  sleep 40

  two_start="$(announcements_delivered hub-central)"
  info "measuring with two baselines announcing (${window}s)..."
  sleep "$window"
  two_end="$(announcements_delivered hub-central)"
  with_two=$((two_end - two_start))

  contributed=$((with_three - with_two))
  info "three: $with_three   two: $with_two   hub-west contributes: $contributed"

  # Announce cadence is 10s, so one copy over 90s is about 9 messages. The bounds are generous
  # because the cadence and the window are not synchronised, and a message either way is jitter
  # rather than a loop. A DOUBLED contribution is the failure this exists to catch.
  if [ "$contributed" -ge 6 ] && [ "$contributed" -le 12 ]; then
    info "[pass] hub-west adds one copy of its announcements - max-hops=1 is doing its job"
  elif [ "$contributed" -gt 12 ]; then
    record_fail "hub-west added $contributed copies, which is re-forwarding around the mesh"
  else
    record_fail "hub-west added only $contributed - it may not be federating at all"
  fi

  info "restoring hub-west"
  scale_component hub-west mesh-gateway 1
  wait_until "hub-central's view of hub-west" REACHABLE 180 \
    peer_view hub-central hub-west reachability || true
}

cmd_scenario() {
  require kubectl
  local want="${1:-}" known=0 s
  [ -n "$want" ] || usage_scenarios

  # The certificate scenarios run LAST under `all`, and that is ordering rather than taste: they
  # rewrite TLS material and roll brokers, so a failure part-way through one leaves the mesh needing
  # its re-issue step. Everything before it has already reported by then.
  if [ "$want" = "all" ]; then
    for s in "${SCENARIOS[@]}"; do run_scenario "$s" || true; done
  else
    for s in "${SCENARIOS[@]}"; do
      if [ "$s" = "$want" ]; then known=1; fi
    done
    [ "$known" = 1 ] || { printf 'unknown scenario: %s\n' "$want" >&2; usage_scenarios; }
    run_scenario "$want" || true
  fi

  if [ "$FAILURES" -gt 0 ]; then
    printf '\n\033[1;31m!!  %s assertion(s) failed\033[0m\n' "$FAILURES" >&2
    exit 1
  fi
  step "All assertions passed"
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
  images) cmd_images ;;
  seed)   cmd_seed ;;
  redeploy) shift; cmd_redeploy "$@" ;;
  render) shift; cmd_render "$@" ;;
  check)  cmd_check ;;
  stop)   shift; cmd_stop "$@" ;;
  start)  shift; cmd_start "$@" ;;
  scenario) shift; cmd_scenario "$@" ;;
  deploy) cmd_deploy ;;
  *)
    printf 'usage: %s {up|images|deploy|seed|redeploy <baseline> <service>|render <baseline>|check|stop/start <baseline> <component>|scenario <name>|scenario all|down|status|pods}\n' "$0" >&2
    exit 2
    ;;
esac
