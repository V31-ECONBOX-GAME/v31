# Kubespray + Calico — V31 OrbStack cluster

Kubespray `v2.32.0`, Kubernetes `1.36.4`, Calico `3.31.7`, containerd `2.3.5`,
etcd `3.6.14`. All six nodes are arm64 Ubuntu 26.04.1.

## Layout

```
inventory/v31/hosts.yaml                    6 nodes, groups, node IPs
inventory/v31/group_vars/all/all.yml        VIP, DNS, swap
inventory/v31/group_vars/all/etcd.yml       stacked etcd
inventory/v31/group_vars/all/kube-vip.yml   kube-vip track
inventory/v31/group_vars/k8s_cluster/       cluster, Calico, addons
playbooks/orbstack-prepare.yml              swap + IP drift check
versions.env                                pinned versions (shared)
```

## Deploy

`versions.env` and the bootstrap that materialises `$KUBESPRAY_SRC` / `$KUBESPRAY_VENV`
are shared tooling. Once those exist:

```sh
source versions.env

# OrbStack-specific pre-work: durable swapoff, MemorySwapMax drop-ins, IP drift check
"$KUBESPRAY_VENV/bin/ansible-playbook" -i "$V31_INV/hosts.yaml" \
  playbooks/orbstack-prepare.yml

# Kubespray must run from its own checkout so its ansible.cfg applies
cd "$KUBESPRAY_SRC" && "$KUBESPRAY_VENV/bin/ansible-playbook" \
  -i "$V31_INV/hosts.yaml" cluster.yml

export KUBECONFIG="$V31_INV/artifacts/admin.conf"
kubectl get nodes -o wide
```

The inventory is passed by absolute path, so `credentials_dir` and the
`kubeconfig_localhost` artifacts land under `inventory/v31/` in this repo, where
`.gitignore` excludes them.

`orbstack-prepare.yml` fails loudly if a node no longer holds the address recorded
in `hosts.yaml`. Fix the inventory before deploying — never let Kubespray run
against a stale IP.

## Post-install checks

```sh
# All six nodes Ready, InternalIP matches hosts.yaml
kubectl get nodes -o custom-columns=NAME:.metadata.name,IP:.status.addresses[0].address

# Calico is not encapsulating: expect no vxlan.calico device carrying traffic and
# direct routes to peer pod blocks via node IPs on eth0
kubectl -n kube-system get pods -l k8s-app=calico-node
ssh cp1.orb.local ip route | grep 10.233

# Pod egress and DNS (this is the one thing that could not be verified pre-install)
kubectl run t --rm -it --image=nicolaka/netshoot --restart=Never -- \
  sh -c 'nslookup kubernetes.default; nslookup github.com; curl -sS -o /dev/null -w "%{http_code}\n" https://github.com'

# Swap really off
ansible -i inventory/v31/hosts.yaml k8s_cluster -a 'swapon --show'
```

## Switching to no-encapsulation

Native routing across the OrbStack bridge was verified to work, so the 50 bytes
VXLAN reserves can be reclaimed. It costs a BIRD BGP mesh:

```yaml
# group_vars/k8s_cluster/k8s-net-calico.yml
calico_network_backend: bird
calico_vxlan_mode: Never
calico_ipip_mode: Never
calico_mtu: 1500
calico_veth_mtu: 1500
```

Re-run `cluster.yml`, then confirm cross-node pod-to-pod traffic before trusting it.

## What Calico must not do, because Istio ambient sits on top

- **No eBPF dataplane.** `calico_bpf_enabled` stays false. eBPF mode replaces
  kube-proxy and breaks ambient: ztunnel readiness probes fail, and istio-cni needs
  Calico CTLB enabled or pods cannot reach the API server while starting.
- **Leave the CNI chain alone.** Calico writes `/etc/cni/net.d/10-calico.conflist`
  with binaries in `/opt/cni/bin` — both Istio's defaults, so `istio-cni` appends
  itself with no path overrides. It must stay a `.conflist`; a single-plugin `.conf`
  cannot be chained.
- **Do not add Calico NetworkPolicy for mesh traffic.** Ambient re-originates
  connections through ztunnel, so a policy selecting the source pod sees the local
  ztunnel instead and silently blocks. Express L4/L7 rules as Istio
  `AuthorizationPolicy`; keep Calico policy for host-level and non-mesh namespaces.
- **MTU is shared.** HBONE tunnels ride inside the pod MTU, so the 1450 here is the
  ceiling ambient works under. Change `calico_mtu` and the mesh inherits it.

## When a node's DHCP lease changes

Leases are keyed on MAC (`dhcp-identifier: mac`, 1 day, T1 12h), so an address
normally survives reboots. If one does move:

1. `playbooks/orbstack-prepare.yml` reports which node drifted.
2. Update `ip` in `hosts.yaml` and commit.
3. Worker only — drain, `remove-node.yml`, then `scale.yml` to rejoin.
4. Control plane — the etcd member's peer URL is persisted in the etcd data dir and
   will not follow. Update it first, then re-run `cluster.yml`:

   ```sh
   ssh cp1.orb.local
   sudo etcdctl --endpoints=https://127.0.0.1:2379 \
     --cacert=/etc/ssl/etcd/ssl/ca.pem \
     --cert=/etc/ssl/etcd/ssl/admin-cp1.pem \
     --key=/etc/ssl/etcd/ssl/admin-cp1-key.pem member list
   sudo etcdctl ... member update <memberID> --peer-urls=https://<new-ip>:2380
   ```

Certificates already carry all six node addresses plus the VIP
(`supplementary_addresses_in_ssl_keys`), so a node taking over another node's old
address needs no certificate regeneration.

## Pinning an address — measured, and rejected

A `99-` netplan drop-in does work at the IP layer: it overrides OrbStack's
`10-lxc.yaml` (which is never rewritten), it survives a machine restart, and the
address stays put with no DHCP transaction at all.

Do not use it anyway. Measured on w3 on 2026-09-27: after the next restart the
machine lost OrbStack's host-side proxy, so `ssh <name>.orb.local` timed out while
`.212` still answered ICMP from every peer. The proxy learns a guest's address from
the DHCP lease it serves; a machine that never takes one has no entry, and port 22
is that proxy, not an sshd. No ssh means no Ansible. The rescue path is
`orb -m <name>`, which goes through the agent instead of the network.

Two further traps if it is ever attempted: a static configuration also loses the
DHCP-supplied host route to OrbStack's resolver at `0.250.250.200`, which breaks
node DNS unless the route is replicated; and a statically held address inside the
pool can still be offered to a newly created machine, because the allocator does
not probe.

Detection, the full component analysis and the repair procedures are in
[ip-drift.md](ip-drift.md).
