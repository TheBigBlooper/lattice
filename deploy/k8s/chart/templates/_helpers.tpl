{{/*
Shared naming and labelling. One definition each, used everywhere - the chart equivalent of the
project's reuse rule: a second way to build a name is a second thing that can drift.
*/}}

{{- define "lattice.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Release-qualified name. Two baselines in ONE cluster (which the local kind run does, and which a
real deployment usually does not) must not collide, so every object is named for its release.
*/}}
{{- define "lattice.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "lattice.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "lattice.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{/* Which baseline this object belongs to. The one label worth having on everything: in a cluster
     holding more than one baseline it is the difference between "restart the gateway" and
     "restart hub-east's gateway". */}}
lattice.io/baseline: {{ .Values.baseline.clusterId }}
{{- end -}}

{{/*
The image reference for a service. Kept here so the "no floating tag" rule is enforced in one place
rather than repeated per service: an untagged image would make "which baseline is running" a
question nobody can answer from the cluster.
*/}}
{{- define "lattice.image" -}}
{{- $tag := .root.Values.image.tag | default .root.Chart.AppVersion -}}
{{- if .root.Values.image.registry -}}
{{- printf "%s/lattice/%s:%s" .root.Values.image.registry .name $tag -}}
{{- else -}}
{{- printf "lattice/%s:%s" .name $tag -}}
{{- end -}}
{{- end -}}

{{/*
Where Keycloak reaches its database. An explicit host wins; otherwise it is the in-cluster MySQL
this chart deploys. Defined once because the value is read by the Keycloak Deployment and asserted
by the guard below, and two ways to derive one address is one way for them to disagree.
*/}}
{{- define "lattice.keycloakDbHost" -}}
{{- if .Values.keycloak.database.host -}}
{{- .Values.keycloak.database.host -}}
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
{{- if not .Values.keycloak.devMode -}}
{{- if and (not .Values.keycloak.database.deployInCluster) (not .Values.keycloak.database.host) -}}
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
*/}}
{{- define "lattice.commonEnv" -}}
- name: HTTP_PORT
  value: "8080"
- name: KEYCLOAK_URL
  value: {{ .Values.keycloak.hostname | quote }}
- name: KEYCLOAK_INTERNAL_URL
  value: http://{{ include "lattice.fullname" . }}-keycloak:8080
- name: KEYCLOAK_REALM
  value: {{ .Values.keycloak.realm | quote }}
- name: BASELINE_VERSION
  value: {{ .Chart.AppVersion | quote }}
{{/* Publishes the OpenAPI document at /docs/json. Set false for a production baseline - serving it
     there publishes the exact shape of every endpoint to anyone who can reach the service. */}}
- name: API_DOCS_ENABLED
  value: {{ .Values.apiDocs.enabled | quote }}
{{- end -}}
