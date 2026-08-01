# Fails when a container declares the same environment variable name twice.
#
# WHY THIS EXISTS. `BASELINE_VERSION` was declared in two places inside ONE chart - `_helpers.tpl`
# and `services.yaml`. Kubernetes takes the last entry, so the duplicate changed no behaviour and
# nothing complained, until Helm 4 began applying server-side and REJECTED it outright. The chart
# could not install a single service, and the reason it had gone unnoticed for so long is that every
# earlier deployment happened to pass an empty service list. This check would have caught it at
# render time, on the commit that introduced it, with no cluster involved.
#
# ITS LIMITS, stated here rather than implied. It covers ONE of the three defect shapes behind
# locked #77. It cannot see a value present in one deployment mechanism and absent from another
# (`CORS_ALLOWED_ORIGINS`, which existed in compose and nowhere in the chart), nor a list read by
# index in three readers where only two were updated. Claiming it enforces derive-over-declare would
# be the overstatement this project's design docs exist to avoid.
#
# Reads rendered manifests on stdin. Exits non-zero, having named every offender, when any container
# repeats an environment variable name.
#
# A CHECK NOBODY HAS SEEN FAIL IS NOT A CHECK. `mesh-clusters.sh check` runs it against
# testdata/duplicate-env.yaml, which must fail, and testdata/legal-env.yaml, which must pass and
# holds every shape that looks like a duplicate and is not: two containers declaring the same key,
# an initContainer doing likewise, a `valueFrom.secretKeyRef` whose nested `name` is a Secret rather
# than a variable, and `ports` entries.
#
# Pure awk, deliberately: neither `yq` nor `jq` is present on a developer machine here, and a gate
# that has to be installed before it runs is a gate that gets skipped.

function indent_of(line,   pos) {
  pos = match(line, /[^ ]/)
  return (pos == 0) ? 0 : pos - 1
}

BEGIN {
  duplicates = 0
  contextless = 0
  doc = 1
}

# Closes the container currently being read and judges it. Separate from the parsing so every way a
# container can end - the next one, the end of the list, a new document, end of file - reaches the
# same verdict rather than three of the four doing so.
function close_container() {
  if (container == "") return
  if (has_context) { container = ""; return }

  # NO EXEMPTION BY KIND, and there used to be one. A Job's spec.template is immutable, so the three
  # data jobs could not take a securityContext without breaking `helm upgrade` on every baseline that
  # already had them, and this check waved them through - printing the exemption rather than hiding
  # it, which was the least bad version of a hole. The jobs are suspended CronJobs now, whose
  # jobTemplate is mutable, so the constraint is gone and so is the branch: left behind it would have
  # gone on excusing whatever context-less container happened to be a Job.
  contextless++
  printf("container %s in %s/%s declares no securityContext (document %d)\n",
         container, kind, metaname, doc) > "/dev/stderr"
  container = ""
}

# A new document resets everything: kind, name, and whatever env block was open.
/^---[ \t]*$/ {
  close_container()
  doc++
  kind = ""
  metaname = ""
  container = ""
  in_list = 0
  in_env = 0
  delete seen
  next
}

{
  ind = indent_of($0)
}

# Enough context to name the offender. `kind` is always at column zero; the first `name` two spaces
# in is metadata's, which is the one an operator would search for.
ind == 0 && /^kind:[ \t]/ { kind = $2; next }
ind == 2 && /^  name:[ \t]/ && metaname == "" { metaname = $2 }

# A container list opens here, and its indentation is what bounds it.
/^[ \t]*(initContainers|containers):[ \t]*$/ {
  close_container()
  in_list = 1
  list_indent = ind
  next
}

# A container starts: a list item exactly one level inside the list it belongs to. DEPTH is what
# distinguishes it from `- name: http` under ports or `- name: config` under volumes, which sit
# deeper or in a different list - matching `- name:` anywhere would count both as containers.
in_list == 1 && ind == list_indent + 2 && /^[ \t]*- name:[ \t]/ {
  close_container()
  candidate = $0
  sub(/^[ \t]*- name:[ \t]*/, "", candidate)
  sub(/[ \t]*$/, "", candidate)
  container = candidate
  field_indent = ind + 2
  has_context = 0
  next
}

# The list ends at the first non-blank line at or left of the column it opened in.
in_list == 1 && $0 !~ /^[ \t]*$/ && ind <= list_indent {
  close_container()
  in_list = 0
}

# Only at the container's own field depth. A pod-level `spec.securityContext` sits shallower and is a
# different setting entirely - counting it would let a container with none pass on its pod's behalf.
in_list == 1 && container != "" && ind == field_indent && /^[ \t]*securityContext:[ \t]*$/ {
  has_context = 1
}

# An env block opens here. Its indentation is what closes it again: anything at or left of this
# column belongs to the container, not to the list.
/^[ \t]*env:[ \t]*$/ {
  in_env = 1
  env_indent = ind
  delete seen
  next
}

in_env == 1 {
  # A blank line inside a block is not the end of it, but a shallower non-blank line is.
  if ($0 ~ /^[ \t]*$/) { next }
  if (ind <= env_indent) { in_env = 0 }
}

# Only `- name:` entries count. The nested `name:` under `valueFrom.secretKeyRef` carries no dash,
# so it is a Secret's name rather than a variable's and must not be collected.
in_env == 1 && /^[ \t]*- name:[ \t]/ {
  var = $0
  sub(/^[ \t]*- name:[ \t]*/, "", var)
  sub(/[ \t]*$/, "", var)
  gsub(/^["']|["']$/, "", var)

  if (var in seen) {
    duplicates++
    printf("duplicate env key %s in container %s of %s/%s (document %d, first seen line %d, again line %d)\n",
           var, container, kind, metaname, doc, seen[var], FNR) > "/dev/stderr"
  } else {
    seen[var] = FNR
  }
}

END {
  close_container()

  if (duplicates > 0) {
    printf("\n%d duplicate environment key(s). Kubernetes keeps the last and Helm 4 rejects the manifest, so this is an install failure rather than a style point.\n",
           duplicates) > "/dev/stderr"
  }
  if (contextless > 0) {
    printf("\n%d container(s) declare no securityContext. The chart is otherwise unscanned for this - a Helm template is not valid YAML, so a file-reading scanner skips it entirely.\n",
           contextless) > "/dev/stderr"
  }
  if (duplicates > 0 || contextless > 0) exit 1
}
