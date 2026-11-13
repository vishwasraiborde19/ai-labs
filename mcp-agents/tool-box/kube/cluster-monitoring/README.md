# cluster-monitoring

Sends a Kubernetes cluster's metrics to the lab's central Prometheus, so the central Grafana is the one
place to watch every cluster, VM and app.

```
 every node                       in the cluster (namespace monitoring)            observability VM (.137)
 node-exporter (DaemonSet) ──┐
 kubelet / cAdvisor ─────────┼──▶ Prometheus ──remote_write──▶ Prometheus :9090 ◀── Grafana :3000
 kube-state-metrics ─────────┤    1 replica, 1 day, rules      30 days history     folder "Kubernetes"
 apiserver, CoreDNS ─────────┘    cluster=proxmox01 on every series
```

| Component | Version | Source |
|---|---|---|
| kube-prometheus-stack (chart) | 93.3.0 (`CHART_VERSION`) | `prometheus-community` Helm repo |
| Prometheus Operator / CRDs | 0.95.2 | in the chart |
| node-exporter | 1.12.1 | in the chart |
| kube-state-metrics | 2.20.0 | in the chart |

The chart's own Grafana and Alertmanager are off: the central Grafana is used instead.

## Install

From a machine where `kubectl` and `helm` point at the cluster (the installer VM):

```bash
cd mcp-agents/tool-box/kube/cluster-monitoring
./install-monitoring.sh --dry-run      # validate against the cluster, change nothing
./install-monitoring.sh                # install or upgrade (idempotent)
```

It applies the Prometheus Operator CRDs (server-side, so upgrades update them too), installs the
release, waits for node-exporter on every node, and checks that `up{cluster="<name>"}` arrives at the
central Prometheus.

| Variable | Default | |
|---|---|---|
| `CLUSTER_NAME` | `proxmox01` | the `cluster` label on every series; one per cluster |
| `REMOTE_WRITE_URL` | `http://192.168.1.137:9090/api/v1/write` | central Prometheus, started with `--web.enable-remote-write-receiver` |
| `NAMESPACE` / `RELEASE` | `monitoring` / `kube-monitoring` | |
| `CHART_VERSION` | `93.3.0` | bump to upgrade; the chart needs Kubernetes >= 1.25 |
| `KUBE_CONTEXT` | current context | to install into another cluster from the same machine |

Another cluster: `CLUSTER_NAME=<name> KUBE_CONTEXT=<ctx> ./install-monitoring.sh`. All clusters share
the dashboards; pick one with the *cluster* variable.

### Dashboards in the central Grafana

On the Grafana VM:

```bash
./grafana-dashboards.sh                 # data source uid "prometheus" (default)
DS_UID=lab-prometheus ./grafana-dashboards.sh   # Grafana set up by grafana-installer
```

It downloads these from grafana.com into `/var/lib/grafana/dashboards/kubernetes` and provisions them in
the folder **Kubernetes** (read-only in the UI; re-run to refresh):

| Dashboard | grafana.com | Shows |
|---|---|---|
| Kubernetes / Views / Global | 15757 | cluster CPU, memory, pods, namespaces at a glance |
| Kubernetes / Views / Namespaces | 15758 | per namespace: workloads, CPU / memory vs requests and limits |
| Kubernetes / Views / Nodes | 15759 | per node: pods, resources, conditions |
| Kubernetes / Views / Pods | 15760 | per pod / container: CPU, memory, restarts, network |
| Node Exporter Full | 1860 | per machine: everything node-exporter reports |
| Longhorn Monitoring & Backups | 16888 | Longhorn volumes, space per node and disk, backups (see [longhorn](../longhorn/)) |

Useful queries in *Explore*:

```promql
up{cluster="proxmox01"} == 0                                         # a target is down
sum by (node) (rate(node_cpu_seconds_total{cluster="proxmox01",mode!="idle"}[5m]))
kube_pod_container_status_restarts_total{cluster="proxmox01"} > 0
sum by (namespace) (container_memory_working_set_bytes{cluster="proxmox01",container!=""})
```

## What it sets up

* Namespace `monitoring`, Helm release `kube-monitoring`.
* **node-exporter** DaemonSet on every node, control plane included: host network, port `9100`.
* **kube-state-metrics**: state of deployments, pods, nodes, PVCs, jobs.
* **Prometheus** (one replica, prefers worker nodes): scrapes node-exporter, kubelet and cAdvisor,
  kube-state-metrics, the API servers and CoreDNS; evaluates the kube-prometheus recording and alerting
  rules; keeps 1 day on `emptyDir` (the cluster has no StorageClass) and remote-writes to the central
  Prometheus with `cluster=<name>`. A restart loses only samples not yet sent.
* **What is sent**: everything except histogram buckets and high-volume internals (`apiserver_*`,
  `etcd_*`, `workqueue_*`, `rest_client_*`, Go / process metrics, ...), see `writeRelabelConfigs` in
  [values.yaml](values.yaml). Recording-rule results (e.g. `node_namespace_pod_container:...`) are
  computed in the cluster and always sent, so the dashboards work. About 14,000 series for `proxmox01`.
* Alerting rules are evaluated (`ALERTS{cluster=...}` reaches the central Prometheus) but nothing is
  notified: there is no Alertmanager. Use Grafana alerting on the central Prometheus instead.
* ServiceMonitors / PodMonitors / PrometheusRules from **any namespace** are picked up, so an app adds
  its own scrape target with a `ServiceMonitor` next to its Deployment.

## Control-plane metrics (not enabled yet)

kubespray binds kube-proxy (`127.0.0.1:10249`), kube-scheduler and kube-controller-manager to localhost,
and etcd runs on the hosts with client TLS, so those scrapes are off. To add them:

1. In `semaphore/playbooks-library/kubernetes/inventories/proxmox01/kubespray-vars.yml`:
   ```yaml
   kube_proxy_metrics_bind_address: 0.0.0.0:10249
   kube_scheduler_bind_address: 0.0.0.0
   kube_controller_manager_bind_address: 0.0.0.0
   etcd_metrics_port: 2381          # plain-HTTP /metrics on each etcd member
   etcd_listen_metrics_urls: "http://0.0.0.0:2381"
   ```
   and run `kubespray.sh cluster` (rolling, restarts the control-plane components).
2. In `values.yaml`, set `enabled: true` for `kubeProxy`, `kubeScheduler`, `kubeControllerManager`
   and `kubeEtcd` (with `kubeEtcd.service.port/targetPort: 2381`), remove the matching `false` entries
   under `defaultRules.rules`, and re-run `install-monitoring.sh`.

## Operations

```bash
kubectl -n monitoring get pods -o wide
kubectl -n monitoring port-forward svc/kube-monitoring-kube-prome-prometheus 9090   # in-cluster UI: Status → Targets
kubectl -n monitoring logs prometheus-kube-monitoring-kube-prome-prometheus-0 -c prometheus | grep -i remote
# on the central Prometheus: samples arriving per cluster
curl -s localhost:9090/api/v1/query --data-urlencode 'query=count by (cluster) ({cluster!=""})'
./install-monitoring.sh --uninstall    # removes the release; CRDs stay:
kubectl get crd -o name | grep monitoring.coreos.com | xargs kubectl delete   # only if nothing else uses them
```

## Security

The central Prometheus has no authentication: anything that can reach `:9090` can read all metrics and
write samples. Restrict `:9090` on the observability VM to the cluster nodes and the lab admin
machines, e.g. with ufw:

```bash
sudo ufw allow from 192.168.1.0/24 to any port 9090 proto tcp   # or each node IP
```
