# Architecture Decision Records

Decisions that are costly to reverse, non-obvious, or likely to be questioned. How to write one:
`.claude/skills/architecture-docs/references/adr-template.md`. Never renumber; supersede instead of
rewriting an accepted decision.

| # | Decision | Status | Date |
|---|---|---|---|
| [0001](0001-run-harbor-over-plain-http-on-a-dedicated-vm.md) | Run Harbor over plain HTTP on a dedicated VM | Accepted | 2026-11-14 |
| [0002](0002-keep-longhorn-data-on-a-storage-only-nas-node.md) | Keep Longhorn data on a storage-only NAS node | Accepted | 2026-11-13 |
| [0003](0003-provision-through-semaphore-with-pinned-kubespray-and-ansible-core.md) | Provision through Semaphore with pinned kubespray and ansible-core | Accepted | 2026-11-13 |
| [0004](0004-forward-cluster-metrics-to-a-central-prometheus.md) | Forward cluster metrics to a central Prometheus | Accepted | 2026-11-13 |
| [0005](0005-apps-write-log-files-to-per-app-longhorn-volumes.md) | Apps write log files to per-app Longhorn volumes | Accepted | 2026-11-13 |
| [0006](0006-build-fault-injection-into-infra-canary-off-by-default.md) | Build fault injection into infra-canary, off by default | Accepted | 2026-11-14 |

Pending: 0007 — canonical image path (Harbor vs Jenkins → Docker Hub), see open items in [arch/README.md](../README.md#open-items).
