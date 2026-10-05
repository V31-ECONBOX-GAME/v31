# Troubleshooting

Failure modes specific to this stack on this host. Generic Kubernetes debugging
is not repeated here.

Every command assumes `source versions.env` and a working `KUBECONFIG`; most of
the on-node checks are also wrapped by `scripts/postflight.sh`.

---

## 1. A node advertises 41 GiB but pods are killed long before that

**Symptom.** `kubectl describe node cp1` shows tens of gigabytes allocatable.
Pods on that node die with exit code 137 and no eviction event, no
`MemoryPressure` condition, and nothing in the kubelet log.

**Cause.** These machines are cgroup-limited containers inside one OrbStack VM.
`/proc/meminfo` reports the VM's memory, not the cgroup's, and kubelet takes
capacity from `/proc/meminfo`.

```bash
for n in cp1 cp2 cp3 w1 w2 w3; do
  printf '%-4s ' "$n"
  orb -m "$n" sh -c 'echo "MemTotal $(awk "/MemTotal/{printf \"%.2f\", \$2/1048576}" /proc/meminfo) GiB  cgroup $(awk "{printf \"%.2f\", \$1/1073741824}" /sys/fs/cgroup/memory.max) GiB"'
done
```

Expect 41.14 GiB reported against a 4 GiB cgroup on cp1-3 and 10 GiB on w1-3.
The kill comes from the kernel via the cgroup, so kubelet never sees pressure
and never reschedules — the process just dies.

**Fix.** The reservations in
`inventory/v31/group_vars/kube_control_plane/kubelet.yml` and
`inventory/v31/group_vars/kube_node/kubelet.yml` exist for exactly this. Confirm
they took:

```bash
kubectl get nodes -o custom-columns=\
'NAME:.metadata.name,CAP:.status.capacity.memory,ALLOC:.status.allocatable.memory'
```

Allocatable should be roughly 2.15 GiB on control planes and 8.14 GiB on
workers. If it still reads ~39 GiB, the reservation did not apply — re-run
`make deploy` or `cluster.yml --tags=node`. See `docs/runbook.md` section 10.

---

## 2. A pod is in an ambient namespace but is not enrolled

**Symptom.** Traffic to the pod is plaintext; `istioctl ztunnel-config workload`
does not list it.

**Checks, in order.**

```bash
kubectl get ns v31-demo --show-labels | grep dataplane-mode   # istio.io/dataplane-mode=ambient
kubectl -n istio-system get ds ztunnel -o wide                # DESIRED == READY == 6
kubectl -n istio-system logs ds/ztunnel --tail=50
istioctl ztunnel-config workload | grep v31-demo
```

**Common causes.** The namespace label was applied *after* the pod started —
ztunnel enrols on pod creation, so restart the workload. Or istio-cni is not
running on that node, in which case see section 5. Or the pod has
`istio.io/dataplane-mode: none` on it, which wins over the namespace label; the
generated gateway pods carry that deliberately.

---

## 3. A waypoint exists but never receives traffic

**Symptom.** L4 works, mTLS is on, but HTTPRoute rules and L7
`AuthorizationPolicy` have no effect.

**Cause.** ztunnel does L4 only. Nothing above L4 happens until traffic is
*directed* to a waypoint, and the waypoint has to be bound — creating it is not
enough.

```bash
kubectl -n v31-demo get gateway                          # class istio-waypoint, PROGRAMMED=True
kubectl -n v31-demo get ns/svc -o jsonpath='{.metadata.labels}' # istio.io/use-waypoint
istioctl -n v31-demo analyze
istioctl ztunnel-config workload | grep -i waypoint      # a waypoint column per workload
```

**Fix.** The binding label is `istio.io/use-waypoint: <gateway-name>` on the
namespace or the Service. `istio/manifests/sample-app/mesh.yaml` shows both the
Gateway and the binding; apply the Gateway *before* labelling workloads, or the
namespace spends the gap pointing at a waypoint that does not exist.

For traffic that enters through the edge Gateway and should then pass through a
waypoint, `ENABLE_INGRESS_WAYPOINT_ROUTING` must be on in istiod *and* the
Service needs `istio.io/ingress-use-waypoint: "true"`. Both are set in this
repo; if you copy the manifests elsewhere, they travel together.

---

## 4. kube-vip VIP not failing over

**Symptom.** `192.168.139.240:6443` stops answering after the leader node
reboots, or answers from the wrong node.

**Checks.**

```bash
scripts/kube-vip.sh verify
kubectl -n kube-system get lease plndr-cp-lock -o jsonpath='{.spec.holderIdentity}{"\n"}'
for n in cp1 cp2 cp3; do
  printf '%-4s ' "$n"; orb -m "$n" sh -c 'ip -4 -o addr show eth0 | grep -c 192.168.139.240'
done
arp -an | grep 192.168.139.240        # from the macOS host: MAC must match the holder
```

Exactly one node should carry `192.168.139.240/32` and it must be the lease
holder.

**Cause A — the address was taken by DHCP.** OrbStack's DHCP does not probe for
conflicts and can lease `.240` to a new machine. Check whether something else
answers:

