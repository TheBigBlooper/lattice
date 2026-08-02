#!/usr/bin/env bash
#
# The Lattice certificate authority, and one certificate per baseline (locked #50). An authority
# names nobody, so a baseline that appears later is trusted with no edit anywhere - the property
# locked #44 needs. Everything runs inside the pinned Artemis image, which carries openssl and
# keytool, so the material is reproducible and no host tooling is required. Nothing is committed.
# Detail: docs/design/features/per_baseline_identity.md, "Broker identity".
#
# Usage:
#   ./issue-certs.sh                      issue the authority (if absent) plus all known baselines
#   ./issue-certs.sh issue <baseline>     issue one baseline, for a fourth that does not exist yet
#   ./issue-certs.sh revoke <baseline>    revoke a baseline and refresh the revocation list
#   ./issue-certs.sh rotate <baseline>    re-issue before expiry, keeping the same authority
#   ./issue-certs.sh foreign <baseline>   mint a certificate from an UNTRUSTED authority (harness use)
#   ./issue-certs.sh clean                delete all generated material

set -euo pipefail

cd "$(dirname "$0")"

readonly IMAGE="apache/activemq-artemis:2.44.0-alpine"
readonly BASELINES=("hub-central" "hub-east" "hub-west")

# Both stores are PKCS12: it is the standard interchange format, it is what a Kubernetes Secret will
# carry unchanged, and it avoids keytool's JKS-specific behaviour.
readonly STORE_PASS="${LATTICE_TLS_PASSWORD:-lattice}"

# The distinguished name every baseline shares below its common name. The broker matches ONE regular
# expression against this, so the constant part is what makes "is this one of ours" answerable
# without naming a single peer. Changing it means changing artemis-cert-users.properties to match.
readonly DN_SUFFIX="OU=Lattice Baseline,O=Lattice,C=US"

# Deliberately short for a development authority: 825 days is the maximum a modern client accepts for
# a leaf certificate, and a rotation that is never exercised is a rotation that does not work.
readonly CA_DAYS=3650
readonly LEAF_DAYS=825

info() { printf '  %s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
die()  { printf '\nerror: %s\n' "$*" >&2; exit 1; }

# Runs openssl/keytool inside the pinned image and brings the result back out.
#
# Defect note. Symptom: regenerating certificates works once per machine, then fails ever after with
# "File exists" for a file both `ls` listings agree is gone.
#
# On Docker Desktop for Windows a host-side delete leaves a tombstone in the container's view of a
# bind mount. So nothing is written into the mount from inside: it is read-only input, the container
# works in its own workspace and streams it back as a tar the host unpacks. Progress goes to stderr
# because stdout carries that tar, and MSYS_NO_PATHCONV stops Git Bash rewriting container paths.
in_image() {
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd):/in:ro" \
    --entrypoint sh "$IMAGE" -c "
      set -e
      work=/tmp/lattice-tls
      rm -rf \$work && mkdir -p \$work && cd \$work
      cp -r /in/. \$work/ 2>/dev/null || true
      rm -f \$work/issue-certs.sh \$work/.gitignore
      (
$1
      ) >&2
      tar -c -C \$work .
    " | tar -x -C .
}

require_ca() {
  [ -f ca/ca.crt ] && [ -f ca/ca.key ] || die "no authority yet - run ./issue-certs.sh with no arguments first"
}

create_ca() {
  if [ -f ca/ca.crt ]; then
    info "authority already exists, keeping it (re-creating it would invalidate every issued certificate)"
    return
  fi

  step "Creating the Lattice development certificate authority"
  in_image "
    set -e
    mkdir -p ca
    openssl req -x509 -newkey rsa:4096 -sha256 -days $CA_DAYS -nodes \
      -keyout ca/ca.key -out ca/ca.crt \
      -subj '/CN=Lattice Development CA/$(echo "$DN_SUFFIX" | tr ',' '/')' \
      -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
      -addext 'keyUsage=critical,keyCertSign,cRLSign' 2>/dev/null

    # The authority's serial and index back the revocation list. openssl ca refuses to run without
    # them, and they are what makes a revocation durable rather than a one-off file edit.
    touch ca/index.txt
    [ -f ca/serial ] || echo 1000 > ca/serial
    [ -f ca/crlnumber ] || echo 1000 > ca/crlnumber
  "
  info "authority created (valid $CA_DAYS days)"

  # The truststore is the authority certificate and nothing else, which is what preserves
  # no-edit-on-join. keytool, not openssl: `pkcs12 -export -nokeys` writes a plain bag rather than a
  # trustedCertEntry, and the broker then fails with "the trustAnchors parameter must be non-empty".
  step "Building the shared truststore (the authority certificate, nobody else)"
  in_image "
    rm -f truststore.p12
    keytool -importcert -noprompt -alias lattice-ca -file ca/ca.crt \
      -keystore truststore.p12 -storetype PKCS12 -storepass $STORE_PASS 2>/dev/null
  "
  info "truststore.p12 - identical in every baseline"
}

