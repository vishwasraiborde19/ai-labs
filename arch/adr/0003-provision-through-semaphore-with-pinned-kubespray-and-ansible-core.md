# 0003. Provision through Semaphore with pinned kubespray and ansible-core

- **Status:** Accepted
- **Date:** 2026-11-13
- **Related:** [playbooks-library](../../mcp-agents/tool-box/semaphore/playbooks-library/), [kubespray.env](../../mcp-agents/tool-box/semaphore/playbooks-library/kubernetes/inventories/proxmox01/kubespray.env), [Semaphore admin runbook](../../mcp-agents/tool-box/semaphore/admin/runbooks.md)

## Context

Cluster and VM changes were being made from different machines with different tool versions. The
cluster runs kubespray v2.23.0 (Kubernetes v1.27.5). Adding nas-server failed with "Conditional is
marked as unsafe": tested on the Semaphore VM, ansible-core 2.14.12 and later fail with kubespray v2.23.0,
2.14.11 works.

## Decision

All node and VM changes run as Semaphore templates (192.168.1.42) using playbooks from this repo:
kubespray deploy/converge, add workers, Harbor. kubespray is pinned to v2.23.0 and runs with its own
ansible-core **2.14.11** (`ANSIBLE_CORE_VERSION` in `kubespray.env`). Inventories live in the repo
(`proxmox01`, `registry`). Task CLI args carry only `--limit` / `--tags`, because Semaphore appends them to
the template's arguments.

## Alternatives considered

| Option | Why not |
|---|---|
| Run ansible/kubespray from laptops | unrepeatable versions and keys; no history of who ran what |
| Latest ansible-core | breaks kubespray v2.23.0 |
| Upgrade kubespray first | a cluster upgrade with its own risks; separate decision |

## Consequences

- **Good:** one place with logs of every change; versions reproducible; anyone can rerun a template.
- **Bad:** Semaphore's own state (templates, inventories, keys) lives in its database — edits made there
  must be backed up first and mirrored in the repo.
- **Bad:** hand edits on nodes are overwritten by the next kubespray run unless the repo matches.
- **Follow-ups:** kubespray/Kubernetes upgrade path (1.27 is past end of life) will need a newer
  ansible-core pin — a new ADR when it happens.
