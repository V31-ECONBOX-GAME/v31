#!/usr/bin/env bash
# Whole-cluster readiness check, run from the macOS control machine before
# cluster.yml. Covers what scripts/kube-vip.sh preflight does not: architecture,
# OS, kernel, modules, sysctls, swap, clock skew, disk, CPU, node-to-node
# reachability on the etcd and API ports, DNS, egress and leftover k8s state.
#
#   scripts/preflight.sh              # per-node matrix, details for anything not PASS
#   scripts/preflight.sh -v           # every row, including PASS
#   scripts/preflight.sh --installed  # a cluster is meant to be here: k8s state is INFO
#   scripts/preflight.sh --no-ports   # skip the port-reachability pass
#
# Read-only except for two things Kubespray does anyway: it may `modprobe` a
# missing module, and it writes each sysctl's current value back to prove the key
# is writable. Nothing is installed and no service is touched.
#
# Exit 0 all clear (warnings allowed), 1 at least one FAIL, 2 could not run.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)/common.sh"
. "$V31_ROOT/versions.env"

VERBOSE=0
INSTALLED=0
DO_PORTS=1
# Seconds allowed for six ssh sessions to start and bind before any of them probes.
# The barrier is passed on as an absolute epoch, not a sleep, so the nodes wait for
# the same instant; their clocks agree to within the skew this script measures.
BARRIER=${V31_PREFLIGHT_BARRIER:-8}
ETCD_PORTS=2379,2380,6443

while [ $# -gt 0 ]; do
  case $1 in
    -v | --verbose) VERBOSE=1 ;;
    --installed) INSTALLED=1 ;;
    --no-ports) DO_PORTS=0 ;;
    -h | --help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done

need_cmd ssh
need_cmd awk

TMP=$(mktemp -d "${TMPDIR:-/tmp}/v31-preflight.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
RESULTS=$TMP/results.tsv
results_init "$RESULTS"

inventory_load
NODES=$(inventory_names)
[ -n "$NODES" ] || die "no hosts in $V31_INVENTORY_DIR/hosts.yaml"

add() { result_add "$RESULTS" "$@"; }

# ------------------------------------------------------------------ node probe --
# Fed to each node's bash by ssh_node_script. Emits group/check/status/detail.

cat >"$TMP/node-probe.sh" <<'PROBE'
set -uo pipefail
EXPECT_IP=${1:-}
ROLES=${2:-}
INSTALLED=${3:-0}

row()   { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4"; }
is_cp() { case ",$ROLES," in *,kube_control_plane,*) return 0 ;; *) return 1 ;; esac; }
have()  { command -v "$1" >/dev/null 2>&1; }
s()     { sudo -n "$@" 2>/dev/null; }

# --- architecture, OS, kernel
arch=$(uname -m)
if [ "$arch" = aarch64 ]; then
  row arch machine PASS "$arch"
else
  row arch machine FAIL "$arch - every image and checksum in this tree is arm64"
fi

id=; ver=; pretty=
[ -r /etc/os-release ] && . /etc/os-release
id=${ID:-unknown}; ver=${VERSION_ID:-unknown}; pretty=${PRETTY_NAME:-unknown}
case $id in
  ubuntu)
    case $ver in
      26.04) row os distribution PASS "$pretty" ;;
      *) row os distribution WARN "$pretty - this tree was verified on Ubuntu 26.04" ;;
    esac ;;
  *) row os distribution FAIL "$pretty - allow_unsupported_distribution_setup is false" ;;
esac

kern=$(uname -r)
case ${kern%%.*} in
  '' | *[!0-9]*) row kernel version WARN "$kern - cannot read a major version" ;;
  *)
    if [ "${kern%%.*}" -ge 5 ]; then row kernel version PASS "$kern"
    else row kernel version FAIL "$kern - Calico VXLAN and ambient need 5.x or newer"; fi ;;
esac

