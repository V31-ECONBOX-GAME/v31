#!/usr/bin/env bash
# Operate the control-plane VIP from the macOS host.
#
#   scripts/kube-vip.sh preflight   # before cluster.yml: VIP free, eth0 present, ARP works
#   scripts/kube-vip.sh verify      # after cluster.yml: leader, lease, image, API via VIP
#   scripts/kube-vip.sh failover    # move the VIP by deleting the leader's pod, measure loss
#
# Read-only except for `failover`, which deletes one kube-vip pod.
set -uo pipefail

VIP="${V31_KUBE_VIP_ADDRESS:-192.168.139.240}"
GATEWAY_VIP="${V31_ISTIO_GATEWAY_VIP:-192.168.139.241}"
PORT="${V31_KUBE_VIP_PORT:-6443}"
IFACE="${V31_KUBE_VIP_INTERFACE:-eth0}"
LEASE="${V31_KUBE_VIP_LEASENAME:-plndr-cp-lock}"
SSH_KEY="${V31_ORB_SSH_KEY:-${HOME}/.orbstack/ssh/id_ed25519}"
SSH_USER="${V31_ORB_SSH_USER:-wangxiang}"
CONTROL_PLANE=(cp1 cp2 cp3)
WORKERS=(w1 w2 w3)
PINNED_INDEX_DIGEST="sha256:dde4c0669d9058c74c69c0bc2f0122e26900e1e4b913c03577cb6e4e28083079"

rc=0
ok()   { printf '  ok    %s\n' "$*"; }
bad()  { printf '  FAIL  %s\n' "$*"; rc=1; }
warn() { printf '  warn  %s\n' "$*"; }
head1(){ printf '\n== %s\n' "$*"; }

on() { # on <host> <command...>
  local h="$1"; shift
  ssh -i "$SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
      -o ConnectTimeout=10 "${SSH_USER}@${h}.orb.local" "$*" 2>/dev/null
}

kctl() { on "${CONTROL_PLANE[0]}" "sudo kubectl --kubeconfig /etc/kubernetes/admin.conf $*"; }

# Which node's MAC currently answers ARP for an address, as seen from a peer node.
vip_holder_mac() {
  # NOT `ip -br neigh`: brief prints "<addr> <mac>" (mac in $2) while the default
  # prints "<addr> lladdr <mac> <state>" (mac in $3). This parsed $3 of the brief
  # form, always got an empty string, and reported "ARP says none" for a VIP that
  # was demonstrably held -- every other check in this file said so. Match on
  # lladdr so the field position is anchored rather than counted.
  on "${WORKERS[0]}" "ping -c1 -W1 $1 >/dev/null 2>&1; ip neigh show $1 dev $IFACE" \
    | awk '/lladdr/ {print $3; exit}'
}

node_mac() { on "$1" "cat /sys/class/net/${IFACE}/address"; }

resolve_holder() { # resolve_holder <addr> -> hostname or empty
  local want; want="$(vip_holder_mac "$1")"
  [[ -z "$want" ]] && return 0
  local h
  for h in "${CONTROL_PLANE[@]}"; do
    [[ "$(node_mac "$h")" == "$want" ]] && { echo "$h"; return 0; }
  done
  echo "unknown-mac:${want}"
}

cmd_preflight() {
  head1 "SSH and sudo on all six nodes"
  local h
  for h in "${CONTROL_PLANE[@]}" "${WORKERS[@]}"; do
    if [[ "$(on "$h" 'sudo -n true && echo yes')" == "yes" ]]; then ok "$h reachable, passwordless sudo"
    else bad "$h unreachable or sudo needs a password"; fi
  done

  head1 "Interface ${IFACE} exists and carries a 192.168.139.0/24 address"
  for h in "${CONTROL_PLANE[@]}" "${WORKERS[@]}"; do
    local a; a="$(on "$h" "ip -4 -o addr show ${IFACE} | awk '{print \$4}' | paste -sd, -")"
    case "$a" in
      192.168.139.*) ok "$h ${IFACE} ${a}" ;;
      "")            bad "$h has no IPv4 on ${IFACE}" ;;
      *)             bad "$h ${IFACE} is ${a}, not on 192.168.139.0/24" ;;
    esac
  done

  head1 "VIPs are unclaimed"
  local v
  for v in "$VIP" "$GATEWAY_VIP"; do
    if on "${WORKERS[0]}" "ping -c2 -W1 $v >/dev/null 2>&1"; then
      bad "$v already answers: $(resolve_holder "$v")"
    else
      ok "$v silent from ${WORKERS[0]}"
    fi
    if ping -c2 -W1000 "$v" >/dev/null 2>&1; then bad "$v answers from the macOS host too"
    else ok "$v silent from the macOS host"; fi
  done

  head1 "No stale kube-vip state"
  for h in "${CONTROL_PLANE[@]}"; do
    local s; s="$(on "$h" 'ls /etc/kubernetes/manifests/kube-vip.yml 2>/dev/null')"
    [[ -z "$s" ]] && ok "$h has no kube-vip static pod yet" || warn "$h already has $s"
  done

  head1 "Nothing else will answer ARP for the VIP range"
  warn "OrbStack's DHCP covers all of 192.168.139.0/24 and does not probe for"
  warn "conflicts. Re-run this before creating any new OrbStack machine."
  return $rc
}

