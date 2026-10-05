#!/usr/bin/env bash
# Node address drift detector, run from the macOS control machine, any day.
# Answers one question: does every node's CURRENT IPv4 address still match what
# the inventory, Kubernetes, etcd and Calico each believe it to be?
#
#   scripts/ip-drift.sh              # per-node table, then details for anything not PASS
#   scripts/ip-drift.sh -v           # every row, including PASS
#   scripts/ip-drift.sh --suggest    # also print inventory blocks with the live addresses
#   scripts/ip-drift.sh --no-cluster # skip Kubernetes, etcd and Calico; inventory only
#
# Read-only everywhere. Works before the cluster exists: the three cluster-side
# sources are reported SKIP with the reason, and the inventory comparison still runs,
# because that is the one that decides whether `cluster.yml` will even start.
#
# Why each source is asked separately, rather than trusting one of them:
#
#   inventory   hosts.yaml `ip` becomes Kubespray's main_ip. A mismatch makes
#               roles/kubernetes/preinstall/0040-verify-settings.yml abort with
#               "do not contain", so every playbook stops until it is corrected.
#   kubernetes  the Node's InternalIP, written by kubelet --node-ip at registration
#               and never revisited. Stale means kubectl logs/exec and metrics break.
#   etcd        the member peer URL, held inside etcd's own data, not in a file.
#               Re-rendering /etc/etcd.env does not change it, and neither does a
#               full cluster.yml; only `etcdctl member update` does. This is the one
#               source that never self-heals.
#   calico      Node.spec.bgp.ipv4Address. Self-heals on calico-node restart because
#               calico_ip_auto_method re-detects, so a mismatch here means the pod
#               has not restarted since the address moved.
#
# The VXLAN tunnel address is reported but never compared: it is allocated out of
# kube_pods_subnet, not from the node's subnet, so it cannot drift with the node
# address. Only that it exists is worth knowing.
#
# Depth on the VIP itself is scripts/kube-vip.sh; the repair procedures are in
# docs/ip-drift.md.
#
# Exit 0 no drift (warnings allowed), 1 drift found, 2 could not run.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)/common.sh"
. "$V31_ROOT/versions.env"

VERBOSE=0
SUGGEST=0
USE_CLUSTER=1

while [ $# -gt 0 ]; do
  case $1 in
    -v | --verbose) VERBOSE=1 ;;
    --suggest) SUGGEST=1 ;;
    --no-cluster) USE_CLUSTER=0 ;;
    -h | --help) sed -n '2,38p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done

need_cmd ssh
need_cmd awk

inventory_load
NODES=$(inventory_names)
[ -n "$NODES" ] || die "no hosts in $V31_INVENTORY_DIR/hosts.yaml"

