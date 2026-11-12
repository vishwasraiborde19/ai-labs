# playbooks-library

Reusable playbooks and scripts for [Semaphore UI](../semaphore-installer/), one folder per topic.

| Folder | What's in it |
|---|---|
| [kubernetes/](kubernetes/) | kubespray runner (deploy, scale, upgrade, reset) with a pinned version and its own Ansible, MetalLB, node static IPs, node checks, the `proxmox01` inventory, and `bootstrap.py` that sets it all up in Semaphore. |

## Adding a topic

Create a folder next to `kubernetes/` (for example `databases/`, `monitoring/`, `linux/`) and keep it
self-contained:

```
<topic>/
├── README.md            what it does, variables, the Semaphore templates to create
├── inventories/<env>/   hosts.yaml + group_vars/ (or reuse ../kubernetes/inventories/<env>)
├── <playbook>.yml
└── semaphore/           optional: a bootstrap script like kubernetes/semaphore/bootstrap.py
```

In Semaphore, a template's playbook path is relative to the repository root, e.g.
`mcp-agents/semaphore/playbooks-library/<topic>/<playbook>.yml`.

## Conventions

* **Settings come from the inventory** (`group_vars/`, `host_vars/`) or extra vars, never from edits
  to the playbook. List every variable and its default in a comment at the top of the playbook.
* **Idempotent**: a second run reports no changes. Mark read-only commands `changed_when: false` and
  `check_mode: false`, so a run with `--check --diff` is a real preview.
* **Targets are parameters**: `hosts: "{{ target | default('all') }}"` lets one template serve any
  group; use `serial` for anything that restarts or re-networks hosts.
* **Destructive actions need an explicit confirmation** argument or variable that names what gets
  destroyed (see `kubespray.sh reset --confirm-reset=<inventory>`).
* **No secrets in the repository**: SSH keys stay on the Semaphore server or in its Key Store;
  passwords and tokens go in Semaphore secret variables.
* **Tool versions are pinned** where it matters (kubespray, MetalLB). Bump them deliberately.

## Testing

```bash
ansible-playbook -i <topic>/inventories/<env>/hosts.yaml --syntax-check <topic>/<playbook>.yml
ansible-playbook -i <topic>/inventories/<env>/hosts.yaml <topic>/<playbook>.yml --check --diff
```