cmd_verify() {
  head1 "VIP is up and held by exactly one control-plane node"
  local holder; holder="$(resolve_holder "$VIP")"
  if [[ -z "$holder" ]]; then bad "$VIP answers nobody"
  elif [[ "$holder" == unknown-mac:* ]]; then bad "$VIP held by ${holder#unknown-mac:} - not cp1/cp2/cp3"
  else ok "$VIP held by $holder"; fi

  local h n=0
  for h in "${CONTROL_PLANE[@]}"; do
    on "$h" "ip -4 -o addr show ${IFACE}" | grep -q "${VIP}/32" && { ok "$h carries ${VIP}/32"; n=$((n+1)); }
  done
  [[ "$n" -eq 1 ]] && ok "exactly one node carries the VIP" || bad "$n nodes carry the VIP (want 1)"

  head1 "API server answers through the VIP from every node and the macOS host"
  for h in "${CONTROL_PLANE[@]}" "${WORKERS[@]}"; do
    if [[ "$(on "$h" "timeout 5 bash -c '</dev/tcp/${VIP}/${PORT}' && echo up")" == "up" ]]; then
      ok "$h -> ${VIP}:${PORT}"
    else bad "$h cannot reach ${VIP}:${PORT}"; fi
  done
  if nc -z -G 3 "$VIP" "$PORT" >/dev/null 2>&1; then ok "macOS host -> ${VIP}:${PORT}"
  else bad "macOS host cannot reach ${VIP}:${PORT}"; fi

  head1 "kubeadm and every kubeconfig point at the VIP"
  local ep; ep="$(on "${CONTROL_PLANE[0]}" "sudo awk '/server:/{print \$2; exit}' /etc/kubernetes/admin.conf")"
  [[ "$ep" == "https://${VIP}:${PORT}" ]] && ok "admin.conf server is $ep" || bad "admin.conf server is $ep"
  local wep; wep="$(on "${WORKERS[0]}" "sudo awk '/server:/{print \$2; exit}' /etc/kubernetes/kubelet.conf")"
  [[ "$wep" == "https://${VIP}:${PORT}" ]] && ok "${WORKERS[0]} kubelet.conf server is $wep" || bad "${WORKERS[0]} kubelet.conf server is $wep"

  head1 "Lease and node label agree with the wire"
  local lease_holder; lease_holder="$(kctl "-n kube-system get lease ${LEASE} -o jsonpath={.spec.holderIdentity}")"
  [[ -n "$lease_holder" ]] && ok "lease ${LEASE} held by ${lease_holder}" || bad "lease ${LEASE} missing or unheld"
  [[ -n "$holder" && "$lease_holder" == "$holder" ]] && ok "lease holder matches the ARP holder" \
    || warn "lease holder ${lease_holder:-none} vs ARP holder ${holder:-none}"
  local labelled; labelled="$(kctl "get nodes -l kube-vip.io/has-ip=${VIP} -o jsonpath={.items[*].metadata.name}")"
  [[ "$labelled" == "$holder" ]] && ok "node label kube-vip.io/has-ip=${VIP} on $labelled" \
    || warn "kube-vip.io/has-ip=${VIP} on '${labelled:-none}', ARP says '${holder:-none}'"

  head1 "Static pods are running the pinned image"
  for h in "${CONTROL_PLANE[@]}"; do
    # repoDigests is empty on these nodes -- measured 2026-09-27, containerd records
    # only repoTags for this image, so a digest comparison can never pass however
    # correct the image is. Verify the tag, and confirm the digest only when
    # containerd actually has one. Reporting "no kube-vip image" for an image that
    # is present and serving metrics is worse than admitting the digest is unknown.
    local dg tg
    dg="$(on "$h" "sudo crictl inspecti -o go-template --template '{{range .status.repoDigests}}{{.}} {{end}}' ghcr.io/kube-vip/kube-vip:v1.2.4" 2>/dev/null)"
    tg="$(on "$h" "sudo crictl inspecti -o go-template --template '{{range .status.repoTags}}{{.}} {{end}}' ghcr.io/kube-vip/kube-vip:v1.2.4" 2>/dev/null)"
    case "$dg" in
      *"$PINNED_INDEX_DIGEST"*) ok "$h image digest matches image-pin.yaml" ;;
      *)
        case "$tg" in
          *"kube-vip:v${KUBE_VIP_VERSION:-1.2.4}"*)
            warn "$h image is ${tg% }; containerd reports no repoDigest, so the pin is unconfirmed" ;;
          "") bad "$h has no kube-vip image" ;;
          *)  bad "$h image is ${tg% }, expected v${KUBE_VIP_VERSION:-1.2.4}" ;;
        esac ;;
    esac
  done
  kctl "-n kube-system get pods -l k8s-app=kube-vip -o wide" || bad "cannot list kube-vip pods"

  head1 "Metrics endpoint"
  for h in "${CONTROL_PLANE[@]}"; do
    on "$h" "curl -sf -m3 http://127.0.0.1:2112/metrics >/dev/null && echo up" | grep -q up \
      && ok "$h serves metrics on 2112" || warn "$h metrics on 2112 not answering"
  done
  return $rc
}

