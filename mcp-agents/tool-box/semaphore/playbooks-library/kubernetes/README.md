# Kubernetes playbooks

Build and run Kubernetes clusters from [Semaphore UI](../../semaphore-installer/): kubespray (deploy,
scale, upgrade, reset) with a pinned version and its own Ansible, MetalLB, node networking and checks.
Part of the [playbooks library](../). Each playbook takes its settings from the inventory (or extra
vars) and lists its variables at the top of the file.

```
kubernetes/
├── inventories/
│   └── proxmox01/                 the 6-node Kubernetes cluster on Proxmox
│       ├── hosts.yaml             hosts and groups (kubespray layout)
│       ├── group_vars/all/        connection.yml (SSH user), metallb.yml, network.yml
│       ├── kubespray.env          kubespray version + SSH user
│       ├── kubespray-vars.yml     cluster settings passed to kubespray with -e
│       └── patches/               kubeadm patches (copied along; enable with kubeadm_patches)
├── kubespray/kubespray.sh         kubespray runner: pinned version + its own Ansible, live output
├── metallb.yml                    MetalLB + address pools + L2 advertisements
├── nfs-client.yml                 nfs-common on the nodes + NAS export check
├── ping.yml                       SSH / sudo / OS check of every node, changes nothing
├── static-ip.yml                  pin each node's IP in netplan, one node at a time
└── semaphore/bootstrap.py         creates/updates the Semaphore project, inventory and templates
```

## Catalogue

| Item | Type in Semaphore | Runs on | What it does |
|---|---|---|---|
| `ping.yml` | Ansible | any inventory | Connectivity, Python, sudo, OS, uptime. First template to run for a new inventory or key. |
| `static-ip.yml` | Ansible | Ubuntu (netplan) | Turns the current DHCP address into a static netplan config, disables cloud-init networking, applies it detached and waits for the node to return. `serial: 1`. |
| `nfs-client.yml` | Ansible | `k8s_cluster` | Installs `nfs-common` on every node and lists the NAS exports. Only needed for pods that mount NFS directly; the NFS CSI driver does not need it. |
| `metallb.yml` | Ansible | first `kube_control_plane` host | Installs/upgrades MetalLB from its native manifest and applies pools and L2 advertisements. Idempotent. |
| `kubespray/kubespray.sh preflight` | Bash | inventory with kubespray layout | Checks SSH, sudo, OS and kubelet on every node. Changes nothing. |
| `kubespray/kubespray.sh cluster` | Bash | | `cluster.yml`: deploy, or converge an existing cluster. Idempotent. |
| `kubespray/kubespray.sh scale` | Bash | | `scale.yml`: add workers (add them to `hosts.yaml`, pass `--limit=<new nodes>`). |
| `kubespray/kubespray.sh upgrade` | Bash | | `upgrade-cluster.yml`: rolling upgrade to `kube_version`. |
| `kubespray/kubespray.sh remove-node` | Bash | | `remove-node.yml -e node=<name>`. |
| `kubespray/kubespray.sh recover` | Bash | | `recover-control-plane.yml`. |
| `kubespray/kubespray.sh reset` | Bash | | `reset.yml`: **destroys the cluster**. Refuses to run without `--confirm-reset=<inventory>`. |

### Why kubespray is a Bash template

Semaphore runs every Ansible template with the server's own `ansible-playbook` (ansible-core 2.17 on
this server). Each kubespray release accepts only a narrow range of ansible-core: v2.23.0, which built
`proxmox01` with Kubernetes v1.27.5, needs 2.14. `kubespray.sh` therefore:

1. clones kubespray at the version in the inventory's `kubespray.env` (cached per version),
2. builds a venv from that release's own `requirements.txt` (cached, rebuilt when it changes),
3. assembles a run inventory: kubespray's `inventory/sample/group_vars` for that version, with the
   library's `group_vars`, `host_vars`, `patches` and `hosts.yaml` laid over it,
4. runs the playbook from the checkout with `-e @kubespray-vars.yml`, `--become`, as `ANSIBLE_USER`.

Cache: `$KUBESPRAY_HOME` (default `~/.cache/kubespray`, i.e. `/var/lib/semaphore/.cache/kubespray`
when Semaphore runs it). kubespray's generated `credentials/` (kubeadm certificate key) stay there
between runs. One run per inventory at a time (`flock`).

Every other playbook here runs on the server's Ansible as a normal Ansible template.

## Setting it up in Semaphore

`semaphore/bootstrap.py` does steps 2-5 below through the Semaphore API: it creates (or updates, matched
by name) a project for one inventory with its repository, inventory, variable groups, views and every
template in the table. Re-run it after adding a template or inventory, or to switch the repository URL.

