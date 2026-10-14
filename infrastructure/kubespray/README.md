# V31 Kubernetes cluster

## Install

```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)"/inventory/v31,dst=/inventory \
  --mount type=bind,source="${HOME}"/.lima/_config/user,dst=/root/.ssh/id_ed25519 \
  quay.io/kubespray/kubespray:v2.32.0 \
  ansible-playbook -i /inventory/inventory.ini --private-key /root/.ssh/id_ed25519 -b cluster.yml
```

## Uninstall

```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)"/inventory/v31,dst=/inventory \
  --mount type=bind,source="${HOME}"/.lima/_config/user,dst=/root/.ssh/id_ed25519 \
  quay.io/kubespray/kubespray:v2.32.0 \
  ansible-playbook -i /inventory/inventory.ini --private-key /root/.ssh/id_ed25519 -b reset.yml
```
```bash
for m in control-plane1 control-plane2 control-plane3 worker1 worker2 worker3; do limactl restart $m; done
```
