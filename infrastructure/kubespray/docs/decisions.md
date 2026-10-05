# Decisions

Why this stack looks the way it does. Each entry states the alternative that was
rejected and what the choice costs, because every one of them is a trade.

Measurements quoted here were taken on the real machines on 2026-09-26; the
commands that produced them are in `scripts/preflight.sh` and `scripts/kube-vip.sh`.

---

## 1. Kubespray, not kubeadm by hand

**Context.** Six nodes, three of them control planes, rebuilt often.

**Decision.** Kubespray v2.32.0 pinned as a clone, never vendored.

**Consequences.** One release pins Kubernetes, etcd, containerd, runc, CNI
plugins, CoreDNS and Calico together, which removes a whole class of version
skew. The cost is that Kubespray's defaults are invisible: anything not set in
`inventory/v31/` comes from `roles/kubespray_defaults`, so reading this
directory alone never tells you the full configuration. Every variable set here
is set because the default was wrong for this environment, and says why in a
comment.

---

## 2. Calico, not Cilium

**Context.** The CNI has to carry Istio ambient on top of it.

**Decision.** Calico 3.31.7, as shipped by the pinned Kubespray release,
iptables dataplane, VXLAN off where the segment is flat.

**Consequences.** Calico is what Kubespray installs and tests; Cilium would mean
taking the CNI out of Kubespray's version matrix and owning it separately. The
cost is giving up Cilium's eBPF datapath and its native L7 story — but the L7
requirement here is met by Istio waypoints, so that overlap would have been
wasted anyway.

**Why not Calico's own eBPF dataplane.** It replaces kube-proxy, which changes
how ztunnel's readiness probes resolve, and istio-cni then needs Calico's
connect-time load balancing configured to match. Architecture is not the
obstacle — Felix builds BPF for arm64 and this kernel is 7.0.14, well past the
5.8 floor — the obstacle is the number of moving parts. `calico_bpf_enabled`
stays `false`.

---

## 3. Istio ambient, not sidecars

**Context.** The requirement was explicitly ambient *with* L7.

**Decision.** ztunnel as a DaemonSet for L4, Envoy waypoint proxies for L7,
enrolled per namespace with `istio.io/dataplane-mode=ambient`.

**Consequences.** No sidecar in every pod means a much smaller per-workload
footprint, which matters when three of six nodes have a 4 GiB cgroup. It also
means L7 is *opt-in*: ztunnel alone gives mTLS and L4 authorization, and nothing
above that works until a waypoint exists for the namespace or service. That is a
feature here — you pay for Envoy only where you route on HTTP — but it surprises
people who expect sidecar behaviour by default.

---

## 4. Gateway API, not the legacy Istio Gateway CRD

**Context.** North-south traffic, plus waypoints, which are themselves Gateway
API resources.

**Decision.** Gateway API v1.6.2 standard channel, installed by the Istio track
rather than by Kubespray.

**Consequences.** One CRD version, owned by one file, applied before istiod so
the ordering is explicit. `gateway_api_enabled: false` in `addons.yml` keeps
Kubespray out of it — otherwise a Kubespray bump could move the CRD version
underneath Istio. The cost is one more manual step in the install sequence, and
it must be a **server-side** apply: the v1.6.2 bundle is about 1.1 MB and the
largest CRDs exceed the 262144-byte annotation a client-side apply writes.

---

## 5. kube-vip in ARP mode, not haproxy + keepalived

**Context.** Three control planes need one endpoint. The segment is a Linux
bridge inside OrbStack on a macOS host, not a physical switch, so layer-2
failover was not safe to assume.

**Decision.** kube-vip 1.2.4, ARP mode, leader election on `plndr-cp-lock`.

**Evidence.** Measured before choosing: a test VIP was moved cp1 → cp2 and every
peer node *and* the macOS host updated its ARP cache to the new MAC, with no
loss. OrbStack's bridge behaves like a learning switch.

**Consequences.** One static pod per control plane, no extra daemon, no second
address to operate. Rejected alternatives: BGP mode has nothing to peer with
here; haproxy+keepalived is two more components for the same result; Kubespray's
localhost load balancer works without any VIP but gives every client a different
view of which apiserver is up.

**What it costs.** See decision 6 — the address cannot be reserved.

---

## 6. Accepting that the VIP cannot be reserved

**Context.** OrbStack's DHCP server owns the whole `192.168.139.0/24`.