# --- kernel modules. Required ones break the install; the ip_vs family only
# matters if kube_proxy_mode ever moves back to ipvs.
mods_required="br_netfilter overlay nf_conntrack vxlan"
mods_optional="ip_vs ip_vs_rr ip_vs_wrr ip_vs_sh xt_set ipip"
builtin=/lib/modules/$kern/modules.builtin
loaded=$(lsmod 2>/dev/null | awk 'NR > 1 {print $1}')
for m in $mods_required $mods_optional; do
  sev=WARN
  case " $mods_required " in *" $m "*) sev=FAIL ;; esac
  if printf '%s\n' "$loaded" | grep -qx "$m"; then
    row modules "$m" PASS loaded
  elif [ -r "$builtin" ] && grep -q "/$m\.ko" "$builtin"; then
    row modules "$m" PASS builtin
  elif s modprobe "$m"; then
    row modules "$m" PASS "loaded by this check"
  elif modinfo "$m" >/dev/null 2>&1; then
    row modules "$m" WARN "packaged but modprobe failed"
  else
    row modules "$m" "$sev" "no such module on $kern"
  fi
done

# --- sysctls. Writing the current value back proves the key is writable without
# changing anything; Kubespray is what sets them to 1.
for k in net.bridge.bridge-nf-call-iptables net.bridge.bridge-nf-call-ip6tables \
         net.ipv4.ip_forward; do
  cur=$(s sysctl -n "$k") || cur=
  if [ -z "$cur" ]; then
    row sysctls "$k" FAIL "absent - br_netfilter must be loaded for the bridge keys"
  elif s sysctl -qw "$k=$cur"; then
    if [ "$cur" = 1 ]; then row sysctls "$k" PASS "1, writable"
    else row sysctls "$k" WARN "$cur, writable - Kubespray will set 1"; fi
  else
    row sysctls "$k" FAIL "$cur, not writable - the install cannot set it"
  fi
done
rpf=$(s sysctl -n net.ipv4.conf.all.rp_filter) || rpf=
row sysctls net.ipv4.conf.all.rp_filter INFO "${rpf:-unknown} (2 = loose, what Calico wants here)"

# --- swap. Never turned off here: swapoff -a on a 41G zram device is not a
# read-only act. playbooks/orbstack-prepare.yml owns that.
sw=$(awk 'NR > 1 {printf "%s(%s,%sK) ", $1, $2, $3}' /proc/swaps 2>/dev/null)
if [ -z "$sw" ]; then row swap devices PASS off
else row swap devices WARN "active: ${sw% }"; fi
nfs=$(awk '$1 !~ /^#/ && $3 == "swap" {n++} END {print n + 0}' /etc/fstab 2>/dev/null) || nfs=0
if [ "${nfs:-0}" -eq 0 ]; then row swap fstab PASS "no swap entry"
else row swap fstab WARN "$nfs swap entry in /etc/fstab"; fi
unit=$(systemctl is-enabled v31-swapoff.service 2>/dev/null) || unit=
if [ "$unit" = enabled ]; then row swap v31-swapoff.service PASS enabled
else row swap v31-swapoff.service WARN "${unit:-absent} - run playbooks/orbstack-prepare.yml"; fi
if s /sbin/swapoff --help >/dev/null; then row swap "swapoff privilege" PASS "sudo -n /sbin/swapoff"
else row swap "swapoff privilege" FAIL "cannot run /sbin/swapoff under sudo -n"; fi

# --- disk
check_disk() { # path fail_gb warn_gb
  local p=$1 f=$2 w=$3 t line avail mnt gb
  t=$p
  while [ ! -d "$t" ] && [ "$t" != / ]; do t=$(dirname "$t"); done
  line=$(df -Pk "$t" 2>/dev/null | awk 'NR == 2 {print $4, $6}')
  if [ -z "$line" ]; then row disk "$p" WARN "df gave nothing"; return; fi
  avail=${line%% *}; mnt=${line##* }
  gb=$((avail / 1048576))
  if [ "$gb" -lt "$f" ]; then row disk "$p" FAIL "${gb}G free on $mnt, want ${f}G"
  elif [ "$gb" -lt "$w" ]; then row disk "$p" WARN "${gb}G free on $mnt"
  else row disk "$p" PASS "${gb}G free on $mnt"; fi
}
check_disk /var 10 25
check_disk /tmp 3 8

# --- CPU and memory
cpus=$(nproc 2>/dev/null) || cpus=$(getconf _NPROCESSORS_ONLN 2>/dev/null) || cpus=0
min=1; is_cp && min=2
if [ "$cpus" -lt "$min" ]; then
  row cpu count FAIL "$cpus vCPU - kubeadm requires $min on a control-plane node"
elif is_cp && [ "$cpus" -le 2 ]; then
  row cpu count WARN "$cpus vCPU - at the kubeadm minimum, hence -e etcd_retries=10"
else
  row cpu count PASS "$cpus vCPU"
fi

memtotal=$(awk '/^MemTotal:/ {printf "%d", $2 / 1024}' /proc/meminfo)
row memory /proc/meminfo INFO "${memtotal}Mi - the whole VM, which kubelet will believe"
lim=$(cat /sys/fs/cgroup/memory.max 2>/dev/null) ||
  lim=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null) || lim=
