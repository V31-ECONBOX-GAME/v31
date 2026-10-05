# Day-2 runbook

Procedures for operating the cluster described in [../README.md](../README.md).
Symptom-driven debugging lives in [troubleshooting.md](troubleshooting.md).

Every block assumes this preamble:

```bash
cd /Users/wangxiang/IdeaProjects/v31/infrastructure/kubespray
source versions.env
source "$KUBESPRAY_VENV/bin/activate"
export KUBECONFIG="$V31_INV/artifacts/admin.conf"
```

`ssh cp1` and friends use the short host aliases from `~/.ssh/config`. The FQDN
form needs an explicit `-i ~/.orbstack/ssh/id_ed25519`.

**Standing rule.** Kubespray owns `/etc/kubernetes`, `/etc/etcd.env` and every
static pod manifest. Editing them on a node is a way to get a dead cluster
answering long enough to run a playbook — never the fix itself. The fix is in the
inventory.

---

## Contents

1. [Node IP drift](#1-node-ip-drift)
2. [Adding a worker](#2-adding-a-worker)
3. [Removing a worker](#3-removing-a-worker)
4. [Replacing a failed control-plane node](#4-replacing-a-failed-control-plane-node)
5. [Upgrading Kubernetes](#5-upgrading-kubernetes)
6. [Upgrading Istio — revision-based canary in ambient mode](#6-upgrading-istio--revision-based-canary-in-ambient-mode)
7. [etcd backup and restore](#7-etcd-backup-and-restore)
8. [Certificate renewal](#8-certificate-renewal)
9. [Full teardown and re-install](#9-full-teardown-and-re-install)
10. [Correcting advertised node capacity](#10-correcting-advertised-node-capacity)

---

## 1. Node IP drift

**This has already happened.** Node addresses are DHCP leases from OrbStack at
`192.168.139.1`, 1 day long, with no static-address facility. On 2026-09-27 the six
machines were stopped for three hours and five of six came back on a different
address — a clean lease, no conflict, `dhcp-identifier: mac` notwithstanding.

The full analysis, the measured verdict on every way of preventing it, and the
repair procedures are in **[ip-drift.md](ip-drift.md)**. This section is the short
form.

### What survives, and why it matters

Management access survives. `hosts.yaml` sets `ansible_host` to
`<name>.orb.local`, which resolves to OrbStack's host-side proxy at
`192.168.138.6-.11` — a different, stable network. There is no sshd inside the
machines; SSH is OrbStack's own gateway. **You can always reach a node to repair
it, no matter what its cluster address became.** Every procedure below relies on
that. The one way to lose it is to configure a static address inside the guest,
which takes the machine off that gateway on its next restart —
[measured](ip-drift.md#a-static-address-inside-the-guest--works-and-breaks-ansible-do-not-use).

### What breaks

`hosts.yaml`'s `ip` becomes Kubespray's `main_ip`, and that one value is baked
into these places:

| What | Where it is written | Symptom when stale |
|---|---|---|
| every playbook | `roles/kubernetes/preinstall/tasks/0040-verify-settings.yml` | run aborts: `IPv4: [...] do not contain '<ip>'` |
| etcd listen URLs | `/etc/etcd.env` | `etcd.service` dies: `bind: cannot assign requested address`; quorum lost if 2 of 3 move |
| etcd member peer URL, **in etcd's own data** | the etcd cluster, not a file | member unreachable even after `/etc/etcd.env` is fixed; `etcdserver: unhealthy cluster` |
| etcd member certificates | `/etc/ssl/etcd/ssl/member-*.pem` | `x509: certificate is valid for …, not <new>` on peer TLS |
| kubelet `--node-ip`, and the Node InternalIP it registers | kubelet env file; the Node object | `kubectl logs`/`exec` fail with `dial tcp <old>:10250`; Node `NotReady` after a kubelet restart |
| apiserver `--advertise-address` | `/etc/kubernetes/manifests/kube-apiserver.yaml` | the apiserver still serves (`--bind-address` is `::`), but the default `kubernetes` Service publishes a dead endpoint |
| Calico node address | Calico datastore | other nodes route pod traffic to a dead next hop |

Not affected: kube-proxy (binds `0.0.0.0`, keys on the node *name*), the VXLAN
tunnel address (allocated from `kube_pods_subnet`), and kube-vip leader election
(the `plndr-cp-lock` Lease holds a node name, and the VIP is a /32 independent of
the holder's own address).

What repairs itself, and what does not:

| On its own | On the next `cluster.yml` | Needs a human |
|---|---|---|
| kube-proxy; Calico's node address, once calico-node restarts and re-detects through `calico_ip_auto_method` | apiserver cert SANs (`kubeadm-setup.yml` checks every SAN with `openssl -checkip` and regenerates); etcd certificates (`force_etcd_cert_refresh` defaults true); `/etc/etcd.env`; kubelet `--node-ip` | `hosts.yaml`; the etcd member peer URL |

### Detect

```bash
scripts/ip-drift.sh              # or: make ip-drift
scripts/ip-drift.sh --suggest    # plus paste-ready inventory blocks
```

Per-node table, non-zero exit on drift, works before the cluster exists. It
compares each node's live address against `hosts.yaml`, the Node InternalIP, the
etcd member peer URL and Calico's node resource, and fails if a node has been
leased the kube-vip VIP.

Without the script:

```bash
orbctl list | awk '{printf "%-4s %s\n", $1, $NF}'
grep -E '^\s+ip:' "$V31_INV/hosts.yaml"
kubectl get nodes -o custom-columns=\
'NAME:.metadata.name,IP:.status.addresses[?(@.type=="InternalIP")].address,READY:.status.conditions[-1].type'
```

Which boot moved it, read from the node's own journal (it survives restarts):

```bash
ssh cp1 'for b in -6 -5 -4 -3 -2 -1 0; do printf "boot %3s " $b; \
  journalctl -b $b -u systemd-networkd | grep -oE "DHCPv4 address [0-9.]+" | tail -1; done'
```

### Repair — a worker moved

Remove and re-add; slower than in-place and has no surprises.

```bash
# 1. New address into the inventory (hosts.yaml `ip` and the cert SAN lists).
scripts/ip-drift.sh --suggest

# 2. Out.
kubectl drain w2 --ignore-daemonsets --delete-emptydir-data --timeout=300s
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" remove-node.yml -e node=w2

# 3. Back in.
cd "$V31_K8S_ROOT"
ansible-playbook -i "$V31_INV/hosts.yaml" playbooks/orbstack-prepare.yml --limit=w2
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" scale.yml --limit=w2

# 4. Verify.
cd "$V31_K8S_ROOT" && scripts/ip-drift.sh
kubectl get node w2 -o wide
```

In place, when you would rather not drain — the kubelet restart is what
re-registers the InternalIP:

```bash
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml --limit=w2 -e etcd_retries=10
ssh w2 'sudo systemctl restart kubelet'
kubectl -n kube-system delete pod -l k8s-app=calico-node --field-selector spec.nodeName=w2
```

It does not clean stale iptables rules or CNI state, which is why
remove-and-re-add is the default.

### Repair — a control-plane node moved, quorum intact

Two of three etcd members still up, so the cluster is serving. Work on one node.

```bash
# 1. Inventory first, including etcd_cert_alt_ips.
scripts/ip-drift.sh --suggest

# 2. Re-render. This rewrites /etc/etcd.env, rebuilds the etcd certificates
#    (force_etcd_cert_refresh defaults to true) and regenerates apiserver.crt if a
#    SAN is now missing. No manual `rm` of a certificate is needed.
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml \
  --limit=etcd,kube_control_plane -e etcd_retries=10
```

Step 2 does **not** change the peer URL recorded inside etcd's own data. Fix that
from a healthy member:

```bash
ssh cp1
sudo -i
set -a; . /etc/etcd.env; set +a      # supplies ETCDCTL_*, endpoint 127.0.0.1:2379

etcdctl member list -w table          # note the moved member's hex ID
etcdctl member update <MEMBER_ID> --peer-urls=https://<NEW_IP>:2380
etcdctl endpoint health --cluster -w table
```

Then restart the moved member and its control-plane pods:

```bash
ssh cp2 'sudo systemctl restart etcd && sudo systemctl restart kubelet'
kubectl -n kube-system get pods -o wide | grep cp2
kubectl -n kube-system get lease plndr-cp-lock -o jsonpath='{.spec.holderIdentity}{"\n"}'
scripts/ip-drift.sh
```

When `member update` is not enough — the member fell behind the compacted log, or
comes back `unstarted` — replace it:
[ip-drift.md 4.3](ip-drift.md#43-a-control-plane-node-moved-quorum-intact).

### Repair — quorum lost

Every lease changed while the cluster was down: no member can reach a peer, etcd
cannot elect, and nothing kubectl-shaped works. Ansible still reaches all six.
Snapshot every data directory before touching anything, then follow
[ip-drift.md 4.4](ip-drift.md#44-all-three-control-planes-moved-quorum-lost). If
the keyspace matters more than the nodes,
[section 7](#7-etcd-backup-and-restore) onto a reset cluster is the shorter path.

### Prevent

Each option below was measured in this environment. The verdicts, with the
evidence, are in [ip-drift.md section 2](ip-drift.md#2-stopping-the-address-from-changing).

| Option | Verdict here |
|---|---|
| **Run `scripts/ip-drift.sh` before every playbook** | **do this.** Free, and it turns drift into a 10-second check instead of a failed install |
| **Keep the leases alive** — leases renew at T1 = 12 h; keeping the Mac awake and the machines running is what held these addresses for six boots | **do this.** A habit, not a control, but it is the only thing that has actually prevented drift |
| **Drop `ip:` from `hosts.yaml`** — `main_ip` then comes from `ansible_default_ipv4`, and the preinstall assert is gated on `ip is defined` | **worth considering.** A drifted node needs no inventory edit at all; the cost is that the inventory no longer documents the addresses |
| **`playbooks/kube-vip-dhcp-guard.yml`** — `SendDecline=yes`, verified to apply even though netplan renders its unit into `/run` | **run it**, but understand it: it stops a node *taking* the VIP or a peer's address. It does nothing about a node being handed a different free address, which is what happened on 2026-09-27 |
| **Static address in `/etc/netplan/99-*.yaml`** — a `99-` drop-in does survive and does override OrbStack's `10-lxc.yaml`, which is never rewritten | **do not.** Measured on w3: the address pins, but the machine loses OrbStack's host-side proxy on its next restart, so `ssh <name>.orb.local` times out and Ansible cannot reach it. `orb -m <name>` is then the only way in |
| **Name-based control plane** — `etcd_peer_url`, `etcd_client_url`, `etcd_address: 0.0.0.0`, `etcd_cert_alt_names` | **no.** No resolver these nodes and the macOS host share, and `--node-ip` and `ETCD_LISTEN_*` are addresses by protocol regardless |
| **Widen the certificate SANs** across the plausible lease range | unnecessary. `apiserver_sans` already includes each control plane's live `ansible_default_ipv4`, and etcd certs are rebuilt every run |

---

## 2. Adding a worker

```bash
# 1. Create the machine. Match the existing workers.
orbctl create -a arm64 ubuntu:26.04 w4
orb config set machine.w4.cpu 4
orb config set machine.w4.memory_mib 10240
orbctl restart w4
orbctl list | grep w4                       # note the address
ssh w4 'sudo -n true && echo passwordless-sudo-ok'
```

```bash
# 2. Inventory: add under all.hosts and under kube_node, and add the address to
#    supplementary_addresses_in_ssl_keys so a later cert regen keeps working.
$EDITOR "$V31_INV/hosts.yaml" "$V31_INV/group_vars/all/all.yml"
```

```bash
# 3. Prepare and scale in.
cd "$V31_K8S_ROOT"
ansible-playbook -i "$V31_INV/hosts.yaml" playbooks/orbstack-prepare.yml --limit=w4
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" scale.yml --limit=w4
```

`scale.yml` never touches etcd or an existing node's control-plane components, so
it is safe on a live cluster. It still gathers facts from every host, which is
why `--limit` does not break it.

```bash
# 4. Verify Kubernetes, then the mesh.
kubectl get node w4 -o wide
kubectl -n kube-system get pods -o wide --field-selector spec.nodeName=w4
kubectl -n istio-system get pods -o wide --field-selector spec.nodeName=w4
```

`calico-node`, `kube-proxy`, `ztunnel` and `istio-cni-node` are DaemonSets and
land on their own. Nothing Istio-side needs applying for a new node.

One thing does need attention: the edge gateway carries
`topologySpreadConstraints` with `whenUnsatisfiable: DoNotSchedule` over
`kubernetes.io/hostname` at `replicas: 2`. A fourth worker widens the spread
domain but does not add a replica. Raise `replicas` in the `v31-edge-options`
ConfigMap if the edge should use it:

```bash
$EDITOR istio/manifests/ingress-gateway.yaml
kubectl apply -f istio/manifests/ingress-gateway.yaml
kubectl -n istio-ingress rollout status deploy/v31-edge-istio
```

## 3. Removing a worker

```bash
kubectl drain w3 --ignore-daemonsets --delete-emptydir-data --timeout=600s
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" remove-node.yml -e node=w3
```

`remove-node.yml` drains the node, deletes the `Node` object, and runs the reset
tasks on it. Read [what reset does not clean](#9-full-teardown-and-re-install)
before reusing the machine for anything else.

If the machine is unreachable or already destroyed:

```bash
ansible-playbook -i "$V31_INV/hosts.yaml" remove-node.yml \
  -e node=w3 -e reset_nodes=false -e allow_ungraceful_removal=true
```

`reset_nodes=false` skips trying to clean a node that cannot be reached;
`allow_ungraceful_removal=true` skips the drain that would otherwise hang.

Then, and only then, take it out of the inventory — `remove-node.yml` needs the
host to still be in `hosts.yaml` to act on it:

```bash
$EDITOR "$V31_INV/hosts.yaml" "$V31_INV/group_vars/all/all.yml"
kubectl get nodes
```

`orbctl delete w3` last, once the cluster no longer mentions it.

## 4. Replacing a failed control-plane node

Harder than a worker because the node is also an etcd member, and etcd's
membership lives in etcd rather than in a file Kubespray can re-render.

> **Check the inventory order first.** Kubespray treats the first host in the
> `etcd` group specially — certificate generation and the initial cluster string
> are driven from it. Removing the *first* member (`cp1` as committed) needs the
> group reordered so a surviving node leads, before anything else:
>
> ```yaml
> etcd:
>   hosts:
>     cp2: {}      # now first
>     cp3: {}
>     cp1: {}      # the one being replaced
> ```
>
> Do the same for `kube_control_plane`.

```bash
# 1. Confirm you still have quorum. 2 of 3 is enough; 1 of 3 is not — go to
#    "all six moved" in section 1, or restore from backup in section 7.
ssh cp1 'sudo -i bash -c "set -a; . /etc/etcd.env; set +a;
  ETCDCTL_API=3 ETCDCTL_CACERT=/etc/ssl/etcd/ssl/ca.pem \
  ETCDCTL_CERT=/etc/ssl/etcd/ssl/admin-\$(hostname).pem \
  ETCDCTL_KEY=/etc/ssl/etcd/ssl/admin-\$(hostname)-key.pem \
  etcdctl --endpoints=\$ETCD_ADVERTISE_CLIENT_URLS endpoint health --cluster -w table"'
```

```bash
# 2. Take a snapshot before changing membership. Section 7 has the full command.
```

```bash
# 3. Remove the dead node.
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" remove-node.yml \
  -e node=cp2 -e reset_nodes=false -e allow_ungraceful_removal=true
```

```bash
# 4. Confirm the etcd member is gone. remove-node.yml usually does this; verify,
#    because a half-removed member blocks the replacement from joining.
#    Run the etcdctl preamble from section 1 on a surviving control plane.
etcdctl member list -w table
etcdctl member remove <STALE_MEMBER_ID>     # only if it is still listed
```

```bash
# 5. Build the replacement and put it in the inventory under the SAME name,
#    keeping etcd_member_name stable so the member identity is reused.
orbctl delete cp2 && orbctl create -a arm64 ubuntu:26.04 cp2
orb config set machine.cp2.cpu 2
orb config set machine.cp2.memory_mib 4096
orbctl restart cp2
orbctl list | grep cp2
$EDITOR "$V31_INV/hosts.yaml" "$V31_INV/group_vars/all/all.yml"   # new address
```

```bash
# 6. Prepare, then join it. cluster.yml, NOT scale.yml — scale.yml deliberately
#    refuses to touch etcd and the control plane.
cd "$V31_K8S_ROOT"
ansible-playbook -i "$V31_INV/hosts.yaml" playbooks/orbstack-prepare.yml --limit=cp2
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml \
  --limit=etcd,kube_control_plane -e etcd_retries=10
```

```bash
# 7. Verify: three healthy members, three control-plane nodes, one VIP holder.
etcdctl member list -w table
etcdctl endpoint status --cluster -w table
kubectl get nodes -l node-role.kubernetes.io/control-plane -o wide
kubectl -n kube-system get lease plndr-cp-lock -o jsonpath='{.spec.holderIdentity}{"\n"}'
for h in cp1 cp2 cp3; do
  echo -n "$h VIP: "; ssh $h "ip -4 -o addr show eth0 | grep -c $KUBE_VIP_ADDRESS"
done
```

**Workers need no attention.** Because `loadbalancer_apiserver` points every
kubelet at the VIP, Kubespray does not install the per-node localhost apiserver
proxy, so there is no per-node list of apiserver addresses to refresh. On a
cluster without a VIP this step would mean re-running against every node.

## 5. Upgrading Kubernetes

One minor version at a time. 1.36 → 1.38 is two runs, not one.

```bash
# 1. Upgrade Kubespray itself first — a Kubespray release is what knows how to
#    install a given Kubernetes version, and it also moves Calico, etcd, CoreDNS,
#    runc and containerd. Read its release notes before anything else.
$EDITOR versions.env                        # KUBESPRAY_VERSION
source versions.env
git -C "$KUBESPRAY_SRC" fetch --depth 1 origin tag "$KUBESPRAY_VERSION"
git -C "$KUBESPRAY_SRC" checkout "$KUBESPRAY_VERSION"
pip install -r "$KUBESPRAY_SRC/requirements.txt"
```

```bash
# 2. Check the target version is one this Kubespray release ships checksums for.
grep -c "$KUBE_VERSION" "$KUBESPRAY_SRC/roles/kubespray_defaults/vars/main/checksums.yml" \
  || grep -rc "$KUBE_VERSION" "$KUBESPRAY_SRC/roles/"*/vars/main*/checksums.yml 2>/dev/null
```

```bash
# 3. Back up etcd. Section 7. Do not skip this one.
```

```bash
# 4. Bump the version in BOTH places, in one commit.
$EDITOR "$V31_INV/group_vars/k8s_cluster/k8s-cluster.yml"   # kube_version
$EDITOR versions.env                                        # KUBE_VERSION
```

```bash
# 5. Upgrade. Control planes run serially, each node is drained and uncordoned.
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" upgrade-cluster.yml \
  -e etcd_retries=10 \
  -e drain_timeout=300s \
  -e drain_grace_period=60 \
  -e drain_fallback_enabled=true \
  -e upgrade_cluster_setup=true
```

`drain_fallback_enabled=true` retries a failed drain with
`--disable-eviction`, which matters here: the edge gateway's PDB is
`minAvailable: 1` over 2 replicas spread `DoNotSchedule` across 3 workers, so
draining the second-to-last worker can deadlock.

`upgrade_cluster_setup=true` re-runs the setup roles so changed container and
binary versions actually land rather than being skipped as already-present.

Expect well over an hour. The control-plane nodes have 2 vCPU and share a
10-vCPU VM; the apiserver restart plus etcd catch-up on each is the slow part.

```bash
# 6. Verify, in this order.
kubectl get nodes -o wide                    # all Ready, all on the new version
kubectl -n kube-system get pods
etcdctl endpoint status --cluster -w table   # preamble from section 1
kubectl -n istio-system get pods             # ztunnel and istio-cni back up
istioctl x precheck                          # mesh still consistent
kubectl -n v31-demo exec deploy/client -- curl -sS -o /dev/null -w '%{http_code}\n' \
  http://echo.v31-demo.svc.cluster.local:8080
```

To go node-by-node instead of in one run — worth it on this hardware:

```bash
ansible-playbook -i "$V31_INV/hosts.yaml" upgrade-cluster.yml --limit=cp1 -e etcd_retries=10
# verify, then cp2, cp3, then each worker
```

Rollback is a restore, not a downgrade. Kubernetes does not support moving a
minor version backwards, and etcd's schema may already have moved. Section 7.

## 6. Upgrading Istio — revision-based canary in ambient mode

Ambient does not upgrade like sidecars, and the difference is the whole point of
this section:

- **istiod is revisioned.** Two revisions run side by side; a namespace chooses
  one with `istio.io/rev`.
- **ztunnel and istio-cni are not.** They are one-per-node DaemonSets shared by
  every revision, so they are upgraded **in place**. This is the one step of the
  procedure that is not a canary, and it is the step that carries risk.
- **Waypoints are revisioned**, because a waypoint is a Gateway and picks up the
  revision of its namespace or its own `istio.io/rev` label.
- **L4 does not need a pod restart.** Moving a namespace's revision makes ztunnel
  talk to the new istiod; workload pods are untouched. Waypoint pods do restart,
  so L7 policy has a brief gap.

`istio/values/base.yaml` sets `defaultRevision: default`, so unlabelled
namespaces stay on the `default` revision. A canary therefore requires explicit
labels, and promoting the canary means moving `defaultRevision`.

> Confirm the supported istiod↔ztunnel version skew in the target release's
> upgrade notes before starting. It is the constraint that decides whether step 3
> can lag step 2 at all.

```bash
# 0. Pre-flight.
istioctl x precheck
istioctl version
kubectl -n istio-system get pods
helm ls -n istio-system
```

```bash
# 1. New version and a revision name. A revision name cannot contain dots.
$EDITOR istio/versions.yml versions.env      # istio_version / ISTIO_VERSION
$EDITOR istio/values/*.yaml                  # global.tag / tag
source versions.env
export ISTIO_REV="$(echo "$ISTIO_VERSION" | tr '.' '-')"     # e.g. 1-32-0
```

```bash
# 2. CRDs first, then a second istiod beside the running one.
helm upgrade istio-base "$ISTIO_CHART_REGISTRY/base" \
  --version "$ISTIO_VERSION" -n istio-system -f istio/values/base.yaml

helm install "istiod-$ISTIO_REV" "$ISTIO_CHART_REGISTRY/istiod" \
  --version "$ISTIO_VERSION" -n istio-system \
  -f istio/values/istiod.yaml --set revision="$ISTIO_REV" --wait

kubectl -n istio-system get pods -l app=istiod        # two revisions running
kubectl get mutatingwebhookconfiguration | grep istio
```

`helm upgrade istio-base` updates CRDs in place. Never `kubectl delete` an Istio
or Gateway API CRD to "clean up" — it deletes every object of that kind,
including every Gateway and HTTPRoute in the cluster.

```bash
# 3. The shared DaemonSets, in place. Do ztunnel last: istiod must be able to
#    serve certificates to a ztunnel that has just restarted.
helm upgrade istio-cni "$ISTIO_CHART_REGISTRY/cni" \
  --version "$ISTIO_VERSION" -n istio-system -f istio/values/cni.yaml --wait
kubectl -n istio-system rollout status ds/istio-cni-node

helm upgrade ztunnel "$ISTIO_CHART_REGISTRY/ztunnel" \
  --version "$ISTIO_VERSION" -n istio-system -f istio/values/ztunnel.yaml --wait
kubectl -n istio-system rollout status ds/ztunnel
```

`ztunnel.yaml` pins `maxUnavailable: 0, maxSurge: 1`, so nodes lose their L4
proxy one at a time and never two at once. Watch for enrolment gaps while it
rolls:

```bash
istioctl ztunnel-config workload | wc -l     # compare before and after
```

```bash
# 4. Move one namespace to the canary and prove it.
kubectl label ns v31-demo istio.io/rev="$ISTIO_REV" --overwrite
kubectl -n v31-demo rollout restart deploy/v31-demo-waypoint
kubectl -n v31-demo rollout status deploy/v31-demo-waypoint

istioctl ztunnel-config workload --node w1 -o json | jq -r '.[].workloadName' | head
kubectl -n v31-demo exec deploy/client -- curl -sS -o /dev/null -w '%{http_code}\n' \
  http://echo.v31-demo.svc.cluster.local:8080
kubectl -n v31-demo logs deploy/v31-demo-waypoint --tail=20
istioctl ztunnel-config certificate --node w1 | head
```

```bash
# 5. Roll the rest, then promote: make the canary the default and retire the old.
for ns in $(kubectl get ns -l istio.io/dataplane-mode=ambient -o name | cut -d/ -f2); do
  kubectl label ns "$ns" istio.io/rev="$ISTIO_REV" --overwrite
done

helm upgrade istio-base "$ISTIO_CHART_REGISTRY/base" \
  --version "$ISTIO_VERSION" -n istio-system \
  -f istio/values/base.yaml --set defaultRevision="$ISTIO_REV"
helm uninstall istiod -n istio-system          # the old default revision
istioctl version
```

```bash
# 6. Roll back, before promotion. This is why the canary exists.
kubectl label ns v31-demo istio.io/rev- --overwrite
kubectl -n v31-demo rollout restart deploy/v31-demo-waypoint
helm uninstall "istiod-$ISTIO_REV" -n istio-system
```

After promotion, rolling back means reinstalling the old istiod **and**
downgrading the ztunnel and istio-cni DaemonSets — which is a real outage, not a
label change. Promote only once the canary has carried traffic.

### Gateway API CRDs

Independent of Istio's own version, and an Istio upgrade can require a newer
bundle. Upgrade the CRDs **before** istiod.

```bash
$EDITOR istio/versions.yml versions.env      # gateway_api_version / GATEWAY_API_VERSION
source versions.env
# --server-side: the v1.6.2 standard bundle is ~1.1 MB and the largest CRDs exceed
# the 262144-byte last-applied-configuration annotation a client-side apply writes.
kubectl apply --server-side -f \
  "https://github.com/kubernetes-sigs/gateway-api/releases/download/${GATEWAY_API_VERSION}/${GATEWAY_API_CHANNEL}-install.yaml"
kubectl get crd gateways.gateway.networking.k8s.io \
  -o jsonpath='{.metadata.annotations.gateway\.networking\.k8s\.io/bundle-version}{"\n"}'
kubectl get gateway -A
kubectl get httproute -A
```

## 7. etcd backup and restore

`etcd_deployment_type: host`, so etcd is a systemd unit and `etcdctl` is on the
node. Certificates are Kubespray's, in `/etc/ssl/etcd/ssl/`, named after the
inventory hostname.

### Back up

```bash
ssh cp1
sudo -i
set -a; . /etc/etcd.env; set +a
export ETCDCTL_API=3 \
  ETCDCTL_CACERT=/etc/ssl/etcd/ssl/ca.pem \
  ETCDCTL_CERT="/etc/ssl/etcd/ssl/admin-$(hostname).pem" \
  ETCDCTL_KEY="/etc/ssl/etcd/ssl/admin-$(hostname)-key.pem" \
  ETCDCTL_ENDPOINTS="$ETCD_ADVERTISE_CLIENT_URLS"

mkdir -p /var/backups/etcd
SNAP="/var/backups/etcd/v31-$(date -u +%Y%m%dT%H%M%SZ).db"
etcdctl snapshot save "$SNAP"
etcdctl snapshot status "$SNAP" -w table     # non-zero revision and key count
```

A snapshot on the node only survives the node. Pull it to the Mac — `backup/` is
gitignored:

```bash
mkdir -p "$V31_K8S_ROOT/backup"
scp -i ~/.orbstack/ssh/id_ed25519 \
  "wangxiang@cp1.orb.local:/var/backups/etcd/v31-*.db" "$V31_K8S_ROOT/backup/"
```

As a one-liner across all three, before any upgrade or membership change:

```bash
ansible -i "$V31_INV/hosts.yaml" etcd -m shell -a '
  set -a; . /etc/etcd.env; set +a
  mkdir -p /var/backups/etcd
  ETCDCTL_API=3 ETCDCTL_CACERT=/etc/ssl/etcd/ssl/ca.pem \
  ETCDCTL_CERT=/etc/ssl/etcd/ssl/admin-$(hostname).pem \
  ETCDCTL_KEY=/etc/ssl/etcd/ssl/admin-$(hostname)-key.pem \
  etcdctl --endpoints=$ETCD_ADVERTISE_CLIENT_URLS \
    snapshot save /var/backups/etcd/v31-$(date -u +%Y%m%dT%H%M%SZ).db'
```

Nothing schedules this. Add a `systemd` timer, or take a snapshot before every
one of the procedures above that says to.

### Restore

A snapshot is the whole cluster at one instant. Restoring rewinds every object —
Deployments, Secrets, Istio config, Gateway status — to that instant.

Restore on **all three** members from the **same** snapshot, each with its own
name and peer URL. Read the values from `/etc/etcd.env` rather than assuming
them; `ETCD_INITIAL_CLUSTER_TOKEN` in particular must match what the cluster was
built with.

```bash
# 1. Stop the control plane everywhere. Moving the manifest aside stops the
#    static pod; kubelet restarts it when the file comes back.
ansible -i "$V31_INV/hosts.yaml" kube_control_plane -m shell -a '
  mkdir -p /root/manifests-parked
  mv /etc/kubernetes/manifests/kube-apiserver.yaml /root/manifests-parked/ || true'

# 2. Stop etcd and move the live data aside. Never delete it.
ansible -i "$V31_INV/hosts.yaml" etcd -m shell -a '
  systemctl stop etcd
  mv /var/lib/etcd /var/lib/etcd.bad-$(date -u +%Y%m%dT%H%M%SZ)'

# 3. Put the snapshot on every member.
ansible -i "$V31_INV/hosts.yaml" etcd -m copy \
  -a "src=$V31_K8S_ROOT/backup/v31-<STAMP>.db dest=/var/backups/etcd/restore.db mode=0600"
```

```bash
# 4. Restore, once per member. Run on each of cp1, cp2, cp3.
ssh cp1
sudo -i
set -a; . /etc/etcd.env; set +a
echo "name=$ETCD_NAME peer=$ETCD_INITIAL_ADVERTISE_PEER_URLS"
echo "cluster=$ETCD_INITIAL_CLUSTER token=$ETCD_INITIAL_CLUSTER_TOKEN data=$ETCD_DATA_DIR"

ETCDCTL_API=3 etcdctl snapshot restore /var/backups/etcd/restore.db \
  --name "$ETCD_NAME" \
  --initial-cluster "$ETCD_INITIAL_CLUSTER" \
  --initial-cluster-token "$ETCD_INITIAL_CLUSTER_TOKEN" \
  --initial-advertise-peer-urls "$ETCD_INITIAL_ADVERTISE_PEER_URLS" \
  --data-dir "$ETCD_DATA_DIR"

# Match the ownership of the directory you moved aside.
stat -c '%U:%G' /var/lib/etcd.bad-*
chown -R <THAT_OWNER> "$ETCD_DATA_DIR"
```

`--initial-cluster` must list all three members, and each member must be
restored with its **own** `--name` and `--initial-advertise-peer-urls`. Getting
this wrong produces a cluster that starts and then cannot agree on membership.

```bash
# 5. Start etcd everywhere, then the apiservers.
ansible -i "$V31_INV/hosts.yaml" etcd -m shell -a 'systemctl start etcd'
ansible -i "$V31_INV/hosts.yaml" kube_control_plane -m shell -a '
  mv /root/manifests-parked/kube-apiserver.yaml /etc/kubernetes/manifests/'

# 6. Verify.
etcdctl member list -w table
etcdctl endpoint health --cluster -w table
kubectl get nodes
kubectl get pods -A
```

Expect churn after a restore: controllers reconcile the rewound state, pods that
existed only after the snapshot are deleted, and Istio re-programs every proxy.
Give it a few minutes before judging.

Kubespray also ships `recover-control-plane.yml`, which automates the multi-member
case and looks for snapshots under `etcd_backup_prefix`. Read its documentation in
`$KUBESPRAY_SRC/docs/` before using it — it makes assumptions about which members
survived, and the manual path above is the one that is verifiable step by step.

## 8. Certificate renewal

`auto_renew_certificates: true` installs a `k8s-certs-renew` systemd timer on
each control plane, firing on the first Monday of each month with a per-node
offset. Kubernetes control-plane certificates are 1-year; the CA is 10-year.

```bash
# Where things stand.
ssh cp1 'sudo kubeadm certs check-expiration'
ssh cp1 'systemctl list-timers k8s-certs-renew.timer --all'
ssh cp1 'systemctl status k8s-certs-renew.service --no-pager | tail -20'
```

Renew now, the supported way — re-running the playbook regenerates what is due
and restarts what needs restarting:

```bash
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml \
  --limit=kube_control_plane --tags=k8s-certs -e etcd_retries=10
```

On a single node, when the cluster is too broken for a playbook:

```bash
ssh cp1
sudo kubeadm certs renew all
sudo systemctl restart kubelet
# static pods restart when their manifest's mtime changes
sudo touch /etc/kubernetes/manifests/kube-{apiserver,controller-manager,scheduler}.yaml
```

Three things the timer does **not** cover:

1. **Your kubeconfig on the Mac.** `$V31_INV/artifacts/admin.conf` embeds a
   client certificate that expires with the rest and is never refreshed in place.
   Symptom is a sudden `Unauthorized` from a kubectl that worked yesterday.
   ```bash
   ssh cp1 'sudo kubeadm certs renew admin.conf'
   scp -i ~/.orbstack/ssh/id_ed25519 \
     wangxiang@cp1.orb.local:/etc/kubernetes/admin.conf "$V31_INV/artifacts/admin.conf"
   kubectl get nodes
   ```
   Check its expiry directly:
   ```bash
   kubectl config view --raw -o jsonpath='{.users[0].user.client-certificate-data}' \
     | base64 -d | openssl x509 -noout -enddate
   ```
2. **etcd's certificates.** Kubespray generates them itself, outside kubeadm, so
   `kubeadm certs check-expiration` does not list them. Check them directly:
   ```bash
   ssh cp1 'for f in /etc/ssl/etcd/ssl/*.pem; do
     printf "%-44s " "$f"; sudo openssl x509 -in "$f" -noout -enddate 2>/dev/null; done'
   ```
   Regenerate by deleting the expiring file and re-running with `--tags=etcd`.
3. **Istio's certificates.** A separate PKI. istiod's root CA is self-signed and
   long-lived; workload certificates are short-lived and rotated automatically by
   ztunnel and the gateway proxies. Verify rather than renew:
   ```bash
   istioctl ztunnel-config certificate --node w1
   istioctl proxy-config secret deploy/v31-edge-istio -n istio-ingress
   ```
   Expired workload certificates mean istiod is unreachable or its CA changed —
   that is an istiod problem, not a renewal task.

The CA itself is the one renewal that is genuinely disruptive: it invalidates
every certificate and kubeconfig at once. At 10 years it is out of scope here;
rebuilding the cluster is the cheaper answer in this environment.

## 9. Full teardown and re-install

```bash
# Back up first if anything in the cluster matters. Section 7.
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" reset.yml -e reset_confirmation=yes
```

### What `reset.yml` does not clean

This cluster was previously wiped by hand, and that wipe left behind exactly the
kind of residue `reset.yml` also leaves. A second install on top of it can
inherit a stale CNI config, a stale binary or a stale systemd drop-in. Check,
do not assume:

```bash
ansible -i "$V31_INV/hosts.yaml" k8s_cluster -m shell -a '
for p in \
  /opt/containerd /usr/libexec/kubernetes \
  /etc/cni/net.d /opt/cni/bin /var/lib/containerd /var/lib/kubelet \
  /var/lib/etcd /etc/kubernetes /etc/ssl/etcd \
  /usr/local/bin/kubeadm /usr/local/bin/kubelet /usr/local/bin/etcdctl \
  /usr/local/bin/calicoctl /usr/local/bin/helm \
  /tmp/releases \
  /etc/systemd/system/v31-swapoff.service \
  /etc/systemd/system/kubelet.service.d/10-v31-noswap.conf \
  /etc/systemd/system/etcd.service.d/10-v31-noswap.conf \
  /etc/systemd/system/containerd.service.d/10-v31-noswap.conf \
  ; do [ -e "$p" ] && echo "LEFT  $p"; done
echo "--- apt cache: $(du -sh /var/cache/apt 2>/dev/null | cut -f1)"
echo "--- swap: $(swapon --noheadings --show=NAME | tr "\n" " ")"
echo "--- cni conflists: $(ls /etc/cni/net.d 2>/dev/null | tr "\n" " ")"
true'
```

Known residue, and why each matters:

| Left behind | Consequence on re-install |
|---|---|
| `/opt/containerd` | containerd's plugin and state root. `reset.yml` clears `/var/lib/containerd`, not this. Stale plugin state can make containerd refuse to start. |
| `/usr/libexec/kubernetes` | kubelet's exec volume-plugin directory. Harmless but never removed, so it is a reliable sign a previous install was here. |
| `/var/cache/apt` (105 MiB measured) | Only disk. Reclaim with `apt-get clean`. |
| `/tmp/releases` (`local_release_dir`) | Downloaded binaries and images. Speeds a re-install up, but a stale tarball for a version you are re-pinning is a confusing failure. |
| `v31-swapoff.service` and the `10-v31-noswap.conf` drop-ins | Created by `playbooks/orbstack-prepare.yml`, which `reset.yml` knows nothing about. Harmless to keep — desirable, in fact — but they are not part of a clean machine. |
| OrbStack swap | Recreated at boot regardless. `reset.yml` unmasking `swap.target` does not matter because the devices were never in `/etc/fstab`. |
| Istio, everything | `reset.yml` has no idea Istio exists. Its CRDs and namespaces live in etcd and die with the cluster. What can survive is on-disk: `/opt/cni/bin/istio-cni` and the `istio-cni` entry appended to `/etc/cni/net.d/10-calico.conflist`. |
| Helm release state | Also in etcd, so it dies with the cluster — but a `helm install` after a partial reset will fail on objects it thinks it does not own. |

Purge the residue that actually causes trouble:

```bash
ansible -i "$V31_INV/hosts.yaml" k8s_cluster -m shell -a '
rm -rf /opt/containerd /usr/libexec/kubernetes /tmp/releases
rm -rf /etc/cni/net.d /opt/cni/bin
apt-get clean
true'
```

Leave the swap drop-ins in place unless you are handing the machine back.

### Re-install

```bash
# 1. Addresses drift while a cluster is down. Re-check before rebuilding.
orbctl list
grep -E '^\s+ip:' "$V31_INV/hosts.yaml"

# 2. Prepare, install, then follow README steps 5-9 for kubeconfig and Istio.
cd "$V31_K8S_ROOT"
ansible-playbook -i "$V31_INV/hosts.yaml" playbooks/orbstack-prepare.yml
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml -e etcd_retries=10
```

The nuclear option is faster and more reliable than debugging a half-reset
machine, and costs nothing here:

```bash
for m in cp1 cp2 cp3 w1 w2 w3; do orbctl delete "$m"; done
# recreate per section 2, then update every address in the inventory
```

## 10. Correcting advertised node capacity

Not a failure yet — a latent one, and it is specific to this environment.

The machines are cgroup-limited containers inside one OrbStack VM. `nproc` is
cgroup-aware, but `/proc/meminfo` is **not**: it reports the VM's memory on every
node. kubelet derives node capacity from `/proc/meminfo`, so it advertises
roughly 42 GiB on a node whose cgroup kills it at 4 GiB.

```bash
for h in cp1 w1; do
  echo "== $h"
  ssh $h 'printf "  cgroup limit  %s GiB\n" $(( $(cat /sys/fs/cgroup/memory.max) / 1073741824 ))
          printf "  /proc/meminfo %s\n" "$(grep MemTotal /proc/meminfo)"'
done
kubectl get nodes -o custom-columns=\
'NAME:.metadata.name,CPU:.status.capacity.cpu,MEM:.status.capacity.memory,ALLOC:.status.allocatable.memory'
```

Measured: cp1/cp2/cp3 are capped at 2 vCPU and 4 GiB, w1/w2/w3 at 4 vCPU and
10 GiB, and all six report 42 GiB. The VM itself has 10 vCPU and 42 GiB, so CPU
is oversubscribed 18:10 and memory is committed to the last byte.

The consequence: the scheduler believes a control plane has ~40 GiB free and will
place work accordingly. Nothing warns you. The node's cgroup starts OOM-killing
once real usage passes 4 GiB, and because it is the cgroup and not kubelet doing
the killing, there is no eviction event and no `MemoryPressure` condition —
processes just die. `kubectl describe node` will show plenty of allocatable
memory the whole time.

The fix is to reserve the difference explicitly, so allocatable reflects the
cgroup rather than the VM. `kube_reserved: true` and `system_reserved: true` are
already set but leave the amounts at Kubespray's defaults, which are computed
from the same wrong capacity:

The reservation has to be the *difference between the reported capacity and the
cgroup*, not a token amount. Capacity is 41.14 GiB on every node, so reserving
1.5 GiB still advertises 39 GiB on a machine the kernel kills at 4 GiB. Both
files now exist and carry the arithmetic:

```yaml
# inventory/v31/group_vars/kube_control_plane/kubelet.yml
# 41.14 GiB capacity - 1 GiB kube - 38400Mi system - 500Mi eviction
#   => ~2.15 GiB allocatable inside a 4 GiB cgroup
kube_memory_reserved: 1Gi
system_memory_reserved: 38400Mi
kube_cpu_reserved: 100m
system_cpu_reserved: 500m
eviction_hard:
  memory.available: "500Mi"
```

```yaml
# inventory/v31/group_vars/kube_node/kubelet.yml
# 41.14 GiB capacity - 1 GiB kube - 31 GiB system - 1 GiB eviction
#   => ~8.14 GiB allocatable inside a 10 GiB cgroup
kube_memory_reserved: 1Gi
system_memory_reserved: 31Gi
kube_cpu_reserved: 100m
system_cpu_reserved: 300m
eviction_hard:
  memory.available: "1Gi"
```

`system_cpu_reserved` stays at 500m on the control planes: etcd runs as a host
systemd service, is charged to system.slice, and has to fit inside it. Do not
lower it. `eviction_hard` cannot save you on its own, because it is evaluated
against the same capacity the reservation is correcting. Apply and verify:

```bash
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml --tags=node -e etcd_retries=10
kubectl get nodes -o custom-columns=\
'NAME:.metadata.name,CAPACITY:.status.capacity.memory,ALLOCATABLE:.status.allocatable.memory'
```

The alternative is to stop lying to kubelet: raise the machines' cgroup limits to
match what the guest reports. That over-commits the 42 GiB VM six ways and swaps
a scheduling problem for a VM-level one. Reserving is the right answer.