TMP=$(mktemp -d "${TMPDIR:-/tmp}/v31-ip-drift.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
RESULTS=$TMP/results.tsv
results_init "$RESULTS"
add() { result_add "$RESULTS" "$@"; }

# Every name below comes from the committed configuration, so this script holds no
# second copy of an address, an interface or a subnet.
IFACE=${V31_NODE_INTERFACE:-$(cfg_get kube_vip_interface || echo eth0)}
BIN_DIR=$(cfg_get bin_dir || echo /usr/local/bin)
PODS_CIDR=$(cfg_get kube_pods_subnet || echo 10.233.64.0/18)
PODS_PREFIX=$(printf '%s' "$PODS_CIDR" | cut -d. -f1-2)
VIP=${V31_KUBE_VIP_ADDRESS:-$KUBE_VIP_ADDRESS}
GATEWAY_VIP=${V31_ISTIO_GATEWAY_VIP:-$(awk -F'"' \
  '/kube-vip\.io\/loadbalancerIPs/ {print $2; exit}' \
  "$V31_ROOT/istio/manifests/ingress-gateway.yaml" 2>/dev/null)}

# The addresses under one top-level list key, and no other key's.
yaml_ip_list() { # key file
  [ -f "$2" ] || return 0
  awk -v key="$1" '
    $0 ~ "^" key ":" { inlist = 1; next }
    inlist && /^[^[:space:]#]/ { inlist = 0 }
    inlist && match($0, /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/) {
      printf "%s ", substr($0, RSTART, RLENGTH)
    }
  ' "$2"
}

KUBECTL=${V31_KUBECTL:-}
if [ -z "$KUBECTL" ]; then
  if [ -x "$V31_ROOT/bin/kubectl" ]; then KUBECTL=$V31_ROOT/bin/kubectl; else KUBECTL=kubectl; fi
fi
export KUBECONFIG=${KUBECONFIG:-$V31_INVENTORY_DIR/artifacts/admin.conf}
k() { "$KUBECTL" --request-timeout="${V31_KUBECTL_TIMEOUT:-20s}" "$@" 2>/dev/null; }

CLUSTER=0
CLUSTER_WHY=

# ------------------------------------------------------------------------ live ---
# The only source that is always reachable: ansible_host is OrbStack's host-side
# gateway on a different network, so a node whose cluster address moved is still
# one ssh away.

live_probe() {
  ssh_node "$1" "ip -4 -o addr show $IFACE; echo ---; ip -4 -o addr show scope global"
}

collect_live() {
  heading "live addresses"
  info "reading $IFACE from every node over the OrbStack ssh gateway"
  fanout "$TMP" live live_probe $NODES || true

  local n
  for n in $NODES; do
    # 3: eth0  inet 192.168.139.212/24 metric 100 ... scope global dynamic eth0\
    #          valid_lft 86382sec preferred_lft 86382sec
    awk '
      /^---$/ { after = 1; next }
      !after && / inet / {
        for (i = 1; i <= NF; i++) if ($i == "inet") { split($(i + 1), a, "/"); addr = a[1] }
        kind = ($0 ~ / dynamic /) ? "dhcp" : "static"
        lease = "forever"
        if (match($0, /valid_lft [0-9]+sec/)) lease = substr($0, RSTART + 10, RLENGTH - 13)
      }
      after && / inet / { for (i = 1; i <= NF; i++) if ($i == "inet") others = others " " $(i + 1) }
      END { printf "%s\t%s\t%s\t%s\n", addr, kind, lease, substr(others, 2) }
    ' "$TMP/$n.live" >"$TMP/$n.addr"

    local addr kind lease others
    IFS=$'\t' read -r addr kind lease others <"$TMP/$n.addr"

    if [ -z "$addr" ]; then
      local why; why=$(head -c 160 "$TMP/$n.err" 2>/dev/null | tr '\t\n' '  ')
      add "$n" live "$IFACE address" FAIL "nothing came back over ssh${why:+ - $why}"
      continue
    fi
    add "$n" live "$IFACE address" PASS "$addr ($others)"

    # Measured: a node that boots without taking a DHCP lease loses its OrbStack
    # host-side proxy, and with it ssh and Ansible. See docs/ip-drift.md.
    if [ "$kind" = static ]; then
      add "$n" live "lease" WARN \
        "$addr is configured statically; OrbStack's ssh gateway does not survive a restart in this state"
    else
      add "$n" live "lease" INFO "dhcp, ${lease}s left ($(( ${lease:-0} / 3600 ))h)"
    fi

    # A node holding a VIP is drift too: OrbStack's DHCP does not probe before it
    # offers, so a lease can land on .240 or .241 and take the control plane down.
    local v
    for v in "$VIP" "$GATEWAY_VIP"; do
      [ -n "$v" ] || continue
      if [ "$addr" = "$v" ]; then
        add "$n" live "vip collision" FAIL "$n was leased $v, which belongs to kube-vip"
      fi
    done
  done
}

# ------------------------------------------------------------------- inventory ---

collect_inventory() {
  heading "inventory"
  info "comparing hosts.yaml ip, supplementary_addresses_in_ssl_keys and etcd_cert_alt_ips"
  local n want live sans alt
  for n in $NODES; do
    want=$(inventory_field "$n" 3)
    live=$(cut -f1 "$TMP/$n.addr" 2>/dev/null)
    [ -n "$live" ] || { add "$n" inventory "hosts.yaml ip" SKIP "live address unknown"; continue; }
    if [ -z "$want" ]; then
      add "$n" inventory "hosts.yaml ip" INFO \
        "unset, so main_ip comes from ansible_default_ipv4 and follows $live by itself"
    elif [ "$want" = "$live" ]; then
      add "$n" inventory "hosts.yaml ip" PASS "$want"
    else
      add "$n" inventory "hosts.yaml ip" FAIL \
        "hosts.yaml says $want, $n holds $live - every playbook will abort in preinstall"
    fi
  done

  # The two hand-maintained SAN lists. Neither is load-bearing for a `cluster.yml`
  # run -- apiserver_sans already includes each control plane's ansible_default_ipv4
  # and kubeadm-setup.yml regenerates the cert when a SAN is missing, and etcd certs
  # are rebuilt every run because force_etcd_cert_refresh defaults to true. What the
  # lists buy is tolerating the NEXT drift with no playbook at all, which is exactly
  # the window this cluster lives in. Stale lists are therefore a warning.
  # Scoped to the one key's own block: all.yml also holds upstream_dns_servers, and
  # 1.1.1.1 must not read as a pre-seeded node address.
  sans=$(yaml_ip_list supplementary_addresses_in_ssl_keys "$V31_INVENTORY_DIR/group_vars/all/all.yml")
  alt=$(yaml_ip_list etcd_cert_alt_ips "$V31_INVENTORY_DIR/group_vars/all/etcd.yml")
  for n in $NODES; do
    live=$(cut -f1 "$TMP/$n.addr" 2>/dev/null)
    [ -n "$live" ] || continue
    case " $sans " in
      *" $live "*) add "$n" inventory "apiserver cert SAN" PASS "$live in supplementary_addresses_in_ssl_keys" ;;
      *) add "$n" inventory "apiserver cert SAN" WARN \
           "$live absent from supplementary_addresses_in_ssl_keys; the next cluster.yml still covers it, but nothing does before then" ;;
    esac
    inventory_in_group "$n" etcd || continue
    case " $alt " in
      *" $live "*) add "$n" inventory "etcd cert SAN" PASS "$live in etcd_cert_alt_ips" ;;
      *) add "$n" inventory "etcd cert SAN" WARN \
           "$live absent from etcd_cert_alt_ips; if $n moves here before the next cluster.yml, peer TLS stops verifying" ;;
    esac
  done
}

