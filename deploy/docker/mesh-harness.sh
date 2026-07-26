#!/usr/bin/env bash
# Lattice mesh harness - stands up two baselines and induces the failure states the federation is
# designed around, so they can be demonstrated and QA'd without reproducing them by hand.
#
# WHY THIS EXISTS: the interesting behaviour of this federation is what happens when things go
# wrong, and none of it is visible in a healthy screenshot. A peer ageing out to UNREACHABLE while
# staying on screen with its last-known detail, a baseline dropping to degraded, discovery going
# quiet when a broker is lost - those states are the reason the design chose retention over
# deletion, and until now showing them meant stopping containers by hand and watching.
#
# IT ACTS ON THE INFRASTRUCTURE, NEVER ON THE PRODUCT. There is no demo mode in a service and no
# test affordance in the console: the harness stops containers, which is exactly how these states
# occur in the real world and exactly how they were reproduced by hand. A service that could be
# told to pretend would be a service that can lie in production.
#
# Usage:
#   ./deploy/docker/mesh-harness.sh up                  bring both baselines up, wait for discovery
#   ./deploy/docker/mesh-harness.sh status              what each baseline currently sees
#   ./deploy/docker/mesh-harness.sh scenario <name>     induce a failure, verify it, restore it
#   ./deploy/docker/mesh-harness.sh scenarios           list the scenarios
#   ./deploy/docker/mesh-harness.sh up --three          add the third baseline, hub-west
#   ./deploy/docker/mesh-harness.sh loop-check          prove max-hops=1 at the broker
#   ./deploy/docker/mesh-harness.sh qa                  run the qa_protocol two-cluster mesh pass
#   ./deploy/docker/mesh-harness.sh down                tear both baselines down
#
# Prerequisites: docker compose, curl, and a built reactor (./mvnw package) so the service images
# have their fat jars. jq is deliberately NOT required - the few fields read here are pulled with
# sed, so the harness runs on a machine that has only what the rest of the QA loop already needs.

set -u

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"

PRIMARY_FILE=deploy/docker/docker-compose.yml
PEER_FILE=deploy/docker/docker-compose.peer.yml
PEER2_FILE=deploy/docker/docker-compose.peer2.yml

# hub-local (the primary project)
LOCAL_NAME=hub-local
LOCAL_GATEWAY=8082
LOCAL_KEYCLOAK=8083
LOCAL_CONSOLE=3000

# hub-east (the peer project)
EAST_NAME=hub-east
EAST_GATEWAY=8092
EAST_KEYCLOAK=8084
EAST_CONSOLE=3001

# hub-west (the third baseline; only used by the three-baseline commands)
WEST_NAME=hub-west
WEST_GATEWAY=8102
WEST_KEYCLOAK=8085
WEST_CONSOLE=3002

# Broker containers, for the measurements that can only be taken at the broker.
LOCAL_BROKER=lattice-artemis-1
EAST_BROKER=lattice-peer-artemis-peer-1
WEST_BROKER=lattice-peer2-artemis-peer2-1

# A peer unheard for PEER_TTL (30s by default) flips to UNREACHABLE, so anything waiting on that
# transition must allow for the TTL plus a heartbeat, not just the TTL.
TTL_WAIT=75
READY_WAIT=180

bold=$(printf '\033[1m')
dim=$(printf '\033[2m')
red=$(printf '\033[31m')
green=$(printf '\033[32m')
yellow=$(printf '\033[33m')
reset=$(printf '\033[0m')

say() { printf '%s\n' "$*"; }
step() { printf '\n%s==> %s%s\n' "$bold" "$*" "$reset"; }
note() { printf '    %s%s%s\n' "$dim" "$*" "$reset"; }
pass() { printf '    %s[pass]%s %s\n' "$green" "$reset" "$*"; }
fail() { printf '    %s[FAIL]%s %s\n' "$red" "$reset" "$*"; }
warn() { printf '    %s[warn]%s %s\n' "$yellow" "$reset" "$*"; }

failures=0
record_fail() {
  fail "$1"
  failures=$((failures + 1))
}

# --- talking to a baseline -------------------------------------------------------------------
# Every /api/v1 read needs a bearer token from THAT baseline's own realm. A token from the other
# baseline is rejected, which is the point of per-baseline identity and is asserted in the QA pass.

