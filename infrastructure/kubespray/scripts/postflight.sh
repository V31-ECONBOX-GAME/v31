#!/usr/bin/env bash
# Post-install smoke test, run from the macOS control machine after cluster.yml and
# istio/install.yml. Proves the cluster works rather than that it was configured:
# nodes Ready, system pods healthy, the VIP answering, CoreDNS resolving, cross-node
# pod traffic through Calico, ztunnel on every node, a waypoint taking traffic and
# the edge Gateway holding an address.
#
# Plus the two checks this environment specifically needs, because nothing else
# notices either failure until something breaks:
#   * the node addresses Kubernetes registered still match the current DHCP leases
#   * istio-cni chained itself onto Calico's conflist instead of replacing it
#
#   scripts/postflight.sh            # matrix, then details for anything not PASS
#   scripts/postflight.sh -v         # every row
#   scripts/postflight.sh --no-probe # skip the two checks that create a pod
#   scripts/postflight.sh --keep     # leave the probe namespace behind
#
# Creates and deletes one namespace, v31-postflight, with two pods pinned to
# different nodes; everything else is read-only. Depth on the VIP itself is
# scripts/kube-vip.sh verify, and on the mesh istio/verify.yml.
#
# Exit 0 all clear (warnings allowed), 1 at least one FAIL, 2 could not run.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)/common.sh"
. "$V31_ROOT/versions.env"

VERBOSE=0
DO_PROBE=1
KEEP=0
PROBE_NS=${V31_PROBE_NAMESPACE:-v31-postflight}
DEMO_NS=${V31_DEMO_NAMESPACE:-v31-demo}
# Overridable: on an oversubscribed host (measured 2026-09-27 with the OrbStack
# Helper at 98.8% CPU) two probe pods can need well over two minutes just to be
# scheduled and pulled. A timeout here is a host-capacity signal, not a network
# fault -- verify by hand before believing it: see docs/troubleshooting.md.
PROBE_TIMEOUT=${V31_PROBE_TIMEOUT:-180s}

while [ $# -gt 0 ]; do
  case $1 in
    -v | --verbose) VERBOSE=1 ;;
    --no-probe) DO_PROBE=0 ;;
    --keep) KEEP=1 ;;
    -h | --help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done

# bin/kubectl when make tools has run, so the client matches the pinned server.
KUBECTL=${V31_KUBECTL:-}
if [ -z "$KUBECTL" ]; then
  if [ -x "$V31_ROOT/bin/kubectl" ]; then KUBECTL=$V31_ROOT/bin/kubectl; else KUBECTL=kubectl; fi
fi
command -v "$KUBECTL" >/dev/null 2>&1 || die "kubectl not found ($KUBECTL); run: make tools"

need_cmd ssh
need_cmd awk
need_cmd curl

inventory_load
NODES=$(inventory_names)

export KUBECONFIG=${KUBECONFIG:-$V31_INVENTORY_DIR/artifacts/admin.conf}
[ -f "$KUBECONFIG" ] ||
  die "no kubeconfig at $KUBECONFIG (kubeconfig_localhost writes it during cluster.yml)"

VIP=${V31_KUBE_VIP_ADDRESS:-$KUBE_VIP_ADDRESS}
# The Gateway Service's address is set by an annotation in the committed manifest;
# read it there rather than writing the number down a third time.
GATEWAY_VIP=${V31_ISTIO_GATEWAY_VIP:-$(awk -F'"' \
  '/kube-vip\.io\/loadbalancerIPs/ {print $2; exit}' \
  "$V31_ROOT/istio/manifests/ingress-gateway.yaml")}
EDGE_HOST=${V31_EDGE_HOST:-echo.v31.local}
ISTIO_NS=istio-system
INGRESS_NS=istio-ingress

TMP=$(mktemp -d "${TMPDIR:-/tmp}/v31-postflight.XXXXXX")
RESULTS=$TMP/results.tsv
results_init "$RESULTS"

