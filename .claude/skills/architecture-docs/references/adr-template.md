# ADR template

File: `arch/adr/NNNN-<decision-in-kebab-case>.md`. Copy from the line below.

---

# NNNN. <The decision, as a short statement>

- **Status:** Proposed | Accepted | Deprecated | Superseded by [NNNN](NNNN-....md)
- **Date:** YYYY-MM-DD
- **Deciders:** <who agreed>
- **Related:** <links to design doc, runbook, PR/commit, other ADRs>

## Context

What forces are at play: the problem, constraints (hardware, time, skills, versions), what happened that
made this necessary, relevant numbers. Neutral — someone who disagrees should recognise it as fair.

## Decision

What we do, in active voice ("We run Harbor on a dedicated VM, 192.168.1.173, over plain HTTP …").
Specific enough to check whether the system follows it.

## Alternatives considered

| Option | Why not (or why not now) |
|---|---|
| … | … |

## Consequences

- **Good:** what gets easier.
- **Bad:** what gets harder or riskier — and the mitigation for each.
- **Follow-ups:** work this creates (link open items).

---

## Example (abridged)

# 0001. Run Harbor over plain HTTP on a dedicated VM

- **Status:** Accepted
- **Date:** 2026-11-14
- **Related:** `mcp-agents/tool-box/semaphore/playbooks-library/registry/harbor.yml`, `mcp-agents/tool-box/harbour/runbook.md`

## Context
The previous Harbor (192.168.1.34) broke with its VM. The lab has no internal CA, so TLS would mean
self-signed certificates distributed to every Docker client and containerd node. Rebuilds must be
repeatable from the repo.

## Decision
Harbor v2.15.5 runs on its own VM (192.168.1.173), installed by the Semaphore `harbor` playbook, over
plain HTTP on port 80, reachable only on the lab LAN. Clients list it as an insecure registry; nodes get
an `http://` containerd mirror via kubespray.

## Alternatives considered
| Option | Why not |
|---|---|
| Self-signed TLS | CA distribution to every client and node; more moving parts than the lab needs today |
| Harbor inside Kubernetes | registry outage would block the cluster's own recovery |
| Plain `registry:2` | no UI, robots, projects or Trivy scanning |

## Consequences
- **Good:** one playbook rebuilds it; no certificates to rotate.
- **Bad:** credentials cross the LAN unencrypted → robots are per-project, push/pull only, rotated; Jib
  needs `-DsendCredentialsOverHttp=true`; every client needs the insecure-registry setting.
- **Follow-ups:** revisit with an internal CA (e.g. step-ca) when Keycloak/Grafana TLS is unified.