token() {
  curl -s --max-time 10 \
    -d client_id=lattice-console -d username=operator -d password=operator -d grant_type=password \
    "http://localhost:$1/realms/lattice/protocol/openid-connect/token" |
    sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

# Reads one baseline's own rollup: ready | degraded | down, or empty when it cannot be reached.
baseline_health() {
  local port=$1 tok=$2
  curl -s --max-time 10 -H "Authorization: Bearer $tok" \
    "http://localhost:$port/api/v1/baseline" |
    sed -n 's/.*"health":"\([a-z]*\)".*/\1/p'
}

# Reads the reachability this baseline currently reports for a named peer, or empty when that peer
# is absent from the registry entirely (which is different from being present and unreachable).
peer_reachability() {
  local port=$1 tok=$2 peer=$3
  curl -s --max-time 10 -H "Authorization: Bearer $tok" \
    "http://localhost:$port/api/v1/peers" |
    tr '{' '\n' |
    sed -n "/\"clusterId\":\"$peer\"/s/.*\"reachability\":\"\([A-Z]*\)\".*/\1/p"
}

# Reads a field this baseline has retained for a peer, to show a peer that has gone quiet is kept
# with its last-known detail rather than dropped.
peer_field() {
  local port=$1 tok=$2 peer=$3 field=$4
  curl -s --max-time 10 -H "Authorization: Bearer $tok" \
    "http://localhost:$port/api/v1/peers" |
    tr '{' '\n' |
    sed -n "/\"clusterId\":\"$peer\"/s/.*\"$field\":\"\([^\"]*\)\".*/\1/p"
}

http_code() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"
}

# Polls until a command prints the expected value, or gives up. Used instead of a fixed sleep so a
# fast machine is not punished and a slow one is not flaky.
wait_for() {
  local what=$1 expected=$2 timeout=$3
  shift 3
  local waited=0 actual=
  while [ "$waited" -lt "$timeout" ]; do
    actual=$("$@" 2>/dev/null)
    if [ "$actual" = "$expected" ]; then
      pass "$what -> $expected (after ${waited}s)"
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  record_fail "$what -> expected '$expected', last saw '${actual:-nothing}' after ${timeout}s"
  return 1
}

compose_primary() { docker compose -f "$PRIMARY_FILE" "$@"; }
compose_peer() { docker compose -f "$PEER_FILE" "$@"; }
compose_peer2() { docker compose -f "$PEER2_FILE" "$@"; }

# How many announcements a broker has delivered to its gateway subscription since it started.
# Taken at the broker deliberately: the peer registry dedupes by cluster id, so a duplicate
# announcement is invisible there - which is exactly the thing loop prevention has to be judged on.
announcements_delivered() {
  # The awk program is single-quoted: its $1/$2/$5 are awk fields, and double quotes would have the
  # shell expand them as its own positional parameters instead - which under `set -u` fails loudly
  # rather than silently reading the wrong column.
  MSYS_NO_PATHCONV=1 docker exec "$1" /var/lib/artemis-instance/bin/artemis queue stat \
    --user "${ARTEMIS_USER:-artemis}" --password "${ARTEMIS_PASSWORD:-artemis}" \
    --url tcp://localhost:61616 2>/dev/null |
    sed 's/|/ /g' |
    awk '$2 ~ /^topic:/ && $1 !~ /^federated/ && $1 !~ /^ / {print $5}' | head -1
}

# How many peers a baseline currently lists.
peer_count() {
  curl -s --max-time 10 -H "Authorization: Bearer $2" "http://localhost:$1/api/v1/peers" |
    grep -o "\"clusterId\":\"[^\"]*\"" | wc -l | tr -d " "
}

# --- lifecycle -------------------------------------------------------------------------------

