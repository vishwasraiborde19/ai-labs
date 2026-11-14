# Diagram templates (ai-labs)

Copy a template, then replace the example content with verified facts. Contents:

1. Context (C4 L1)
2. Container (C4 L2)
3. Deployment
4. Sequence
5. Data flow
6. State

---

## 1. Context — "what is it, who uses it, what does it depend on"

System of interest in the middle, people on the left, dependencies on the right. System-level boxes
only — no pods, no ports unless they define the integration.

```mermaid
flowchart LR
    dev(["Developer<br/>laptop, Docker, Claude Code"])
    ops(["Operator<br/>Semaphore UI, Grafana"])

    canary["infra-canary<br/>Spring Boot app in k8s<br/>proves delivery + storage + logging"]

    harbor[("Harbor<br/>192.168.1.173<br/>image registry")]
    k8s["Kubernetes proxmox01<br/>runs the pod"]
    lh[("Longhorn<br/>log volume, replica on nas-server")]
    obs["Observability<br/>192.168.1.137<br/>Prometheus, Grafana"]

    dev -- "build + push image" --> harbor
    ops -- "opens chaos console<br/>http://192.168.1.241" --> canary
    k8s -- "pulls image (HTTP)" --> harbor
    k8s -- "schedules" --> canary
    canary -- "writes logs" --> lh
    canary -- "GET /canary probes" --> obs
    obs -. "scrape /actuator/prometheus<br/>(planned)" .-> canary
```

## 2. Container — "what runs inside, how the parts talk"

One subgraph per runtime boundary (VM, namespace). Show protocols and ports on arrows.

```mermaid
flowchart LR
    subgraph NS["namespace infra-canary"]
        dep["Deployment infra-canary<br/>1 replica, uid 10001"]
        svc["Service infra-canary<br/>ClusterIP :8080"]
        con["Service infra-canary-console<br/>LB 192.168.1.241 :80<br/>publishNotReadyAddresses"]
        pvc[("PVC infra-canary-logs<br/>1Gi RWO longhorn")]
        cm["ConfigMap infra-canary"]
        sec["Secret harbor-pull"]
    end
    svc --> dep
    con --> dep
    dep -- "mount /app/logs/canary" --> pvc
    cm -- "envFrom" --> dep
    sec -- "imagePullSecrets" --> dep
```

## 3. Deployment — "which machine hosts what"

Nest physical/virtual hosts → VMs → cluster roles. Addresses on every host.

```mermaid
flowchart TB
    subgraph PVE["Proxmox VE  .30 / .40"]
        subgraph CP["control plane"]
            cp1["control-panel-001<br/>.20"]
            cp2["control-panel-002<br/>.21"]
            cp3["control-panel-003<br/>.51"]
        end
        subgraph WK["workers"]
            w1["kube-node-001<br/>.104"]
            w2["kube-node-002<br/>.198"]
            w3["kube-node-003<br/>.142"]
        end
        nas["nas-server .13<br/>storage-only, tainted<br/>Longhorn 196 GiB + NFS"]
        harbor["docker-registry-harbor .173<br/>Harbor v2.15.5"]
        sem["Semaphore .42"]
        obs["observability .137"]
    end
    sem -- "SSH / ansible" --> CP & WK & nas & harbor
```

## 4. Sequence — "what happens, in order"

Use for delivery pipelines, request paths, failure handling. `alt` for branches, `Note` for the
non-obvious rule that makes it work.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as Service
    participant K as kubelet
    C->>S: GET /orders
    alt pod Ready
        S-->>C: 200
    else readiness failing
        Note over S,K: pod removed from endpoints after 3 x 5s
        S-->>C: routed to another replica (or 503 if none)
    end
    K->>S: GET /actuator/health/liveness every 10s
```

## 5. Data flow — "where does data go and where does it rest"

Stores as cylinders; label each hop with format/protocol and retention if relevant.

```mermaid
flowchart LR
    app["app logback<br/>rotate 5MB x3"] -- "file writes" --> vol[("Longhorn volume<br/>ext4, 1Gi")]
    vol -- "replication (LAN)" --> rep[("replica on nas-server<br/>/var/lib/longhorn")]
    app -- "stdout" --> kl["kubelet / containerd logs<br/>on the node, rotated"]
    app -- "/actuator/prometheus" --> prom[("Prometheus .137<br/>retention per server config")]
```

## 6. State — "what states does it move through"

```mermaid
stateDiagram-v2
    [*] --> Pending
    Pending --> ContainerCreating: scheduled, volume attached
    ContainerCreating --> Running: image pulled
    ContainerCreating --> ImagePullBackOff: pull failed
    ImagePullBackOff --> ContainerCreating: retry
    Running --> CrashLoopBackOff: exits repeatedly
    Running --> [*]: deleted
```
