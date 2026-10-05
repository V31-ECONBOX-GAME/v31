# V31 Kubernetes cluster — Kubespray on OrbStack

Six-node Kubernetes cluster for the V31 Global Bank services, installed by
Kubespray onto OrbStack Linux machines on an Apple Silicon Mac. Three stacked
control-plane/etcd nodes behind a kube-vip VIP, three workers, Calico CNI, and
Istio in **ambient** mode with Gateway API for north–south traffic.

This is a development and integration cluster. It is not bare metal and not a
cloud, and several decisions here exist only because of that — they are recorded
in [docs/decisions.md](docs/decisions.md).

| | |
|---|---|
| Day-2 procedures | [docs/runbook.md](docs/runbook.md) |
| Surviving DHCP node-address drift | [docs/ip-drift.md](docs/ip-drift.md) |
| Failure modes of this stack | [docs/troubleshooting.md](docs/troubleshooting.md) |
| Why each choice was made | [docs/decisions.md](docs/decisions.md) |
| Every version, in one place | [versions.env](versions.env) |

## What this deploys

| Layer | Component | Version lives in |
|---|---|---|
| Installer | Kubespray | `versions.env` → `KUBESPRAY_VERSION` |
| Cluster | Kubernetes, stacked etcd | `inventory/v31/group_vars/k8s_cluster/k8s-cluster.yml` → `kube_version` |
| Runtime | containerd | ships with the Kubespray release |
| CNI | Calico, VXLAN `CrossSubnet`, kdd datastore | ships with the Kubespray release |
| API VIP | kube-vip, ARP + leader election | `inventory/v31/group_vars/all/kube-vip.yml` → `kube_vip_version` |
| Service LB | kube-vip cloud provider | same file → `kube_vip_services_enabled` |
| Mesh | Istio ambient — istiod, ztunnel, istio-cni, waypoints | `istio/versions.yml` → `istio_version` |
| North–south | Gateway API CRDs + `gatewayClassName: istio` | `istio/versions.yml` → `gateway_api_version` |
| Metrics | metrics-server | `inventory/v31/group_vars/k8s_cluster/addons.yml` |

kube-proxy runs in **iptables** mode, not ipvs. Calico uses the standard
iptables dataplane, not eBPF. Both are deliberate; see the decisions file.

## Topology

```
macOS host — MacBookPro18,2 (M1 Pro), 10 cores, 64 GiB, macOS 26.6.2
│
├── OrbStack VM — 10 vCPU, 42 GiB
│   │   The six "machines" are cgroup-limited containers inside this one VM.
│   │   Host bridge102 = 192.168.138.0/23, and the host itself holds 192.168.139.3,
│   │   so the Mac is on the same L2 segment as the nodes and can reach the VIP.
│   │
│   │   node subnet 192.168.139.0/24 — flat L2; gateway and DHCP at 192.168.139.1
│   │  ┌────────────────────────────────────────────────────────────────────────┐
│   │  │  control plane — kube_control_plane + etcd, stacked                    │
│   │  │                                                                        │
│   │  │   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                  │
│   │  │   │ cp1  .27    │   │ cp2  .101   │   │ cp3  .98    │                  │
│   │  │   │ etcd1       │   │ etcd2       │   │ etcd3       │                  │
│   │  │   │ 2 vCPU 4GiB │   │ 2 vCPU 4GiB │   │ 2 vCPU 4GiB │                  │
│   │  │   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘                  │
│   │  │          └─────────────────┼─────────────────┘                         │
│   │  │              kube-vip ARP, lease plndr-cp-lock                          │
│   │  │              VIP 192.168.139.240/32 : 6443  ← every kubelet and         │
│   │  │                                              kubeconfig points here     │
│   │  │                                                                        │
│   │  │  workers — kube_node                                                   │
│   │  │   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                  │
│   │  │   │ w1   .128   │   │ w2   .143   │   │ w3   .212   │                  │
│   │  │   │ 4 vCPU 10GiB│   │ 4 vCPU 10GiB│   │ 4 vCPU 10GiB│                  │
│   │  │   └─────────────┘   └─────────────┘   └─────────────┘                  │
│   │  │   per node: calico-node · ztunnel · istio-cni · kube-proxy             │
│   │  │   istio-ingress gateway: 2 replicas, spread across workers             │
│   │  └────────────────────────────────────────────────────────────────────────┘
│   │
└───┴── SSH gateway — cp1.orb.local .. w3.orb.local → 192.168.138.6-.11, port 22
        There is no sshd inside the machines. Ansible reaches them through this
        gateway, which is why management survives any change of node IP.
```

Traffic path for a request from outside the mesh:

