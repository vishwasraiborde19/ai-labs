# registry

Container registry for the lab: [Harbor](https://goharbor.io/) on its own Ubuntu VM, installed and kept
in shape by a Semaphore playbook. Using it (Docker, Jib, Kubernetes pulls, robot accounts):
[tool-box/harbour/runbook.md](../../../harbour/runbook.md).

| | |
|---|---|
| VM | `harbor-server`, 192.168.1.173 ([inventories/lab/hosts.yaml](inventories/lab/hosts.yaml)) |
| Harbor | v2.15.5, online installer, sha256-pinned ([group_vars/all/harbor.yml](inventories/lab/group_vars/all/harbor.yml)) |
| Access | http://192.168.1.173/ (plain HTTP), user `admin` |
| Data | `/data` on the VM (images, PostgreSQL, Redis, job logs) |
| Secrets | generated on the VM on first run: `/root/harbor/admin-password`, `/root/harbor/db-password` |

## What the playbook does ([harbor.yml](harbor.yml))

1. Docker Engine + compose plugin from Docker's apt repository (removes Ubuntu's `docker.io` if present).
2. Downloads `harbor-online-installer-<version>.tgz`, checks its sha256, unpacks it to `/opt/harbor`.
3. Generates the admin and database passwords once (they stay on the VM).
4. Renders `/opt/harbor/harbor.yml` from the installer's own `harbor.yml.tmpl`: `hostname` = the VM's
   address, HTTP on port 80, **no https section**, `data_volume: /data`.
5. Runs `install.sh --with-trivy` when the config changed or Harbor is not running (pulls the images,
   runs `prepare`, starts the containers; they restart with Docker on boot).
6. Waits until `/api/v2.0/health` reports every component healthy, and checks that the registry's
   login challenge (token realm) names `hostname`; a wrong hostname here is what broke the previous
   Harbor (realm `192.168.0.90` after the lab moved to `192.168.1.x`).

Re-running is safe: with no changes it reports `ok` everywhere and Harbor keeps running.

## Semaphore

| Object | Value |
|---|---|
| Inventory `registry` | type *File*, repository `ai-labs`, path `mcp-agents/tool-box/semaphore/playbooks-library/registry/inventories/lab/hosts.yaml` |
| Template `Registry: Harbor install / converge` | app *Ansible*, playbook `mcp-agents/tool-box/semaphore/playbooks-library/registry/harbor.yml`, inventory `registry`, environment `default` |

Before the first run the Semaphore server must trust the VM and be able to log in:

```bash
# on the Harbor VM: Semaphore's public key in vishwas' authorized_keys (passwordless sudo)
# on the Semaphore server, after comparing the fingerprint with the VM's console
#   (ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub):
sudo -u semaphore sh -c 'ssh-keyscan -t ed25519 192.168.1.173 >> ~/.ssh/known_hosts'
sudo -u semaphore ssh vishwas@192.168.1.173 'hostname && sudo -n true && echo ok'
```

`--check --diff` previews changes on an installed Harbor (install.sh is skipped in check mode). On a fresh
VM it stops at "Read harbor.yml.tmpl", because check mode does not unpack the installer.

## After the install

1. Log in at http://192.168.1.173/ as `admin` with `sudo cat /root/harbor/admin-password` (on the VM),
   change the password in the portal, store it in the password manager (**Lab / Harbor admin**).
2. Create the projects (e.g. `infracanary`) and robot accounts: runbook section 1.
3. Clients: `192.168.1.173` in Docker's `insecure-registries` (runbook section 2), and the
   `containerd_registries_mirrors` entry in
   [kubespray-vars.yml](../kubernetes/inventories/proxmox01/kubespray-vars.yml) for the cluster nodes
   (runbook section 3).

## Settings

| Variable | Default | |
|---|---|---|
| `harbor_version` | `v2.15.5` | with `harbor_installer_sha256` (GitHub release asset digest) |
| `harbor_hostname` | the host's `ansible_host` | the address every client uses; it is baked into logins |
| `harbor_http_port` | `80` | |
| `harbor_data_volume` | `/data` | put it on a disk with room for the images |
| `harbor_with_trivy` | `true` | vulnerability scanning |
| `target` | `harbor` | host pattern |

**Upgrading Harbor** is not automatic: the playbook refuses to install a different version over an
existing one. Back up `/data`, follow Harbor's upgrade guide for the target version (`prepare` migration),
then set `harbor_version` / `harbor_installer_sha256` and update `/opt/harbor/.ai-labs-version`.