# The address a peer in another cluster dials this baseline at. A certificate has to carry the name
# actually dialled, which only the issuer knows (delivery_model.md, requirement 2). Unset, it falls
# back to the kind node name, a local convenience and nothing more.
#
#   BROKER_EXTERNAL_HOSTS="hub-central=artemis.central.example,hub-east=artemis.east.example"
external_host_for() {
  local baseline="$1" pair
  for pair in $(printf '%s' "${BROKER_EXTERNAL_HOSTS:-}" | tr ',' ' '); do
    case "$pair" in
      "$baseline="*) printf '%s' "${pair#*=}"; return 0 ;;
    esac
  done
  printf '%s-control-plane' "$baseline"
}

issue_baseline() {
  local baseline="$1"
  local external
  external="$(external_host_for "$baseline")"
  require_ca

  step "Issuing a certificate for $baseline"
  in_image "
    set -e
    mkdir -p $baseline
    openssl req -newkey rsa:2048 -sha256 -nodes \
      -keyout $baseline/$baseline.key -out $baseline/$baseline.csr \
      -subj '/CN=$baseline/$(echo "$DN_SUFFIX" | tr ',' '/')' 2>/dev/null

    # subjectAltName carries only this baseline's addresses - its Kubernetes service name, its own
    # name, and the external name a peer dials. Not the other baselines', which would let any of
    # them impersonate any other.
    printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:$baseline,DNS:artemis.$baseline.svc.cluster.local,DNS:$external,DNS:localhost\n' > $baseline/ext.cnf

    openssl x509 -req -in $baseline/$baseline.csr -CA ca/ca.crt -CAkey ca/ca.key \
      -CAcreateserial -CAserial ca/serial -out $baseline/$baseline.crt \
      -days $LEAF_DAYS -sha256 -extfile $baseline/ext.cnf 2>/dev/null

    # One keystore holding this baseline's key and certificate. extendedKeyUsage carries BOTH
    # serverAuth and clientAuth because federation is symmetric: the same broker accepts a peer's
    # link and dials out on its own, so it is server and client on the same material.
    openssl pkcs12 -export -in $baseline/$baseline.crt -inkey $baseline/$baseline.key \
      -out $baseline/keystore.p12 -passout pass:$STORE_PASS -name $baseline 2>/dev/null

    rm -f $baseline/$baseline.csr $baseline/ext.cnf
  "
  info "$baseline/keystore.p12 - CN=$baseline,$DN_SUFFIX (valid $LEAF_DAYS days, externally as $external)"
}

# openssl ca needs a configuration file to revoke and to generate a revocation list. It is written on
# demand rather than committed, because it only ever describes the layout above.
write_ca_config() {
  cat > ca/openssl.cnf <<'CONF'
[ ca ]
default_ca = lattice

[ lattice ]
dir               = .
database          = ./ca/index.txt
serial            = ./ca/serial
crlnumber         = ./ca/crlnumber
certificate       = ./ca/ca.crt
private_key       = ./ca/ca.key
default_md        = sha256
default_crl_days  = 30
policy            = any_policy

[ any_policy ]
countryName             = optional
stateOrProvinceName     = optional
organizationName        = optional
organizationalUnitName  = optional
commonName              = optional
emailAddress            = optional
CONF
}

refresh_crl() {
  write_ca_config
  in_image "
    openssl ca -config ca/openssl.cnf -gencrl -out ca/crl.pem 2>/dev/null
  "
  info "ca/crl.pem refreshed - brokers read it via the acceptor's crlPath"
}