```bash
arp -an | grep 192.168.139.240
orb list
```

Fix by applying `playbooks/kube-vip-dhcp-guard.yml`, which installs a
systemd-networkd `SendDecline=yes` drop-in so the guest refuses the offer. The
address cannot be reserved; see `docs/decisions.md` section 6.

**Cause B — leadership is flapping.** The leases here are deliberately loosened
to 15/10/2 because the control planes have 2 vCPU and share a macOS host;
Kubespray's default 5/3/1 loses leadership under load and the VIP oscillates.

```bash
kubectl -n kube-system logs -l name=kube-vip --tail=100 | grep -i 'lead\|arp'
```

If you see repeated acquire/lose cycles, the node is CPU-starved — see section 6.

**Cause C — kube-vip was OOM-killed.** kubeadm renders the static pod with
`resources: {}`, which makes it BestEffort and the kernel's first victim inside
a 4 GiB cgroup. `crictl ps -a | grep kube-vip` on the node will show repeated
exits with code 137.

---

## 5. kubeadm init hangs at wait-control-plane and `crictl ps` is empty

**Symptom.** `kubeadm init` fails with

```
error execution phase wait-control-plane: cannot obtain client without bootstrap:
could not bootstrap the admin user in file admin.conf: unable to create
ClusterRoleBinding: ... dial tcp <node>:6443: connect: connection refused
```

kubelet is `active`, `/etc/kubernetes/manifests/` holds all four manifests, and
`crictl ps -a` shows **nothing at all**. That combination is the tell.

**Cause.** Kubespray hard-codes, in `roles/kubernetes/node/vars/ubuntu-26.yml`:

```yaml
kube_resolv_conf: "/run/systemd/resolve/resolv.conf"
```

Correct on a normal Ubuntu 26.04. OrbStack guests run no systemd-resolved, so
that path does not exist and **every** pod sandbox fails to be generated:

```bash
orb -m cp1 sudo journalctl -u kubelet --since -10min \
  | grep -m3 'Failed to generate sandbox config'
# Failed to generate sandbox config for pod
#   err="open /run/systemd/resolve/resolv.conf: no such file or directory"
```

The apiserver is one of those pods, so 6443 never opens and kubeadm waits until
it times out. Nothing is wrong with the certificates or with etcd.

**Fix.** Already applied: `ORBSTACK_EXTRA` in the Makefile passes
`-e kube_resolv_conf=/etc/resolv.conf` to `deploy`, `upgrade` and `scale`. It has
to be an **extra-var** — role vars outrank `group_vars`, so putting it in
`inventory/v31/` is silently ignored. Confirm it is reaching Ansible with:

```bash
make -n deploy | grep -o 'kube_resolv_conf=[^ ]*'
```

`/etc/resolv.conf` on these guests is a symlink to
`/opt/orbstack-guest/etc/resolv.conf` (the macOS resolver) and is readable.

---

## 6. istio-cni replaced Calico instead of chaining with it

**Symptom.** Pods fail to get an IP, or get one and have no connectivity.
`kubectl describe pod` shows a CNI error naming only `istio-cni`.

**Check the conflist on the node:**

```bash
orb -m w1 sudo sh -c 'ls /etc/cni/net.d/; cat /etc/cni/net.d/10-calico.conflist' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["name"], [p["type"] for p in d["plugins"]])'
```

**Expected:** `k8s-pod-network ['calico', 'portmap', 'bandwidth', 'istio-cni']`.
istio-cni must be **last**, and calico must still be **first**.

**Normal, not a fault:** Calico's `install-cni` runs as an initContainer, so it
rewrites this file every time calico-node restarts — not only on upgrade. The
istio-cni entry disappears for a moment and istio-cni's watcher re-appends it. A
snapshot taken during that window looks broken and is not.

**Actually broken** if `cniConfDir` or `cniBinDir` in `istio/values/cni.yaml`
disagree with where Calico writes (`/etc/cni/net.d` and `/opt/cni/bin`), or if
`chained` is false — then istio-cni writes its own conflist and Calico is
bypassed.

---

## 7. Control-plane CPU starvation

**Symptom.** apiserver latency spikes, etcd leader elections, kube-vip
leadership flapping, `kubectl` timing out intermittently.

**Context.** Each control plane has 2 vCPU (`cpu.max 200000 100000`) and 1400m
allocatable after reservations. What must run there already requests 850m. The
whole VM is 10 real vCPU carrying 18 guest vCPU, so these are nominal numbers —
under load the guests contend.

```bash
kubectl top nodes
kubectl -n kube-system get events --field-selector reason=NodeNotReady
orb -m cp1 sh -c 'cat /sys/fs/cgroup/cpu.stat'   # throttled_usec climbing == contention
```

**Do not** lower `system_cpu_reserved` below 500m on the control planes — etcd
runs as a host systemd service, is charged to `system.slice`, and has to fit
inside it. If you need headroom, raise the machines' CPU in OrbStack instead, or
move workloads to the 4-vCPU workers.