```mermaid
flowchart LR
  C[client on macOS] -->|VIP :80| GW["Gateway v31-edge<br/>istio-ingress<br/>Envoy, Gateway API"]
  GW -->|HBONE, ingress-use-waypoint| WP["waypoint<br/>Envoy, L7 policy"]
  WP -->|HBONE mTLS| ZT["ztunnel on the target node<br/>L4, DaemonSet"]
  ZT -->|in-pod redirect| POD[application pod]
  POD -.->|"peer, L4 only"| ZT
```

ztunnel gives every enrolled pod mTLS and L4 authorization with no sidecar. L7
work — HTTP routing, retries, header policy, `AuthorizationPolicy` on methods
and paths — happens only where a waypoint exists for that namespace or service.

## Environment facts that shape this configuration

Four properties of this environment drive most of the non-obvious settings. Read
these before changing anything.

1. **Node addresses are DHCP leases with a 1-day lifetime**, handed out by
   OrbStack at `192.168.139.1`. They are not pinned. A moved address breaks
   etcd peer URLs, the apiserver advertise address and certificate SANs — this
   is the single most likely failure in this cluster and has its own section at
   the top of the runbook.
2. **`/proc/meminfo` inside a machine reports the whole VM, not the machine's
   cgroup limit.** cp1 is capped at 4 GiB but reports 42 GiB; w1 is capped at
   10 GiB and reports the same 42 GiB. kubelet derives node capacity from
   `/proc/meminfo`, so it over-advertises allocatable memory by roughly 10x on
   the control planes. See the runbook's resource-reservation section.
3. **Swap is created by OrbStack, outside the guest's control** — `/dev/zram0`
   (41 GiB) and `/dev/vdc` (1 GiB), neither in `/etc/fstab`. Kubespray's own
   swapoff cannot keep it off across a reboot, so `kubelet_fail_swap_on: false`
   plus `playbooks/orbstack-prepare.yml` handle it instead.
4. **`/etc/resolv.conf` is a symlink onto a read-only overlay.** Kubespray's
   default `host_resolvconf` mode edits it in place and fails, hence
   `resolvconf_mode: none` and explicit `upstream_dns_servers`.

CIDRs were picked to avoid everything else on this Mac — OrbStack Docker
(192.168.97/107/117/147/148, bridge 192.168.215.0/24), Parallels
(10.211.55.0/24, 10.37.129.0/24), the LAN (192.168.0.0/24) and OrbStack's
external-address mapping range (198.18.0.0/15):

| Range | Use |
|---|---|
| `192.168.139.0/24` | nodes (DHCP) |
| `192.168.139.240` | control-plane VIP — never allocatable to a Service |
| `192.168.139.241` | Istio edge Gateway, claimed by the `kube-vip.io/loadbalancerIPs` annotation |
| `192.168.139.241-245` | kube-vip LoadBalancer pool, unapplied (`istio_apply_kubevip_pool: false`) |
| `10.233.0.0/18` | Services |
| `10.233.64.0/18` | Pods, `/24` per node |

## Prerequisites on the macOS control machine

Kubespray runs from the Mac. Nothing is installed on the nodes by hand.

```bash
brew install python@3.11 git helm kubernetes-cli jq
brew install istioctl            # version must match ISTIO_VERSION
```

The six machines must exist and be running:

```bash
orbctl list        # expect cp1 cp2 cp3 w1 w2 w3, all "running", arch arm64
```

SSH must work without a password. `~/.ssh/config` already carries a block
matching the **short** names:

```bash
ssh cp1 'hostname; sudo -n true && echo passwordless-sudo-ok'
```

> `ssh cp1` works. `ssh cp1.orb.local` does **not** — the `Host` block matches
> `cp1`, not the FQDN, so the FQDN falls through to your default keys. Ansible is
> unaffected because `hosts.yaml` passes `ansible_ssh_private_key_file`
> explicitly. Use the short names for anything you type by hand.

Host keys for all six FQDNs are already in `~/.ssh/known_hosts`, so Ansible will
not prompt. If they are missing:

```bash
ssh-keyscan -H cp1.orb.local cp2.orb.local cp3.orb.local \
                w1.orb.local w2.orb.local w3.orb.local >> ~/.ssh/known_hosts
```

## From clean checkout to working cluster

Every block below assumes `versions.env` has been sourced. It defines
`$V31_INV`, `$KUBESPRAY_SRC` and every version, so no command here spells a
number out.

```bash
cd /Users/wangxiang/IdeaProjects/v31/infrastructure/kubespray
source versions.env
```

### 1. Fetch Kubespray and its Python dependencies

Kubespray is a build input, not V31 source: it is cloned, never vendored, and
`.gitignore` keeps the checkout and the venv out of git.