# ------------------------------------------------------------------- kubernetes ---

collect_kubernetes() {
  heading "kubernetes"
  if [ ! -f "$KUBECONFIG" ]; then
    CLUSTER_WHY="no kubeconfig at $KUBECONFIG"
  elif ! command -v "$KUBECTL" >/dev/null 2>&1 && [ ! -x "$KUBECTL" ]; then
    CLUSTER_WHY="kubectl not found ($KUBECTL); run: make tools"
  elif [ "$(k get --raw /readyz || true)" != ok ]; then
    CLUSTER_WHY="the apiserver is not answering through $(awk '/server:/ {print $2; exit}' "$KUBECONFIG")"
  else
    CLUSTER=1
  fi

  if [ "$CLUSTER" = 0 ]; then
    warn "$CLUSTER_WHY"
    info "treating the cluster as not installed; inventory and live checks still apply"
    skip_cluster_rows "$CLUSTER_WHY"
    return 0
  fi

  info "reading Node InternalIP through $(awk '/server:/ {print $2; exit}' "$KUBECONFIG")"
  k get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.addresses[?(@.type=="InternalIP")].address}{"\n"}{end}' \
    >"$TMP/k8s-nodes" || : >"$TMP/k8s-nodes"

  local n live reg
  for n in $NODES; do
    live=$(cut -f1 "$TMP/$n.addr" 2>/dev/null)
    reg=$(awk -F'\t' -v n="$n" '$1 == n { print $2 }' "$TMP/k8s-nodes")
    if [ -z "$reg" ]; then
      add "$n" kubernetes "node InternalIP" WARN "$n is not registered as a Node"
    elif [ -z "$live" ]; then
      add "$n" kubernetes "node InternalIP" SKIP "live address unknown"
    elif [ "$reg" = "$live" ]; then
      add "$n" kubernetes "node InternalIP" PASS "$reg"
    else
      add "$n" kubernetes "node InternalIP" FAIL \
        "Node says $reg, $n holds $live - kubectl logs/exec and metrics go to $reg"
    fi
  done

  # Nodes Kubernetes knows about that the inventory does not.
  local extra
  extra=$(awk -F'\t' -v names=" $NODES " '{ if (index(names, " " $1 " ") == 0) printf "%s ", $1 }' \
    "$TMP/k8s-nodes")
  if [ -n "$extra" ]; then
    add local kubernetes "unknown nodes" WARN \
      "registered but absent from hosts.yaml: ${extra% }"
  fi
  return 0
}