```bash
cd mcp-agents/tool-box/semaphore/playbooks-library
SEMAPHORE_URL=http://192.168.1.42:3000 SEMAPHORE_TOKEN=<admin API token> \
  python3 kubernetes/semaphore/bootstrap.py --inventory proxmox01                  # repository: GitHub, main
#   --repo /opt/ai-labs     use a local copy on the Semaphore server instead (same layout)
#   --project NAME          default: the inventory name
```

Step 1 (the SSH key) is done on the server. Paths below are relative to the repository root (`ai-labs`).

1. **SSH key**: the `semaphore` user's own key, `/var/lib/semaphore/.ssh/id_ed25519` (generate it with
   `sudo -u semaphore ssh-keygen -t ed25519 -N "" -f /var/lib/semaphore/.ssh/id_ed25519`), authorized
   for `vishwas` on every node, with the nodes' host keys in `/var/lib/semaphore/.ssh/known_hosts`
   (`ssh-keyscan`). Ansible and kubespray templates both use it as the default key, and the inventory
   sets `ansible_user`, so the private key never leaves the server and is not in the Key Store.
   **Key Store**: one entry `none`, type *None*, used for the repository and the inventory.
2. **Repository** `ai-labs`: `https://github.com/vishwasraiborde19/ai-labs.git`, branch `main`, key
   `none`. To use changes before pushing, a local copy on the Semaphore server works too: repository
   URL `/opt/ai-labs` with the same layout, so template paths do not change.
3. **Inventory** `proxmox01`: type *File*, repository `ai-labs`, path
   `mcp-agents/tool-box/semaphore/playbooks-library/kubernetes/inventories/proxmox01/hosts.yaml`, user credentials
   `none`, sudo credentials *none* (the nodes have passwordless sudo).
4. **Variable Groups**
   * `default`: extra variables `{}`.
   * `kubespray-proxmox01`: environment `{"INVENTORY": "proxmox01"}` (add `SSH_KEY_FILE` to use a key
     other than the `semaphore` user's default).
5. **Task Templates**

| Template | App | Playbook / script | Arguments | Inventory | Variable group |
|---|---|---|---|---|---|
| Linux: ping | Ansible | `mcp-agents/tool-box/semaphore/playbooks-library/kubernetes/ping.yml` | | proxmox01 | default |
| Linux: static IP | Ansible | `.../kubernetes/static-ip.yml` | | proxmox01 | default |
| K8s: MetalLB | Ansible | `.../kubernetes/metallb.yml` | | proxmox01 | default |
| Kubespray: preflight | Bash | `.../kubernetes/kubespray/kubespray.sh` | `["preflight"]` | proxmox01 | kubespray-proxmox01 |
| Kubespray: deploy / converge | Bash | `.../kubernetes/kubespray/kubespray.sh` | `["cluster"]` | proxmox01 | kubespray-proxmox01 |
| Kubespray: add workers | Bash | `.../kubernetes/kubespray/kubespray.sh` | `["scale"]` (+ `--limit=` when running) | proxmox01 | kubespray-proxmox01 |
| Kubespray: upgrade | Bash | `.../kubernetes/kubespray/kubespray.sh` | `["upgrade"]` | proxmox01 | kubespray-proxmox01 |
| Kubespray: RESET (destroys cluster) | Bash | `.../kubernetes/kubespray/kubespray.sh` | `["reset", "--confirm-reset=proxmox01"]` | proxmox01 | kubespray-proxmox01 |

Allow "CLI args" on the kubespray templates so a run can add `--limit=...`, `-e node=...`,
`--tags=...` or `--check`. The Bash app has to be enabled under *Settings → Apps* (admin).

The server needs `git` and `python3-venv` (both installed by `semaphore-installer`).

## Adding a cluster

Copy `inventories/proxmox01` to `inventories/<name>`, edit `hosts.yaml`, `kubespray.env`,
`kubespray-vars.yml` and the vars, then run `semaphore/bootstrap.py --inventory <name>`: it creates a
project `<name>` with the same templates. Every playbook works with it unchanged.

## Testing without Semaphore

```bash
cd mcp-agents/tool-box/semaphore/playbooks-library/kubernetes
ansible-playbook -i inventories/proxmox01/hosts.yaml --syntax-check ping.yml
ansible-playbook -i inventories/proxmox01/hosts.yaml ping.yml
ansible-playbook -i inventories/proxmox01/hosts.yaml metallb.yml --check --diff
INVENTORY=proxmox01 ./kubespray/kubespray.sh preflight
INVENTORY=proxmox01 ./kubespray/kubespray.sh cluster --check     # kubespray's check mode is partial
```
