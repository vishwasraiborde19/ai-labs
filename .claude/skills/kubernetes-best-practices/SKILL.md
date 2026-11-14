---
name: kubernetes-best-practices
description: Kubernetes best practices for the ai-labs proxmox01 cluster (kubespray v2.23 / k8s 1.27, containerd, Calico, MetalLB, Longhorn on nas-server, Harbor over HTTP). Use this whenever writing, reviewing or changing Kubernetes manifests, Helm values, Deployments, Services, PVCs, probes, ConfigMaps/Secrets, NetworkPolicies or RBAC, deploying an app to the cluster, exposing something on a LoadBalancer IP, or debugging pods that won't pull, start, become Ready or keep restarting — even if the user just says "deploy this to kube" or "why is my pod not starting".
---

# Kubernetes best practices (ai-labs)

Goal: workloads that start reliably, survive node and pod failure, restart cleanly, can't hurt their
neighbours, and can be debugged from their own signals. Apply the checklist when writing manifests; use it
as review criteria when reviewing them.

## The cluster you are targeting

Know these before writing anything; most "it doesn't work" in this lab is one of them.

| Thing | Value / consequence |
|---|---|
| Version | k8s **v1.27.5** via kubespray v2.23.0 — use APIs valid for 1.27 (`policy/v1` PDB, `autoscaling/v2` HPA; no `ValidatingAdmissionPolicy` GA) |
| Nodes | control planes .20 .21 .51 (also schedulable), workers kube-node-001/002/003 (.104 .198 .142), `nas-server` (.13) tainted `node-role.kubernetes.io/storage=true:NoSchedule` — don't tolerate that taint for apps |
| Storage | `longhorn` = default StorageClass, data on nas-server; `nfs` StorageClass for RWX. Longhorn volumes are **ReadWriteOnce** |
| Registry | Harbor `192.168.1.173`, **plain HTTP**. Nodes need the containerd mirror (`containerd_registries_mirrors` in `kubespray-vars.yml`) or pulls fail with `dial tcp 192.168.1.173:443: connect: connection refused`. Private projects need an `imagePullSecrets` entry (`harbor-pull`) |
| LoadBalancer | MetalLB pool `192.168.1.230-250`. Pin with annotation `metallb.universe.tf/loadBalancerIPs: <ip>`; check what's taken first: `kubectl get svc -A \| grep LoadBalancer` |
| Monitoring | cluster-monitoring remote-writes to Prometheus on .137; it does **not** discover pods by annotation yet |
| Changes to nodes | through Semaphore + kubespray (`--tags=...`, `--limit=...` in CLI args only), never hand-edits that kubespray will overwrite — or, if you must hand-edit, make the repo match |

## Workload checklist

Each item has a reason; if you deviate, say why in a comment in the manifest.

1. **Image pinned.** A version tag (`:0.2.0`) or digest — never `latest` in a Deployment: a rollback or
   reschedule could silently run different code. `imagePullPolicy: IfNotPresent` for immutable tags.
2. **Requests and limits.** CPU + memory requests (scheduling, eviction order) and a memory limit (OOM blast
   radius). CPU limits are optional — they cause throttling; prefer requests. Size the JVM to the limit:
   `-XX:MaxRAMPercentage=75` leaves room for metaspace, threads and direct buffers.
3. **Probes that mean different things.**
   - *readiness* = "can I serve now?" May check critical dependencies. Failing removes the pod from Service endpoints.
   - *liveness* = "am I wedged beyond recovery?" Must **not** check dependencies (a DB outage would restart every pod and make it worse). Spring Boot: `/actuator/health/liveness` and `/readiness` with `management.endpoint.health.probes.enabled=true`.
   - *startup* probe for slow starters instead of a huge `initialDelaySeconds` on liveness.
   - Write out `periodSeconds`, `timeoutSeconds`, `failureThreshold` so behaviour is visible in review.
4. **Security context (restricted Pod Security Standard).**
   ```yaml
   securityContext:            # pod
     runAsNonRoot: true
     runAsUser: 10001
     fsGroup: 10001            # makes Longhorn/NFS volumes writable for that group
     seccompProfile: {type: RuntimeDefault}
   containers:
   - securityContext:
       allowPrivilegeEscalation: false
       readOnlyRootFilesystem: true      # mount emptyDir for /tmp and any write path
       capabilities: {drop: [ALL]}
   ```
