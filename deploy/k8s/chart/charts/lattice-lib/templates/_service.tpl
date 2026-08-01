{{/*
One Vert.x service: its Deployment and its Service.

WHY THIS IS IN THE LIBRARY AND NOT COPIED INTO EACH SERVICE SUBCHART. The three services differ only
in name, image, and whether they carry Elasticsearch or mesh configuration. Three copies of this
template would drift the moment one of them gained a probe or an environment variable the others did
not - the same defect a bespoke second component is (CLAUDE.md, reuse over rebuild), just wearing
YAML. The subchart split is about giving each component its own values and its own `enabled` flag,
not about giving each one its own copy of the same manifest.

So a service subchart is deliberately thin: it declares WHAT it is in values.yaml and includes this.
Adding a fourth service is a new subchart of four short files, none of which is a manifest.

Reads from its own `.Values`:
  serviceName         the component name, which is also the image name and the label
  replicas
  needsElasticsearch  gate startup on the datastore being reachable
  isMeshGateway       carry the mesh and rollup configuration
*/}}
{{- define "lattice.serviceWorkload" -}}
{{- $g := .Values.global -}}
{{- $full := include "lattice.fullname" . -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $full }}-{{ .Values.serviceName }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "lattice.labels" . | nindent 4 }}
    baseline-component: service
    lattice.io/service: {{ .Values.serviceName }}
spec:
  replicas: {{ .Values.replicas | default 1 }}
  selector:
    matchLabels:
      {{- include "lattice.serviceSelector" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "lattice.podLabels" . | nindent 8 }}
        baseline-component: service
        lattice.io/service: {{ .Values.serviceName }}
    spec:
      {{- if .Values.needsElasticsearch }}
      # Services gate startup on Elasticsearch being reachable, so one never starts against a
      # not-ready datastore and latches a failed readiness state. The mesh-gateway deliberately has
      # no such gate: it is the sole mesh participant (locked #42) and must keep serving through a
      # broker outage, rejoining on its own when the broker returns.
      initContainers:
        - name: wait-for-elasticsearch
          image: {{ $g.elasticsearch.image | quote }}
          command:
            - sh
            - -c
            - |
              until curl -fsS "http://{{ $full }}-elasticsearch:9200/_cluster/health" >/dev/null 2>&1; do
                echo "waiting for elasticsearch"
                sleep 5
              done
      {{- end }}
      containers:
        - name: {{ .Values.serviceName }}
          image: {{ include "lattice.image" (dict "ctx" . "name" .Values.serviceName) | quote }}
          imagePullPolicy: {{ $g.image.pullPolicy }}
          env:
            {{- include "lattice.commonEnv" . | nindent 12 }}
            {{- if .Values.needsElasticsearch }}
            - name: ELASTICSEARCH_URL
              value: http://{{ $full }}-elasticsearch:9200
            {{- end }}
            # Baseline identity, given to EVERY service rather than only the one that announces it.
            # A service that cannot name its own baseline cannot say so in anything it serves or
            # logs, and an operator with three baselines open has no way to tell which one answered.
            - name: CLUSTER_ID
              value: {{ $g.baseline.clusterId | quote }}
            - name: REGION
              value: {{ $g.baseline.region | quote }}
            - name: BASELINE_VERSION
              value: {{ $g.baseline.version | quote }}
            {{- if .Values.isMeshGateway }}
            # Mesh wiring, which only the announcing service needs. Single-valued by design: a
            # service addresses its OWN broker only, and peer brokers are reached by federation.
            - name: CONSOLE_URL
              value: {{ $g.baseline.consoleUrl | quote }}
            - name: API_BASE_URL
              value: {{ $g.baseline.apiBaseUrl | quote }}
            # THIS baseline's own broker, always - never a peer list. Federation is broker-to-broker,
            # so a service never addresses another baseline's broker (locked #44). It uses the
            # internal 61616 acceptor with a password; the mutual-TLS acceptor is for peers only.
            - name: ARTEMIS_URL
              value: tcp://{{ $full }}-artemis:61616
            - name: ARTEMIS_USER
              valueFrom:
                secretKeyRef:
                  name: {{ $g.artemis.credentialsSecret }}
                  key: username
            - name: ARTEMIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ $g.artemis.credentialsSecret }}
                  key: password
            # THE UMBRELLA AUTHORS THIS LIST, and that is load-bearing rather than incidental: it is
            # what the gateway polls to compute the health rollup that rides the mesh (locked #43).
            # Split across subcharts with no umbrella, no chart would know the full set and the
            # rollup would have no author.
            - name: CLUSTER_SERVICES
              value: >-
                {{- $svcs := list -}}
                {{- range $g.services -}}
                {{- if not .isMeshGateway -}}
                {{- $svcs = append $svcs (printf "%s=http://%s-%s:8080" .name $full .name) -}}
                {{- end -}}
                {{- end }}
                {{ join "," $svcs }}
            # The infrastructure this baseline reports on beside its services, as name:kind=url. The
            # kind selects the probe, so a deployment may label a component whatever it calls it. The
            # Artemis entry carries no URL on purpose: its state is the gateway's own broker
            # connection, and a second broker check would duplicate a signal that already exists.
            # Keycloak is probed on its MANAGEMENT Service, not the main one: it serves /health/ready
            # there, and that Service publishes not-ready addresses, so the probe still reaches it
            # when it has taken itself out of rotation - which is precisely when there is something
            # to report.
            - name: CLUSTER_INFRASTRUCTURE
              value: {{ printf "elasticsearch:elasticsearch=http://%s-elasticsearch:9200,artemis:artemis=,keycloak:keycloak=http://%s-keycloak-management:9000" $full $full | quote }}
            {{- end }}
          ports:
            - name: http
              containerPort: 8080
          # /health and /readiness are unauthenticated by contract, which is what lets kubelet call
          # them at all - every other /api/v1 operation requires a bearer token.
          readinessProbe:
            httpGet:
              path: {{ $g.probes.readinessPath }}
              port: http
            initialDelaySeconds: {{ $g.probes.initialDelaySeconds }}
            periodSeconds: {{ $g.probes.periodSeconds }}
            failureThreshold: 30
          livenessProbe:
            httpGet:
              path: {{ $g.probes.livenessPath }}
              port: http
            initialDelaySeconds: 30
            periodSeconds: {{ $g.probes.periodSeconds }}
          resources:
            {{- toYaml $g.resources | nindent 12 }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $full }}-{{ .Values.serviceName }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "lattice.labels" . | nindent 4 }}
    baseline-component: service
    lattice.io/service: {{ .Values.serviceName }}
spec:
  {{/* A service is exposed to the host only if serviceNodePorts names it. The console is a static
       bundle whose API addresses are baked in at build time, so its browser calls this baseline's
       orders, inventory and gateway DIRECTLY - each therefore needs an address reachable from
       outside the cluster in a local multi-cluster run.

       A MAP keyed by service name, not a value on each subchart: it is set per environment by
       whoever exposes the baseline, and keeping the whole mapping in one place is what lets the
       three ports be read together rather than hunted across three files. */}}
  {{- $nodePort := get ($g.serviceNodePorts | default dict) .Values.serviceName }}
  type: {{ if $nodePort }}NodePort{{ else }}ClusterIP{{ end }}
  selector:
    {{- include "lattice.serviceSelector" . | nindent 4 }}
  ports:
    - name: http
      port: 8080
      targetPort: http
      {{- if $nodePort }}
      nodePort: {{ $nodePort }}
      {{- end }}
{{- end -}}