case $lim in
  '' | max | *[!0-9]*)
    row memory "cgroup limit" WARN \
      "not visible inside the machine; see docs/runbook.md section 10" ;;
  *)
    limmi=$((lim / 1048576))
    if is_cp && [ "$limmi" -lt 1700 ]; then
      row memory "cgroup limit" FAIL "${limmi}Mi - kubeadm requires 1700Mi"
    elif [ "$limmi" -lt 2048 ]; then
      row memory "cgroup limit" WARN "${limmi}Mi"
    else
      row memory "cgroup limit" PASS "${limmi}Mi"
    fi ;;
esac

# --- the address Kubespray will bake into every certificate and etcd peer URL
addrs=$(ip -4 -o addr show scope global 2>/dev/null |
          awk '{split($4, a, "/"); printf "%s ", a[1]}')
if [ -z "$EXPECT_IP" ]; then
  row "node ip" inventory INFO "derived at run time; see check_inventory"
else
  case " $addrs" in
    *" $EXPECT_IP "*) row "node ip" inventory PASS "$EXPECT_IP" ;;
    *) row "node ip" inventory FAIL \
         "holds ${addrs:-nothing}, hosts.yaml says $EXPECT_IP - DHCP moved this node (docs/runbook.md section 1)" ;;
  esac
  iface=$(ip -4 -o addr show 2>/dev/null |
            awk -v a="$EXPECT_IP" '{split($4, p, "/"); if (p[1] == a) {print $2; exit}}')
  if [ "${iface:-}" = eth0 ]; then row "node ip" interface PASS eth0
  else row "node ip" interface WARN \
         "${iface:-none} - kube_vip_interface and calico_ip_auto_method both name eth0"; fi
fi
gw=$(ip -4 route show default 2>/dev/null | awk '{print $3; exit}')
row "node ip" gateway INFO "${gw:-none}"

# --- DNS and egress. Every host below is one the install pulls from.
resolv=$(readlink -f /etc/resolv.conf 2>/dev/null) || resolv=/etc/resolv.conf
row dns resolv.conf INFO "$resolv"
ns=$(awk '/^nameserver/ {printf "%s ", $2}' /etc/resolv.conf 2>/dev/null)
row dns nameservers INFO "${ns:-none}"
for h in registry.k8s.io quay.io ghcr.io docker.io github.com get.helm.sh ports.ubuntu.com; do
  if getent hosts "$h" >/dev/null 2>&1; then row dns "$h" PASS resolves
  else row dns "$h" FAIL "no answer"; fi
done
for hp in registry.k8s.io:443 quay.io:443 ghcr.io:443 docker.io:443 github.com:443 \
          get.helm.sh:443 ports.ubuntu.com:80; do
  if timeout 6 bash -c "exec 3<>/dev/tcp/${hp%:*}/${hp##*:}" 2>/dev/null; then
    row egress "$hp" PASS reachable
  else
    row egress "$hp" FAIL "no TCP connect"
  fi
done

# --- what Ansible and Kubespray need to exist before they start
if [ -x /usr/bin/python3 ]; then
  row commands /usr/bin/python3 PASS "$(/usr/bin/python3 -V 2>&1)"
else
  row commands /usr/bin/python3 FAIL "absent - hosts.yaml pins ansible_python_interpreter to it"
fi
# Ansible and the Kubespray preinstall role need these before anything is installed.
for c in sudo tar ip systemctl mount findmnt; do
  if have "$c"; then row commands "$c" PASS "$(command -v "$c")"
  else row commands "$c" FAIL "not on PATH"; fi
