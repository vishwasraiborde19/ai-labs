# infra-canary

A small Spring Boot app whose only job is to prove the lab's delivery path works end to end:

```
 docker build / mvn jib ──push──▶ Harbor 192.168.1.173/infracanary/infra-canary ──pull──▶ Kubernetes (ns infra-canary)
                                                                                   │
                         Longhorn PVC infra-canary-logs (replica on nas-server) ◀──┤ /app/logs/canary/infra-canary.log
                                                                                   └ GET /canary: volume, DNS, Harbor,
                                                                                     Prometheus, Grafana
```

If it builds, pushes to Harbor, gets pulled by the cluster, starts, writes its log file to the Longhorn
volume and `/canary` says `OK`, the platform is healthy.

Registry setup (insecure HTTP registry for Docker and containerd, projects, robot accounts, pull secrets):
[tool-box/harbour/runbook.md](../mcp-agents/tool-box/harbour/runbook.md).

## Endpoints

| Endpoint | Does |
|---|---|
| `GET /canary` | runs every check; **200** + `"status":"OK"` or **503** + the failing checks |
| `GET /hello` | one INFO line; returns the pod name and a request id |
| `POST /log?level=WARN&count=5&msg=hi` | `count` lines (max 10 000) at `level` (TRACE..ERROR) |
| `POST /error` | an ERROR with a stack trace |
| `POST /burst?lines=100000` | many ~250-byte lines fast, to watch log rotation |
| `/actuator/health`, `/actuator/loggers` | probes; change log levels at runtime |

A heartbeat INFO line is logged every 30 s, so an idle pod still shows it is logging.

| `/canary` check | Passes when |
|---|---|
| `log-volume` | a probe file can be written, read back and deleted in `/app/logs/canary`; reports free space |
| `dns` | `kubernetes.default.svc.cluster.local` resolves (CoreDNS) |
| `http:<name>` | each `canary.http-targets` URL answers 2xx (Harbor, central Prometheus, Grafana; `k8s/20-config.yaml`) |

## Logs

| Where | What |
|---|---|
| `/app/logs/canary/infra-canary.log` | current file, on the Longhorn PVC `infra-canary-logs` (1 GiB) |
| `/app/logs/canary/infra-canary.log.<date>.<n>.gz` | rotated: 5 MB per file, 3 days, 500 MB cap (`k8s/20-config.yaml`) |
| `kubectl logs` | the same lines on stdout |

The container runs as uid/gid **10001** (not root); the Deployment's `fsGroup: 10001` makes the volume
writable for it.

## Build the image

Pick one. Both produce `192.168.1.173/infracanary/infra-canary:0.1.0`, non-root, logs under `/app/logs/canary`.

### Docker

One-time: Harbor serves plain HTTP, so add it to Docker's `insecure-registries` and log in
([runbook §2](../mcp-agents/tool-box/harbour/runbook.md#2-docker-add-the-registry-log-in-push-pull)):

```bash
# /etc/docker/daemon.json  ->  { "insecure-registries": ["192.168.1.173"] }   then: sudo systemctl restart docker
read -rsp 'robot secret: ' HARBOR_TOKEN; echo
echo "$HARBOR_TOKEN" | docker login 192.168.1.173 -u 'robot$infracanary+robo' --password-stdin
```

Build the jar with Maven (runs the tests), then the image from it (`Dockerfile` copies
`target/infra-canary-0.1.0.jar`; other versions: `--build-arg JAR_FILE=target/infra-canary-<version>.jar`), and push:

```bash
cd infra-canary
mvn -B package                       # -> target/infra-canary-0.1.0.jar
docker build -t 192.168.1.173/infracanary/infra-canary:0.1.0 -t 192.168.1.173/infracanary/infra-canary:latest .
docker push 192.168.1.173/infracanary/infra-canary:0.1.0
docker push 192.168.1.173/infracanary/infra-canary:latest
```

Try it locally before pushing (logs go to the container's own `/app/logs/canary`):

```bash
docker run --rm -p 8080:8080 192.168.1.173/infracanary/infra-canary:0.1.0 &
curl -s localhost:8080/canary          # dns check fails outside Kubernetes: expected
docker exec $(docker ps -qf ancestor=192.168.1.173/infracanary/infra-canary:0.1.0) tail -3 /app/logs/canary/infra-canary.log
```

### Jib (no Docker; used on the installer VM)

```bash
cd infra-canary
mvn -B test
mvn -B compile jib:build -DsendCredentialsOverHttp=true -Djib.to.auth.username='robot$infracanary+robo' -Djib.to.auth.password="$HARBOR_TOKEN"
# pushes :0.1.0 and :latest
```

Check the push: portal → *Projects → infracanary → Repositories → infra-canary*.

## Deploy

Prerequisites (once per cluster / namespace):

1. Harbor project **infracanary** exists and the robot has push + pull on it
   ([runbook §1](../mcp-agents/tool-box/harbour/runbook.md#1-projects-and-robot-accounts)).
2. Every node's containerd accepts `http://192.168.1.173`
   ([runbook §3](../mcp-agents/tool-box/harbour/runbook.md#3-kubernetes-nodes-let-containerd-pull-over-http)).
3. The pull secret, below.

```bash
kubectl apply -f k8s/00-namespace.yaml
kubectl -n infra-canary create secret docker-registry harbor-pull --docker-server=192.168.1.173 \
  --docker-username='robot$infracanary+robo' --docker-password="$HARBOR_TOKEN"
kubectl apply -f k8s/
kubectl -n infra-canary rollout status deploy/infra-canary
kubectl -n infra-canary get pod -o wide          # Running; image pulled from 192.168.1.173
kubectl -n infra-canary get pvc                  # infra-canary-logs  Bound  longhorn
```

## Check

```bash
kubectl -n infra-canary port-forward svc/infra-canary 8080:8080 &
curl -s localhost:8080/canary | python3 -m json.tool               # "status": "OK"
curl -X POST 'localhost:8080/log?level=ERROR&count=3&msg=boom'
curl -X POST localhost:8080/error

kubectl -n infra-canary exec deploy/infra-canary -- ls -la /app/logs/canary          # on the Longhorn volume
kubectl -n infra-canary exec deploy/infra-canary -- tail -20 /app/logs/canary/infra-canary.log
kubectl -n infra-canary exec deploy/infra-canary -- df -h /app/logs/canary            # ~1G block device
kubectl -n infra-canary logs deploy/infra-canary --tail=20                            # same lines on stdout

curl -X POST 'localhost:8080/burst?lines=100000'                                       # ~25 MB
kubectl -n infra-canary exec deploy/infra-canary -- ls -lh /app/logs/canary           # rotated .gz files
```

The logs survive a restart (`kubectl -n infra-canary rollout restart deploy/infra-canary`): the new pod
mounts the same volume. The volume shows in the Longhorn UI (http://192.168.1.240) under *Volume*.

| Pod status | Look at |
|---|---|
| `ErrImagePull` / `ImagePullBackOff` | `kubectl -n infra-canary describe pod` events; runbook §6 (HTTP mirror, pull secret, project) |
| `Pending` | `kubectl -n infra-canary describe pvc infra-canary-logs` (Longhorn disk on nas-server) |
| `CrashLoopBackOff` | `kubectl -n infra-canary logs deploy/infra-canary --previous` |
| `/canary` 503 | the failing check's `detail` in the response |

## Remove

```bash
kubectl delete namespace infra-canary      # the Longhorn volume stays (Retain): delete it in the Longhorn UI
```
