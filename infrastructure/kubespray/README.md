# V31 Kubernetes cluster

## Install

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