done
# These the preinstall role installs itself; absence is only worth knowing.
for c in iptables conntrack socat ethtool crictl; do
  if have "$c"; then row commands "$c" PASS "$(command -v "$c")"
  else row commands "$c" INFO "absent - Kubespray's preinstall role adds it"; fi
done
if s true; then row commands "passwordless sudo" PASS ok
else row commands "passwordless sudo" FAIL "sudo -n failed"; fi
state=$(systemctl is-system-running 2>/dev/null) || state=
case $state in
  running) row commands systemd PASS running ;;
  *) row commands systemd WARN "${state:-unknown}" ;;
esac

# --- leftover k8s state. A second install on top of this inherits it.
if [ "$INSTALLED" = 1 ]; then hard=INFO; soft=INFO; else hard=FAIL; soft=WARN; fi

etcd_n=$(s ls -A /var/lib/etcd 2>/dev/null | wc -l | tr -d ' ') || etcd_n=0
if [ "${etcd_n:-0}" -gt 0 ]; then row leftovers /var/lib/etcd "$hard" "$etcd_n entries"
else row leftovers /var/lib/etcd PASS empty; fi

man_n=$(s ls -A /etc/kubernetes/manifests 2>/dev/null | wc -l | tr -d ' ') || man_n=0
if [ "${man_n:-0}" -gt 0 ]; then
  row leftovers /etc/kubernetes/manifests "$hard" \
    "$(s ls /etc/kubernetes/manifests | awk '{printf "%s ", $0}')"
else
  row leftovers /etc/kubernetes/manifests PASS empty
fi

for u in kubelet containerd etcd; do
  a=$(systemctl is-active "$u" 2>/dev/null) || a=
  if [ "$a" = active ]; then row leftovers "$u.service" "$hard" active
  else row leftovers "$u.service" PASS "${a:-absent}"; fi
done

cni=$(ls /etc/cni/net.d 2>/dev/null | awk '{printf "%s ", $0}')
if [ -n "$cni" ]; then row leftovers /etc/cni/net.d "$soft" "$cni"
else row leftovers /etc/cni/net.d PASS empty; fi

stale=
for p in /usr/local/bin/kubeadm /usr/local/bin/kubelet /usr/local/bin/etcdctl \
         /usr/local/bin/calicoctl /opt/cni/bin/istio-cni /opt/containerd \
         /usr/libexec/kubernetes /etc/ssl/etcd /var/lib/kubelet /tmp/releases; do
  if s test -e "$p"; then stale="$stale $p"; fi
done
if [ -n "$stale" ]; then row leftovers "stale paths" "$soft" "${stale# }"
else row leftovers "stale paths" PASS none; fi

# Last row, so a probe that died half way through is not read as a clean pass.
row probe complete PASS "$(cat /proc/sys/kernel/hostname 2>/dev/null) $(date -u +%FT%TZ)"
exit 0
PROBE

# rc distinguishes an ssh failure (255) from a node whose shell runs but fails.
probe_alive() {
  local out rc=0
  out=$(ssh_node "$1" 'cat /proc/sys/kernel/hostname; id -un; uptime -p' 2>&1) || rc=$?
  printf '%s\t%s\n' "$rc" "$(printf '%s' "$out" | tr '\n\t' '  ')"
}

probe_node() {
  ssh_node_script "$1" "$TMP/node-probe.sh" \
    "$(inventory_field "$1" 3)" "$(inventory_field "$1" 4)" "$INSTALLED"
}

# ------------------------------------------------------------------ port probe --
# Each node binds the etcd and API ports, waits for its five peers to do the same,
# then connects to every one of them. A refusal still proves nothing filters the
# path; a timeout is the answer that matters.

cat >"$TMP/port-probe.sh" <<'PORTS'
set -uo pipefail
SELF=${1:-}
PORTLIST=${2:-}
PEERS=${3:-}
START_AT=${4:-0}

row() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4"; }

