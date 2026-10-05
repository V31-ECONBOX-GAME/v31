# kube-vip

Highly-available virtual IP for the Kubernetes control plane. ARP mode, leader
election across cp1/cp2/cp3. Kubespray deploys it; nothing here is applied by hand.

| | |
|---|---|
| VIP | `192.168.139.240:6443` |
| Mode | Layer 2 / ARP, `vip_leaderelection` on lease `plndr-cp-lock` |
| Version | kube-vip `v1.2.4`, `ghcr.io/kube-vip/kube-vip` (publishes `linux/arm64`) |
| Interface | `eth0` (a veth on every node) |
| Placement | static pod on `kube_control_plane` only, `/etc/kubernetes/manifests/kube-vip.yml` |
| Lease timings | 15 / 10 / 2 s (duration / renew deadline / retry) |
| Service LB | enabled, address by annotation, no cloud provider |

## Files

| Path | Role |
|---|---|
| `inventory/v31/group_vars/all/kube-vip.yml` | the whole configuration |
| `manifests/kube-vip/kube-vip.static-pod.cp1.reference.yaml` | what Kubespray renders, for review and diff |
| `manifests/kube-vip/address-allocation.yaml` | every static address on the segment |
| `manifests/kube-vip/image-pin.yaml` | image digests, audited by `scripts/kube-vip.sh verify` |
| `scripts/kube-vip.sh` | `preflight` \| `verify` \| `failover` |
| `playbooks/kube-vip-dhcp-guard.yml` | optional DHCP collision guard |

## Order of operations

```sh
scripts/kube-vip.sh preflight     # VIP unclaimed, eth0 present, SSH and sudo work
bin/kubespray.sh prepare
bin/kubespray.sh cluster.yml
scripts/kube-vip.sh verify
scripts/kube-vip.sh failover      # optional: prove the VIP moves
```

## Bootstrap

`loadbalancer_apiserver` makes kubeadm's `controlPlaneEndpoint` the VIP, so every
kubeconfig — including the `admin.conf` kube-vip itself mounts — points at an
address kube-vip has not created yet. It resolves in one pass:

1. `kubernetes/node` writes the static pod. The kubelet has no
   `/var/lib/kubelet/config.yaml` yet, so it restart-loops and starts nothing.
2. `kubeadm init` writes certs, kubeconfigs and the control-plane manifests, then
   `kubelet-start` writes the kubelet config and restarts it.
3. The kubelet starts `kube-apiserver` and `kube-vip` together. The apiserver binds
   `::` with `bindv6only=0`, so it answers on `127.0.0.1:6443`.
4. `cp_detect: false` makes kube-vip rewrite its API host to `kubernetes:6443`, and
   the static pod's `hostAliases` maps `kubernetes` to `127.0.0.1`. It reaches the
   local apiserver without the VIP. `kubernetes` is already an apiserver cert SAN.
5. kube-vip wins `plndr-cp-lock`, adds `192.168.139.240/32` to `eth0` and sends
   gratuitous ARP. `kubeadm init`'s later phases, which do use the VIP, now work —
   and Kubespray already retries `kubeadm init` for exactly this race.

Two-pass fallback, if step 5 ever loses the race: comment out
`loadbalancer_apiserver`, run `cluster.yml`, uncomment it, run `cluster.yml` again.

## VIP address collision

The VIP must sit in `192.168.139.0/24`: OrbStack proxy-ARPs all of
`192.168.138.0/24` and answers for it itself, so an address there is unreachable.
That whole /24 is OrbStack's DHCP pool, and the server offers addresses that are
already answering ARP. The address cannot be reserved, only defended:

- `scripts/kube-vip.sh preflight` before creating any new OrbStack machine.
- `playbooks/kube-vip-dhcp-guard.yml` to make nodes decline a colliding offer.
