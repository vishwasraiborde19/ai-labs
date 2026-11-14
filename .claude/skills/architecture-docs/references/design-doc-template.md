# Design doc template

File: `arch/designs/YYYY-MM-<topic>.md`. Each section lists the questions it must answer; delete the
questions, keep the answers. Scale to the change: a small integration may need half a page.

---

# <Title>

- **Status:** Draft | In review | Agreed | Built | Abandoned
- **Author / date:** … / YYYY-MM-DD
- **Related:** ADRs, runbooks, issues

## 1. Summary
Two or three sentences: what we're building and why, and the expected outcome.

## 2. Context and problem
What exists today (link `arch/README.md`)? What's wrong or missing? Who is affected? What triggers this now?

## 3. Goals and non-goals
Goals: measurable where possible ("pods pull from Harbor on every node", "rebuild in < 30 min").
Non-goals: what we deliberately won't do, so scope doesn't creep.

## 4. Proposed design
- Context or container diagram (architecture-diagrams skill) showing new parts **dashed** until built.
- Components and responsibilities; where each runs (VM / namespace / IP).
- Interfaces: APIs, ports, protocols, data formats.
- Data: what is stored where, size, retention, backup.
- Key flows as sequence diagrams (happy path + the most likely failure).

## 5. Quality attributes
| Attribute | Target / approach |
|---|---|
| Availability / failure modes | what happens when X dies, how it recovers |
| Security | trust boundaries, authn/authz, secrets (where they live), exposure |
| Operability | install/upgrade path (playbook?), monitoring, logs, runbook |
| Performance / capacity | expected load, limits, headroom on the hosts |
| Cost / resources | VMs, disk, RAM on Proxmox |

## 6. Alternatives considered
Each with why it wasn't chosen. Include "do nothing".

## 7. Rollout and rollback
Steps in order (who runs what: Semaphore template, kubectl, manual), how to verify each step, how to undo.
Note anything destructive or outward-facing that needs explicit confirmation.

## 8. Risks and open questions
Risk → likelihood/impact → mitigation. Questions that need an answer before or during build.

## 9. Docs to update when built
`arch/README.md` (systems table, diagrams dashed→solid, open items), runbook, ADRs for lasting decisions.