cmd_up() {
  step "Bringing up $LOCAL_NAME"
  compose_primary up -d --build || { record_fail "$LOCAL_NAME did not come up"; return 1; }

  step "Bringing up $EAST_NAME"
  # The peer project joins the primary's network rather than creating one, so the primary must
  # exist first. That ordering is the only thing the two baselines share.
  compose_peer up -d --build || { record_fail "$EAST_NAME did not come up"; return 1; }

  step "Waiting for both baselines to answer"
  wait_for "$LOCAL_NAME readiness" 200 "$READY_WAIT" http_code "http://localhost:$LOCAL_GATEWAY/readiness"
  wait_for "$EAST_NAME readiness" 200 "$READY_WAIT" http_code "http://localhost:$EAST_GATEWAY/readiness"

  step "Waiting for mutual discovery over the mesh"
  local lt et
  lt=$(token "$LOCAL_KEYCLOAK")
  et=$(token "$EAST_KEYCLOAK")
  wait_for "$LOCAL_NAME sees $EAST_NAME" REACHABLE 90 peer_reachability "$LOCAL_GATEWAY" "$lt" "$EAST_NAME"
  wait_for "$EAST_NAME sees $LOCAL_NAME" REACHABLE 90 peer_reachability "$EAST_GATEWAY" "$et" "$LOCAL_NAME"

  # Discovery happens before the health rollup has caught up: the rollup is recomputed on the
  # announce heartbeat, so a baseline whose services are still starting announces 'down' for a few
  # seconds. Returning at that moment would start every demo and every QA run on a state that looks
  # like a failure and is not, so wait for both to settle before handing over.
  step "Waiting for both health rollups to settle"
  wait_for "$LOCAL_NAME announces" ready 90 baseline_health "$LOCAL_GATEWAY" "$lt"
  wait_for "$EAST_NAME announces" ready 90 baseline_health "$EAST_GATEWAY" "$et"

  cmd_status
}

cmd_down() {
  step "Tearing both baselines down"
  compose_peer down -v >/dev/null 2>&1
  compose_primary down -v >/dev/null 2>&1
  say "    both baselines removed, volumes included"
}

cmd_status() {
  step "What each baseline currently sees"
  local lt et
  lt=$(token "$LOCAL_KEYCLOAK")
  et=$(token "$EAST_KEYCLOAK")

  printf '    %-12s %-12s %-14s %s\n' baseline "own health" "peer" "peer state"
  printf '    %-12s %-12s %-14s %s\n' "$LOCAL_NAME" \
    "$(baseline_health "$LOCAL_GATEWAY" "$lt")" "$EAST_NAME" \
    "$(peer_reachability "$LOCAL_GATEWAY" "$lt" "$EAST_NAME")"
  printf '    %-12s %-12s %-14s %s\n' "$EAST_NAME" \
    "$(baseline_health "$EAST_GATEWAY" "$et")" "$LOCAL_NAME" \
    "$(peer_reachability "$EAST_GATEWAY" "$et" "$LOCAL_NAME")"
  note "consoles: http://localhost:$LOCAL_CONSOLE ($LOCAL_NAME)  http://localhost:$EAST_CONSOLE ($EAST_NAME)"
}


cmd_up_three() {
  cmd_up || return 1

  step "Joining $WEST_NAME to the running pair"
  note "Neither existing baseline is edited, restarted, or redeployed - the joiner declares links to"
  note "both of them and commands each to open one back. That is the whole cost of joining."

  # Built as a separate step so a build failure is reported as one, and so the join is not competing
  # with an image build for the host.
  compose_peer2 build >/dev/null 2>&1 || { record_fail "$WEST_NAME images did not build"; return 1; }
  compose_peer2 up -d || { record_fail "$WEST_NAME did not come up"; return 1; }

  wait_for "$WEST_NAME readiness" 200 "$READY_WAIT" http_code "http://localhost:$WEST_GATEWAY/readiness"

  step "Waiting for a full triangle"
  local lt et wt
  lt=$(token "$LOCAL_KEYCLOAK")
  et=$(token "$EAST_KEYCLOAK")
  wt=$(token "$WEST_KEYCLOAK")
  local before=$failures
  wait_for "$LOCAL_NAME sees two peers" 2 90 peer_count "$LOCAL_GATEWAY" "$lt"
  wait_for "$EAST_NAME sees two peers" 2 90 peer_count "$EAST_GATEWAY" "$et"
  wait_for "$WEST_NAME sees two peers" 2 90 peer_count "$WEST_GATEWAY" "$wt"

  # No retry here on purpose. The triangle either forms on the first attempt or something is wrong:
  # a broker keys arriving federations by NAME, so this used to fail whenever two baselines named
  # theirs the same thing and the second was discarded as a duplicate. Names are now per-baseline
  # (see artemis/*/federation.xml). A retry would only hide the next name collision.

  # Claiming success here unconditionally is worse than any bug it could hide: a harness that
  # reports a pass immediately after printing a failure teaches everyone to stop reading its output.
  if [ "$failures" -eq "$before" ]; then
    pass "all three baselines discovered each other"
  else
    fail "the triangle did not form - see the failures above, and do not read what follows as a pass"
    return 1
  fi
}

cmd_down_three() {
  step "Tearing all three baselines down"
  compose_peer2 down -v >/dev/null 2>&1
  cmd_down
}