# ------------------------------------------------------------------------- etcd ---
# The peer URL lives in etcd's own data. Asked over ssh rather than through the
# apiserver, because etcd runs as a host service here (etcd_deployment_type: host)
# and the CLI environment is already in /etc/etcd.env.

etcd_hosts() { local n; for n in $NODES; do inventory_in_group "$n" etcd && printf '%s ' "$n"; done; }

collect_etcd() {
  heading "etcd"
  local members='' from='' n
  for n in $(etcd_hosts); do
    members=$(ssh_node "$n" \
      "sudo sh -c 'set -a; . /etc/etcd.env 2>/dev/null; set +a; exec $BIN_DIR/etcdctl member list'" \
      2>/dev/null) || members=
    [ -n "$members" ] && { from=$n; break; }
  done

  if [ -z "$members" ]; then
    warn "etcdctl answered on no control-plane node; the peer URLs cannot be checked"
    for n in $(etcd_hosts); do
      add "$n" etcd "member peer URL" SKIP "no etcd member answered on any control-plane node"
    done
    return 0
  fi
  info "member list read from $from"
  printf '%s\n' "$members" >"$TMP/etcd-members"

  # 8e9e05c52164694d, started, etcd1, https://192.168.139.27:2380, https://...:2379, false
  local name peer live matched=''
  for n in $(etcd_hosts); do
    name=$(inventory_field "$n" 5)
    [ -n "$name" ] || name=$n
    peer=$(awk -F', *' -v m="$name" '$3 == m { print $4 }' "$TMP/etcd-members" | head -n1)
    live=$(cut -f1 "$TMP/$n.addr" 2>/dev/null)
    if [ -z "$peer" ]; then
      add "$n" etcd "member peer URL" FAIL "no member named $name in the cluster"
      continue
    fi
    matched="$matched $name"
    # https://192.168.139.27:2380 -> 192.168.139.27
    local host; host=$(printf '%s' "$peer" | sed -E 's#^https?://##; s#:[0-9]+$##; s#^\[(.*)\]$#\1#')
    if [ -z "$live" ]; then
      add "$n" etcd "member peer URL" SKIP "live address unknown; member $name is $host"
    elif [ "$host" = "$live" ]; then
      add "$n" etcd "member peer URL" PASS "$name $peer"
    else
      add "$n" etcd "member peer URL" FAIL \
        "member $name advertises $host, $n holds $live - needs etcdctl member update"
    fi
  done

  local orphans
  orphans=$(awk -F', *' -v seen=" $matched " '{ if (index(seen, " " $3 " ") == 0) printf "%s ", $3 }' \
    "$TMP/etcd-members")
  if [ -n "$orphans" ]; then
    add local etcd "unknown members" WARN "members with no inventory host: ${orphans% }"
  fi
  return 0
}