cleanup() {
  if [ "$DO_PROBE" = 1 ] && [ "$KEEP" = 0 ]; then
    k delete namespace "$PROBE_NS" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

add() { result_add "$RESULTS" "$@"; }
k()   { "$KUBECTL" --request-timeout="${V31_KUBECTL_TIMEOUT:-25s}" "$@"; }
kq()  { k "$@" 2>/dev/null; }

# ---------------------------------------------------------------- api server ----

check_api() {
  heading "api server"
  local server ready

  server=$(awk '/server:/ {print $2; exit}' "$KUBECONFIG")
  if [ "$server" = "https://$VIP:6443" ]; then
    add local api endpoint PASS "$server"
  else
    add local api endpoint FAIL "kubeconfig points at $server, not https://$VIP:6443"
  fi

  ready=$(kq get --raw /readyz) || ready=
  if [ "$ready" = ok ]; then
    add local api readyz PASS ok
  else
    add local api readyz FAIL "/readyz said '${ready:-nothing}' through $server"
    err "the apiserver is not answering; nothing below will be meaningful"
  fi

  local client
  client=$(kq version --client -o yaml | awk '/gitVersion:/ {print $2; exit}') || client=
  if [ "$client" = "v$KUBE_VERSION" ]; then
    add local api "kubectl version" PASS "$client"
  else
    add local api "kubectl version" WARN \
      "client is ${client:-unknown}, the cluster is v$KUBE_VERSION (make tools pins it)"
  fi
}

# --------------------------------------------------------------------- nodes ----

check_nodes() {
  heading "nodes"
  local n name ready version ip sched seen=''

  kq get nodes -o go-template --template \
    '{{range .items}}{{.metadata.name}}{{"\t"}}{{range .status.conditions}}{{if eq .type "Ready"}}{{.status}}{{end}}{{end}}{{"\t"}}{{.status.nodeInfo.kubeletVersion}}{{"\t"}}{{range .status.addresses}}{{if eq .type "InternalIP"}}{{.address}}{{end}}{{end}}{{"\t"}}{{if .spec.unschedulable}}unschedulable{{else}}schedulable{{end}}{{"\n"}}{{end}}' \
    >"$TMP/nodes" || : >"$TMP/nodes"

  if [ ! -s "$TMP/nodes" ]; then
    add local nodes list FAIL "kubectl get nodes returned nothing"
    return
  fi

  while IFS=$'\t' read -r name ready version ip sched; do
    [ -n "${name:-}" ] || continue
    seen="$seen $name"
    if [ "$ready" = True ]; then add "$name" nodes Ready PASS "$ip"
    else add "$name" nodes Ready FAIL "Ready=${ready:-unknown}"; fi
    if [ "$version" = "v$KUBE_VERSION" ]; then add "$name" nodes kubelet PASS "$version"
    else add "$name" nodes kubelet FAIL "kubelet is $version, versions.env pins v$KUBE_VERSION"; fi
    if [ "$sched" = schedulable ]; then add "$name" nodes scheduling PASS schedulable
    else add "$name" nodes scheduling WARN "cordoned - a drain did not finish"; fi
  done <"$TMP/nodes"

  for n in $NODES; do
    case " $seen " in
      *" $n "*) : ;;
      *) add "$n" nodes registered FAIL "in the inventory but not in the cluster" ;;
    esac
  done
  for n in $seen; do
    case " $NODES " in
      *" $n "*) : ;;
      *) add local nodes "extra node $n" WARN "in the cluster but not in hosts.yaml" ;;
    esac
  done
}

# --------------------------------------------------------------- system pods ----

check_system_pods() {
  heading "system pods"
  local ns name phase node ready restarts total pending=0

  kq get pods -A -o go-template --template \
    '{{range .items}}{{.metadata.namespace}}{{"\t"}}{{.metadata.name}}{{"\t"}}{{.status.phase}}{{"\t"}}{{if .spec.nodeName}}{{.spec.nodeName}}{{else}}-{{end}}{{"\t"}}{{range .status.conditions}}{{if eq .type "Ready"}}{{.status}}{{end}}{{end}}{{"\t"}}{{range .status.containerStatuses}}{{.restartCount}},{{end}}{{"\n"}}{{end}}' \
    >"$TMP/pods" || : >"$TMP/pods"

  if [ ! -s "$TMP/pods" ]; then
    add local "system pods" list FAIL "kubectl get pods -A returned nothing"
    return
  fi

  while IFS=$'\t' read -r ns name phase node ready restarts; do
    case $ns in kube-system | "$ISTIO_NS" | "$INGRESS_NS") : ;; *) continue ;; esac
    [ -n "${name:-}" ] || continue
    local who=${node:--}
    [ "$who" = - ] && who=local
    case $phase in
      Succeeded) add "$who" "system pods" "$ns/$name" PASS Succeeded ;;
      Running)
        if [ "${ready:-}" = True ]; then
          total=$(printf '%s' "${restarts:-}" | awk -F, '{s = 0; for (i = 1; i < NF; i++) s += $i; print s}')
          if [ "${total:-0}" -gt 5 ]; then
            add "$who" "system pods" "$ns/$name" WARN "Running, $total container restarts"
          else
            add "$who" "system pods" "$ns/$name" PASS "Running"
          fi
        else
          add "$who" "system pods" "$ns/$name" FAIL "Running but not Ready"
        fi ;;
      Pending)
        pending=$((pending + 1))
        add "$who" "system pods" "$ns/$name" FAIL "Pending" ;;
      *) add "$who" "system pods" "$ns/$name" FAIL "$phase" ;;
    esac
  done <"$TMP/pods"

  if [ "$pending" -gt 0 ]; then
    warn "$pending system pods are Pending; kubectl describe one of them"
  fi
}

