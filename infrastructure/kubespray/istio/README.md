# Istio ambient mesh

Istio 1.31.1 in **ambient** mode on the V31 Kubespray cluster: `ztunnel` for L4
(mTLS, HBONE, L4 authorization) and **waypoint proxies** — Envoy — for L7
(HTTP routing, timeouts, L7 authorization, HTTP telemetry). North-south traffic
enters through a Gateway API `Gateway`.

## Layout

| Path | What it is |
|------|------------|
| `versions.yml` | every pinned version; `install.yml` asserts it matches `values/` |
| `values/base.yaml` | CRDs and cluster RBAC |
| `values/istiod.yaml` | control plane, ambient profile |
| `values/cni.yaml` | CNI node agent; chains after Calico |
| `values/ztunnel.yaml` | node proxy DaemonSet |
| `values/gateway.yaml` | statically managed ingress; off by default |
| `manifests/ingress-gateway.yaml` | edge `Gateway` + generated-resource patches |
| `manifests/kubevip-address-pool.yaml` | LoadBalancer pool; owned by the kube-vip track |
| `manifests/sample-app/namespace.yaml` | namespace + waypoint; applied and waited for first |
| `manifests/sample-app/workloads.yaml` | Deployments and Services; `echo` carries the binding |
| `manifests/sample-app/mesh.yaml` | HTTPRoutes and policies |
| `install.yml` / `verify.yml` | idempotent install; read-only checks |

## Prerequisites

Kubespray has run, Calico is healthy, and `/etc/kubernetes/admin.conf` exists on
`kube_control_plane[0]`. Kubernetes must be 1.32–1.36; Kubespray v2.32.0 ships
1.36.4, which is in range.

## Run

```sh
ansible-playbook -i inventory/v31/hosts.yaml istio/install.yml
ansible-playbook -i inventory/v31/hosts.yaml istio/verify.yml
```

Both are idempotent. `install.yml` upgrades a Helm release only when the rendered
manifest differs from what is live.

## Reaching the edge

The edge Service is `LoadBalancer` with fixed nodePorts, so it answers either way:

```sh
curl -H 'Host: echo.v31.local' http://cp1.orb.local:30080/    # always
curl -H 'Host: echo.v31.local' http://192.168.139.241/        # once kube-vip claims
                                                              # .241 from the annotation
```

The generated Service gets its 15021 and 80 entries from istiod: the deployment
controller always prepends a `status-port` at 15021 and then adds one port per
listener. The `service` overlay in `manifests/ingress-gateway.yaml` is a strategic
merge keyed on the port number, which is why it can add `nodePort` without
restating `name`, `protocol` or `targetPort`.

Supplying `podDisruptionBudget` in that ConfigMap is what makes the PDB exist —
istiod drops the templated PDB and HPA unless an overlay for them is present. Not
supplying `horizontalPodAutoscaler` is therefore deliberate: it drops the HPA, so
the `replicas: 2` in the `deployment` overlay is not fought over.

The address pool lives in `manifests/kubevip-address-pool.yaml` and belongs to the
kube-vip track. Set `istio_apply_kubevip_pool=true` only if this track should own it.

## The part that breaks

Ambient needs `istio-cni` appended to Calico's CNI plugin chain. `values/cni.yaml`
leaves `cniConfFileName` empty, which means the agent takes the first valid conf
file in `cniConfDir` by sorted name and appends itself to its `plugins` array. On a
Kubespray/Calico node that is `10-calico.conflist`, written by calico-node's
`install-cni` with `CNI_CONF_NAME=10-calico.conflist`:

```sh
python3 -c 'import json;print([p["type"] for p in json.load(open("/etc/cni/net.d/10-calico.conflist"))["plugins"]])'
# ['calico', 'portmap', 'bandwidth', 'istio-cni']
#
# Calico's install-cni runs as an initContainer, so it rewrites this file on every
# calico-node start, not just on upgrade. The istio-cni entry disappears briefly and
# istio-cni's watcher re-appends it. verify.yml retries through that window.
```

`verify.yml` asserts this on every node. If `istio-cni` is missing, restart
`daemonset/istio-cni-node`; it rewrites the chain on start.

Calico must stay on its iptables dataplane (`bpfEnabled: false`) and must not be
given `cni.exclusive`-style behaviour — ambient's in-pod TPROXY rules and Calico's
eBPF dataplane both claim the same hooks.

## Enrolling a namespace

```sh
kubectl label namespace <ns> istio.io/dataplane-mode=ambient      # L4, ztunnel
kubectl label service <svc> istio.io/use-waypoint=<name>          # L7, waypoint
```

`istio.io/use-waypoint` is also valid on a `Namespace`, where it binds every `Pod`
and `Service` in it. The sample app binds the `Service` instead, on purpose: the
namespace-wide form would also bind `echo-v1` and `echo-v2`, which are the
waypoint's own HTTPRoute backends, so every request would be a candidate for a
second waypoint hop. Binding the aggregate Service gives exactly one L7 hop.

**Create the waypoint before anything names it.** A `Service` carrying
`istio.io/use-waypoint` for a waypoint that does not exist has an unresolvable
reference and its traffic stays L4 until the waypoint appears. `install.yml`
applies `namespace.yaml`, waits for the waypoint Deployment to roll out, and only
then applies `workloads.yaml`.

The waypoint itself is a `Gateway` with `gatewayClassName: istio-waypoint`. That
class sets `DisableNameSuffix`, so the Deployment, Service and ServiceAccount are
all named after the Gateway alone — `v31-demo-waypoint`. The `istio` class does
append the class name, which is why the edge resources are `v31-edge-istio`. Both
names appear as SPIFFE principals in `mesh.yaml`; they are not interchangeable.

`HTTPRoute.rules[].retry` is not in the Gateway API standard channel at v1.6.2.
Switch `gateway_api_channel` to `experimental` if retry budgets are needed;
`timeouts.request` and `timeouts.backendRequest` are standard and are used.

## What verify.yml proves

Every check is a state query or a live request. Nothing greps a log — ztunnel 1.31
has no `inpod_enabled` config field (the equivalent is `proxy_mode: Shared`), the
chart still sets a legacy `INPOD_ENABLED` env that ztunnel does not read, and
`values/ztunnel.yaml` sets `logAsJson`, so a plain-text grep could never match.

| Check | What it rules out |
|-------|-------------------|
| plugin chain on every node | istio-cni not in Calico's chain |
| `numberReady == desiredNumberScheduled == node count` | a node with no ztunnel or no CNI agent |
| `istio-cni-config` `AMBIENT_ENABLED`/`CHAINED_CNI_PLUGIN` | the agent running in sidecar or standalone mode |
| `ambient.istio.io/redirection: enabled` on each sample pod | a pod the agent never actually captured |
| waypoint `Gateway` `Programmed: True` | a waypoint istiod never provisioned |
| `GET /` → 200 | nothing; it is the baseline |
| `x-v31-mesh: waypoint` echoed by the backend | the request bypassing the waypoint |
| `x-v31-canary: true` → `Name: echo-v2` | ztunnel masquerading as L7 — only Envoy reads a header |
| `POST /` → 403 | L4-only policy; ztunnel cannot see a method |
| `GET /not-allowed` → 403 | L4-only policy; ztunnel cannot see a path |
| pod IP direct → not 200 | the L7 policy being bypassable at L4 |
| edge Service + `Host: echo.v31.local` → 200 | north-south never reaching the waypoint |

The edge `Gateway`'s `Programmed` condition is reported, not asserted: it is a
LoadBalancer, so it stays `False` until kube-vip claims `.241`.
