# Deploying v31-compliance-service on Docker Desktop Kubernetes

The image is built from `../Dockerfile`, pushed to a Harbor running as a Docker Compose stack on
the same machine, and pulled from there by the cluster with a Harbor robot account. Nothing is
side-loaded into the node, so the path the cluster takes is the one a real environment takes.

The registry is addressed as `harbor.local:80`, with the port spelled out. Without it the Docker
client resolves the registry to port 443, finds nothing listening, and does not fall back to HTTP
even though the registry is configured as insecure.

## Host setup

| Piece | How it is set |
|-------|---------------|
| Docker Desktop Kubernetes, `kind` provisioner | `kubernetesEnabled` in Docker Desktop's settings |
| `harbor.local` on the host | `127.0.0.1 harbor.local` in `/etc/hosts` |
| Insecure registry for the Docker engine | `insecure-registries: ["harbor.local"]` in Docker Engine settings |
| Direct connection, no Docker Desktop proxy | `harbor.local` in the proxy exclusion list; without it the built-in proxy answers 503 |
| Harbor | `~/harbor-install/harbor`, `install.sh` without Trivy |

Harbor's official images are amd64 only, so on Apple Silicon the whole stack runs under Rosetta.

## Node setup

The node's containerd needs both a route to Harbor and permission to talk to it over HTTP:

```bash
docker exec desktop-control-plane sh -c '
  grep -q harbor.local /etc/hosts || echo "172.20.0.1 harbor.local" >> /etc/hosts
  mkdir -p "/etc/containerd/certs.d/harbor.local:80"
  cat > "/etc/containerd/certs.d/harbor.local:80/hosts.toml" <<TOML
server = "http://harbor.local:80"

[host."http://harbor.local:80"]
  capabilities = ["pull", "resolve"]
  skip_verify = true
TOML
'
```

`172.20.0.1` is the gateway of the `kind` network, where Harbor's published port 80 answers.
Docker rewrites a container's `/etc/hosts` every time it starts, so **the hosts line has to be
re-applied after the cluster restarts**; the `certs.d` file lives in the node's filesystem and
survives. A pull that fails with `dial tcp: connect: connection refused` is this line missing.

## The pull secret

`compliance.yaml` refers to a secret named `harbor` that is not committed, because it holds a
credential. Create it from a Harbor robot account that can only pull:

```bash
kubectl --namespace v31 create secret docker-registry harbor \
  --docker-server=harbor.local:80 \
  --docker-username='robot$v31+v31-puller' \
  --docker-password='<robot secret>'
```

## Build, push, deploy

```bash
./gradlew :cloud:v31-compliance-service:bootJar

docker build \
  --build-arg JAR_FILE=build/libs/v31-compliance-service-0.2.0-SNAPSHOT.jar \
  --tag harbor.local:80/v31/v31-compliance-service:0.2.0-SNAPSHOT .

docker login harbor.local:80
docker push harbor.local:80/v31/v31-compliance-service:0.2.0-SNAPSHOT

kubectl apply --filename k8s/namespace.yaml
kubectl apply --filename k8s/postgres.yaml
kubectl apply --filename k8s/compliance.yaml
kubectl --namespace v31 rollout status deployment/v31-compliance-service
```

The tag is a snapshot that gets overwritten, so the deployment pulls with `imagePullPolicy: Always`
and a rebuilt image needs only a restart:

```bash
docker push harbor.local:80/v31/v31-compliance-service:0.2.0-SNAPSHOT
kubectl --namespace v31 rollout restart deployment/v31-compliance-service
```

## Reaching the service

```bash
kubectl --namespace v31 port-forward service/v31-compliance-service 8084:8084
```

The service has no controllers yet, so `/` answers 404. That is Tomcat replying, not a failure.
