# 0004. Forward cluster metrics to a central Prometheus

- **Status:** Accepted
- **Date:** 2026-11-13
- **Related:** [cluster-monitoring](../../mcp-agents/tool-box/kube/cluster-monitoring/), [grafana-installer](../../mcp-agents/tool-box/observability-monitoring/grafana-installer/)

## Context

The lab has a central observability VM (192.168.1.137: Prometheus with remote-write receiver and OTLP,
Grafana). Clusters, VMs and apps should be watched from one Grafana, and the cluster may be rebuilt.

## Decision

Each cluster runs kube-prometheus-stack (chart 93.3.0) in namespace `monitoring` with node-exporter,
kube-state-metrics and a small Prometheus (1 replica, 1 day retention) that **remote-writes everything to
`http://192.168.1.137:9090/api/v1/write`** with external label `cluster=proxmox01`. The chart's own
Grafana and Alertmanager are disabled; dashboards are provisioned in the central Grafana.

## Alternatives considered

| Option | Why not |
|---|---|
| Central Prometheus scrapes the cluster directly | needs node/pod network reachability and per-target config from outside |
| Full monitoring stack per cluster | one Grafana per cluster; history lost on rebuild |
| Managed SaaS | data leaves the lab |

## Consequences

- **Good:** 30 days of history survive cluster rebuilds; one Grafana for everything; adding a cluster
  means one install with a new `CLUSTER_NAME`.
- **Bad:** if the observability VM is down, in-cluster Prometheus buffers only briefly — gaps appear.
- **Bad:** pod-level app metrics need explicit ServiceMonitors/PodMonitors; annotations alone are not
  scraped (open item). Longhorn's network policy blocks its metrics until `metricsScrapeSources` is applied.