# ----------------------------------------------------------------------- vip ----

check_vip() {
  heading "control-plane VIP"
  if nc -z -G 3 "$VIP" 6443 >/dev/null 2>&1; then
    add local vip "macOS -> $VIP:6443" PASS "TCP open"
  else
    add local vip "macOS -> $VIP:6443" FAIL "no answer - the Mac reaches it over bridge102"
  fi

  local holder
  holder=$(kq -n kube-system get lease plndr-cp-lock \
    -o jsonpath='{.spec.holderIdentity}') || holder=
  if [ -n "$holder" ]; then add local vip "lease plndr-cp-lock" PASS "held by $holder"
  else add local vip "lease plndr-cp-lock" FAIL "missing or unheld"; fi

  local labelled
  labelled=$(kq get nodes -l "kube-vip.io/has-ip=$VIP" \
    -o jsonpath='{.items[*].metadata.name}') || labelled=
  case $labelled in
    '') add local vip "has-ip label" WARN "no node carries kube-vip.io/has-ip=$VIP" ;;
    *' '*) add local vip "has-ip label" FAIL "more than one node claims the VIP: $labelled" ;;
    *) add local vip "has-ip label" PASS "$labelled" ;;
  esac
}

# ------------------------------------------------------------------- coredns ----

check_coredns() {
  heading "CoreDNS"
  local want have svc
  have=$(kq -n kube-system get deployment coredns \
    -o jsonpath='{.status.readyReplicas}') || have=
  want=$(kq -n kube-system get deployment coredns \
    -o jsonpath='{.spec.replicas}') || want=
  if [ -z "$want" ]; then
    add local coredns deployment FAIL "no coredns Deployment in kube-system"
  elif [ "${have:-0}" = "$want" ]; then
    add local coredns deployment PASS "$have/$want ready"
  else
    add local coredns deployment FAIL "${have:-0}/$want ready"
  fi

  # Kubespray names it coredns; a kubeadm-only cluster names it kube-dns. Accept both.
  svc=$(kq -n kube-system get service coredns -o jsonpath='{.spec.clusterIP}') || svc=
  dnsname=coredns
  if [ -z "$svc" ]; then
    svc=$(kq -n kube-system get service kube-dns -o jsonpath='{.spec.clusterIP}') || svc=
    dnsname=kube-dns
  fi
  if [ -n "$svc" ]; then add local coredns "service $dnsname" PASS "$svc"
  else add local coredns "service coredns/kube-dns" FAIL "absent - no pod can resolve anything"; fi
}

# ------------------------------------------------- pod network and in-cluster DNS
# Two pods pinned to different nodes, in a namespace with no ambient label, so what
# is measured is Calico and kube-proxy and not the mesh. The ambient sample app
# cannot serve for this: its L4 AuthorizationPolicy only admits the waypoint, so a
# direct pod-IP request there is denied by design.