**Never schedule istiod here.** `istio/values/istiod.yaml` enforces that with a
`node-role.kubernetes.io/control-plane DoesNotExist` requirement.

---

## 8. kubelet refuses to start because of swap

**Symptom.** kubelet exits at boot complaining about swap being enabled.

**Context.** Swap here is zram plus a `vdc` device set up by OrbStack at boot.
It is **not** in `/etc/fstab`, so disabling it in the guest does not survive a
restart of the machine.

```bash
orb -m cp1 sh -c 'swapon --show'
```

`playbooks/orbstack-prepare.yml` turns it off and installs a `MemorySwapMax=0`
drop-in for `etcd.service` so the most latency-sensitive component stays off
swap even if OrbStack re-enables it. `kubelet_swap_behavior: NoSwap` covers the
pods; it does not cover etcd, because etcd is a host service here
(`etcd_deployment_type: host`).

---

## 9. A node's address changed

**Symptom.** A node goes `NotReady`; or etcd peers cannot reach each other after
a reboot; or certificates suddenly fail hostname verification.

```bash
scripts/ip-drift.sh          # if present; otherwise compare by hand:
kubectl get nodes -o custom-columns='NAME:.metadata.name,IP:.status.addresses[?(@.type=="InternalIP")].address'
for n in cp1 cp2 cp3 w1 w2 w3; do
  printf '%-4s ' "$n"; orb -m "$n" sh -c 'ip -4 -o addr show eth0 | awk "{print \$4}"'
done
```

A **worker** whose address moves usually re-registers on its own. A **control
plane** does not: the apiserver certificate, the etcd peer URL and the etcd
member list all carry the old address. `etcd_cert_alt_ips` in
`inventory/v31/group_vars/all/etcd.yml` pre-seeds all six addresses so the
certificate still covers the node — but the etcd member list still needs the
repair procedure in `docs/runbook.md`.

---

## 10. The edge Gateway has no external address

**Symptom.** `kubectl -n istio-ingress get svc` shows `EXTERNAL-IP <pending>`
forever.

```bash
kubectl -n istio-ingress get gateway v31-edge -o yaml | grep -A5 status
kubectl -n istio-ingress get svc -o yaml | grep -A3 annotations
kubectl -n kube-system logs -l name=kube-vip --tail=50 | grep -i service
```

The address comes from the `kube-vip.io/loadbalancerIPs: "192.168.139.241"`
annotation on the Service, patched in through the `v31-edge-options` ConfigMap.
If the annotation is missing, the strategic-merge patch did not apply — check
that `infrastructure.parametersRef` on the Gateway still names that ConfigMap.

There is **no** cloud provider and **no** address pool in play; if someone
applied `istio/manifests/kubevip-address-pool.yaml`, remove it. Two allocators
for one address ends badly.

Even with no address, the Service keeps fixed nodePorts, so the edge is reachable
at `<any-node>:30080` for debugging.

---

## 11. Every API call fails with "Service Unavailable" after exactly 5 seconds

**Symptom.** `kubectl` from the control machine fails on everything:

```
couldn't get current server API group list: Get "https://192.168.139.240:6443/api": Service Unavailable
```

Meanwhile the cluster is fine: `ssh cp1 sudo kubectl get nodes` works, all nodes
are Ready, and `nc -z 192.168.139.240 6443` succeeds.

**Cause.** A local HTTP proxy. The 503 comes from the *proxy*, not Kubernetes.
The tell is in curl's timings:

```bash
curl -sk --max-time 30 -o /dev/null \
  -w 'connect %{time_connect}s  tls %{time_appconnect}s  total %{time_total}s\n' \
  https://192.168.139.240:6443/readyz
# connect 0.000332s  tls 0.000000s  total 5.057908s
```

TCP connects in under a millisecond, TLS **never starts**, and it gives up at
exactly 5.0s no matter what `--max-time` says — a proxy cutting the CONNECT, not
a slow server. Confirm with:

```bash
env | grep -i proxy
curl -sk --noproxy '*' -o /dev/null -w '%{http_code}\n' https://192.168.139.240:6443/readyz   # 200
```

**Fix.** Already applied: the Makefile and `bin/kubespray.sh` both export

```
NO_PROXY=192.168.139.0/24,192.168.138.0/24,.orb.local,localhost,127.0.0.1,::1
```

so no target here can be intercepted. If you call `kubectl` by hand outside
`make`, export the same value, or add the cluster ranges to your proxy client's
bypass list — a `NO_PROXY` of only `localhost,127.0.0.1,::1,.local` does not
cover them.

---

## 12. Nothing can reach any node over SSH

**Symptom.** Ansible, Termius and `ssh cp1` all fail at once.

**Cause.** There is no `sshd` inside these machines — port 22 is OrbStack's own
gateway. When OrbStack is not running, SSH to every node is gone simultaneously.

**Escape hatch.** `orb -m <machine> <command>` does not use SSH and still works
whenever OrbStack itself is up. If OrbStack is down, start it; there is no other
path in. See `docs/decisions.md` section 9.
