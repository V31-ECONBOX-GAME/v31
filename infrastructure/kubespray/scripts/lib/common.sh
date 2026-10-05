#!/usr/bin/env bash
# Shared helpers for the validation scripts. Runs on the control machine only:
# bash 3.2 (macOS) compatible, BSD awk/sed safe, no GNU coreutils, no `timeout`.
# shellcheck shell=bash

[ -n "${V31_COMMON_SH:-}" ] && return 0
V31_COMMON_SH=1

V31_ROOT=${V31_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
V31_SCRIPTS=$V31_ROOT/scripts

# ---------------------------------------------------------------- output ------

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then
  C_RESET=$(printf '\033[0m'); C_BOLD=$(printf '\033[1m'); C_DIM=$(printf '\033[2m')
  C_RED=$(printf '\033[31m'); C_GREEN=$(printf '\033[32m')
  C_YELLOW=$(printf '\033[33m'); C_BLUE=$(printf '\033[34m')
else
  C_RESET=; C_BOLD=; C_DIM=; C_RED=; C_GREEN=; C_YELLOW=; C_BLUE=
fi

log()  { printf '%s\n' "$*" >&2; }
info() { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*" >&2; }
warn() { printf '%swarn%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
err()  { printf '%serror%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
die()  { err "$*"; exit 2; }

hr() { printf '%s\n' "------------------------------------------------------------------------"; }

heading() { printf '\n%s%s%s\n' "$C_BOLD" "$*" "$C_RESET"; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "$1 not found on the control machine${2:+ ($2)}"
}

# Fractional epoch seconds. BSD date has no %N, so prefer perl then python3.
epoch_now() {
  if command -v perl >/dev/null 2>&1; then
    perl -MTime::HiRes=time -e 'printf "%.6f\n", time'
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time; print("%.6f" % time.time())'
  else
    date +%s
  fi
}

# --------------------------------------------------------------- results ------
# One TSV row per check: node, group, check, status, detail.
# Status is one of PASS WARN FAIL SKIP INFO.

results_init() { : >"$1"; }

result_add() { # file node group check status detail
  printf '%s\t%s\t%s\t%s\t%s\n' "$2" "$3" "$4" "$5" "$(printf '%s' "$6" | tr '\t\n' '  ')" >>"$1"
}

# Renders the per-node matrix: one row per group, one column per node.
render_matrix() { # file "node1 node2 ..."
  awk -v nodes="$2" -v red="$C_RED" -v green="$C_GREEN" -v yellow="$C_YELLOW" \
      -v dim="$C_DIM" -v reset="$C_RESET" -v bold="$C_BOLD" '
    function rank(s) {
      if (s == "FAIL") return 4; if (s == "WARN") return 3
      if (s == "SKIP") return 2; if (s == "PASS") return 1
      return 0
    }
    function cell(s) {
      if (s == "FAIL") return red "FAIL" reset
      if (s == "WARN") return yellow " !! " reset
      if (s == "SKIP") return dim "skip" reset
      if (s == "PASS") return green " ok " reset
      if (s == "INFO") return dim "info" reset
      return dim "  - " reset
    }
    BEGIN { FS = "\t"; nn = split(nodes, nd, " ") }
    {
      if (!(($2 SUBSEP "") in seen_group)) { seen_group[$2 SUBSEP ""] = 1; gorder[++ng] = $2 }
      k = $2 SUBSEP $1
      if (rank($4) > rank(worst[k])) worst[k] = $4
    }
    END {
      printf "%s%-26s%s", bold, "check group", reset
      for (i = 1; i <= nn; i++) printf " %-6s", nd[i]
      printf "\n"
      for (g = 1; g <= ng; g++) {
        printf "%-26s", gorder[g]
        for (i = 1; i <= nn; i++) {
          s = worst[gorder[g] SUBSEP nd[i]]
          printf " %-6s", cell(s)
        }
        printf "\n"
      }
    }
  ' "$1"
}

# Non-PASS rows, grouped by node. Pass verbose=1 to include PASS/INFO rows.
render_details() { # file verbose
  awk -v verbose="${2:-0}" -v red="$C_RED" -v yellow="$C_YELLOW" -v dim="$C_DIM" \
      -v reset="$C_RESET" -v bold="$C_BOLD" '
    function colour(s) {
      if (s == "FAIL") return red s reset
      if (s == "WARN") return yellow s reset
      if (s == "SKIP" || s == "INFO") return dim s reset
      return s
    }
    BEGIN { FS = "\t" }
    verbose == "1" || ($4 != "PASS" && $4 != "INFO") {
      if ($1 != last) { printf "\n%s%s%s\n", bold, $1, reset; last = $1 }
      printf "  %-4s %-34s %s\n", colour($4), $3, $5
    }
  ' "$1"
}

# Exit status for a results file: 1 if any FAIL, 0 otherwise.
results_exit_code() { awk -F'\t' '$4 == "FAIL" { f = 1 } END { exit (f ? 1 : 0) }' "$1"; }

results_tally() {
  awk -F'\t' '{ n[$4]++ } END {
    printf "%d pass, %d warn, %d fail, %d skip\n", n["PASS"], n["WARN"], n["FAIL"], n["SKIP"]
  }' "$1"
}

# ------------------------------------------------------------- inventory ------
# Parsed from the Kubespray YAML inventory itself: node names, SSH transport,
# node IPs and group membership have exactly one source of truth.

v31_inventory_dir() {
  if [ -n "${V31_INVENTORY:-}" ]; then printf '%s\n' "$V31_INVENTORY"; return; fi
  for d in "$V31_ROOT"/inventory/*/; do
    [ -f "$d/hosts.yaml" ] && { printf '%s\n' "${d%/}"; return; }
  done
  die "no inventory with hosts.yaml under $V31_ROOT/inventory (set V31_INVENTORY)"
}

_inventory_awk='
function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
{
  line = $0; sub(/\r$/, "", line)
  if (line ~ /^[ \t]*#/ || line ~ /^[ \t]*$/ || line ~ /^---/) next
  match(line, /^ */); ind = RLENGTH
  rest = substr(line, ind + 1)
  if (rest !~ /^[A-Za-z0-9_.\-]+:/) next
  ci = index(rest, ":")
  key = substr(rest, 1, ci - 1)
  val = trim(substr(rest, ci + 1))
  sub(/[ \t]+#.*$/, "", val)
  if (val == "{}" || val == "[]") val = ""
  gsub(/^"|"$|^'"'"'|'"'"'$/, "", val)
  while (top > 0 && indent[top] >= ind) top--
  top++; indent[top] = ind; keys[top] = key
  path = keys[1]
  for (i = 2; i <= top; i++) path = path "." keys[i]

  if (path ~ /^all\.hosts\.[^.]+$/) {
    h = keys[3]
    if (!(h in hseen)) { hseen[h] = 1; horder[++nh] = h }
  } else if (path ~ /^all\.hosts\.[^.]+\.[^.]+$/) {
    attr[keys[3] SUBSEP keys[4]] = val
  } else if (path ~ /^all\.vars\.[^.]+$/) {
    gvar[keys[3]] = val
  } else if (path ~ /^all\.children\.[^.]+\.hosts\.[^.]+$/) {
    member[keys[3] SUBSEP keys[5]] = 1
    if (!(keys[3] in gseen)) { gseen[keys[3]] = 1; gorder[++ngp] = keys[3] }
  } else if (path ~ /^all\.children\.[^.]+\.children\.[^.]+$/) {
    child[keys[3] SUBSEP keys[5]] = 1
    if (!(keys[3] in gseen)) { gseen[keys[3]] = 1; gorder[++ngp] = keys[3] }
  }
}
END {
  for (pass = 0; pass < 3; pass++)
    for (g = 1; g <= ngp; g++)
      for (c = 1; c <= ngp; c++)
        if ((gorder[g] SUBSEP gorder[c]) in child)
          for (i = 1; i <= nh; i++)
            if ((gorder[c] SUBSEP horder[i]) in member) member[gorder[g] SUBSEP horder[i]] = 1
  for (k in gvar) printf "V\t%s\t%s\n", k, gvar[k]
  for (i = 1; i <= nh; i++) {
    h = horder[i]; roles = ""
    for (g = 1; g <= ngp; g++)
      if ((gorder[g] SUBSEP h) in member) roles = (roles == "" ? gorder[g] : roles "," gorder[g])
    printf "H\t%s\t%s\t%s\t%s\t%s\n", h, attr[h SUBSEP "ansible_host"], attr[h SUBSEP "ip"], roles, attr[h SUBSEP "etcd_member_name"]
  }
}'

# Cached parse of the inventory. H rows: name, ansible_host, ip, groups.
inventory_load() {
  [ -n "${_V31_INV:-}" ] && return 0
  local dir; dir=$(v31_inventory_dir)
  V31_INVENTORY_DIR=$dir
  [ -f "$dir/hosts.yaml" ] || die "$dir/hosts.yaml not found"
  _V31_INV=$(awk "$_inventory_awk" "$dir/hosts.yaml")
  [ -n "$_V31_INV" ] || die "could not parse $dir/hosts.yaml"
}

inventory_hosts() { # name \t ansible_host \t ip \t groups \t etcd_member_name
  inventory_load
  printf '%s\n' "$_V31_INV" | awk -F'\t' '$1 == "H" { print $2 "\t" $3 "\t" $4 "\t" $5 "\t" $6 }'
}

inventory_names() { inventory_hosts | cut -f1 | tr '\n' ' ' | sed -E 's/ $//'; }

inventory_field() { # name index(2=host,3=ip,4=groups,5=etcd_member_name)
  inventory_hosts | awk -F'\t' -v n="$1" -v i="$2" '$1 == n { print $i }'
}

inventory_var() { # ansible_user / ansible_port / ansible_ssh_private_key_file ...
  inventory_load
  printf '%s\n' "$_V31_INV" | awk -F'\t' -v k="$1" '$1 == "V" && $2 == k { print $3; exit }'
}

inventory_in_group() { # name group
  case ",$(inventory_field "$1" 4)," in *",$2,"*) return 0 ;; *) return 1 ;; esac
}

# Flat `key: value` lookup across the committed group_vars, so the validation
# asserts what the install was told to do rather than a second copy of it.
cfg_get() { # key [extra files...]
  inventory_load
  local key=$1; shift
  local f v
  for f in "$@" "$V31_INVENTORY_DIR"/group_vars/*/*.yml "$V31_INVENTORY_DIR"/group_vars/*/*.yaml \
           "$V31_ROOT"/istio/versions.yml; do
    [ -f "$f" ] || continue
    v=$(sed -n -E "s/^${key}:[[:space:]]+(.*)$/\1/p" "$f" | head -n1)
    [ -n "$v" ] || continue
    v=${v%%#*}
    v=$(printf '%s' "$v" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/')
    [ -n "$v" ] && { printf '%s\n' "$v"; return 0; }
  done
  return 1
}

# ------------------------------------------------------------------- ssh ------

v31_ssh_opts() {
  inventory_load
  local key port user
  key=$(inventory_var ansible_ssh_private_key_file)
  port=$(inventory_var ansible_port)
  user=$(inventory_var ansible_user)
  case $key in "~"*) key=$HOME${key#\~} ;; esac
  V31_SSH_KEY=${V31_SSH_KEY:-$key}
  V31_SSH_PORT=${V31_SSH_PORT:-${port:-22}}
  V31_SSH_USER=${V31_SSH_USER:-$user}
  [ -n "$V31_SSH_USER" ] || die "ansible_user missing from the inventory"
  [ -f "$V31_SSH_KEY" ] || die "ssh key $V31_SSH_KEY not found"
}

# ssh to a node by inventory name, over ansible_host (OrbStack's SSH gateway).
ssh_node() { # name cmd...
  v31_ssh_opts
  local name=$1; shift
  local host; host=$(inventory_field "$name" 2)
  [ -n "$host" ] || die "unknown node $name"
  ssh -n -o BatchMode=yes -o ConnectTimeout="${V31_SSH_CONNECT_TIMEOUT:-10}" \
      -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR \
      -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
      -i "$V31_SSH_KEY" -p "$V31_SSH_PORT" "$V31_SSH_USER@$host" "$@"
}

# Feeds a local script to a node's bash; arguments reach it as "$1", "$2", ...
ssh_node_script() { # name script_path [args...]
  v31_ssh_opts
  local name=$1 script=$2; shift 2
  local host; host=$(inventory_field "$name" 2)
  [ -n "$host" ] || die "unknown node $name"
  [ -f "$script" ] || die "$script not found"
  local q='' a
  for a in "$@"; do q="$q $(shell_quote "$a")"; done
  ssh -o BatchMode=yes -o ConnectTimeout="${V31_SSH_CONNECT_TIMEOUT:-10}" \
      -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR \
      -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
      -i "$V31_SSH_KEY" -p "$V31_SSH_PORT" "$V31_SSH_USER@$host" \
      "bash -s --$q" <"$script"
}

shell_quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# Runs fn per node in parallel, each writing to $outdir/<node>.<ext>.
fanout() { # outdir ext fn nodes...
  local outdir=$1 ext=$2 fn=$3; shift 3
  local n pids=''
  for n in "$@"; do
    "$fn" "$n" >"$outdir/$n.$ext" 2>"$outdir/$n.err" &
    pids="$pids $!"
  done
  local p rc=0
  for p in $pids; do wait "$p" || rc=1; done
  return $rc
}