probe_manifest() {
  cat <<YAML
apiVersion: v1
kind: Namespace
metadata:
  name: $PROBE_NS
---
apiVersion: v1
kind: Service
metadata:
  name: probe
  namespace: $PROBE_NS
spec:
  selector: {app: probe}
  ports: [{name: http, port: 8080, targetPort: 8080}]
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: probe
  namespace: $PROBE_NS
spec:
  replicas: 2
  selector:
    matchLabels: {app: probe}
  template:
    metadata:
      labels: {app: probe}
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            - topologyKey: kubernetes.io/hostname
              labelSelector:
                matchLabels: {app: probe}
      containers:
        - name: whoami
          image: docker.io/traefik/whoami:v1.11.0
          args: ["--port=8080", "--name=probe"]
          ports: [{containerPort: 8080}]
          readinessProbe:
            httpGet: {path: /health, port: 8080}
          resources:
            requests: {cpu: 10m, memory: 32Mi}
            limits: {cpu: 200m, memory: 128Mi}
          securityContext:
            allowPrivilegeEscalation: false
            runAsNonRoot: true
            runAsUser: 65532
            capabilities: {drop: ["ALL"]}
        - name: curl
          image: docker.io/curlimages/curl:8.19.0
          command: ["sleep", "infinity"]
          resources:
            requests: {cpu: 10m, memory: 32Mi}
            limits: {cpu: 200m, memory: 128Mi}
          securityContext:
            allowPrivilegeEscalation: false
            runAsNonRoot: true
            # Numeric UID required: the image declares USER as the NAME "curl_user",
            # and with runAsNonRoot the kubelet refuses a user it cannot prove is
            # non-root. The pod then sits in CreateContainerConfigError forever and
            # the rollout wait times out, which reads as a scheduling or network
            # fault. Measured from the image: uid=100(curl_user) gid=101(curl_group).
            runAsUser: 100
            runAsGroup: 101
            capabilities: {drop: ["ALL"]}
YAML
}

check_pod_network() {
  heading "pod network"
  if [ "$DO_PROBE" = 0 ]; then
    add local "pod net" "cross-node" SKIP "--no-probe"
    add local "pod net" "cluster dns" SKIP "--no-probe"
    return
  fi

  if ! probe_manifest | k apply -f - >/dev/null 2>"$TMP/probe.err"; then
    add local "pod net" apply FAIL "$(tr '\n' ' ' <"$TMP/probe.err" | head -c 180)"
    return
  fi

  if ! k -n "$PROBE_NS" rollout status deployment/probe \
       --timeout="$PROBE_TIMEOUT" >/dev/null 2>&1; then
    add local "pod net" schedule FAIL \
      "the two probe pods did not both become Ready in $PROBE_TIMEOUT - podAntiAffinity needs two schedulable nodes"
    return
  fi

  kq -n "$PROBE_NS" get pods -l app=probe -o go-template --template \
    '{{range .items}}{{.metadata.name}}{{"\t"}}{{.status.podIP}}{{"\t"}}{{.spec.nodeName}}{{"\n"}}{{end}}' \
    >"$TMP/probe-pods" || : >"$TMP/probe-pods"

  local a_pod a_ip a_node b_pod b_ip b_node
  a_pod=$(awk -F'\t' 'NR == 1 {print $1}' "$TMP/probe-pods")
  a_node=$(awk -F'\t' 'NR == 1 {print $3}' "$TMP/probe-pods")
  b_pod=$(awk -F'\t' 'NR == 2 {print $1}' "$TMP/probe-pods")
  b_ip=$(awk -F'\t' 'NR == 2 {print $2}' "$TMP/probe-pods")
  b_node=$(awk -F'\t' 'NR == 2 {print $3}' "$TMP/probe-pods")

  if [ -z "${b_ip:-}" ] || [ "$a_node" = "$b_node" ]; then
    add local "pod net" "cross-node" FAIL \
      "both probe pods landed on ${a_node:-nowhere}; there is no cross-node path to test"
    return
  fi

  local code
  code=$(kq -n "$PROBE_NS" exec "$a_pod" -c curl -- \
    curl -sS -m 8 -o /dev/null -w '%{http_code}' "http://$b_ip:8080/") || code=
  if [ "$code" = 200 ]; then
    add "$a_node" "pod net" "pod-to-pod" PASS "$a_node -> $b_node ($b_ip) 200"
    add "$b_node" "pod net" "pod-to-pod" PASS "answered $a_node"
  else
    add "$a_node" "pod net" "pod-to-pod" FAIL \
      "$a_node -> $b_node ($b_ip) returned '${code:-nothing}'; Calico is not carrying cross-node pod traffic"
  fi

  code=$(kq -n "$PROBE_NS" exec "$a_pod" -c curl -- \
    curl -sS -m 8 -o /dev/null -w '%{http_code}' \
    "http://probe.$PROBE_NS.svc.cluster.local:8080/") || code=
  if [ "$code" = 200 ]; then
    add local "pod net" "cluster dns" PASS "probe.$PROBE_NS.svc.cluster.local resolves and routes"
  else
    add local "pod net" "cluster dns" FAIL \
      "probe.$PROBE_NS.svc.cluster.local returned '${code:-nothing}' - CoreDNS or kube-proxy"
  fi

  code=$(kq -n "$PROBE_NS" exec "$a_pod" -c curl -- \
    curl -sSk -m 8 -o /dev/null -w '%{http_code}' \
    https://kubernetes.default.svc.cluster.local/version) || code=
  case $code in
    200 | 401 | 403) add local "pod net" "apiserver from a pod" PASS "HTTP $code" ;;
    *) add local "pod net" "apiserver from a pod" FAIL \
         "returned '${code:-nothing}' - a pod cannot reach the apiserver Service" ;;
  esac
}