# Loop prevention cannot be judged from the registry, which dedupes by cluster id, and it cannot be
# judged from a single absolute count either - that depends on whether a gateway hears its own
# announcement, which is a detail of the broker rather than of the mesh. So it is measured as a
# DIFFERENCE at one broker: three baselines, then two. Whatever the third contributes is exactly the
# number of copies of its announcements that reach the others.
cmd_loop_check() {
  step "Loop prevention: does a third baseline add one copy, or more?"
  note "With three brokers every baseline is reachable by two paths - directly and via the third -"
  note "so a re-forwarded announcement would arrive twice. Measured at $LOCAL_NAME's broker."

  local window=90 expected=9
  note "Announce cadence is 10s, so one copy over ${window}s is about ${expected} messages."

  local three_start three_end two_start two_end
  three_start=$(announcements_delivered "$LOCAL_BROKER")
  say "    measuring with three baselines (${window}s)..."
  sleep "$window"
  three_end=$(announcements_delivered "$LOCAL_BROKER")
  local with_three=$((three_end - three_start))

  note "Stopping $WEST_NAME and letting it age out"
  compose_peer2 stop >/dev/null 2>&1
  sleep 40

  two_start=$(announcements_delivered "$LOCAL_BROKER")
  say "    measuring with two baselines (${window}s)..."
  sleep "$window"
  two_end=$(announcements_delivered "$LOCAL_BROKER")
  local with_two=$((two_end - two_start))

  local contributed=$((with_three - with_two))
  say "    three baselines: $with_three   two baselines: $with_two   third contributes: $contributed"

  # A doubled contribution is the failure this exists to catch; the bounds are generous because the
  # announce cadence and the sampling window are not synchronised, and one message either way is
  # ordinary jitter rather than a loop.
  if [ "$contributed" -ge 6 ] && [ "$contributed" -le 12 ]; then
    pass "the third baseline adds one copy of its announcements - max-hops=1 is doing its job"
  elif [ "$contributed" -gt 12 ]; then
    record_fail "the third baseline added $contributed copies, which is re-forwarding around the mesh"
  else
    record_fail "the third baseline added only $contributed - it may not be federating at all"
  fi

  note "Restoring $WEST_NAME"
  compose_peer2 start >/dev/null 2>&1
}

# --- scenarios -------------------------------------------------------------------------------

scenario_peer_lost() {
  step "Scenario: a peer baseline is lost"
  note "Stopping $EAST_NAME entirely. $LOCAL_NAME should keep the peer on screen as UNREACHABLE,"
  note "with its last-known detail retained - the design chose retention over deletion so an"
  note "operator sees a baseline went quiet rather than one that silently vanished."

  local lt before_url
  lt=$(token "$LOCAL_KEYCLOAK")
  before_url=$(peer_field "$LOCAL_GATEWAY" "$lt" "$EAST_NAME" consoleUrl)
  note "before: consoleUrl=$before_url"

  compose_peer stop >/dev/null 2>&1
  wait_for "$LOCAL_NAME reports $EAST_NAME" UNREACHABLE "$TTL_WAIT" peer_reachability "$LOCAL_GATEWAY" "$lt" "$EAST_NAME"

  lt=$(token "$LOCAL_KEYCLOAK")
  local after_url
  after_url=$(peer_field "$LOCAL_GATEWAY" "$lt" "$EAST_NAME" consoleUrl)
  if [ -n "$after_url" ] && [ "$after_url" = "$before_url" ]; then
    pass "last-known detail retained (consoleUrl still $after_url)"
  else
    record_fail "peer detail was not retained (was '$before_url', now '${after_url:-dropped}')"
  fi

  if [ "$(baseline_health "$LOCAL_GATEWAY" "$lt")" = "ready" ]; then
    pass "$LOCAL_NAME still reports its own health as ready - a lost peer is not a local outage"
  else
    record_fail "$LOCAL_NAME's own health changed because a PEER went away"
  fi

  note "Restoring $EAST_NAME"
  compose_peer start >/dev/null 2>&1
  wait_for "$LOCAL_NAME sees $EAST_NAME again" REACHABLE 90 peer_reachability "$LOCAL_GATEWAY" "$lt" "$EAST_NAME"
  pass "recovered without restarting anything"
}

