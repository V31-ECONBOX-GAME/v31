# V31 Kubernetes cluster

## Install

```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)",dst=/kubespray-cluster \
  --mount type=bind,source="${HOME}"/.lima/_config/user,dst=/root/.ssh/id_ed25519 \
  --workdir /kubespray-cluster \
  quay.io/kubespray/kubespray:v2.32.0 \
  ansible-playbook cluster.yaml
```

## Update

`upgrade_cluster_setup` makes kubeadm apply control-plane changes.

```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)",dst=/kubespray-cluster \
  --mount type=bind,source="${HOME}"/.lima/_config/user,dst=/root/.ssh/id_ed25519 \
  --workdir /kubespray-cluster \
  quay.io/kubespray/kubespray:v2.32.0 \
  ansible-playbook -e '{"upgrade_cluster_setup": true}' cluster.yaml
```

## Uninstall

```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)"/inventory/v31,dst=/inventory \
  --mount type=bind,source="${HOME}"/.lima/_config/user,dst=/root/.ssh/id_ed25519 \
  quay.io/kubespray/kubespray:v2.32.0 \
  ansible-playbook -i /inventory/inventory.ini --private-key /root/.ssh/id_ed25519 -b -e reset_confirmation=yes reset.yml
```
```bash
for m in control-plane1 control-plane2 control-plane3 worker1 worker2 worker3; do limactl restart $m; done
```

## Molecule Test

```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)",dst=/kubespray-cluster \
  --mount type=bind,source=/var/run/docker.sock,dst=/var/run/docker.sock \
  --workdir /kubespray-cluster/roles/etcd_certs \
  quay.io/kubespray/kubespray:v2.32.0 \
  sh -c '
    pip install -q --break-system-packages --no-cache-dir \
      molecule==26.10.0 \
      "molecule-plugins[docker]==26.10.12" &&
    molecule test
  '
```

## Integration Test

```bash
docker run --rm -it --platform linux/amd64 \
  --mount type=bind,source="$(pwd)",dst=/kubespray-cluster \
  --mount type=bind,source="${HOME}"/.lima/_config/user,dst=/root/.ssh/id_ed25519 \
  --workdir /kubespray-cluster \
  quay.io/kubespray/kubespray:v2.32.0 \
  ansible-playbook tests/testcases/tests.yaml
```
