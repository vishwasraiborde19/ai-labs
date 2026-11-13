# mcp-kube-agent

An MCP server in Java (Spring Boot 3.5, Spring AI 1.1) that lets Claude manage Kubernetes clusters
through a **service account**: inspect anything, troubleshoot, deploy and roll back, scale, and look
after nodes. It talks straight to the Kubernetes API (no kubectl needed) and works on any resource
type the cluster serves, CRDs included.

The agent never uses admin credentials. You create the `kube-agent` service account once, with an
admin kubeconfig, and the agent runs on that account's token. Two limits apply on top of each other:
- **RBAC** on the service account, which the cluster enforces;
- the **agent policy** in its config: read-only clusters, allowed namespaces, protected namespaces
  and cluster-scoped writes.

## 1. Create the service account

With an admin kubeconfig on a machine that has `kubectl`:

```bash
scripts/setup-service-account.sh                 # read everywhere + edit workloads + node ops
MODE=read scripts/setup-service-account.sh       # read-only
```

The script applies [deploy/rbac-read.yaml](deploy/rbac-read.yaml), plus
[deploy/rbac-operate.yaml](deploy/rbac-operate.yaml) unless `MODE=read`. It then writes three files
to `~/.kube-agent/`: `<cluster>.token` (mode 600), `<cluster>-ca.pem` and a first `config.yml`.

| Manifest | Grants |
|---|---|
| `rbac-read.yaml` | namespace `kube-agent`, service account, long-lived token Secret, built-in `view` (namespaced reads, not Secrets) and `kube-agent-cluster-read` (nodes, namespaces, PVs, storage classes, CRDs, RBAC objects, metrics) |
| `rbac-operate.yaml` | built-in `edit` in every namespace (workloads, services, config maps, Secrets, PVCs, ...; no RBAC), and `kube-agent-node-ops` (cordon, uncordon, evict for drain) |

To revoke the token, delete it: `kubectl -n kube-agent delete secret kube-agent-token`. Re-applying
`rbac-read.yaml` issues a new token. To limit edits to some namespaces, replace the
`kube-agent-edit` ClusterRoleBinding with RoleBindings, as the comment in `rbac-operate.yaml`
shows.

## 2. Build

Needs JDK 21 and Maven 3.6.3+.

```bash
mvn -q package            # runs the unit tests, produces target/mcp-kube-agent.jar
```

## 3. Configure

`~/.kube-agent/config.yml` (override with `KUBE_AGENT_CONFIG`); see
[config/config.example.yml](config/config.example.yml):

```yaml
kube:
  clusters:
    - name: proxmox01
      api-server: https://192.168.1.20:6443
      token-ref: path:~/.kube-agent/proxmox01.token
      ca-cert-ref: path:~/.kube-agent/proxmox01-ca.pem
      read-only: false
      allowed-namespaces: []                     # empty = all
      protected-namespaces: [kube-system, kube-public, kube-node-lease]
      cluster-scoped-writes: false               # nodes, namespaces, CRDs, RBAC
```

| Setting | Effect |
|---|---|
| `read-only` | every change is refused |
| `allowed-namespaces` | the agent reads and changes only these (empty: all) |
| `protected-namespaces` | readable, never changed (default `kube-system`, `kube-public`, `kube-node-lease`) |
| `cluster-scoped-writes` | needed to cordon or drain nodes, change namespaces, CRDs, storage classes, RBAC |

Secret references: `path:<file>`, `env:<NAME>`, `file:<key>` (key in `~/.kube-agent/secrets.yml`).
Tokens are re-read after `kube.access.cache-ttl` (5m), so a rotated token is picked up without a
restart.

## 4. Connect Claude Code

stdio, on a machine with Java:

```bash
claude mcp add kube -- java -jar $PWD/target/mcp-kube-agent.jar
```

Streamable HTTP, for a shared server (a bearer token is required):

```bash
export KUBE_AGENT_TOKEN=$(openssl rand -base64 32)
KUBE_AGENT_HOST=0.0.0.0 java -jar target/mcp-kube-agent.jar --spring.profiles.active=http   # :8767/mcp
claude mcp add --transport http kube http://HOST:8767/mcp --header "Authorization: Bearer $KUBE_AGENT_TOKEN"
```

`/healthz` answers without a token, for probes. Put TLS in front of the agent when its traffic
crosses an untrusted network.

To run the agent in the cluster it manages, use [Dockerfile](Dockerfile) and
[deploy/in-cluster.yaml](deploy/in-cluster.yaml). The agent then uses the pod's own service-account
token, with no token file.

## Tools

| Area | Tools |
|---|---|
| Cluster | `k8s_clusters`, `k8s_whoami`, `k8s_can_i`, `k8s_api_resources` |
| Any resource | `k8s_list`, `k8s_get`, `k8s_describe`, `k8s_apply`, `k8s_patch`, `k8s_delete` |
| Workloads | `k8s_scale`, `k8s_rollout_restart`, `k8s_rollout_status`, `k8s_set_image`, `k8s_rollout_history`, `k8s_rollout_undo` |
| Troubleshooting | `k8s_troubleshoot`, `k8s_events`, `k8s_logs` |
| Nodes | `k8s_nodes`, `k8s_top`, `k8s_cordon`, `k8s_drain` |

- **Kinds**: accepted the way kubectl accepts them: `pod`, `deploy`, `svc`, `ingresses.networking.k8s.io`,
  CRD kinds.
- **`k8s_apply`**: server-side apply with field manager `mcp-kube-agent`. It takes several
  documents and checks policy for all of them before changing any. `dryRun=true` validates on the
  server.
- **`k8s_delete` and `k8s_drain`**: `confirm` must repeat the object or node name.
  `k8s_drain dryRun=true` shows what would be evicted and what blocks the drain.
- **`k8s_troubleshoot`**: reports NotReady nodes, crashing, pending or restarting pods (with hints),
  workloads short of replicas, unbound PVCs, and grouped warning events.
- **Not supported**: `exec`, `port-forward` and `attach`. They need streaming connections.

## Safety

- Secret values never reach the model. `k8s_get` and `k8s_describe` show only key names and sizes,
  and `managedFields` and the last-applied annotation are dropped. Any token the agent has resolved
  is masked as `****` in all output, including pod logs.
- The agent policy above is checked before every change. `k8s_can_i` reports both the RBAC answer
  and the policy answer.
- Logs go to `~/.kube-agent/logs/` (`KUBE_AGENT_LOG_DIR`). `audit.log` records every tool call with
  its arguments (redacted), its duration and its outcome.