# --------------------------------------------------------------- ambient mesh ----

daemonset_check() { # name group
  local ds=$1 group=$2 want have
  want=$(kq -n "$ISTIO_NS" get daemonset "$ds" \
    -o jsonpath='{.status.desiredNumberScheduled}') || want=
  have=$(kq -n "$ISTIO_NS" get daemonset "$ds" \
    -o jsonpath='{.status.numberReady}') || have=
  if [ -z "$want" ]; then
    add local "$group" daemonset FAIL "no $ds DaemonSet in $ISTIO_NS"
    return
  fi
  if [ "${have:-0}" = "$want" ]; then
    add local "$group" daemonset PASS "$have/$want ready"
  else
    add local "$group" daemonset FAIL "${have:-0}/$want ready"
  fi

  # A DaemonSet that is fully ready can still be scheduled on fewer nodes than the
  # cluster has, which for ztunnel means silently unenrolled pods.
  local n on
  # Read the DaemonSet's OWN selector instead of assuming app=<name>: ztunnel uses
  # app=ztunnel but istio-cni-node uses k8s-app=istio-cni-node, and guessing marked
  # all six nodes as missing a pod that was in fact Running on every one of them.
  local sel
  sel=$(kq -n "$ISTIO_NS" get daemonset "$ds" -o go-template --template \
    '{{range $k, $v := .spec.selector.matchLabels}}{{$k}}={{$v}},{{end}}' |
    sed 's/,$//') || sel=
  [ -n "$sel" ] || sel="app=$ds"
  on=$(kq -n "$ISTIO_NS" get pods -l "$sel" -o go-template --template \
    '{{range .items}}{{.spec.nodeName}} {{end}}') || on=
  for n in $NODES; do
    case " $on " in
      *" $n "*) add "$n" "$group" pod PASS "running on $n" ;;
      *) add "$n" "$group" pod FAIL "no $ds pod on this node" ;;
    esac
  done
}

check_mesh() {
  heading "ambient mesh"
  if ! kq get namespace "$ISTIO_NS" >/dev/null 2>&1; then
    add local ztunnel namespace SKIP "$ISTIO_NS does not exist; run make istio"
    add local istio-cni namespace SKIP "$ISTIO_NS does not exist"
    add local waypoint namespace SKIP "$ISTIO_NS does not exist"
    return
  fi
  daemonset_check ztunnel ztunnel
  daemonset_check istio-cni-node istio-cni

  # The waypoint proves itself by rewriting the request: the HTTPRoute in
  # sample-app/mesh.yaml sets x-v31-mesh, and whoami echoes the headers it was given.
  if ! kq get namespace "$DEMO_NS" >/dev/null 2>&1; then
    add local waypoint traffic SKIP "$DEMO_NS absent (istio_deploy_sample_app is false)"
    return
  fi

  local programmed
  programmed=$(kq -n "$DEMO_NS" get gateway "$DEMO_NS-waypoint" \
    -o jsonpath='{.status.conditions[?(@.type=="Programmed")].status}') || programmed=
  if [ "$programmed" = True ]; then
    add local waypoint Programmed PASS "$DEMO_NS-waypoint"
  else
    add local waypoint Programmed FAIL "$DEMO_NS-waypoint is Programmed=${programmed:-unknown}"
  fi

  local body code
  body=$(kq -n "$DEMO_NS" exec deployment/client -- \
    curl -sS -m 8 "http://echo:8080/") || body=
  case $body in
    *X-V31-Mesh*waypoint* | *x-v31-mesh*waypoint*)
      add local waypoint traffic PASS "echo:8080 answered with the waypoint's header" ;;
    '')
      add local waypoint traffic FAIL "echo:8080 returned nothing from the client pod" ;;
    *)
      add local waypoint traffic FAIL \
        "echo:8080 answered without x-v31-mesh, so the request bypassed the waypoint" ;;
  esac

  code=$(kq -n "$DEMO_NS" exec deployment/client -- \
    curl -sS -m 8 -X POST -o /dev/null -w '%{http_code}' "http://echo:8080/") || code=
  if [ "$code" = 403 ]; then
    add local waypoint "L7 policy" PASS "POST refused with 403"
  else
    add local waypoint "L7 policy" FAIL \
      "POST returned '${code:-nothing}', want 403 - the waypoint is not enforcing echo-l7"
  fi
}

