# Node address drift

What breaks when a node's IPv4 address changes, how to stop it changing, and how
to repair a cluster it has already happened to.

`docs/runbook.md` [section 1](runbook.md#1-node-ip-drift) is the procedural entry
point and links here. Detection is `scripts/ip-drift.sh`.

Everything marked **measured** was tested first-hand on these six machines, with
the date. Everything else is read out of the Kubespray v2.32.0 source, with the
file named.

---

## 0. It has already happened

**Measured, 2026-09-27.** The six machines were stopped at 22:46 on 2026-09-26 and
started at 02:04 the next morning. Five of six came back on a different address:

| node | boots −6 … −1 | boot 0 |
|---|---|---|
| cp1 | 192.168.139.27 | **.28** |
| cp2 | 192.168.139.101 | **.102** |
| cp3 | 192.168.139.98 | **.99** |
| w1 | 192.168.139.128 | **.129** |
| w2 | 192.168.139.143 | **.144** |
| w3 | 192.168.139.212 | .212 |

Read out of each node's own journal, which survives a machine restart:

```bash
ssh cp1 'for b in -6 -5 -4 -3 -2 -1 0; do printf "boot %3s " $b; \
  journalctl -b $b -u systemd-networkd | grep -oE "DHCPv4 address 192\.168\.139\.[0-9]+" | tail -1; done'
```

Facts worth keeping from that event:

- The lease was a clean single ACK. No DHCPNAK, no DHCPDECLINE, no ARP conflict —
  OrbStack simply offered a different address.
- `dhcp-identifier: mac` in `/etc/netplan/10-lxc.yaml` did not hold the address.
  The MAC did not change; the offer did.
- The macOS host had been up for 17 days and the OrbStack VM for 13 hours. Only
  the machines restarted, so this is not a host-reboot-only risk.
- Every previous lease had just expired (1-day leases, ~3 h of downtime past T2).
  The most likely mechanism is an allocator that will not immediately re-issue a
  just-expired address and steps forward one — but OrbStack does not publish its
  allocator, so treat the +1 as an observation, not a rule. The next event may
  move an address anywhere in the /24.
- **So the addresses in `inventory/v31/hosts.yaml` are currently stale, and
  `cluster.yml` will refuse to run.** See [section 4](#4-repair).

---

## 1. What breaks, component by component

`hosts.yaml`'s `ip` becomes `main_ip`
(`roles/network_facts/tasks/main.yaml`: `_ipv4: "{{ ip | default(fallback_ip) }}"`),
and `main_ip` is what every address below is rendered from.

### Both roles

| Component | Where the address is recorded | Behaviour when it moves | Repair |
|---|---|---|---|
| **Ansible reachability** | `ansible_host: <name>.orb.local` | unaffected — OrbStack's host-side gateway is on 192.168.138.0/24, a different network, and there is no sshd inside the machines | none |
| **Kubespray preinstall assert** | `roles/kubernetes/preinstall/tasks/0040-verify-settings.yml:58` | every playbook aborts: `IPv4: [...] do not contain '<ip>'` | edit `hosts.yaml` |
| **kubelet `--node-ip`** | `kubelet_address: "{{ main_ips \| join(',') }}"` → `KUBELET_ADDRESS` in the kubelet env file | kubelet keeps running on the old flag until restarted; after a restart it cannot bind and the Node goes `NotReady` | re-render + restart kubelet |
| **Node `.status.addresses` InternalIP** | the Node object, written once at registration | never revisited. `kubectl logs`, `kubectl exec`, `kubectl port-forward` and metrics-server all dial the stale address and time out | kubelet restart with the corrected `--node-ip` rewrites it |
| **kube-proxy** | nothing | unaffected. `bindAddress: {{ kube_proxy_bind_address }}` defaults to `0.0.0.0` and `hostnameOverride: {{ kube_override_hostname }}` is the node *name* | none — self-heals |
| **Calico `Node.spec.bgp.ipv4Address`** | the kdd datastore | stale until the pod restarts. Other nodes route pod traffic to a dead next hop | **self-heals** on calico-node restart: `IP: autodetect` with `IP_AUTODETECTION_METHOD: interface=eth.*` re-detects (`calico_ip_auto_method` in `k8s-net-calico.yml`) |
| **Calico VXLAN tunnel address** | `Node.spec.ipv4VXLANTunnelAddr` | unaffected. It is allocated out of `kube_pods_subnet` (10.233.64.0/18), not out of the node subnet, so it has no relationship to the node's address | none |
| **apiserver certificate SANs** | `/etc/kubernetes/ssl/apiserver.crt` | **self-heals** on the next `cluster.yml`. `apiserver_sans` (`roles/kubernetes/control-plane/tasks/kubeadm-setup.yml:25`) already includes each control plane's `ansible_default_ipv4.address`, i.e. the live address; the `Kubeadm \| Check apiserver.crt SANs` block then runs `openssl x509 -checkip/-checkhost` per SAN and deletes and regenerates the cert when one is missing | none, given a playbook run |

### Control plane only

| Component | Where the address is recorded | Behaviour when it moves | Repair |
|---|---|---|---|
| **etcd listen URLs** | `ETCD_LISTEN_PEER_URLS` / `ETCD_LISTEN_CLIENT_URLS` in `/etc/etcd.env`, from `etcd_address` | **hard failure.** These bind. `etcd.service` dies with `listen tcp 192.168.139.27:2380: bind: cannot assign requested address` | re-render `/etc/etcd.env` |
| **etcd advertise URLs** | `ETCD_INITIAL_ADVERTISE_PEER_URLS`, `ETCD_ADVERTISE_CLIENT_URLS` | peers and the apiserver are told to use an address nobody answers | re-render `/etc/etcd.env` |
| **etcd member peer URL, inside etcd's own data** | the raft log, not any file | **does not self-heal and no playbook fixes it.** Re-rendering `/etc/etcd.env` changes what the member advertises at *start-up*; the cluster keeps the recorded URL until it is changed explicitly. Symptom: `etcdserver: unhealthy cluster`, or a member stuck `unstarted` | `etcdctl member update` — [section 4.3](#43-a-control-plane-node-moved-quorum-intact) |
| **etcd member certificates** | `/etc/ssl/etcd/ssl/member-<host>.pem` | `x509: certificate is valid for …, not <new>` on peer TLS, until either the address is already a SAN or the cert is rebuilt. `etcd_cert_alt_ips` in `group_vars/all/etcd.yml` pre-seeds addresses for exactly this reason — **but it currently lists the six pre-drift addresses**. Note that `roles/etcd/tasks/check_certs.yml` never inspects SANs; it regenerates because `force_etcd_cert_refresh` defaults to **true**, so a `cluster.yml` run does rebuild them | none, given a playbook run |
| **kubeadm `advertiseAddress`** | `/etc/kubernetes/manifests/kube-apiserver.yaml` → `--advertise-address` | the apiserver still serves: `--bind-address` is `kube_apiserver_bind_address`, which defaults to `::`. What is wrong is the address published in the default `kubernetes` Service endpoints, so in-cluster clients that bypass the Service VIP are sent to a dead address | re-render on the next `cluster.yml` |
| **kube-vip leader election** | the `plndr-cp-lock` Lease holds a *node name*, not an address | election is unaffected. The VIP itself is a /32 the leader adds to `eth0`, independent of the node's own address, so **the VIP survives a node changing address**. What does break is a node being leased .240 or .241 — see below | none |

### The collision case, which is worse than drift

OrbStack's DHCP owns all of 192.168.139.0/24, has no reservation facility, and
does not probe before it offers (measured previously: 10 of 60 offers landed on
addresses that were actively answering ARP). A node can therefore be leased
**192.168.139.240** (the control-plane VIP) or **.241** (the Istio edge Gateway).
Two hosts then answer ARP for the same address and the control plane becomes
intermittent rather than down — the hardest shape to debug.
`scripts/ip-drift.sh` fails on this explicitly; `playbooks/kube-vip-dhcp-guard.yml`
is the defence.

### Summary

| Self-heals on its own | Self-heals on the next `cluster.yml` | Needs a human |
|---|---|---|
| kube-proxy; the VXLAN tunnel address; kube-vip leader election; Calico's node address once calico-node restarts | apiserver cert SANs; etcd certificates; `/etc/etcd.env`; kubeadm `advertiseAddress`; kubelet `--node-ip` and, through it, the Node InternalIP | `hosts.yaml`; the etcd member peer URL |

---

## 2. Stopping the address from changing

Ranked by how much they actually help **here**.

### A. Static address inside the guest — works, and breaks Ansible. Do not use.

**Measured on w3, 2026-09-27.** Restored afterwards; `10-lxc.yaml` verified
byte-identical by sha256 and the machine verified reachable from all five peers.

What was tested and what happened:

1. `/etc/netplan/10-lxc.yaml` is **not** rewritten by OrbStack. Its mtime was
   still the machine's creation time after seven boots, and a marker comment
   appended to it survived a full machine restart unchanged.
2. A higher-numbered drop-in **does** survive and **does** win. A
   `/etc/netplan/99-v31-static.yaml` setting `dhcp4: false` plus a static address
   merged cleanly over `10-lxc.yaml` (`netplan get` showed `dhcp4: false`), was
   still present and still applied after `orbctl restart w3`, and the boot journal
   showed no DHCP transaction at all.
3. Guest networking was **fully functional** while static: gateway, peer nodes,
   DNS and HTTPS egress all worked — but only because the drop-in also replicated
   the host route to OrbStack's internal resolver. That route arrives as a DHCP
   option, so a static configuration loses it and `/etc/resolv.conf`
   (a read-only symlink to `nameserver 0.250.250.200`) stops resolving. Any static
   config must carry `- to: 0.250.250.200/32 via: 192.168.139.1`.
4. **And then `ssh w3.orb.local` timed out.** OrbStack's host-side proxy for the
   machine (`192.168.138.11`) went dead after the restart, while `.212` still
   answered ICMP from the macOS host and from every peer. The proxy learns the
   guest's address from the DHCP transaction it serves; a machine that boots
   without taking a lease has no entry, so port 22 — which is OrbStack's gateway,
   not an sshd — has nowhere to forward. Applying the static config *without*
   restarting did not break ssh, because the mapping was already cached. The
   restart is what kills it.

   Control group: all six machines restarted at 02:04 on DHCP and ssh worked to
   all six. So this is the static address, not the restart.

Verdict: **works at the IP layer, unusable in practice.** No ssh means no Ansible,
so the cluster can no longer be installed, upgraded or repaired. The only way in
is `orb -m w3 <cmd>`, which goes through the agent rather than the network — fine
for rescue, not a transport Kubespray can use. A static address inside the DHCP
pool is also still offerable to a future machine, since the allocator does not
probe.

If it is ever attempted again: the rescue path is `orb -m <name>`, and a dead-man
switch is mandatory, because a transient timer does **not** survive a machine
restart:

```bash
orb -m w3 sudo rm -f /etc/netplan/99-v31-static.yaml
orb -m w3 sudo netplan apply
```

### B. `SendDecline=yes` — real, but it is not a drift defence

`playbooks/kube-vip-dhcp-guard.yml` installs `[DHCPv4] SendDecline=yes` as a
systemd-networkd drop-in, so a node ARP-probes its offer and declines an address
that is already in use.

**Measured on w3, 2026-09-27**, and the playbook's drop-in path is correct even
though netplan renders its unit into `/run`. systemd resolved and networkd applied
it, logging:

```
eth0: Reconfiguring with /run/systemd/network/10-netplan-eth0.network
      (dropins: /etc/systemd/network/10-netplan-eth0.network.d/10-v31-dhcp-decline.conf)
```

systemd is 259 here and `/etc/…d/` outranks a unit found in `/run`.

Verdict: **keep it, for the collision case only.** It stops a node from *taking*
an address something else is using — the VIP, or a peer node. It does nothing
about a node being handed a different free address, which is what actually
happened on 2026-09-27: that offer was uncontested, so there was nothing to
decline.

One caveat the playbook should carry: its `networkctl reload` step triggers a full
reconfigure and a fresh DHCP ACK, which is itself a drift opportunity. Run it
before `cluster.yml`, not after. (It kept `.212` when measured, but that is one
sample.)

### C. Depend on names instead of addresses — shrinks the blast radius, no more

The real Kubespray variables, and what each one is worth here:

| Variable | Default (v2.32.0) | Can it take a name? | Effect |
|---|---|---|---|
| `ip` (per host, `hosts.yaml`) | unset → `ansible_default_ipv4.address` | no, it is an address | **Leaving it unset is the single most useful change.** `main_ip` is then re-derived from facts on every run, so a drifted node needs no inventory edit and the preinstall assert (which is gated on `ip is defined`) has nothing to fail on. |
| `kubelet_address` | `{{ main_ips \| join(',') }}` | no — `--node-ip` takes addresses | follows `main_ip`; nothing to gain by overriding |
| `supplementary_addresses_in_ssl_keys` | `[]` | yes, names and addresses both | additive SANs. Names here are pointless unless something resolves them |
| `apiserver_loadbalancer_domain_name` | `loadbalancer_apiserver.address` | **yes** | would let `controlPlaneEndpoint` be a name instead of the VIP. Needs a resolver every node and the macOS host agree on. The VIP is already a stable literal, so this buys nothing |
| `etcd_cert_alt_names` | `[]` | yes (DNS SANs) | the etcd cert already carries `groups['etcd']` — the names `cp1`, `cp2`, `cp3` — as DNS SANs (`roles/etcd/templates/openssl.conf.j2`), so the certificate side is *already* name-ready |
| `etcd_cert_alt_ips` | `[]` | no, `roles/validate_inventory` rejects non-addresses | already set in `group_vars/all/etcd.yml`; must be refreshed after drift |
| `etcd_peer_url`, `etcd_client_url` | `https://{{ etcd_access_address }}:2380` / `:2379` | **yes** | per-host override; makes the *advertised* URL and the recorded member peer URL a name |
| `etcd_address` | `main_ip` | no — `ETCD_LISTEN_*` bind | set to `0.0.0.0` to make listening address-independent |

So a name-based control plane is constructible: `etcd_address: 0.0.0.0`,
`etcd_peer_url`/`etcd_client_url` on names, `etcd_cert_alt_names` already covered,
`ip` unset.

**It does not solve the problem.** Three reasons:

1. There is no resolver these nodes and the macOS host share for such names.
   `/etc/resolv.conf` is a read-only symlink to OrbStack's resolver at
   `0.250.250.200`, which only answers inside a node's own network namespace, and
   `resolvconf_mode: none` exists precisely because the file cannot be edited. The
   `<name>.orb.local` names resolve to the 192.168.138.x *proxy* addresses, which
   are not the cluster network. Making names work means running and maintaining
   DNS, or writing `/etc/hosts` on six nodes — and `/etc/hosts` entries hold
   addresses, so the drift is simply moved into a file that a playbook has to
   rewrite anyway.
2. `--node-ip` and `ETCD_LISTEN_*` are addresses by protocol. No variable changes
   that.
3. etcd resolves a peer URL's name at connection time, so names genuinely fix the
   one component that needs a human — but only after something updates the name's
   address, which is the original problem with an extra layer on top.

Verdict: `ip` unset is worth doing. The rest is a lot of machinery to shrink a
blast radius that `scripts/ip-drift.sh` plus a `cluster.yml` re-run already covers.

### D. Keep the leases alive

Leases are 1 day, T1 at 12 h. A machine that stays up renews and keeps its
address; the 2026-09-27 event needed ~3 h of downtime past expiry. Keeping the
Mac awake, and not stopping the machines for long, is what kept these addresses
stable for six boots. Free, and it is the only thing that prevented drift so far —
but it is a habit, not a control.

### Ranked

1. **D + `scripts/ip-drift.sh` before every playbook.** Free, no side effects.
2. **`ip` unset in `hosts.yaml`.** Removes the most common failure (a playbook
   refusing to start) at the cost of the inventory no longer documenting the
   addresses.
3. **`playbooks/kube-vip-dhcp-guard.yml`.** Different problem — VIP collision, not
   drift — but the worst problem, so run it.
4. **Static addresses.** Rejected: measured to break the ssh path Ansible needs.
5. **Name-based control plane.** Rejected: needs DNS infrastructure that does not
   exist here, and cannot cover `--node-ip` or the etcd listen URLs anyway.

---

## 3. Detect

```bash
cd /Users/wangxiang/IdeaProjects/v31/infrastructure/kubespray
scripts/ip-drift.sh              # or: make ip-drift
scripts/ip-drift.sh --suggest    # plus paste-ready inventory blocks
```

Per-node table, non-zero exit on drift, and it works before the cluster exists —
the Kubernetes, etcd and Calico columns come back `SKIP` with the reason. It
compares the live address against `hosts.yaml`, the Node InternalIP, the etcd
member peer URL and Calico's node resource, and fails if a node holds a VIP.

---

## 4. Repair

### 4.1 Before the cluster is installed — where this tree is now

Nothing is deployed, so there is nothing to repair. Correct the inventory and go.

```bash
scripts/ip-drift.sh --suggest
```

Paste its three blocks into `inventory/v31/hosts.yaml` (`ip:` per host),
`group_vars/all/all.yml` (`supplementary_addresses_in_ssl_keys`) and
`group_vars/all/etcd.yml` (`etcd_cert_alt_ips`), then:

```bash
scripts/ip-drift.sh && make preflight
```

Or delete the six `ip:` lines from `hosts.yaml` and let Kubespray derive them
(section 2C). The two `_alt_ips` / `_ssl_keys` lists still want the live values.

### 4.2 A worker moved

Remove and re-add. It is slower than an in-place fix and has no surprises.

```bash
source versions.env && source "$KUBESPRAY_VENV/bin/activate"
export KUBECONFIG="$V31_INV/artifacts/admin.conf"

# 1. Inventory: `ip` in hosts.yaml, and the live address into
#    supplementary_addresses_in_ssl_keys.
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

# 4. Confirm all four sources agree again.
cd "$V31_K8S_ROOT" && scripts/ip-drift.sh
```

In place, when you would rather not drain — the Node InternalIP only changes when
kubelet re-registers, so the restart is the load-bearing step:

```bash
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml --limit=w2 -e etcd_retries=10
ssh w2 'sudo systemctl restart kubelet'
kubectl -n kube-system delete pod -l k8s-app=calico-node --field-selector spec.nodeName=w2
cd "$V31_K8S_ROOT" && scripts/ip-drift.sh
```

It leaves stale iptables rules and CNI state behind, which is why remove-and-re-add
is the default.

### 4.3 A control-plane node moved, quorum intact

Two of three members are up, so the cluster serves and you can work on one node.
The etcd member peer URL is the only step a playbook will not do for you.

```bash
# 1. Inventory first, including etcd_cert_alt_ips — the member certificate has to
#    cover the new address.
scripts/ip-drift.sh --suggest

# 2. Re-render. This rewrites /etc/etcd.env, regenerates the etcd certificates
#    (force_etcd_cert_refresh defaults to true) and regenerates apiserver.crt if a
#    SAN is now missing. No manual `rm` of any certificate is needed.
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml \
  --limit=etcd,kube_control_plane -e etcd_retries=10
```

`etcd.service` on the moved node will now start — it binds the address it actually
has. The cluster still holds the **old** peer URL for it, so the member is
reachable by nobody. Fix that from a member that did not move:

```bash
ssh cp1
sudo -i
set -a; . /etc/etcd.env; set +a      # supplies ETCDCTL_* including endpoint 127.0.0.1

etcdctl member list -w table
# +------------------+---------+-------+-----------------------------+---
# | 8e9e05c52164694d | started | etcd2 | https://192.168.139.101:2380 | ...   <- stale

etcdctl member update 8e9e05c52164694d --peer-urls=https://192.168.139.102:2380
etcdctl endpoint health --cluster -w table
etcdctl endpoint status --cluster -w table
```

`member update` is the whole repair: it is a raft-level write, it takes effect
immediately, and it does not restart anything. Then restart the moved member and
let its static pods re-read the new endpoint list:

```bash
ssh cp2 'sudo systemctl restart etcd && sudo systemctl restart kubelet'
kubectl -n kube-system get pods -o wide | grep cp2
kubectl -n kube-system get lease plndr-cp-lock -o jsonpath='{.spec.holderIdentity}{"\n"}'
cd "$V31_K8S_ROOT" && scripts/ip-drift.sh
```

**When `member update` will not do it** — the member has been down long enough to
fall behind the leader's compacted log, or it comes back `unstarted`. Replace it
instead. Removing a member from a three-node cluster leaves two, which is still
quorum, so this is safe one node at a time:

```bash
# On a healthy member:
etcdctl member remove 8e9e05c52164694d
etcdctl member add etcd2 --peer-urls=https://192.168.139.102:2380
#   -> prints ETCD_NAME / ETCD_INITIAL_CLUSTER / ETCD_INITIAL_CLUSTER_STATE=existing

# On the moved node: wipe its data directory, or it rejoins with the old identity.
ssh cp2 'sudo systemctl stop etcd && sudo mv /var/lib/etcd /var/lib/etcd.pre-repair'

# Let Kubespray write the member back in with the corrected inventory.
cd "$KUBESPRAY_SRC"
ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml --limit=etcd -e etcd_retries=10

ssh cp1 'sudo -i sh -c "set -a; . /etc/etcd.env; set +a; etcdctl endpoint health --cluster -w table"'
```

Keep `/var/lib/etcd.pre-repair` until `endpoint health` is green on all three, then
delete it — it is a full copy of the keyspace.

### 4.4 All three control planes moved, quorum lost

No member can reach a peer, so etcd cannot elect and the apiserver is down.
Ansible still reaches every node over `.orb.local`.

```bash
scripts/ip-drift.sh --suggest          # inventory first; nothing below works without it

# Snapshot before touching anything.
cd "$KUBESPRAY_SRC"
ansible -i "$V31_INV/hosts.yaml" etcd -m shell -a \
  'systemctl stop etcd && cp -a /var/lib/etcd /var/lib/etcd.pre-repair'

ansible-playbook -i "$V31_INV/hosts.yaml" cluster.yml -e etcd_retries=10
```

If etcd still will not form — all three hold each other's old peer URLs and none
can be reached to be updated — restart one member as a single-node cluster, correct
its own URL, then rebuild the other two with 4.3's replacement procedure:

```bash
ssh cp1
sudo -i
set -a; . /etc/etcd.env; set +a
systemctl stop etcd
etcd --name "$ETCD_NAME" --data-dir "$ETCD_DATA_DIR" --force-new-cluster \
     --initial-advertise-peer-urls "$ETCD_INITIAL_ADVERTISE_PEER_URLS" &
etcdctl member list -w table
etcdctl member update <OWN_ID> --peer-urls="$ETCD_INITIAL_ADVERTISE_PEER_URLS"
kill %1; systemctl start etcd
```

`--force-new-cluster` discards the other two members' membership records. It is
a one-way door on a three-node cluster — take the snapshot above first. If the
keyspace matters more than the nodes, restoring from a snapshot
([runbook section 7](runbook.md#7-etcd-backup-and-restore)) onto a freshly reset
cluster is the shorter path.

### 4.5 A node was leased a VIP

```bash
scripts/ip-drift.sh          # FAILs with "was leased 192.168.139.240"
ssh w2 'sudo networkctl reconfigure eth0'   # forces a new DHCP transaction
scripts/ip-drift.sh
```

Then install the guard so the next offer is declined rather than accepted:

```bash
bin/kubespray.sh playbooks/kube-vip-dhcp-guard.yml
```

If the node keeps being offered the VIP, move the VIP instead: `kube_vip_address`
in `group_vars/all/all.yml` is the single source, and it reaches the cert SANs
through `supplementary_addresses_in_ssl_keys`.

---

## 5. Recommendation

**Accept drift; make detection and rebuild cheap. Do not invest in address
stability.**

The one mechanism that would actually pin an address — a static netplan
configuration — was measured to take the machine off OrbStack's ssh gateway after
a restart, which costs you Ansible. That is a worse failure than drift: drift
stops a playbook with a clear message, a missing transport stops everything with
an obscure one. The name-based alternative needs DNS this environment does not
have, and still cannot cover `--node-ip` or the etcd listen URLs.

What drift actually costs, now that it is measured: one `scripts/ip-drift.sh` run
to see it, one `--suggest` paste to correct three files, and a `cluster.yml` run
that repairs certificates, `/etc/etcd.env` and kubelet by itself. The only manual
step in the whole set is `etcdctl member update`, one command, and only when a
control plane moves while the cluster is up.

For a lab cluster that is rebuilt often, that is already cheaper than maintaining
an address-pinning mechanism that fights the platform. Spend the effort on the
three things that make drift a non-event instead:

1. Run `scripts/ip-drift.sh` before every playbook — it is in `make preflight`'s
   territory and takes seconds.
2. Consider dropping `ip:` from `hosts.yaml` entirely, so a rebuild needs no
   inventory edit at all.
3. Run `playbooks/kube-vip-dhcp-guard.yml` once per machine lifetime, because the
   VIP collision is the one drift-shaped failure that is genuinely hard to debug.