scenario_degraded() {
  step "Scenario: one service in a baseline is down"
  note "Stopping orders in $EAST_NAME. That baseline should announce 'degraded' - still serving,"
  note "but not whole - and $LOCAL_NAME should see the degraded rollup on its peer."

  local et lt
  et=$(token "$EAST_KEYCLOAK")
  lt=$(token "$LOCAL_KEYCLOAK")

  compose_peer stop orders-peer >/dev/null 2>&1
  wait_for "$EAST_NAME announces" degraded 60 baseline_health "$EAST_GATEWAY" "$et"

  local peer_health
  peer_health=$(peer_field "$LOCAL_GATEWAY" "$lt" "$EAST_NAME" health)
  if [ "$peer_health" = "degraded" ]; then
    pass "$LOCAL_NAME sees its peer as degraded, over the mesh"
  else
    record_fail "$LOCAL_NAME saw peer health '$peer_health', expected degraded"
  fi

  note "Restoring orders in $EAST_NAME"
  compose_peer start orders-peer >/dev/null 2>&1
  wait_for "$EAST_NAME announces" ready 90 baseline_health "$EAST_GATEWAY" "$et"
  pass "recovered without restarting anything"
}

scenario_baseline_down() {
  step "Scenario: every service in a baseline is down"
  note "Stopping orders and inventory in $EAST_NAME. Its gateway is still up and still announcing,"
  note "so the baseline reports 'down' rather than disappearing - which is the distinction between"
  note "a cluster that cannot serve and a cluster nobody can hear."

  local et
  et=$(token "$EAST_KEYCLOAK")

  compose_peer stop orders-peer inventory-peer >/dev/null 2>&1
  wait_for "$EAST_NAME announces" down 60 baseline_health "$EAST_GATEWAY" "$et"

  local reach
  reach=$(peer_reachability "$LOCAL_GATEWAY" "$(token "$LOCAL_KEYCLOAK")" "$EAST_NAME")
  if [ "$reach" = "REACHABLE" ]; then
    pass "$LOCAL_NAME still hears $EAST_NAME (down is not the same as gone)"
  else
    record_fail "$LOCAL_NAME saw '$reach' - a down baseline that is still announcing must stay REACHABLE"
  fi

  note "Restoring both services"
  compose_peer start orders-peer inventory-peer >/dev/null 2>&1
  wait_for "$EAST_NAME announces" ready 120 baseline_health "$EAST_GATEWAY" "$et"
  pass "recovered without restarting anything"
}

scenario_mesh_cut() {
  step "Scenario: a baseline loses its own broker"
  note "Stopping $LOCAL_NAME's broker. Every baseline now runs its own (locked #44), so this cuts"
  note "$LOCAL_NAME off the mesh while leaving $EAST_NAME's own broker untouched. $LOCAL_NAME must"
  note "keep serving its own data throughout - a mesh outage degrades discovery, not the service."

  local lt
  lt=$(token "$LOCAL_KEYCLOAK")

  compose_primary stop artemis >/dev/null 2>&1

  local health
  health=$(baseline_health "$LOCAL_GATEWAY" "$lt")
  if [ "$health" = "ready" ]; then
    pass "$LOCAL_NAME still serves its own baseline with no broker (readiness deliberately ignores it)"
  else
    record_fail "$LOCAL_NAME reported '$health' with its broker down - it should still serve"
  fi

  if [ "$(http_code "http://localhost:$LOCAL_GATEWAY/readiness")" = "200" ]; then
    pass "readiness stays UP, so an orchestrator does not pull a serving pod out of rotation"
  else
    record_fail "readiness went down when the broker did"
  fi

  # Waiting out the TTL here is what makes this scenario mean anything. Checking immediately after
  # stopping the broker would pass while the mesh was still perfectly healthy - the peer simply has
  # not aged out yet - so the recovery assertion afterwards would prove nothing either.
  note "Waiting out the peer time-to-live, so the cut is real rather than merely recent"
  wait_for "$LOCAL_NAME loses sight of $EAST_NAME" UNREACHABLE "$TTL_WAIT" \
    peer_reachability "$LOCAL_GATEWAY" "$lt" "$EAST_NAME"
  note "note: $EAST_NAME is unaffected - its own broker is untouched, which is the point of one per baseline"

  note "Restoring the broker"
  compose_primary start artemis >/dev/null 2>&1
  wait_for "$LOCAL_NAME rejoins the mesh and sees $EAST_NAME" REACHABLE 120 \
    peer_reachability "$LOCAL_GATEWAY" "$lt" "$EAST_NAME"
  pass "the gateway rejoined on its own, with no restart"
}

cmd_scenario() {
  case "${1:-}" in
    peer-lost) scenario_peer_lost ;;
    degraded) scenario_degraded ;;
    baseline-down) scenario_baseline_down ;;
    mesh-cut) scenario_mesh_cut ;;
    *)
      say "unknown scenario: ${1:-<none>}"
      cmd_scenarios
      return 1
      ;;
  esac
}