for peer in ${PEERS//,/ }; do
  name=${peer%%=*}; ip=${peer##*=}
  [ "$name" = "$SELF" ] && continue
  if ping -c2 -W2 "$ip" >/dev/null 2>&1; then row "peer icmp" "$name" PASS "$ip"
  else row "peer icmp" "$name" FAIL "$ip does not answer ICMP"; fi
done

if ! command -v python3 >/dev/null 2>&1; then
  row "peer ports" python3 SKIP "no python3, cannot stand up a listener"
  exit 0
fi

python3 - "$SELF" "$PORTLIST" "$PEERS" "$START_AT" <<'PY'
import socket, sys, threading, time

self_name, portlist, peerspec, start_at = sys.argv[1:5]
ports = [int(p) for p in portlist.split(",") if p]
peers = [p.split("=", 1) for p in peerspec.split(",") if p and "=" in p]
others = [(n, ip) for n, ip in peers if n != self_name]
rows = []
# Every node was handed the same instant to start probing at, so the six sessions
# are all listening first. Listeners then stay up until each peer has connected
# once per port - a node that never connects holds the rest here until the deadline.
start_at = float(start_at)
deadline = start_at + 15.0


class Listener(threading.Thread):
    def __init__(self, sock, need):
        threading.Thread.__init__(self, daemon=True)
        self.sock, self.need, self.seen = sock, need, 0

    def run(self):
        self.sock.settimeout(1.0)
        while self.seen < self.need and time.time() < deadline:
            try:
                conn, _ = self.sock.accept()
                conn.close()
                self.seen += 1
            except socket.timeout:
                continue
            except OSError:
                return


listeners = []
for port in ports:
    sock = socket.socket()
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", port))
        sock.listen(64)
    except OSError as exc:
        sock.close()
        # Already bound: the live cluster, or leftover state. The connect pass still
        # tells every peer whether the path to this port is open.
        rows.append(("peer ports", "listen %d" % port, "INFO",
                     "in use: %s" % (exc.strerror or exc)))
        continue
    listener = Listener(sock, len(others))
    listener.start()
    listeners.append(listener)

margin = start_at - time.time()
rows.append(("peer ports", "barrier", "PASS" if margin > 0 else "WARN",
             "%d ports listening %.1fs %s the agreed start"
             % (len(listeners), abs(margin), "before" if margin > 0 else "AFTER")))
if margin > 0:
    time.sleep(margin)

for name, ip in others:
    for port in ports:
        conn = socket.socket()
        conn.settimeout(4.0)
        try:
            conn.connect((ip, port))
            rows.append(("peer ports", "%s:%d" % (name, port), "PASS", "connected"))
        except socket.timeout:
            rows.append(("peer ports", "%s:%d" % (name, port), "FAIL",
                         "timeout to %s - something is filtering the path" % ip))
        except ConnectionRefusedError:
            rows.append(("peer ports", "%s:%d" % (name, port), "WARN",
                         "refused by %s - path is open, nothing listening" % ip))
        except ConnectionResetError:
            rows.append(("peer ports", "%s:%d" % (name, port), "WARN",
                         "reset by %s - its listener went away early" % ip))
        except OSError as exc:
            rows.append(("peer ports", "%s:%d" % (name, port), "FAIL",
                         "%s: %s" % (ip, exc.strerror or exc)))
        finally:
            conn.close()

# Do not close a listener a slower peer has not reached yet.
for listener in listeners:
    listener.join(max(0.0, deadline - time.time()))
    listener.sock.close()

for row in rows:
    print("\t".join(row))
PY
exit 0
PORTS

probe_ports() {
  ssh_node_script "$1" "$TMP/port-probe.sh" "$1" "$ETCD_PORTS" "$PEERSPEC" "$START_AT"
}

# --------------------------------------------------------------------- clock ----
# Offset measured the NTP way: the node's clock against the midpoint of the local
# clock either side of the round trip, so the ssh latency cancels out.

probe_clock() {
  local t0 nt t1
  t0=$(epoch_now)
  nt=$(ssh_node "$1" 'date +%s.%N' 2>/dev/null) || nt=
  t1=$(epoch_now)
  printf '%s %s %s\n' "$t0" "${nt:-none}" "$t1"
}

# ------------------------------------------------------------ control machine ---

# Two CIDRs overlap when each range starts at or before the other one ends.
# Range arithmetic, not bit masking: BSD awk has no and()/or().
cidr_overlaps() { # a/len b/len -> 0 when they overlap
  awk -v x="$1" -v y="$2" 'BEGIN {
    split(x, p, "/"); split(y, q, "/")
    if (split(p[1], a, ".") != 4 || split(q[1], b, ".") != 4) exit 1
    xa = ((a[1] * 256 + a[2]) * 256 + a[3]) * 256 + a[4]
    ya = ((b[1] * 256 + b[2]) * 256 + b[3]) * 256 + b[4]
    xz = 2 ^ (32 - (p[2] == "" ? 32 : p[2] + 0))
    yz = 2 ^ (32 - (q[2] == "" ? 32 : q[2] + 0))
    xs = xa - (xa % xz); ys = ya - (ya % yz)
    exit (xs <= ys + yz - 1 && ys <= xs + xz - 1) ? 0 : 1
  }'
}

check_control_machine() {
  heading "control machine"
  local v

  if [ "$(uname -s)" = Darwin ]; then
    add local host platform PASS "macOS $(sw_vers -productVersion 2>/dev/null) $(uname -m)"
  else
    add local host platform WARN "$(uname -s) - the docs and bin/ tooling assume macOS"
  fi
  add local host bash INFO "${BASH_VERSION:-unknown}"

  v31_ssh_opts
  add local ssh key PASS "$V31_SSH_KEY"

  if [ -f "$KUBESPRAY_SRC/cluster.yml" ]; then
    v=$(git -C "$KUBESPRAY_SRC" describe --tags --exact-match 2>/dev/null) || v=
    if [ "$v" = "$KUBESPRAY_VERSION" ]; then
      add local tooling kubespray PASS "$KUBESPRAY_VERSION at $KUBESPRAY_SRC"
    else
      add local tooling kubespray FAIL \
        "checkout is at '${v:-unknown}', versions.env pins $KUBESPRAY_VERSION - run make fetch"
    fi
  else
    add local tooling kubespray FAIL "no checkout at $KUBESPRAY_SRC - run make fetch"
  fi

  if [ -x "$KUBESPRAY_VENV/bin/ansible-playbook" ]; then
    add local tooling ansible PASS \
      "$("$KUBESPRAY_VENV/bin/ansible" --version 2>/dev/null | head -n1)"
  else
    add local tooling ansible FAIL "no venv at $KUBESPRAY_VENV - run make bootstrap"
  fi

  # The inventory must agree with versions.env, because the docs quote versions.env
  # and the install reads the inventory.
  for pair in "kube_version=$KUBE_VERSION" "kube_vip_address=$KUBE_VIP_ADDRESS" \
              "kube_vip_version=$KUBE_VIP_VERSION" "istio_version=$ISTIO_VERSION" \
              "gateway_api_version=$GATEWAY_API_VERSION" "helm_version=$HELM_VERSION"; do
    local key=${pair%%=*} want=${pair#*=} got
    got=$(cfg_get "$key") || got=
    if [ -z "$got" ]; then
      add local versions "$key" WARN "not set in the inventory or istio/versions.yml"
    elif [ "$got" = "$want" ]; then
      add local versions "$key" PASS "$got"
    else
      add local versions "$key" FAIL "config says $got, versions.env says $want"
    fi
  done

  # Swap is tolerated only because the inventory says so; assert that it still does.
  local fail_swap
  fail_swap=$(cfg_get kubelet_fail_swap_on) || fail_swap=
  if [ "$fail_swap" = false ]; then
    add local policy kubelet_fail_swap_on PASS "false - OrbStack re-creates swap at boot"
  else
    add local policy kubelet_fail_swap_on FAIL \
      "${fail_swap:-unset} - kubelet will refuse to start while zram swap exists"
  fi

  # Every CIDR this cluster claims, against every network this Mac already routes.
  local svc pods locals net l hit
  svc=$(cfg_get kube_service_addresses) || svc=
  pods=$(cfg_get kube_pods_subnet) || pods=
  locals=$(ifconfig -a 2>/dev/null | awk '
    /^[A-Za-z][A-Za-z0-9_.]*:/ { iface = substr($1, 1, length($1) - 1) }
    $1 == "inet" && $2 != "127.0.0.1" {
      mask = ""
      for (i = 2; i < NF; i++) if ($i == "netmask") mask = $(i + 1)
      if (mask == "") next
      sub(/^0x/, "", mask)
      bits = 0
      for (i = 1; i <= length(mask); i++) {
        d = index("0123456789abcdef", tolower(substr(mask, i, 1))) - 1
        while (d > 0) { bits += d % 2; d = int(d / 2) }
      }
      printf "%s/%d=%s ", $2, bits, iface
    }')
  for net in $svc $pods; do
    hit=''
    for l in $locals; do
      if cidr_overlaps "$net" "${l%%=*}"; then hit="${hit} ${l%%=*}(${l##*=})"; fi
    done
    if [ -z "$net" ]; then continue; fi
    if [ -n "$hit" ]; then
      add local cidr "$net" FAIL "overlaps this Mac's${hit}"
    else
      add local cidr "$net" PASS "clear of every local network"
    fi
  done

  if command -v orbctl >/dev/null 2>&1; then
    orbctl list 2>/dev/null >"$TMP/orbctl" || : >"$TMP/orbctl"
    local n st
    for n in $NODES; do
      st=$(awk -v n="$n" '$1 == n {print tolower($2)}' "$TMP/orbctl")
      case $st in
        running) add "$n" orbstack machine PASS running ;;
        '') add "$n" orbstack machine WARN "not in orbctl list" ;;
        *) add "$n" orbstack machine FAIL "$st" ;;
      esac
    done
  else
    add local orbstack orbctl WARN "not on PATH, cannot confirm the machines are running"
  fi
}

