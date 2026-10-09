# V31 Kubernetes cluster

## Machines

OrbStack 2.2.3 machines, Ubuntu 26.04 arm64.

| Machine | Role | CPU | Memory | Disk |
|---|---|---|---|---|
| control-plane1 | control plane, etcd | 2 | 4 GiB | 50 GiB |
| control-plane2 | control plane, etcd | 2 | 4 GiB | 50 GiB |
| control-plane3 | control plane, etcd | 2 | 4 GiB | 50 GiB |
| worker1 | worker | 4 | 10 GiB | 100 GiB |
| worker2 | worker | 4 | 10 GiB | 100 GiB |
| worker3 | worker | 4 | 10 GiB | 100 GiB |

## Install

```bash
../linux-vm/orbstack/resolv-conf.sh unlink
```
```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)"/inventory/v31,dst=/inventory \
  --mount type=bind,source="${HOME}"/.orbstack/ssh/id_ed25519,dst=/root/.ssh/id_ed25519 \
  quay.io/kubespray/kubespray:v2.32.0 \
  ansible-playbook -i /inventory/inventory.ini --private-key /root/.ssh/id_ed25519 -b --extra-vars @/inventory/extra-vars.yaml cluster.yml
```

## Uninstall

```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)"/inventory/v31,dst=/inventory \
  --mount type=bind,source="${HOME}"/.orbstack/ssh/id_ed25519,dst=/root/.ssh/id_ed25519 \
  quay.io/kubespray/kubespray:v2.32.0 \
  ansible-playbook -i /inventory/inventory.ini --private-key /root/.ssh/id_ed25519 -b --extra-vars @/inventory/extra-vars.yaml reset.yml
```
```bash
../linux-vm/orbstack/resolv-conf.sh link
```
