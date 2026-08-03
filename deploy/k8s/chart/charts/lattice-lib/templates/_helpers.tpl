{{/*
Shared naming and labelling for every subchart. One definition each, used everywhere - the chart
equivalent of the project's reuse rule: a second way to build a name is a second thing that drift.

EVERYTHING HERE READS `.Values.global`, never `.Values`. A subchart's `.Values` is its own section of
the umbrella's values, so a helper reaching for `.Values.baseline` would resolve in the umbrella and
silently render empty in every subchart. Helm's `global` block is the one scope both can see.
*/}}

{{/*
The chart-wide name, FIXED rather than taken from `.Chart.Name`.

This is the load-bearing line of the whole split. In a subchart `.Chart.Name` is the SUBCHART's name,
so deriving from it would rename every object from `hub-central-lattice-orders` to
`hub-central-orders` - breaking mesh-clusters.sh, the CLUSTER_SERVICES addresses each service is
reached at, and the selectors on already-installed Deployments, which Kubernetes will not let you
change. The baseline is what these objects belong to, and the baseline is still one thing.
*/}}
{{- define "lattice.name" -}}
{{- default "lattice" ((.Values.global).nameOverride) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Release-qualified name. Two baselines in ONE cluster (which a single-cluster run does, and which the
three-cluster local run does not) must not collide, so every object is named for its release.
*/}}
{{- define "lattice.fullname" -}}
{{- if ((.Values.global).fullnameOverride) -}}
{{- (.Values.global).fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := include "lattice.name" . -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
The labels every object carries.

`app.kubernetes.io/version` comes from the BASELINE version rather than from `.Chart.AppVersion`, and
that is a deliberate simplification rather than an accident of the split. The two were always the
same string written in two files, so a subchart would either have to repeat it nine more times or
render a different value from the umbrella. The baseline version is also the more truthful source:
it is the thing an operator names when they say which baseline hub-east is running.

`helm.sh/chart` is the ONE label that legitimately differs per subchart - it says which chart
produced the object, which is now `orders-0.1.0` rather than `lattice-0.1.0`. It is metadata and
appears in no selector, so nothing matches on it.
*/}}
{{- define "lattice.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "lattice.podLabels" . }}
{{- end -}}

{{/*
The labels that belong on a POD, which is everything above except `helm.sh/chart`.

WHY THAT ONE IS EXCLUDED, and it is not tidiness. `helm.sh/chart` carries the chart VERSION, and a
label in a pod template is part of the template: change it and Kubernetes rolls every pod. So while
it was there, bumping the chart from 0.1.0 to 0.1.1 restarted every pod in every baseline even when
nothing about the pods had changed - a real cost paid on every upgrade, for a label recording which
chart installed the object. The OBJECT is the right place to record that; the pod is not.

`app.kubernetes.io/version` deliberately stays: it derives from the baseline version, and a new
baseline version genuinely is a new thing to run, so rolling the pods is the correct response.

Nothing selects on `helm.sh/chart` - selectors use name, instance, and either the service name or
the component - so removing it from the template cannot orphan a pod or collide with a Deployment's
immutable selector.
*/}}
{{- define "lattice.podLabels" -}}
app.kubernetes.io/name: {{ include "lattice.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ (.Values.global).baseline.version | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{/* Which baseline this object belongs to. The one label worth having on everything: in a cluster
     holding more than one baseline it is the difference between "restart the gateway" and
     "restart hub-east's gateway". */}}
lattice.io/baseline: {{ (.Values.global).baseline.clusterId }}
{{- end -}}

{{/*
The selector for one Vert.x service. Split out from the labels because a selector is IMMUTABLE on an
installed Deployment: if it were assembled inline per subchart, one subchart adding a label would
make that Deployment un-upgradable, and the error arrives at deploy time on a running baseline.
*/}}
{{- define "lattice.serviceSelector" -}}
app.kubernetes.io/name: {{ include "lattice.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
lattice.io/service: {{ .Values.serviceName }}
{{- end -}}

{{/*
The image reference for a Lattice-built service. Kept here so the "no floating tag" rule is enforced
in one place rather than repeated per subchart: an untagged image would make "which baseline is
running" a question nobody can answer from the cluster.
*/}}
Takes an explicit dict - `(dict "ctx" . "name" "orders")` - because the two callers name the image
differently: a service subchart from its own values, and the data jobs from a literal, since they run
on a service image without being that service.
*/}}
{{- define "lattice.image" -}}
{{- $img := (.ctx.Values.global).image -}}
{{- $tag := $img.tag | default ((.ctx.Values.global).baseline.version) -}}
{{- if $img.registry -}}
{{- printf "%s/lattice/%s:%s" $img.registry .name $tag -}}
{{- else -}}
{{- printf "lattice/%s:%s" .name $tag -}}
{{- end -}}
{{- end -}}

{{/*
Where Keycloak reaches its database. An explicit host wins; otherwise it is the in-cluster MySQL the
keycloak-db subchart deploys. Defined once because the value is read by the Keycloak Deployment and
asserted by the guard below, and two ways to derive one address is one way for them to disagree.
*/}}
{{- define "lattice.keycloakDbHost" -}}
{{- if (.Values.global).keycloak.database.host -}}
{{- (.Values.global).keycloak.database.host -}}
{{- else -}}
{{- printf "%s-keycloak-db" (include "lattice.fullname" .) -}}
{{- end -}}
{{- end -}}

{{/*
Fails the render when a persisted baseline has nowhere to persist to - deployInCluster false with no
host set. Helm's alternative is to install a Keycloak that starts, cannot reach a database, and
crash-loops, which reports the problem as a runtime fault rather than the configuration error it is.
The chart already refuses to ship a placeholder that deploys; this is the same rule for config.
*/}}
{{- define "lattice.keycloakDbGuard" -}}
{{- $kc := (.Values.global).keycloak -}}
{{- if not $kc.devMode -}}
{{- if and (not $kc.database.deployInCluster) (not $kc.database.host) -}}
{{- fail "keycloak.devMode is false, so this baseline persists identity - set keycloak.database.host to an existing database, or keycloak.database.deployInCluster to true." -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
The environment every service in this baseline shares.

Two Keycloak addresses, deliberately, and they are not interchangeable:
  KEYCLOAK_URL           the address tokens are ISSUED under, so it is what the issuer claim says
                         and what a validator compares against.
  KEYCLOAK_INTERNAL_URL  the in-cluster address used to FETCH signing keys.
They are two addresses for the same realm. Collapsing them breaks either browser login or key
fetching, depending on which one survives - and it breaks it at runtime, not at deploy time.

BASELINE_VERSION is deliberately NOT here. The service subchart sets it from the baseline values,
which is the right source - a baseline's version is a deployment fact an operator sets, not a
property of the chart. Both were emitted once, and Kubernetes takes the last entry, so the values one
was already winning; what removing it fixed was the INSTALL, because Helm 4 applies server-side and
rejects a duplicate env key outright. `mesh-clusters.sh check` now catches that shape at render time.
*/}}
{{- define "lattice.commonEnv" -}}
{{- $g := .Values.global -}}
- name: HTTP_PORT
  value: "8080"
- name: KEYCLOAK_URL
  value: {{ $g.keycloak.hostname | quote }}
- name: KEYCLOAK_INTERNAL_URL
  value: http://{{ include "lattice.fullname" . }}-keycloak:8080
- name: KEYCLOAK_REALM
  value: {{ $g.keycloak.realm | quote }}
{{/* Publishes the OpenAPI document at /docs/json. Set false for a production baseline - serving it
     there publishes the exact shape of every endpoint to anyone who can reach the service. */}}
- name: API_DOCS_ENABLED
  value: {{ $g.apiDocs.enabled | quote }}
{{/* The origin the console's browser sends. Every /api/v1 call from the console is cross-origin -
     the console and the services are different ports, and in a real deployment different hostnames -
     so without this the browser refuses each one before it is sent, and the console reports the
     baseline as unreachable while every service is serving perfectly.

     Derived from baseline.consoleUrl rather than configured separately, because they are the same
     fact: the address the console is served at IS the origin it sends. Two settings could disagree,
     and the failure that produces looks like an outage rather than a mismatch. */}}
- name: CORS_ALLOWED_ORIGINS
  value: {{ $g.corsAllowedOrigins | default $g.baseline.consoleUrl | quote }}
{{/* Metrics on their own port (locked #78). Declared for every service rather than per component,
     because a service that reported metrics only where someone remembered to switch them on is a
     monitoring surface with holes in exactly the places nobody checked. */}}
- name: METRICS_ENABLED
  value: {{ $g.metrics.enabled | quote }}
- name: METRICS_PORT
  value: {{ $g.metrics.port | quote }}
{{- end -}}