```bash
git clone --depth 1 --branch "$KUBESPRAY_VERSION" "$KUBESPRAY_REPO" "$KUBESPRAY_SRC"

python3 -m venv "$KUBESPRAY_VENV"
source "$KUBESPRAY_VENV/bin/activate"
pip install -U pip
pip install -r "$KUBESPRAY_SRC/requirements.txt"

ansible --version        # confirm it resolves inside the venv
```

### 2. Confirm the inventory still matches reality

DHCP moved five of these six addresses on 2026-09-27, so assume it has happened
again. This is a 10-second check that saves a broken install.

```bash
make ip-drift                    # or: scripts/ip-drift.sh
scripts/ip-drift.sh --suggest     # paste-ready inventory blocks for the live addresses
```

Non-zero exit means a node's live address no longer matches `hosts.yaml`, the Node
InternalIP, its etcd member peer URL or Calico's node resource. Stop and follow
[Node address drift](docs/ip-drift.md) before going further. `make preflight` runs
the same check first.

### 3. Prepare the machines for Kubespray

Durable swapoff and the `MemorySwapMax=0` drop-ins for etcd, kubelet and
containerd. The playbook asserts each node still holds its inventory address and
fails loudly if not.

```bash
ansible-playbook -i "$V31_INV/hosts.yaml" playbooks/orbstack-prepare.yml
```

### 4. Install the cluster

```bash
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml -e etcd_retries=10
```

`etcd_retries=10` is raised from the default because etcd bootstrap on 2-vCPU
nodes sharing an oversubscribed host regularly exceeds the default window.

Expect 25–45 minutes. `--limit` is not usable on the first run: etcd and the
control plane must be configured together.

### 5. Take the kubeconfig

`kubeconfig_localhost: true` makes Kubespray drop an admin kubeconfig into the
inventory's artifacts directory, already pointing at the VIP. That directory is
gitignored — it holds a client certificate.

```bash
export KUBECONFIG="$V31_INV/artifacts/admin.conf"
kubectl get nodes -o wide
kubectl -n kube-system get pods
```

All six nodes `Ready`, `INTERNAL-IP` matching `hosts.yaml`. The Mac reaches
`https://$KUBE_VIP_ADDRESS:6443` directly because its `bridge102` interface sits
on the same `/23`.

### 6. Verify the VIP before layering the mesh on top

```bash
kubectl -n kube-system get lease plndr-cp-lock \
  -o jsonpath='{.spec.holderIdentity}{"\n"}'
for h in cp1 cp2 cp3; do
  echo -n "$h: "; ssh $h "ip -4 -o addr show eth0 | grep -c $KUBE_VIP_ADDRESS"
done
```