check_inventory() {
  heading "inventory"
  local n ip dups
  for n in $NODES; do
    ip=$(inventory_field "$n" 3)
    if [ -z "$ip" ]; then
      # Deliberate: these addresses are DHCP with 1-day leases and five of six moved
      # on 2026-09-27, so a literal ip: is a stale value waiting to happen. Kubespray
      # falls back to ansible_default_ipv4.address, which is gathered per run. Each
      # machine has exactly one non-loopback interface, so that cannot pick wrong --
      # the node-ip section below verifies the derived address for real.
      add "$n" inventory ip INFO "derived from ansible_default_ipv4 (no literal ip:)"
    else
      add "$n" inventory ip PASS "$ip"
    fi
    if [ -z "$(inventory_field "$n" 4)" ]; then
      add "$n" inventory groups FAIL "in no group: cluster.yml will skip it"
    else
      add "$n" inventory groups PASS "$(inventory_field "$n" 4)"
    fi
  done

  dups=$(inventory_hosts | awk -F'\t' '$3 != "" {c[$3] = c[$3] " " $1}
    END {for (i in c) if (split(c[i], _, " ") > 1) printf "%s:%s ", i, c[i]}')
  if [ -n "$dups" ]; then
    add local inventory "duplicate ip" FAIL "$dups"
  else
    add local inventory "duplicate ip" PASS "all node addresses distinct"
  fi

  local cps=0 etcds=0
  for n in $NODES; do
    inventory_in_group "$n" kube_control_plane && cps=$((cps + 1)) || true
    inventory_in_group "$n" etcd && etcds=$((etcds + 1)) || true
  done
  case $cps in
    1 | 3 | 5) add local inventory kube_control_plane PASS "$cps nodes" ;;
    0) add local inventory kube_control_plane FAIL "none" ;;
    *) add local inventory kube_control_plane WARN "$cps nodes - an even etcd quorum" ;;
  esac
  if [ "$etcds" = "$cps" ]; then
    add local inventory etcd PASS "$etcds members, stacked on the control plane"
  else
    add local inventory etcd WARN "$etcds members against $cps control-plane nodes"
  fi
}

