# 0005. Apps write log files to per-app Longhorn volumes

- **Status:** Accepted
- **Date:** 2026-11-13
- **Related:** [ADR 0002](0002-keep-longhorn-data-on-a-storage-only-nas-node.md), [infra-canary](../../infra-canary/), platform-cloud2 `.kube/04|05-*`

## Context

Container logs on nodes rotate away quickly and disappear with the pod or node. There is no central log
store in use yet (Elasticsearch/Kibana installer exists but isn't wired). Developers want to read an
app's recent log files after restarts.

## Decision

Each app that needs durable logs gets its own Longhorn PVC (5Gi for platform-config-server and
platform-discovery-server, 1Gi for infra-canary) mounted at its log directory, and Spring Boot writes a
rotated log file there (`logging.file.name` + logback rolling policy) **in addition to** stdout. Pods run
non-root with `fsGroup` so the volume is writable. Deployments using these volumes use
`strategy: Recreate`.

## Alternatives considered

| Option | Why not (now) |
|---|---|
| stdout only | logs lost on pod/node loss; no history across restarts |
| Central log pipeline (OTel → Elasticsearch) | not yet in place; the right long-term answer |
| One shared NFS log volume | apps could overwrite each other; RWX locking |

## Consequences

- **Good:** logs survive restarts and rescheduling; easy to inspect with `kubectl exec … tail`.
- **Bad:** RWO volumes pin each app to one replica and Recreate rollouts (brief downtime).
- **Bad:** inherits ADR 0002's single point of failure; logs are not searchable across apps.
- **Follow-ups:** when a central log store is wired up, ship logs there and drop the PVCs for apps that
  need more than one replica.
