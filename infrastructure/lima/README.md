# Lima machines

Ubuntu 24.04 arm64 virtual machines, one kernel each.

| Machine | IP | CPU | Memory | Disk |
|---|---|---|---|---|
| control-plane1 | 10.10.0.11 | 2 | 4 GiB | 50 GiB |
| control-plane2 | 10.10.0.12 | 2 | 4 GiB | 50 GiB |
| control-plane3 | 10.10.0.13 | 2 | 4 GiB | 50 GiB |
| worker1 | 10.10.0.21 | 4 | 10 GiB | 100 GiB |
| worker2 | 10.10.0.22 | 4 | 10 GiB | 100 GiB |
| worker3 | 10.10.0.23 | 4 | 10 GiB | 100 GiB |

## Network

| Address | Use |
|---|---|
| 10.10.0.0/24 | Lima `shared` network |
| 10.10.0.1 | Gateway, DHCP and DNS, on the Mac |
| 10.10.0.2–10.10.0.9 | DHCP, first boot only |

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

## 3. Create

```bash
for m in control-plane1 control-plane2 control-plane3 worker1 worker2 worker3; do limactl start -y $m.yaml; done
```

## 4. Use

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
./socket_vmnet/vmnet.sh uninstall
```