# ---------------------------------------------------------------------- run -----

collect() { # node ext
  local f=$TMP/$1.$2 g c s d
  if [ ! -s "$f" ]; then
    d=$(head -c 200 "$TMP/$1.err" 2>/dev/null | tr '\t\n' '  ')
    add "$1" ssh "$2 probe" FAIL \
      "nothing came back over ssh to $(inventory_field "$1" 2)${d:+ - $d}"
    return
  fi
  while IFS=$'\t' read -r g c s d; do
    [ -n "${g:-}" ] || continue
    add "$1" "$g" "$c" "$s" "${d:-}"
  done <"$f"
  if [ "$2" = out ] && ! tail -n1 "$f" | grep -q '^probe	'; then
    add "$1" probe complete FAIL "the probe stopped part way through; see $1.err"
  fi
}

check_control_machine
check_inventory

heading "nodes"
info "checking ssh to every node"
fanout "$TMP" alive probe_alive $NODES || true
for n in $NODES; do
  rc=; out=
  IFS=$'\t' read -r rc out <"$TMP/$n.alive" || true
  case ${rc:-255} in
    0)
      if [ -n "${out:-}" ]; then add "$n" ssh reachable PASS "$out"
      else add "$n" ssh reachable FAIL "ssh succeeded but the node returned nothing"; fi ;;
    255) add "$n" ssh reachable FAIL \
           "cannot connect to $(inventory_field "$n" 2): ${out:-no error text}" ;;
    *) add "$n" ssh reachable FAIL \
         "connected to $(inventory_field "$n" 2) but the remote shell exited $rc: ${out:-no output} - the machine is up with a broken userspace" ;;
  esac