**Measured.** It allocates deterministically from a hash of the client MAC,
**ignores the Requested-IP option** (asked for `.250`, was offered `.131`), and
does **not** probe for conflicts — 10 of 60 offers landed inside an address block
that was actively answering ARP. It does honour `DHCPDECLINE`.

**Decision.** Put the VIP at `192.168.139.240`, record every static address in
`manifests/kube-vip/address-allocation.yaml`, and ship
`playbooks/kube-vip-dhcp-guard.yml` — a systemd-networkd `SendDecline=yes`
drop-in — as an opt-in defence.

**Consequences.** The address is defended, not reserved. A new OrbStack machine
created later can still be offered `.240`; the guard makes the *machine* decline
it rather than making the server avoid it. Accepted because the alternative is
no VIP at all.

---

## 7. The `kube-vip.io/loadbalancerIPs` annotation, not the kube-vip cloud provider

**Context.** The Istio edge Gateway needs an external address and there is no
cloud load balancer.

**Decision.** Annotate the Gateway's Service with
`kube-vip.io/loadbalancerIPs: "192.168.139.241"`. No cloud provider, no address
pool ConfigMap.

**Consequences.** kube-vip reads that annotation ahead of `spec.loadBalancerIP`
and writes `status.loadBalancer.ingress` itself, so the address is declared in
git instead of allocated at runtime — one fewer controller, one fewer RBAC
surface, and no dependence on kube-vip-cloud-provider, whose newest release is
16 months older than kube-vip itself. `istio/manifests/kubevip-address-pool.yaml`
remains as the documented alternative and is not applied.

**Do not** add MetalLB or any second L2 announcer alongside this. Two things
gratuitously ARPing for addresses on the same segment is a bad day.

---

## 8. istiod on the workers, never on the control planes

**Context.** cp1/cp2/cp3 have 2 vCPU and a 4 GiB cgroup.

**Measured.** What must run on a control plane already requests 850m of 1400m
allocatable CPU. istiod's own requests would push the total past capacity, and
its 1 GiB request against a 4 GiB cgroup invites a kernel OOM kill of whatever
else is there — including kube-vip, which kubeadm renders as BestEffort.

**Decision.** `istio/values/istiod.yaml` requires
`node-role.kubernetes.io/control-plane DoesNotExist`.

**Consequences.** Stated as a requirement rather than left to chance. Today
istiod avoids the control planes anyway, but only because cp1-3 are absent from
`kube_node` and therefore keep kubeadm's NoSchedule taint — an accident that
would silently reverse if a control plane were ever added to `kube_node`.

---

## 9. OrbStack's SSH gateway, not sshd inside the guests

**Context.** Ansible needs SSH to all six nodes.

**Decision.** Connect to `<name>.orb.local:22` with
`~/.orbstack/ssh/id_ed25519`. No `openssh-server` is installed in the machines.

**Evidence.** SCP, SFTP, `sudo -n` and six concurrent connections were all
verified working through the gateway — everything Ansible actually uses.

**Consequences.** One fewer service to install, patch and keep off port
collisions, and the key is managed by OrbStack. The cost is a hard dependency:
when OrbStack is not running, nothing can reach the nodes over SSH at all. The
`orb -m <machine>` channel still works from the macOS host and is the escape
hatch; Termius and Ansible have none.

---

## 10. Living with DHCP node addresses

**Context.** Leases are one day and OrbStack offers no static-IP setting.

**Decision.** Do not fight it. Seed every node address into the apiserver SANs
(`supplementary_addresses_in_ssl_keys`) *and* separately into the etcd SANs
(`etcd_cert_alt_ips` — the apiserver variable does not reach etcd), and make
rebuilding cheap instead of making addresses permanent.

**Consequences.** A worker whose address moves re-registers and mostly heals; a
control plane whose address moves needs the repair in
`docs/runbook.md`. For a lab cluster that is rebuilt often this is the right
trade, but it would not be acceptable for anything long-lived.

**Confirmed 2026-09-27.** Five of six addresses moved on a stop/start, and the one
mechanism that would have pinned them — a static netplan drop-in — was measured to
take the machine off OrbStack's ssh gateway on its next restart, costing Ansible
its transport. "Do not fight it" is therefore not resignation but the only option
that keeps the cluster repairable. `scripts/ip-drift.sh` and
`docs/ip-drift.md` are what make it cheap.
