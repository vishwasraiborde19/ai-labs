# semaphore-installer

A single script that turns a fresh Ubuntu 22.04 VM into a [Semaphore UI](https://semaphoreui.com)
server, ready for Ansible playbooks and Terraform/OpenTofu projects.

| Component | Version | Source |
|---|---|---|
| Semaphore UI | 2.19.18 | GitHub release `.deb`, sha256-verified |
| PostgreSQL | 14 | Ubuntu, bound to localhost only |
| Ansible | ansible-core 2.17 | official `ppa:ansible/ansible` (Ubuntu's own package is 2.10) |
| Terraform | latest | HashiCorp apt repo |
| OpenTofu | 1.13.8 | GitHub release `.deb`, sha256-verified |
| git | distro | Ubuntu |

## Install

```bash
git clone https://github.com/vishwasraiborde19/ai-labs
./ai-labs/semaphore-installer/install-semaphore.sh
```

Run it as a user with sudo. It takes about 2 minutes and finishes with the URL and a generated
`admin` password. Change that password after your first login.

To pin other versions or set a different public URL, use environment variables:

```bash
SEM_VER=2.19.18 TOFU_VER=1.13.8 WEB_HOST=http://semaphore.lan:3000 ./install-semaphore.sh
```

The script is idempotent. Re-running it keeps the config, the database, the encryption keys and the
admin password. Bumping `SEM_VER` upgrades Semaphore in place, and migrations run automatically.

## What it sets up

* Semaphore runs as the `semaphore` system user (home `/var/lib/semaphore`) under the systemd unit
  `semaphore`, listening on `:3000`.
* Config is in `/etc/semaphore/config.json` (mode 640, root:semaphore), with freshly generated
  cookie and access-key encryption keys. The DB password is in `/etc/semaphore/.dbpass` (mode 600).
* PostgreSQL database `semaphore`, owned by role `semaphore`.

## First Terraform/OpenTofu project

1. **Key Store**: add a credential for your git repo, plus provider secrets.
2. **Repositories**: add the repo that holds your `.tf` files.
3. **Variable Groups**: add provider credentials as environment variables.
4. **Task Templates**: create a new template of type **Terraform Code** or **OpenTofu Code**, then
   run it. Semaphore shows the plan, and you approve it before it applies.

## Operations

```bash
journalctl -u semaphore -f                     # logs
sudo -u semaphore semaphore user change-by-login --login admin --password 'new-pass' \
  --config /etc/semaphore/config.json          # reset admin password
sudo -u postgres pg_dump semaphore > semaphore.sql   # backup
```

Semaphore serves plain HTTP. For HTTPS, put a reverse proxy (nginx or Caddy) in front of port 3000.