5. **Storage matches the access mode.** RWO PVC + more than one replica or `RollingUpdate` = stuck rollout
   (new pod can't attach). Use `strategy: Recreate` for single-replica RWO, a StatefulSet with
   `volumeClaimTemplates` for per-replica storage, or the `nfs` class for shared RWX. Logs that must outlive
   the pod go on a PVC; everything else to stdout.
6. **Graceful shutdown.** `server.shutdown=graceful` (Spring) + `terminationGracePeriodSeconds` longer than
   the drain; optional `preStop: sleep 5` so endpoints update before the app stops accepting.
7. **More than one replica for anything users depend on**, with a `PodDisruptionBudget`
   (`minAvailable: 1`) and `topologySpreadConstraints` on `kubernetes.io/hostname` so a node drain or
   failure doesn't take all replicas.
8. **Config vs secrets.** Non-secret settings in a ConfigMap; credentials in a Secret referenced by
   `secretKeyRef`/`envFrom`. Never put secret values in a ConfigMap, the repo, or `kubectl` command lines
   that land in shell history (use `--from-file` or stdin). ConfigMap changes need a pod restart
   (`kubectl rollout restart`) unless mounted as files and reloaded.
9. **Service account.** A dedicated one only if the app calls the API (with a minimal Role);
   otherwise `automountServiceAccountToken: false`.
10. **Labels.** At least `app.kubernetes.io/name`, `app.kubernetes.io/version`, `app.kubernetes.io/part-of`;
    selectors use a stable subset (selectors are immutable).
11. **Namespaced.** One namespace per app/team; `ResourceQuota` + `LimitRange` for shared namespaces.
12. **Network.** Start from default-deny `NetworkPolicy` in the namespace and allow what is needed
    (ingress from the Service's clients, egress to DNS and dependencies). Calico enforces it.

A complete Deployment/Service/PDB that satisfies all of this: `references/golden-deployment.yaml`.
Read it when writing a new app's manifests.

## Exposing things

- ClusterIP by default. LoadBalancer only for things people open from the LAN; pin the IP and record it
  in `arch/README.md`.
- A Service used for operator/console access during failure tests can set `publishNotReadyAddresses: true`
  (example: `infra-canary/k8s/41-console-service.yaml`); a normal traffic Service never should.

## Before you apply

```bash
kubectl apply --dry-run=server -f k8s/          # schema + admission, without changing anything
kubectl diff -f k8s/                            # what will actually change
kubectl apply -f k8s/ && kubectl -n <ns> rollout status deploy/<name> --timeout=180s
```

Applying to the cluster changes shared state: say what you will apply and confirm with the user first
unless they already asked for it.

## Debugging order

When a pod misbehaves, go from outside in — each step rules out a layer:

1. `kubectl -n <ns> get pod -o wide` — phase, restarts, node.
2. `kubectl -n <ns> describe pod <pod>` — **Events** at the bottom: scheduling, pull, mount, probe failures.
3. `kubectl -n <ns> logs <pod> [--previous]` — app output; `--previous` after a crash/restart.
4. `kubectl -n <ns> get events --sort-by=.lastTimestamp | tail`.
5. Only then exec in or look at the node.

| Symptom | Usual cause here |
|---|---|
| `ErrImagePull … :443 connection refused` | containerd on that node has no HTTP mirror for Harbor |
| `ErrImagePull … unauthorized` | missing/wrong `harbor-pull` secret or robot lacks pull |
| `Pending`, `didn't tolerate taint storage=true` | fine — it's avoiding nas-server; check other nodes' resources |
| `ContainerCreating`, `Multi-Attach error` | RWO volume still attached to the old pod → `strategy: Recreate` |
| `CrashLoopBackOff`, exit 137 / `OOMKilled` | memory limit too low or heap not sized to it |
| `Running` but `0/1` Ready | readiness failing — `curl` the readiness path from inside the pod |
| Liveness restarts under load | probe timeout too short or liveness checks a dependency |

## Review output

When reviewing manifests, report findings grouped by severity — **must fix** (will break or is unsafe),
**should fix** (resilience/operability), **nit** — each with the file, the line or field, why it
matters, and the corrected YAML snippet.