# ------------------------------------------------------------- edge gateway -----

check_gateway() {
  heading "edge gateway"
  if ! kq get namespace "$INGRESS_NS" >/dev/null 2>&1; then
    add local gateway namespace SKIP "$INGRESS_NS does not exist; run make istio"
    return
  fi

  local programmed address lb code node_ip
  programmed=$(kq -n "$INGRESS_NS" get gateway v31-edge \
    -o jsonpath='{.status.conditions[?(@.type=="Programmed")].status}') || programmed=
  if [ "$programmed" = True ]; then add local gateway Programmed PASS v31-edge
  else add local gateway Programmed FAIL "v31-edge is Programmed=${programmed:-unknown}"; fi

  address=$(kq -n "$INGRESS_NS" get gateway v31-edge \
    -o jsonpath='{.status.addresses[0].value}') || address=
  if [ -n "$address" ]; then add local gateway "Gateway address" PASS "$address"
  else add local gateway "Gateway address" FAIL "status.addresses is empty"; fi

  lb=$(kq -n "$INGRESS_NS" get service v31-edge-istio \
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}') || lb=
  if [ "$lb" = "$GATEWAY_VIP" ]; then
    add local gateway "LoadBalancer ip" PASS "$lb, as the kube-vip annotation asks"
  elif [ -n "$lb" ]; then
    add local gateway "LoadBalancer ip" FAIL \
      "$lb, but ingress-gateway.yaml annotates $GATEWAY_VIP"
  else
    add local gateway "LoadBalancer ip" FAIL \
      "empty - kube-vip has not honoured kube-vip.io/loadbalancerIPs=$GATEWAY_VIP"
  fi

  code=$(curl -sS -m 6 -o /dev/null -w '%{http_code}' \
    -H "Host: $EDGE_HOST" "http://$GATEWAY_VIP/" 2>/dev/null) || code=
  if [ "$code" = 200 ]; then
    add local gateway "via the VIP" PASS "http://$GATEWAY_VIP/ Host: $EDGE_HOST -> 200"
  elif [ "$code" = 404 ]; then
    add local gateway "via the VIP" WARN \
      "404 - the edge answers but no HTTPRoute claims $EDGE_HOST"
  else
    add local gateway "via the VIP" FAIL \
      "http://$GATEWAY_VIP/ returned '${code:-nothing}'"
  fi

  # The generated Service keeps fixed nodePorts whatever its type, so this path
  # answers even when no LoadBalancer address has been assigned.
  local last_node
  last_node=$(printf '%s' "$NODES" | awk '{print $NF}')
  node_ip=$(inventory_field "$last_node" 3)
  # hosts.yaml has no literal ip: by design; ask the cluster instead of guessing.
  [ -n "$node_ip" ] || node_ip=$(kq get node "$last_node" \
    -o jsonpath='{.status.addresses[?(@.type=="InternalIP")].address}') || node_ip=
  code=$(curl -sS -m 6 -o /dev/null -w '%{http_code}' \
    -H "Host: $EDGE_HOST" "http://$node_ip:30080/" 2>/dev/null) || code=
  case $code in
    200) add local gateway "via nodePort 30080" PASS "$node_ip:30080 -> 200" ;;
    404) add local gateway "via nodePort 30080" WARN "$node_ip:30080 -> 404, no route for $EDGE_HOST" ;;
    *) add local gateway "via nodePort 30080" FAIL "$node_ip:30080 returned '${code:-nothing}'" ;;
  esac
}