Exactly one control plane must report `1`. Any other result: see
[kube-vip VIP not failing over](docs/troubleshooting.md#4-kube-vip-vip-not-failing-over).

### 7. Install Gateway API CRDs, then Istio

Kubespray's `gateway_api_enabled` is `false` on purpose — it would install a
Gateway API version chosen by the Kubespray release rather than one matched to
Istio, and ambient waypoints are Gateway API resources, so the two must agree.

```bash
# --server-side: the v1.6.2 standard bundle is ~1.1 MB and the largest CRDs exceed
# the 262144-byte last-applied-configuration annotation a client-side apply writes.
kubectl apply --server-side -f \
  "https://github.com/kubernetes-sigs/gateway-api/releases/download/${GATEWAY_API_VERSION}/${GATEWAY_API_CHANNEL}-install.yaml"
kubectl get crd gateways.gateway.networking.k8s.io \
  -o jsonpath='{.metadata.annotations.gateway\.networking\.k8s\.io/bundle-version}{"\n"}'
```

Chart order is not negotiable: `base` creates the CRDs everything else needs,
`istiod` must be serving before `ztunnel` tries to get certificates, and
`cni` must be installed before any pod is expected to be enrolled.

```bash
cd "$V31_K8S_ROOT"
kubectl create namespace istio-system --dry-run=client -o yaml | kubectl apply -f -

helm install istio-base "$ISTIO_CHART_REGISTRY/base" \
  --version "$ISTIO_VERSION" -n istio-system -f istio/values/base.yaml
helm install istiod "$ISTIO_CHART_REGISTRY/istiod" \
  --version "$ISTIO_VERSION" -n istio-system -f istio/values/istiod.yaml --wait
helm install istio-cni "$ISTIO_CHART_REGISTRY/cni" \
  --version "$ISTIO_VERSION" -n istio-system -f istio/values/cni.yaml --wait
helm install ztunnel "$ISTIO_CHART_REGISTRY/ztunnel" \
  --version "$ISTIO_VERSION" -n istio-system -f istio/values/ztunnel.yaml --wait

istioctl version
```

### 8. Install the north–south edge

```bash
kubectl apply -f istio/manifests/ingress-gateway.yaml
kubectl -n istio-ingress get gateway v31-edge \
  -o jsonpath='{.status.conditions[?(@.type=="Programmed")].status}{"\n"}'
kubectl -n istio-ingress get svc
```

istiod provisions the Deployment and Service itself, named
`v31-edge-istio`. The `v31-edge-options` ConfigMap in the same file is
strategic-merge-patched over the generated objects, which is how the fixed
nodePorts, the two replicas and the PDB get set.

If kube-vip's Service load balancer is in use, apply its address pool as well:

```bash
kubectl apply -f istio/manifests/kubevip-address-pool.yaml
kubectl -n istio-ingress get svc v31-edge-istio \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}{"\n"}'
```

The generated Service keeps fixed nodePorts whatever its type, so the edge is
reachable at `http://<any-node>:30080` even before a LoadBalancer address is
assigned. That is the fallback to reach for when debugging the edge — resolve a
node with `orb list`, never from a remembered address, because these are DHCP.

### 9. Smoke test

```bash
# mesh.yaml first: it creates the waypoint the workloads are labelled to use.
kubectl apply -f istio/manifests/sample-app/mesh.yaml
kubectl -n v31-demo wait --for=condition=Programmed gateway/v31-demo-waypoint --timeout=120s
kubectl apply -f istio/manifests/sample-app/workloads.yaml
kubectl -n v31-demo get pods
istioctl ztunnel-config workload | grep v31-demo
```

Every `v31-demo` pod must appear in the ztunnel workload list with a waypoint
set. `mesh.yaml` carries the waypoint Gateway, two HTTPRoutes and two
AuthorizationPolicies, so the L7 path is exercised end to end:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: echo.v31.local' http://192.168.139.241/     # 200
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: echo.v31.local' -X POST http://192.168.139.241/   # 403
```

A `403` on POST is the proof that L7 enforcement is live: only a waypoint can
match on HTTP method, ztunnel alone cannot.

## Where configuration lives

```
infrastructure/kubespray/
├── README.md                     this file
├── versions.env                  every version, sourced by all command blocks
├── docs/
│   ├── runbook.md                day-2 procedures
│   ├── ip-drift.md               node address drift: analysis, prevention, repair
│   ├── troubleshooting.md        failure modes of this stack
│   └── decisions.md              ADRs
├── inventory/
│   ├── v31/                      the inventory Ansible is pointed at
│   │   ├── hosts.yaml            6 hosts, groups, per-node `ip`
│   │   ├── group_vars/all/
│   │   │   ├── all.yml           VIP, cert SANs, DNS, swap, NTP
│   │   │   ├── etcd.yml          stacked host-deployed etcd
│   │   │   └── kube-vip.yml      VIP, ARP, leader election, service LB
│   │   └── group_vars/k8s_cluster/
│   │       ├── k8s-cluster.yml   kube_version, CIDRs, kube-proxy mode, certs
│   │       ├── k8s-net-calico.yml  encapsulation, MTU, IPAM, Felix
│   │       └── addons.yml        metrics-server only
├── istio/
│   ├── versions.yml              Istio, Gateway API and Helm versions
│   ├── values/                   base, istiod, cni, ztunnel, gateway
│   └── manifests/                edge Gateway, kube-vip pool, sample app
├── manifests/kube-vip/           image digest pin, rendered static-pod reference
├── playbooks/orbstack-prepare.yml  swap and pre-flight assertions
├── scripts/                      operator helpers
└── bin/                          pinned tooling
```

Two things about this layout are worth knowing before you edit it:

- **Kubespray's defaults are not copied here.** `roles/kubespray-defaults` in the
  Kubespray checkout supplies everything not listed above, which is why these
  files are short. Anything set here is set because the default was wrong for
  this environment, and the comment in the file says why.
- **`kube-vip.yml` lives in `group_vars/all/`, not `group_vars/k8s_cluster/`.**
  Ansible loads `group_vars/` relative to the inventory file it is given, and
  `loadbalancer_apiserver` has to be visible to the workers for
  `kube_apiserver_endpoint` to resolve to the VIP. Putting it under
  `k8s_cluster/` would hide it from them.

## Conventions

- Never edit `/etc/kubernetes`, `/etc/etcd.env` or a static pod manifest on a
  node as a lasting fix. Kubespray owns those files and will overwrite them.
  Change the inventory and re-run the playbook; an on-node edit is only ever a
  way to get a broken cluster answering long enough to run the playbook.
- Dependency versions change in `versions.env` **and** the authoritative file
  named beside them, in the same commit.
- `manifests/kube-vip/kube-vip.static-pod.cp1.reference.yaml` is a recorded
  rendering, not an input. Nothing applies it; it exists so the real static pod
  can be reviewed and diffed.