# ----------------------------------------------------------------------- calico ---
# kdd datastore, so the Node resource is a CRD and kubectl can read it; no calicoctl
# needed. Asked per node so one malformed resource does not blank the rest.

collect_calico() {
  heading "calico"
  info "reading Node.spec.bgp.ipv4Address from the kdd datastore"
  local n live bgp tun
  : >"$TMP/calico"
  for n in $NODES; do
    live=$(cut -f1 "$TMP/$n.addr" 2>/dev/null)
    bgp=$(k get nodes.crd.projectcalico.org "$n" \
      -o jsonpath='{.spec.bgp.ipv4Address}' || true)
    printf '%s\t%s\n' "$n" "$bgp" >>"$TMP/calico"
    tun=$(k get nodes.crd.projectcalico.org "$n" \
      -o jsonpath='{.spec.ipv4VXLANTunnelAddr}' || true)

    if [ -z "$bgp" ]; then
      add "$n" calico "node address" WARN "no Node.spec.bgp.ipv4Address for $n"
    else
      local addr=${bgp%%/*}
      if [ -z "$live" ]; then
        add "$n" calico "node address" SKIP "live address unknown; Calico has $addr"
      elif [ "$addr" = "$live" ]; then
        add "$n" calico "node address" PASS "$bgp"
      else
        add "$n" calico "node address" FAIL \
          "Calico has $addr, $n holds $live - restart the calico-node pod on $n to re-detect"
      fi
    fi

    # Drawn from kube_pods_subnet, so it never tracks the node address. Only
    # presence and provenance are worth asserting.
    if [ -z "$tun" ]; then
      add "$n" calico "vxlan tunnel address" WARN "unset; the node cannot terminate VXLAN"
    else
      case $tun in
        "$PODS_PREFIX".*)
          add "$n" calico "vxlan tunnel address" INFO "$tun, from $PODS_CIDR" ;;
        *) add "$n" calico "vxlan tunnel address" FAIL "$tun is outside $PODS_CIDR" ;;
      esac
    fi
  done
}

# ------------------------------------------------------------------------ table ---
# One row per node with the live address spelled out and every other source shown
# relative to it: "=" agrees, ".NNN" differs inside the same /24, "-" unknown.

cell() { # live other
  local live=$1 other=$2
  [ -z "$other" ] && { printf -- '-'; return; }
  [ "$other" = "$live" ] && { printf '='; return; }
  if [ "${other%.*}" = "${live%.*}" ]; then printf '.%s' "${other##*.}"; else printf '%s' "$other"; fi
}

render_table() {
  heading "per node"
  printf '%s%-5s %-17s %-7s %-11s %-11s %-11s %-11s %s%s\n' "$C_BOLD" \
    node live lease inventory k8s etcd calico verdict "$C_RESET"
  local n live kind lease want reg peer bgp name worst
  for n in $NODES; do
    IFS=$'\t' read -r live kind lease _ <"$TMP/$n.addr" 2>/dev/null || { live=; kind=; lease=; }
    want=$(inventory_field "$n" 3)
    reg=$(awk -F'\t' -v n="$n" '$1 == n { print $2 }' "$TMP/k8s-nodes" 2>/dev/null)
    name=$(inventory_field "$n" 5)
    peer=$(awk -F', *' -v m="${name:-$n}" '$3 == m { print $4 }' "$TMP/etcd-members" 2>/dev/null |
      head -n1 | sed -E 's#^https?://##; s#:[0-9]+$##')
    bgp=$(awk -F'\t' -v n="$n" '$1 == n { print $2 }' "$TMP/calico" 2>/dev/null)
    inventory_in_group "$n" etcd || peer='n/a'

    worst=$(awk -F'\t' -v n="$n" '
      $1 == n && $4 == "FAIL" { f = 1 }
      $1 == n && $4 == "WARN" { w = 1 }
      END { print (f ? "DRIFT" : (w ? "warn" : "ok")) }' "$RESULTS")

    printf '%-5s %-17s %-7s %-11s %-11s %-11s %-11s ' \
      "$n" "${live:-unreachable}" \
      "$([ "$kind" = static ] && printf 'static' || printf '%sh' "$(( ${lease:-0} / 3600 ))")" \
      "$(cell "$live" "$want")" "$(cell "$live" "$reg")" \
      "$([ "$peer" = n/a ] && printf 'n/a' || cell "$live" "$peer")" \
      "$(cell "$live" "${bgp%%/*}")"
    case $worst in
      DRIFT) printf '%sDRIFT%s\n' "$C_RED" "$C_RESET" ;;
      warn) printf '%swarn%s\n' "$C_YELLOW" "$C_RESET" ;;
      *) printf '%sok%s\n' "$C_GREEN" "$C_RESET" ;;
    esac
  done
}

# ---------------------------------------------------------------------- suggest ---

render_suggest() {
  heading "inventory blocks for the live addresses"
  local n live
  printf '\n# %s\n' "$V31_INVENTORY_DIR/hosts.yaml"
  for n in $NODES; do
    live=$(cut -f1 "$TMP/$n.addr" 2>/dev/null)
    [ -n "$live" ] && printf '    %s:\n      ip: %s\n' "$n" "$live"
  done
  printf '\n# %s  supplementary_addresses_in_ssl_keys\n' "$V31_INVENTORY_DIR/group_vars/all/all.yml"
  printf '  - "{{ kube_vip_address }}"\n'
  for n in $NODES; do
    live=$(cut -f1 "$TMP/$n.addr" 2>/dev/null)
    [ -n "$live" ] && printf '  - %s\n' "$live"
  done
  printf '\n# %s  etcd_cert_alt_ips\n' "$V31_INVENTORY_DIR/group_vars/all/etcd.yml"
  for n in $NODES; do
    live=$(cut -f1 "$TMP/$n.addr" 2>/dev/null)
    [ -n "$live" ] && printf '  - %-16s # %s\n' "$live" "$n"
  done
  printf '\n'
}

# -------------------------------------------------------------------------- run ---

: >"$TMP/k8s-nodes"
: >"$TMP/etcd-members"
: >"$TMP/calico"

skip_cluster_rows() { # reason
  local n
  for n in $NODES; do
    add "$n" kubernetes "node InternalIP" SKIP "$1"
    inventory_in_group "$n" etcd && add "$n" etcd "member peer URL" SKIP "$1"
    add "$n" calico "node address" SKIP "$1"
  done
}

collect_live
collect_inventory

if [ "$USE_CLUSTER" = 0 ]; then
  heading "cluster"
  info "--no-cluster: Kubernetes, etcd and Calico not consulted"
  skip_cluster_rows "--no-cluster"
else
  collect_kubernetes
  if [ "$CLUSTER" = 1 ]; then
    collect_etcd
    collect_calico
  fi
fi

render_table
# render_details starts a new block whenever the node changes, and rows are added
# one check at a time across all nodes, so group them first.
awk -F'\t' -v order="$NODES" '
  { rows[$1] = rows[$1] $0 "\n"; seen[$1] = 1 }
  END {
    n = split(order, nd, " ")
    for (i = 1; i <= n; i++) if (nd[i] in seen) { printf "%s", rows[nd[i]]; delete rows[nd[i]] }
    for (h in rows) printf "%s", rows[h]
  }' "$RESULTS" >"$TMP/by-node.tsv"
render_details "$TMP/by-node.tsv" "$VERBOSE"
if [ "$SUGGEST" = 1 ]; then render_suggest; fi
hr
results_tally "$RESULTS"

if results_exit_code "$RESULTS"; then
  info "no address drift"
else
  err "address drift found; repair with docs/ip-drift.md"
  exit 1
fi