cmd_failover() {
  local before; before="$(resolve_holder "$VIP")"
  head1 "VIP currently on ${before:-nobody}"
  [[ -z "$before" || "$before" == unknown-mac:* ]] && { bad "no clean holder, refusing"; return 1; }

  local pod; pod="$(kctl "-n kube-system get pods -l k8s-app=kube-vip --field-selector spec.nodeName=${before} -o jsonpath={.items[0].metadata.name}")"
  [[ -z "$pod" ]] && { bad "no kube-vip pod on ${before}"; return 1; }

  local log="${TMPDIR:-/tmp}/kube-vip-failover.$$"

  # BSD ping on macOS refuses a sub-second -i for a non-root user, and does so by
  # exiting immediately -- which would leave $log empty and the gap report silent.
  # Probe once, then pick the interval and the seq-to-seconds factor together.
  local pint=0.2 pcount=200 pfactor=0.2
  if ! ping -i 0.2 -c 1 -t 1 127.0.0.1 >/dev/null 2>&1; then
    pint=1; pcount=60; pfactor=1.0
    printf '  note: sub-second ping needs root here; sampling at 1s instead\n'
  fi

  ping -i "$pint" -c "$pcount" "$VIP" > "$log" 2>&1 &
  local pp=$!
  sleep 1
  if ! kill -0 "$pp" 2>/dev/null && [[ ! -s "$log" ]]; then
    bad "ping did not start; failover timing will not be measured"
  fi
  sleep 2
  printf '  deleting %s on %s at %s\n' "$pod" "$before" "$(date +%T)"
  kctl "-n kube-system delete pod ${pod} --wait=false" >/dev/null

  local after="" i
  for i in $(seq 1 40); do
    sleep 1
    after="$(resolve_holder "$VIP")"
    [[ -n "$after" && "$after" != "$before" && "$after" != unknown-mac:* ]] && break
  done
  wait $pp 2>/dev/null

  head1 "Result"
  if [[ -n "$after" && "$after" != "$before" ]]; then ok "VIP moved ${before} -> ${after} in ~${i}s"
  else bad "VIP did not move off ${before} within 40s (now: ${after:-nobody})"; fi
  grep -E 'packets transmitted' "$log" | sed 's/^/  /'
  awk '/icmp_seq=/{match($0,/icmp_seq=[0-9]+/); s=substr($0,RSTART+9,RLENGTH-9)+0;
       if (p && s!=p+1) printf "  gap: seq %d -> %d (~%.1fs unreachable)\n", p, s, (s-p-1)*f; p=s}' \
       f="$pfactor" "$log"
  rm -f "$log"
  return $rc
}

case "${1:-}" in
  preflight) cmd_preflight ;;
  verify)    cmd_verify ;;
  failover)  cmd_failover ;;
  *) printf 'usage: %s {preflight|verify|failover}\n' "${0##*/}" >&2; exit 2 ;;
esac
