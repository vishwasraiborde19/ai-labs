# ai-labs architecture

How the lab's systems fit together: what exists now, why it is like this ([decisions](adr/)), and what is
about to change ([open items](#open-items), [designs](designs/)). Operating one system is in its runbook,
linked from the [systems table](#systems); building one project is in its own README.

**State as of 2026-11-14**, verified against the inventories, `kubectl` on the cluster and the
Semaphore database that day.

**Legend** for every diagram: solid arrow = applied and working; dashed arrow = configured in the repo but
not applied, planned, or broken (the label says which).

---

## 1. Context

Who talks to what. Addresses and versions are in the [systems table](#systems).

```mermaid
flowchart LR
    dev(["Developer<br/>laptop: Docker, Claude Code, IntelliJ"])
    gh[("GitHub<br/>ai-labs repo")]
    hub[("Docker Hub<br/>vishwasraiborde19/infra-canary-img")]

    subgraph AUTO["Automation"]
        sem["Semaphore<br/>Ansible, kubespray"]
        inst["Installer VM<br/>kubectl, helm, Maven/Jib"]
        jen["Jenkins<br/>address unverified"]
    end

    harbor[("Harbor<br/>private registry, HTTP")]

    subgraph K8S["Kubernetes proxmox01"]
        apps["Workloads<br/>platform, infra-canary, nginx"]
        lh[("Longhorn<br/>volumes on nas-server")]
        mon["cluster-monitoring<br/>kube-prometheus-stack"]
    end

    obs["Observability VM<br/>Prometheus, Grafana, OTel"]
    kc["Keycloak<br/>SSO realm lab"]
    mcp{{"MCP worker agents<br/>on each VM"}}

    dev -- "git push" --> gh
    dev -- "Claude Code (MCP)" --> mcp
    dev -- "docker login / push" --> harbor
    gh -- "playbooks per task" --> sem
    gh -- "checkout main" --> jen
    jen -- "docker push" --> hub
    sem -- "SSH: kubespray" --> K8S
    sem -- "SSH: harbor playbook" --> harbor
    inst -- "jib push" --> harbor
    inst -- "kubectl / helm" --> K8S
    apps -- "pull image" --> hub
    apps -. "pull image over HTTP<br/>needs containerd mirror (pending)" .-> harbor
    apps -- "log PVCs" --> lh
    mon -- "remote-write" --> obs
    mcp -. "runs commands on the VMs" .- sem
    kc -. "SSO (planned)" .- sem & obs
```

## 2. Key flows

### 2.1 Image delivery: source → registry → pod

Two paths exist today ([open item](#open-items): choose the canonical one). The cluster currently runs the
Docker Hub image; pulling from Harbor waits for the containerd mirror ([ADR 0001](adr/0001-run-harbor-over-plain-http-on-a-dedicated-vm.md),
[design](designs/2026-11-cluster-pulls-from-harbor.md)).

```mermaid
sequenceDiagram
    autonumber
    participant G as GitHub
    participant J as Jenkins
    participant D as Docker Hub
    participant I as Installer VM / laptop
    participant H as Harbor 192.168.1.173
    participant N as Worker node (containerd 1.7.5)

    alt Jenkins path (deployed today)
        G->>J: checkout main
        J->>J: mvn package, docker build (infra-canary/Dockerfile)
        J->>D: docker push vishwasraiborde19/infra-canary-img
        N->>D: pull over HTTPS
    else Harbor path (image pushed, pull pending)
        I->>H: mvn jib:build -DsendCredentialsOverHttp=true (or docker push)
        Note over H: token realm = http://192.168.1.173/service/token<br/>(built from the request Host header)
        N--xH: pull fails: containerd tries https :443
        Note over N,H: fix: containerd_registries_mirrors http://192.168.1.173<br/>(kubespray-vars.yml, Semaphore --tags=containerd)
    end
```

### 2.2 Logs and storage: app → Longhorn → NAS disk

Every Longhorn volume has **one replica, on nas-server** ([ADR 0002](adr/0002-keep-longhorn-data-on-a-storage-only-nas-node.md)).
Apps write log files to their own PVC as well as stdout ([ADR 0005](adr/0005-apps-write-log-files-to-per-app-longhorn-volumes.md)).

```mermaid
flowchart LR
    canary["infra-canary<br/>/app/logs/canary"]
    cfg["platform-config-server<br/>/var/log/platform"]
    disc["platform-discovery-server<br/>/var/log/platform"]
    pv1[("infra-canary-logs<br/>1Gi RWO")]
    pv2[("platform-config-server-logs<br/>5Gi RWO")]
    pv3[("platform-discovery-server-logs<br/>5Gi RWO")]
    eng["Longhorn engine<br/>on the pod's node"]
    rep[("replicas on nas-server<br/>/var/lib/longhorn, 196 GiB disk")]
    nfs[("NFS export on nas-server<br/>StorageClass nfs (RWX)")]
    bkp[("backup target<br/>none configured")]

    canary -- "logback, rotate 5MB x3" --> pv1
    cfg --> pv2
    disc --> pv3
    pv1 & pv2 & pv3 --> eng
    eng -- "replication over LAN" --> rep
    rep -. "no backups yet (open item)" .-> bkp
    rep --- nfs
```

### 2.3 Provisioning: Semaphore

All node and VM changes go through Semaphore playbooks from this repo
([ADR 0003](adr/0003-provision-through-semaphore-with-pinned-kubespray-and-ansible-core.md)).

```mermaid
flowchart TB
    repo["GitHub ai-labs<br/>mcp-agents/tool-box/semaphore/playbooks-library"]
    subgraph SEM["Semaphore 192.168.1.42 (project 2)"]
        t1["Kubespray: deploy / converge"]
        t2["Kubespray: add workers"]
        t3["harbor (template 18)"]
        inv1["inventory proxmox01"]
        inv2["inventory registry"]
        kh["known_hosts<br/>fingerprints verified"]
    end
    cluster["control planes, workers, nas-server"]
    hv["harbor-server 192.168.1.173<br/>Docker CE, Harbor online installer (sha256),<br/>sdb mounted at /data"]

    repo --> t1 & t2 & t3
    t1 & t2 --> inv1 -- "SSH vishwas, ansible-core 2.14.11" --> cluster
    t3 --> inv2 -- "SSH vishwas" --> hv
    kh -. "stale RSA keys for kube nodes (open item)" .- inv1
```

Task CLI args are **appended** to the template's: enter only `--limit=…` / `--tags=…`.

### 2.4 Observability

In-cluster Prometheus forwards everything to the central one
([ADR 0004](adr/0004-forward-cluster-metrics-to-a-central-prometheus.md)).

```mermaid
flowchart LR
    subgraph K8S["Kubernetes proxmox01, namespace monitoring"]
        ne["node-exporter<br/>DaemonSet, every node"]
        ksm["kube-state-metrics"]
        kp["Prometheus (operator)<br/>1 replica, 1 day<br/>external label cluster=proxmox01"]
        lhm["longhorn-manager :9500"]
        app["infra-canary<br/>/actuator/prometheus"]
    end
    subgraph OBS["Observability VM 192.168.1.137"]
        prom[("Prometheus :9090<br/>30 days, remote-write receiver")]
        graf["Grafana :3000<br/>folder Kubernetes"]
        otel["OTel Collector :4318"]
    end
    ne & ksm --> kp
    kp -- "remote_write /api/v1/write" --> prom
    lhm -. "blocked by Longhorn network policy<br/>(fix in values.yaml, not applied)" .-> kp
    app -. "not scraped: no pod discovery job" .-> kp
    otel --> prom
    graf -- "queries" --> prom
```

## 3. Deployment

Which machine hosts what.

```mermaid
flowchart TB
    subgraph PVE["Proxmox VE  192.168.1.30 / .40"]
        subgraph CLUSTER["Kubernetes proxmox01  v1.27.5, containerd 1.7.5, Calico, MetalLB .230-.250"]
            cp["control-panel-001/002/003<br/>.20 .21 .51<br/>control plane + etcd, also run pods"]
            wk["kube-node-001/002/003<br/>.104 .198 .142"]
            nas["nas-server .13<br/>storage-only (taint), Longhorn disk 196 GiB<br/>+ NFS server outside k8s"]
        end
        sem["semaphore .42"]
        inst["installer .44"]
        harbor["docker-registry-harbor .173<br/>Harbor v2.15.5, /data on 60G sdb"]
        obs["observability .137"]
        kc["keycloak .143"]
        ai["ai-hub .122"]
    end
    mcp{{"mcp-vm-worker-agent :8765<br/>on .42 .44 .13 .137 .122 .143"}}
    mcp -.- sem & inst & nas & obs & kc & ai
```

## Systems

| System | Address | Role | Managed by |
|---|---|---|---|
| GitHub `ai-labs` | github.com/vishwasraiborde19/ai-labs | source of truth; Semaphore and Jenkins pull from it | — |
| Semaphore UI 2.19 | http://192.168.1.42:3000 | runs Ansible: kubespray, Harbor; PostgreSQL inside | [semaphore-installer](../mcp-agents/tool-box/semaphore/semaphore-installer/), [admin runbook](../mcp-agents/tool-box/semaphore/admin/runbooks.md) |
| Installer VM | 192.168.1.44 | kubectl/helm to the cluster, Jib builds (no Docker) | manual |
| Jenkins | **unverified** (not in catalog) | builds infra-canary, pushes to Docker Hub | [Jenkinsfile](../infra-canary/Jenkinsfile) |
| Docker Hub | `vishwasraiborde19/infra-canary-img` | image the cluster runs for infra-canary today | Jenkins |
| Harbor v2.15.5 + Trivy | http://192.168.1.173 (HTTP) | private registry; project `infracanary` | [registry playbook](../mcp-agents/tool-box/semaphore/playbooks-library/registry/), [runbook](../mcp-agents/tool-box/harbour/runbook.md) |
| Control plane | 192.168.1.20, .21, .51 | apiserver :6443, etcd; schedulable | [kubespray inventory](../mcp-agents/tool-box/semaphore/playbooks-library/kubernetes/) |
| Workers | 192.168.1.104, .198, .142 | workloads | kubespray |
| nas-server | 192.168.1.13 | Longhorn storage-only node + NFS server | [nfs-installer](../mcp-agents/tool-box/nfs/nfs-installer/), [longhorn](../mcp-agents/tool-box/kube/longhorn/) |
| Longhorn 1.12.2 UI | http://192.168.1.240 | volumes, replicas, snapshots | [longhorn](../mcp-agents/tool-box/kube/longhorn/) |
| cluster-monitoring | namespace `monitoring` | kube-prometheus-stack 93.3.0 → central Prometheus | [cluster-monitoring](../mcp-agents/tool-box/kube/cluster-monitoring/) |
| platform-config-server | LB 192.168.1.231:9001 | Spring Cloud Config (ns `platform`) | platform-cloud2 repo |
| platform-discovery-server | LB 192.168.1.232:8761 | Eureka (ns `platform`) | platform-cloud2 repo |
| nginx (test) | LB 192.168.1.230 | sample deployment in `default` | — |
| infra-canary | ClusterIP; console LB 192.168.1.241 **reserved, not applied** | delivery/storage canary + chaos console | [infra-canary](../infra-canary/) |
| Observability | 192.168.1.137 | Prometheus :9090, Grafana :3000, OTel :4318 | [grafana-installer](../mcp-agents/tool-box/observability-monitoring/grafana-installer/) |
| Keycloak | https://192.168.1.143:8443 | SSO realm `lab` | [keycloak-installer](../mcp-agents/tool-box/keycloak/keycloak-installer/) |
| ai-hub | 192.168.1.122 | AI tooling VM | mcp-vm-worker-agent |
| MCP worker agents | `http://<vm>:8765/mcp` on .42 .44 .13 .137 .122 .143 | Claude Code runs commands/files/jobs on VMs | [mcp-vm-worker-agent](../mcp-agents/vm/mcp-vm-worker-agent/) |
| Proxmox VE | https://192.168.1.30:8006, https://192.168.1.40:8006 | hypervisors for every VM | manual |
| Old Harbor | 192.168.1.34 | **Retired** 2026-11-14 (VM broke); replaced by .173 | — |

MetalLB pool `192.168.1.230–250`: in use .230 .231 .232 .240; .241 reserved for the canary console.

## Decisions

| ADR | Decision |
|---|---|
| [0001](adr/0001-run-harbor-over-plain-http-on-a-dedicated-vm.md) | Run Harbor over plain HTTP on a dedicated VM |
| [0002](adr/0002-keep-longhorn-data-on-a-storage-only-nas-node.md) | Keep Longhorn data on a storage-only NAS node |
| [0003](adr/0003-provision-through-semaphore-with-pinned-kubespray-and-ansible-core.md) | Provision through Semaphore with pinned kubespray and ansible-core |
| [0004](adr/0004-forward-cluster-metrics-to-a-central-prometheus.md) | Forward cluster metrics to a central Prometheus |
| [0005](adr/0005-apps-write-log-files-to-per-app-longhorn-volumes.md) | Apps write log files to per-app Longhorn volumes |
| [0006](adr/0006-build-fault-injection-into-infra-canary-off-by-default.md) | Build fault injection into infra-canary, off by default |

## Open items

| Item | Effect until done | How |
|---|---|---|
| containerd HTTP mirror for Harbor on all nodes | cluster can't pull from Harbor (`:443 connection refused`) | [design](designs/2026-11-cluster-pulls-from-harbor.md); Semaphore *Kubespray: deploy / converge* `--tags=containerd` |
| Choose the canonical image path (Harbor vs Jenkins → Docker Hub) | two registries, two build paths, the deployment manifest follows whichever was applied last | decide, record as ADR 0007, align `infra-canary/k8s/30-deployment.yaml` and the Jenkinsfile |
| No Longhorn backup target; 1 replica per volume | losing nas-server's disk loses every volume | set a backup target off the NAS (see [ADR 0002](adr/0002-keep-longhorn-data-on-a-storage-only-nas-node.md)) |
| Deploy infra-canary 0.2.0 + console Service (.241) | chaos console not reachable | build/push image, `kubectl apply -f infra-canary/k8s/` |
| Longhorn `networkPolicies.metricsScrapeSources` | no Longhorn metrics in Grafana | `install-longhorn.sh` (helm upgrade) on the installer VM |
| App metrics scrape (`/actuator/prometheus`) | canary/platform metrics not in Prometheus | PodMonitor/ServiceMonitor for kube-prometheus-stack |
| Stale SSH host keys for kube nodes (Semaphore, installer) | SSH to .104 refused | verify fingerprints out of band, then replace |
| Rotate Harbor robot secrets shared in chat | exposure | Harbor → robot → *Refresh secret*; update `harbor-pull` Secrets |
| Jenkins address not recorded | can't link or monitor it | add to `command-center` seed catalog and this table |
| Keycloak SSO for Semaphore and Grafana | separate logins | planned |
| Kubernetes 1.27 is past end of life | no security patches; upgrade gets harder | plan kubespray upgrade path (design doc) |