done

info "probing $(printf '%s' "$NODES" | wc -w | tr -d ' ') nodes in parallel"
fanout "$TMP" out probe_node $NODES || true
for n in $NODES; do collect "$n" out; done

info "measuring clock offsets"
fanout "$TMP" clock probe_clock $NODES || true
: >"$TMP/offsets"
for n in $NODES; do
  t0=; nt=; t1=
  read -r t0 nt t1 <"$TMP/$n.clock" || true
  if [ -z "${nt:-}" ] || [ "$nt" = none ]; then
    add "$n" clock offset FAIL "could not read the clock over ssh"
    continue
  fi
  off=$(awk -v a="$t0" -v b="$nt" -v c="$t1" 'BEGIN {printf "%.3f", b - (a + c) / 2}')
  unc=$(awk -v a="$t0" -v c="$t1" 'BEGIN {printf "%.3f", (c - a) / 2}')
  printf '%s\t%s\t%s\n' "$n" "$off" "$unc" >>"$TMP/offsets"
done
if [ -s "$TMP/offsets" ]; then
  # etcd logs a clock-drift warning at 1s between members and a raft heartbeat here
  # is 250ms, so half of that is the point at which an election gets unreliable.
  mean=$(awk -F'\t' '{s += $2; n++} END {printf "%.3f", s / n}' "$TMP/offsets")
  while IFS=$'\t' read -r n off unc; do
    dev=$(awk -v o="$off" -v m="$mean" 'BEGIN {d = o - m; printf "%.3f", (d < 0 ? -d : d)}')
    sev=$(awk -v d="$dev" 'BEGIN {print ((d > 0.5) ? "FAIL" : (d > 0.125) ? "WARN" : "PASS")}')
    add "$n" clock offset "$sev" "${off}s from this Mac, ${dev}s from the cluster mean (+-${unc}s)"
  done <"$TMP/offsets"
  spread=$(awk -F'\t' 'NR == 1 {lo = hi = $2}
    {if ($2 < lo) lo = $2; if ($2 > hi) hi = $2}
    END {printf "%.3f", hi - lo}' "$TMP/offsets")
  info "worst pairwise clock skew: ${spread}s"
fi

if [ "$DO_PORTS" = 1 ]; then
  PEERSPEC=$(inventory_hosts | awk -F'\t' '$3 != "" {printf "%s=%s,", $1, $3}')
  PEERSPEC=${PEERSPEC%,}
  START_AT=$(awk -v n="$(epoch_now)" -v b="$BARRIER" 'BEGIN {printf "%d", n + b + 1}')
  info "testing node-to-node reachability on $ETCD_PORTS (about $((BARRIER + 10))s)"
  fanout "$TMP" ports probe_ports $NODES || true
  for n in $NODES; do collect "$n" ports; done
else
  for n in $NODES; do add "$n" "peer ports" all SKIP "--no-ports"; done
fi

# -------------------------------------------------------------------- report ----

heading "result"
render_matrix "$RESULTS" "local $NODES"
render_details "$RESULTS" "$VERBOSE"
hr
results_tally "$RESULTS"

if results_exit_code "$RESULTS"; then
  info "preflight passed"
else
  err "preflight failed; fix every FAIL above before cluster.yml"
  exit 1
fi
