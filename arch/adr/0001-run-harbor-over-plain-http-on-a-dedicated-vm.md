# 0001. Run Harbor over plain HTTP on a dedicated VM

- **Status:** Accepted
- **Date:** 2026-11-14
- **Related:** [registry playbook](../../mcp-agents/tool-box/semaphore/playbooks-library/registry/harbor.yml), [Harbor runbook](../../mcp-agents/tool-box/harbour/runbook.md), [design: cluster pulls from Harbor](../designs/2026-11-cluster-pulls-from-harbor.md)

## Context

The first Harbor (192.168.1.34, v2.12.1) was installed by hand. Its configured hostname was a stale
address (192.168.0.90), so every login was redirected to an unreachable token realm, and the VM then
broke on a corrupt dpkg state. Nothing in the repo could rebuild it. The lab has no internal certificate
authority: TLS would mean self-signed certificates distributed to every Docker client, Jib build and
containerd node.

## Decision

Harbor v2.15.5 with Trivy runs on its own VM, `docker-registry-harbor` (192.168.1.173), installed and
re-installed only by the Semaphore `harbor` template (playbook `registry/harbor.yml`): pinned version,
sha256-checked installer, passwords generated on the VM, data on a dedicated 60G disk at `/data`. It
serves **plain HTTP on port 80** to the lab LAN only. Clients mark it insecure (Docker
`insecure-registries`, Jib `allowInsecureRegistries` + `-DsendCredentialsOverHttp=true`), and cluster nodes
get an `http://` containerd mirror through kubespray.

## Alternatives considered

| Option | Why not (now) |
|---|---|
| Self-signed TLS | CA distribution to every client and node, renewals; more moving parts than the lab needs today |
| Harbor inside Kubernetes | the registry would depend on the cluster it serves; a cluster outage would block its own recovery |
| Plain `registry:2` | no UI, projects, robot accounts or vulnerability scanning |
| Docker Hub only | public by default, pull rate limits, nothing stays inside the lab |

## Consequences

- **Good:** one playbook rebuilds Harbor in minutes; data survives a reinstall on its own disk; no
  certificates to rotate.
- **Bad:** credentials cross the LAN unencrypted → per-project robot accounts with push/pull only, rotated
  when exposed. Every client needs the insecure-registry setting; without it errors read
  `connection refused :443`.
- **Bad:** Harbor builds the token realm from the request's Host header — clients must use exactly
  `192.168.1.173`.
- **Follow-ups:** containerd mirror on all nodes (open item); revisit TLS when an internal CA exists.
