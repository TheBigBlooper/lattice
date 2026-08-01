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
# Pure awk, deliberately: neither `yq` nor `jq` is present on a developer machine here, and a gate
# that has to be installed before it runs is a gate that gets skipped.

function indent_of(line,   pos) {
  pos = match(line, /[^ ]/)
  return (pos == 0) ? 0 : pos - 1
}

BEGIN {
  duplicates = 0
  doc = 1
}

# A new document resets everything: kind, name, and whatever env block was open.
/^---[ \t]*$/ {
  doc++
  kind = ""
  metaname = ""
  container = ""
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

# A container's name is the list entry that opens it. Tracked so the message says WHICH container,
# since a pod template holds several and they do not share an env block.
/^[ \t]*- name:[ \t]/ && in_env == 0 {
  candidate = $0
  sub(/^[ \t]*- name:[ \t]*/, "", candidate)
  sub(/[ \t]*$/, "", candidate)
  container = candidate
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
  if (duplicates > 0) {
    printf("\n%d duplicate environment key(s). Kubernetes keeps the last and Helm 4 rejects the manifest, so this is an install failure rather than a style point.\n",
           duplicates) > "/dev/stderr"
    exit 1
  }
}