# ---------------------------------------------------------------- node probes ----
# Everything that has to be read on the node itself: the live address, who carries
# the VIP, Calico's routes, and the CNI plugin chain.

cat >"$TMP/node-probe.sh" <<'PROBE'
set -uo pipefail
VIP=${1:-}
PODS_CIDR=${2:-}

row() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4"; }
s()   { sudo -n "$@" 2>/dev/null; }

# The address Kubernetes should have registered for this node, right now.
live=$(ip -4 -o addr show eth0 scope global 2>/dev/null |
         awk '{split($4, a, "/"); printf "%s ", a[1]}')
row "live ip" eth0 INFO "${live% }"

if ip -4 -o addr show eth0 2>/dev/null | grep -q "$VIP/32"; then
  row vip carrier INFO "carries $VIP/32"
else
  row vip carrier INFO "does not carry the VIP"
fi

# --- Calico. CrossSubnet must not encapsulate on one flat L2 segment.
prefix=${PODS_CIDR%%/*}; prefix=${prefix%.*.*}
routes=$(ip -4 route show 2>/dev/null | grep "^\(blackhole \)\?$prefix\." || true)
if [ -z "$routes" ]; then
  row calico routes FAIL "no route for $PODS_CIDR - calico-node never programmed this node"
else
  own=$(printf '%s\n' "$routes" | grep -c '^blackhole ' || true)
  peers=$(printf '%s\n' "$routes" | grep -c ' dev eth0' || true)
  encap=$(printf '%s\n' "$routes" | grep -c 'vxlan.calico' || true)
  if [ "${own:-0}" -lt 1 ]; then
    row calico "own block" WARN "no blackhole route; this node has no pod block yet"
  else
    row calico "own block" PASS "$own blackhole route"
  fi
  if [ "${encap:-0}" -gt 0 ]; then
    row calico encapsulation WARN \
      "$encap pod routes go through vxlan.calico; CrossSubnet should route natively here"
  else
    row calico encapsulation PASS "no route uses vxlan.calico"
  fi
  if [ "${peers:-0}" -lt 1 ]; then
    row calico "peer blocks" FAIL "no peer pod block reachable on eth0"
  else
    row calico "peer blocks" PASS "$peers peer routes on eth0"
  fi
fi

# --- CNI chain. istio-cni must have appended itself to Calico's conflist. If it
# installed a file of its own instead, that file sorts first and REPLACES Calico.
cni_dir=/etc/cni/net.d
files=$(s ls -1 "$cni_dir" 2>/dev/null | grep -E '\.conflist$|\.conf$' | sort || true)
if [ -z "$files" ]; then
  row "cni chain" "$cni_dir" FAIL "no CNI configuration on this node"
else
  first=$(printf '%s\n' "$files" | head -n1)
  if [ "$first" = 10-calico.conflist ]; then
    row "cni chain" "first file" PASS "$first"
  else
    row "cni chain" "first file" FAIL \
      "$first sorts before 10-calico.conflist, so the kubelet uses it instead of Calico"
  fi
  rogue=$(printf '%s\n' "$files" | grep -i istio || true)
  if [ -n "$rogue" ]; then
    row "cni chain" "standalone istio conf" FAIL \
      "$(printf '%s ' $rogue)- istio-cni replaced Calico instead of chaining (chained: false)"
  else
    row "cni chain" "standalone istio conf" PASS "none"
  fi

  chain=$(s python3 -c '
import json, sys
with open(sys.argv[1]) as fh:
    print(",".join(p["type"] for p in json.load(fh)["plugins"]))' \
    "$cni_dir/10-calico.conflist" 2>/dev/null) || chain=
  if [ -z "$chain" ]; then
    row "cni chain" "plugin order" FAIL "cannot parse $cni_dir/10-calico.conflist"
  else
    case $chain in
      calico,*istio-cni) row "cni chain" "plugin order" PASS "$chain" ;;
      *istio-cni) row "cni chain" "plugin order" FAIL "$chain - calico is not first" ;;
      *) row "cni chain" "plugin order" FAIL \
           "$chain - istio-cni is not on the end; restart daemonset/istio-cni-node" ;;
    esac
  fi
fi

if s test -x /opt/cni/bin/istio-cni; then
  row "cni chain" /opt/cni/bin/istio-cni PASS present
else
  row "cni chain" /opt/cni/bin/istio-cni FAIL "absent - the conflist would reference nothing"
fi

row probe complete PASS "$(cat /proc/sys/kernel/hostname 2>/dev/null)"
exit 0
PROBE

probe_node() { ssh_node_script "$1" "$TMP/node-probe.sh" "$VIP" "$PODS_CIDR"; }

check_node_probes() {
  heading "on the nodes"
  PODS_CIDR=$(cfg_get kube_pods_subnet) || PODS_CIDR=10.233.64.0/18
  info "reading routes, addresses and the CNI chain from every node"
  fanout "$TMP" out probe_node $NODES || true

  local n g c s d
  for n in $NODES; do
    if [ ! -s "$TMP/$n.out" ]; then
      d=$(head -c 200 "$TMP/$n.err" 2>/dev/null | tr '\t\n' '  ')
      add "$n" ssh probe FAIL "nothing came back over ssh${d:+ - $d}"
      continue
    fi
    while IFS=$'\t' read -r g c s d; do
      [ -n "${g:-}" ] || continue
      add "$n" "$g" "$c" "$s" "${d:-}"
    done <"$TMP/$n.out"
  done

  # Exactly one control-plane node must hold the VIP.
  local carriers=''
  for n in $NODES; do
    if grep -q "carries $VIP/32" "$TMP/$n.out" 2>/dev/null; then carriers="$carriers $n"; fi
  done
  case $(printf '%s' "$carriers" | wc -w | tr -d ' ') in
    1) add local vip carrier PASS "${carriers# } carries $VIP/32" ;;
    0) add local vip carrier FAIL "no node carries $VIP/32" ;;
    *) add local vip carrier FAIL "${carriers# } all carry $VIP/32" ;;
  esac
}

# --------------------------------------------------------------- address drift ---
# The failure this cluster is most likely to hit: a DHCP lease moved, so the address
# Kubernetes registered, the address in hosts.yaml and the address the node actually
# holds stop agreeing. Nothing else notices until etcd or a certificate breaks.

check_drift() {
  heading "address drift"
  local n want registered live
  for n in $NODES; do
    want=$(inventory_field "$n" 3)
    registered=$(awk -F'\t' -v n="$n" '$1 == n {print $4}' "$TMP/nodes" 2>/dev/null)
    live=$(awk -F'\t' '$1 == "live ip" {print $4}' "$TMP/$n.out" 2>/dev/null |
             awk '{print $1}')
    if [ -z "$registered" ] || [ -z "$live" ]; then
      add "$n" "ip drift" "three-way" WARN \
        "incomplete: hosts.yaml=${want:-derived} kubernetes=${registered:-?} live=${live:-?}"
    elif [ -z "$want" ]; then
      # hosts.yaml deliberately carries no literal ip: these addresses are DHCP and
      # Kubespray derives main_ip from ansible_default_ipv4. Two-way is all there is.
      if [ "$registered" = "$live" ]; then
        add "$n" "ip drift" "two-way" PASS "$live (kubernetes == live; hosts.yaml derives it)"
      else
        add "$n" "ip drift" "two-way" FAIL \
          "kubernetes=$registered live=$live - follow docs/runbook.md section 1"
      fi
    elif [ "$want" = "$registered" ] && [ "$want" = "$live" ]; then
      add "$n" "ip drift" "three-way" PASS "$want in all three places"
    else
      add "$n" "ip drift" "three-way" FAIL \
        "hosts.yaml=$want kubernetes=$registered live=$live - follow docs/runbook.md section 1"
    fi
  done
}

# ----------------------------------------------------------------------- run -----

check_api
check_nodes
check_system_pods
check_vip
check_coredns
check_node_probes
check_drift
check_pod_network
check_mesh
check_gateway

heading "result"
render_matrix "$RESULTS" "local $NODES"
render_details "$RESULTS" "$VERBOSE"
hr
results_tally "$RESULTS"

if results_exit_code "$RESULTS"; then
  info "postflight passed"
else
  err "postflight failed; see the details above"
  exit 1
fi
