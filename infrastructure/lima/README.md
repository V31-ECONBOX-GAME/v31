# Lima machines

Ubuntu 24.04 arm64 virtual machines, one kernel each.

| Machine | IP | CPU | Memory | Disk |
|:---:|:---:|:---:|:---:|:---:|
| docker | 10.10.0.60 | 4 | 4 GiB | 100 GiB |
| control-plane1 | 10.10.0.11 | 2 | 4 GiB | 50 GiB |
| control-plane2 | 10.10.0.12 | 2 | 4 GiB | 50 GiB |
| control-plane3 | 10.10.0.13 | 2 | 4 GiB | 50 GiB |
| worker1 | 10.10.0.21 | 4 | 10 GiB | 100 GiB |
| worker2 | 10.10.0.22 | 4 | 10 GiB | 100 GiB |
| worker3 | 10.10.0.23 | 4 | 10 GiB | 100 GiB |

## Network

```
Mac                      10.10.0.1
├─ docker                10.10.0.60
│  ├─ bridge             10.250.0.1/24
│  ├─ kube_default       10.251.0.1/24
│  ├─ frr                10.10.0.60, BGP → routes
│  ├─ xray               10.251.0.2, Mac 0.0.0.0:1080
│  └─ routes             default via 10.10.0.1
│                        10.200.0.x via worker1..3
│                        10.200.0.1 via control-plane1..3
├─ control-plane1..3     10.10.0.11..13
├─ worker1..3            10.10.0.21..23
└─ routes                10.200.0.0/24 via 10.10.0.60
```

| Range | Use             |   Owner    |
|:---:|:---------------:|:----------:|
| 10.10.0.0/24 | VMs             |    lima    |
| 10.200.0.1 | KubeApiserver   |   script   |
| [10.200.0.2, 10.200.0.254] | LoadBalancer    |   cilium   |
| 10.233.0.0/18 | Services        | kubernetes |
| 10.233.64.0/18 | Pods            |   cilium   |
| 10.250.0.0/24 | DefaultBridge   |   docker   |
| [10.251.0.0, 10.251.29.255] | UserDefinedBridges |   docker   |

### 10.10.0.0/24

| Addresses | Use |
|:---:|:---:|
| .1 | Gateway (Mac) |
| [.2, .9] | DHCP |
| [.10, .19] | Control plane |
| [.20, .29] | Workers |
| [.30, .39] | etcd |
| [.40, .49] | Ingress |
| [.50, .59] | Storage |
| .60 | Docker |
| [.61, .99] | Reserved |
| [.100, .199] | More workers |
| [.200, .249] | Sandbox |
| [.250, .254] | Network devices |

## 1. Install Lima 2.2.1

```bash
brew install lima
```
```bash
limactl --version
```

## 2. Install the network

```bash
./socket_vmnet/vmnet.sh install
```
```bash
./socket_vmnet/vmnet.sh status
```

## 3. Docker

```bash
limactl start -y docker.yaml
```
```bash
./docker/cli.sh install
```
```bash
./docker/cli.sh status
```
```bash
docker version
```

## 4. Kubernetes machines

```bash
for m in control-plane1 control-plane2 control-plane3 worker1 worker2 worker3; do limactl start -y $m.yaml; done
```

## 5. Use

```bash
limactl list
```
```bash
limactl shell worker1
```
```bash
ssh -i ~/.lima/_config/user wangxiang@10.10.0.21
```
```bash
limactl shell worker1 uname -a
```
```bash
limactl copy ./file worker1:/tmp/
```

## Stop and start

```bash
for m in control-plane1 control-plane2 control-plane3 worker1 worker2 worker3; do limactl stop $m; done
```
```bash
for m in control-plane1 control-plane2 control-plane3 worker1 worker2 worker3; do limactl start $m; done
```
```bash
limactl stop worker1
```
```bash
limactl start worker1
```
```bash
limactl restart worker1
```

## Recreate one machine

```bash
limactl delete -f worker1
```
```bash
limactl start -y worker1.yaml
```

## Delete

```bash
limactl delete -f control-plane1 control-plane2 control-plane3 worker1 worker2 worker3
```
```bash
limactl delete -f docker
```
```bash
./docker/cli.sh uninstall
```
```bash
./socket_vmnet/vmnet.sh uninstall
```