cmd_scenarios() {
  say "scenarios:"
  say "  peer-lost       stop a peer baseline    -> peer flips to UNREACHABLE, detail retained"
  say "  degraded        stop one service        -> that baseline announces degraded"
  say "  baseline-down   stop every service      -> announces down, still heard by its peer"
  say "  mesh-cut        stop a baseline's broker-> discovery goes quiet, the baseline keeps serving"
}

# --- the QA pass -----------------------------------------------------------------------------

cmd_qa() {
  say "${bold}Lattice two-cluster mesh QA${reset}"
  note "This is the qa_protocol.md mesh checklist, run end to end."

  cmd_up || return 1

  step "Checklist: each baseline passes its own health and readiness"
  for pair in "$LOCAL_NAME:$LOCAL_GATEWAY" "$EAST_NAME:$EAST_GATEWAY"; do
    local name=${pair%%:*} port=${pair##*:}
    if [ "$(http_code "http://localhost:$port/health")" = "200" ] &&
      [ "$(http_code "http://localhost:$port/readiness")" = "200" ]; then
      pass "$name health and readiness both 200, unauthenticated"
    else
      record_fail "$name probes did not both answer 200"
    fi
  done

  step "Checklist: identity belongs to the baseline that owns the data"
  local lt et
  lt=$(token "$LOCAL_KEYCLOAK")
  et=$(token "$EAST_KEYCLOAK")
  if [ "$(http_code "http://localhost:$LOCAL_GATEWAY/api/v1/peers")" = "401" ]; then
    pass "an unauthenticated read of the peer registry is refused"
  else
    record_fail "the peer registry answered without a token"
  fi
  if [ "$(http_code -H "Authorization: Bearer $et" "http://localhost:$LOCAL_GATEWAY/api/v1/peers")" = "401" ]; then
    pass "$EAST_NAME's token is refused by $LOCAL_NAME - a peer's grant does not carry"
  else
    record_fail "a peer baseline's token was accepted"
  fi

  step "Checklist: the redirect target is the peer's own console"
  local advertised console_cluster
  advertised=$(peer_field "$LOCAL_GATEWAY" "$lt" "$EAST_NAME" consoleUrl)
  console_cluster=$(curl -s --max-time 10 "$advertised/" |
    grep -o '/assets/[^"]*\.js' | head -1 |
    { read -r asset; [ -n "$asset" ] && curl -s --max-time 10 "$advertised$asset" |
      sed -n 's/.*VITE_CLUSTER_ID:`\([^`]*\)`.*/\1/p'; })
  if [ "$console_cluster" = "$EAST_NAME" ]; then
    pass "$LOCAL_NAME advertises $advertised, which serves $EAST_NAME's own console"
  else
    record_fail "advertised console at $advertised reported cluster '${console_cluster:-unknown}'"
  fi

  step "Checklist: bring one cluster down, the peer reflects it"
  scenario_peer_lost

  printf '\n'
  if [ "$failures" -eq 0 ]; then
    printf '%sMesh QA passed.%s Both baselines up, mutual discovery, per-baseline identity enforced,\n' "$green$bold" "$reset"
    printf 'the advertised redirect lands on the peer, and a lost peer is retained as UNREACHABLE.\n'
    return 0
  fi
  printf '%sMesh QA failed: %s check(s).%s See the [FAIL] lines above.\n' "$red$bold" "$failures" "$reset"
  return 1
}

# --- entry point -----------------------------------------------------------------------------

case "${1:-}" in
  up) if [ "${2:-}" = "--three" ]; then cmd_up_three; else cmd_up; fi ;;
  down) if [ "${2:-}" = "--three" ]; then cmd_down_three; else cmd_down; fi ;;
  loop-check) cmd_loop_check ;;
  status) cmd_status ;;
  scenario) shift; cmd_scenario "${1:-}" ;;
  scenarios) cmd_scenarios ;;
  qa) cmd_qa ;;
  *)
    say "usage: $0 {up [--three]|down [--three]|status|scenario <name>|scenarios|loop-check|qa}"
    say ""
    cmd_scenarios
    exit 1
    ;;
esac

exit $((failures > 0))