revoke_baseline() {
  local baseline="$1"
  require_ca
  [ -f "$baseline/$baseline.crt" ] || die "no certificate for $baseline to revoke"

  step "Revoking $baseline"
  write_ca_config
  in_image "
    openssl ca -config ca/openssl.cnf -revoke $baseline/$baseline.crt 2>/dev/null || true
  "
  refresh_crl

  # Revoking is a statement about the certificate, not about the peers. NOTHING in any other
  # baseline's configuration is touched here - that is the property the deliverable checks.
  info "$baseline is revoked; no peer configuration was changed"
  info "restart the peers' brokers, or wait for the revocation list to be re-read, for it to take effect"
}

# Mints a well-formed certificate signed by an authority nobody trusts, for the harness's
# foreign-authority scenario. It isolates the trust anchor, which a missing or revoked certificate
# cannot - those fail for other reasons. Also the shape a second customer's baseline would present.
mint_foreign() {
  local baseline="$1"
  local external
  external="$(external_host_for "$baseline")"

  step "Minting a certificate for $baseline from an UNTRUSTED authority"
  in_image "
    set -e
    mkdir -p foreign
    openssl req -x509 -newkey rsa:2048 -sha256 -days 30 -nodes \
      -keyout foreign/ca.key -out foreign/ca.crt \
      -subj '/CN=Definitely Not Lattice CA/$(echo "$DN_SUFFIX" | tr ',' '/')' \
      -addext 'basicConstraints=critical,CA:TRUE' \
      -addext 'keyUsage=critical,keyCertSign,cRLSign' 2>/dev/null

    openssl req -newkey rsa:2048 -sha256 -nodes \
      -keyout foreign/$baseline.key -out foreign/$baseline.csr \
      -subj '/CN=$baseline/$(echo "$DN_SUFFIX" | tr ',' '/')' 2>/dev/null

    printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:$baseline,DNS:$external,DNS:localhost\n' > foreign/ext.cnf

    openssl x509 -req -in foreign/$baseline.csr -CA foreign/ca.crt -CAkey foreign/ca.key \
      -CAcreateserial -out foreign/$baseline.crt -days 30 -sha256 -extfile foreign/ext.cnf 2>/dev/null

    openssl pkcs12 -export -in foreign/$baseline.crt -inkey foreign/$baseline.key \
      -out foreign/keystore.p12 -passout pass:$STORE_PASS -name $baseline 2>/dev/null

    rm -f foreign/$baseline.csr foreign/ext.cnf
  "
  info "foreign/keystore.p12 - CN=$baseline,$DN_SUFFIX, signed by an authority no broker trusts"
}

case "${1:-all}" in
  all)
    create_ca
    for b in "${BASELINES[@]}"; do issue_baseline "$b"; done
    step "Generating the (initially empty) revocation list"
    refresh_crl
    step "Done"
    info "material lives beside this script and is git-ignored; regenerate any time with ./issue-certs.sh"
    ;;
  issue)
    [ $# -eq 2 ] || die "usage: ./issue-certs.sh issue <baseline>"
    issue_baseline "$2"
    ;;
  rotate)
    [ $# -eq 2 ] || die "usage: ./issue-certs.sh rotate <baseline>"
    # Rotation is re-issuance against the SAME authority, which is why no peer is touched: peers
    # trust the authority, so a new certificate under an old authority needs no announcement.
    issue_baseline "$2"
    info "restart only $2's broker; no peer is edited (locked #44 holds for rotation too)"
    ;;
  foreign)
    [ $# -eq 2 ] || die "usage: ./issue-certs.sh foreign <baseline>"
    mint_foreign "$2"
    ;;
  revoke)
    [ $# -eq 2 ] || die "usage: ./issue-certs.sh revoke <baseline>"
    revoke_baseline "$2"
    ;;
  clean)
    step "Deleting all generated material"
    rm -rf ca foreign truststore.p12
    for b in "${BASELINES[@]}"; do rm -rf "$b"; done
    info "gone - re-run ./issue-certs.sh to rebuild the authority and every baseline"
    ;;
  *)
    die "unknown command '$1' - one of: all, issue, rotate, revoke, foreign, clean"
    ;;
esac
