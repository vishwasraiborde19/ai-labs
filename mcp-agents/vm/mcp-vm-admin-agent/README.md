# mcp-vm-admin-agent

Control centre for VMs running [mcp-vm-worker-agent](../mcp-vm-worker-agent/). One web UI (and an MCP
endpoint for Claude Code) to see the whole fleet, browse and edit files, run commands and jobs, and
upgrade the agents.

```
 browser (this machine) ──┐                          ┌── web-01  worker agent :8765
                          ├─▶ vm-admin :8767 ──REST──┼── db-01   worker agent :8765
 Claude Code ──MCP /mcp ──┘   fleet.yml, tokens.yml  └── lab-vm  worker agent :8765
                              audit.log
```

The control centre talks to each worker over its REST API with that VM's token. Nothing is installed
on the VMs beyond the worker agent itself.

## What it does

| Area | Features |
|---|---|
| **Fleet** | Dashboard with every host's state (online, offline, token rejected), agent version, uptime and latency, re-checked every 30 s. Groups, tags, notes and a sidebar filter. State changes go to the audit log. |
| **Hosts** | Add, edit, rename and remove hosts in the UI, with **Test connection** before saving. A pasted token is stored in `tokens.yml` (mode 600); a host can instead reference `vault:`, `env:` or `path:`. |
| **Overview** | OS, kernel, uptime, load against CPU count, memory, root disk, swap, IPs, failed systemd units and whether a reboot is pending, gathered in one round trip. |
| **Files** | Browse with breadcrumbs, hidden files and symlink targets. View, edit and save text files (`Ctrl+S`, `.bak` copy by default). Download, upload (button or drag and drop, with a prompt before overwriting), new file, new folder, rename and delete (typed confirmation for folders). The worker refuses to delete `/`, system directories, `$HOME` and itself. |
| **Terminal** | Run commands with a working directory and timeout. Per-host history (arrow keys), one-click snippets (disk, memory, top processes, failed services, ports, recent errors, docker, pending updates, logins), or **Run in background**. |
| **Jobs** | List background jobs, follow their output live and stop them. |
| **Shell** | Interactive terminal (xterm.js) on the VM: `top`, `vim`, prompts, colours, resize. Needs worker 2.2.0+. Sessions close after 30 min idle. Opening and closing are audited; **keystrokes are not logged and the risky-command confirmation does not apply** — on read-only hosts the Shell tab is disabled. |
| **Services** | systemd services with state and enabled flag, filter, status output, and start / stop / restart / reload / enable / disable (`sudo -n systemctl`; stop and disable need confirmation). |
| **Packages** | Package manager (apt, dnf or yum), upgradable packages, installed packages with a filter; install, remove, upgrade one or upgrade all as a background job (`sudo -n`; remove and upgrade-all need confirmation). |
| **Logs** | Live tail of the systemd journal (unit and priority filters, cursor-based, no gaps) or of any file (offset-based, survives truncation and rotation). |
| **Metrics** | Load, memory, disk and swap sampled every 5 min from online hosts, kept 7 days (`metrics/<host>.jsonl`), shown as charts on the Overview. |
| **Alerts** | Host offline (after a 2 min grace period, or token rejected), disk ≥ 90%, memory ≥ 95%, load ≥ 2 per CPU, failed units, and optionally reboot pending. Raised and resolved alerts go to the audit log, a badge in the nav, and an optional Slack or JSON webhook. |
| **Schedules** | Run a command on hosts, a group or a tag on a cron (`*/15 * * * *`, 6-field, or `@daily`/`@hourly`...). Risky commands must be confirmed when the schedule is saved. Offline and read-only hosts are skipped. Results are kept for the last 20 runs and audited as `[schedule:<name>]`. Stored in `schedules.yml`. |
| **Sync** | Push one file (pasted or loaded from a host) to many hosts. **Preview** shows a unified diff per host (new, changed, unchanged); **Apply** writes only the changed ones, with a `.bak` copy. |
| **Token rotation** | **Rotate token** in the host form creates a new token on the VM through the worker CLI, verifies it, stores it, then revokes the old one. |
| **Broadcast** | Run one command on many hosts in parallel, picked by name, group or tag. Shows each host's result and exit code. |
| **Agent upgrade** | Every build bundles the worker agent from `../mcp-vm-worker-agent`. Hosts on an older version get an **Upgrade agent** button, which pushes the code over MCP `update_server` and waits for the new version to report in. If the new code crashes on start, the VM's `run.sh` rolls it back. |
| **Audit** | Control-centre audit log (`[ui@127.0.0.1] host=web-01 RUN cmd=...`, `[mcp] ...`) with a filter, plus each VM's own agent audit log. |
| **Guard rails** | **Read-only** hosts allow browsing and reading only. Risky commands need a typed confirmation in the UI, or `confirm=true` over MCP. That covers `rm -rf /`, reboot or shutdown, `mkfs`, `dd` to a disk, stopping services, firewall flushes, package removal, `DROP TABLE`, `kubectl delete`, changes to the agent itself, and your own `confirm-patterns`. |
| **MCP** | `fleet_hosts`, `fleet_refresh`, `fleet_run`, `fleet_alerts`, `host_sysinfo`, `host_run`, `host_list_dir`, `host_read_file`, `host_write_file`, `host_start_job`, `host_job_status`, `host_kill_job`, `host_services`, `host_service_action`, `host_journal`, `host_updates`. Same guard rails and audit as the UI. |

The confirmation is a speed bump against slips, not a sandbox. Every worker token is a shell on its VM.

## Build and run

Java 21. The build copies the worker agent in from `../mcp-vm-worker-agent`.

```bash
mvn package
java -jar target/mcp-vm-admin-agent.jar          # UI: http://127.0.0.1:8767/ui
```

| Env | Default | |
|---|---|---|
| `VMADMIN_HOME` | `~/.vm-admin` | `fleet.yml`, `tokens.yml` |
| `VMADMIN_CONFIG` | `$VMADMIN_HOME/config.yml` | see [config/config.example.yml](config/config.example.yml) |
| `VMADMIN_LOG_DIR` | `$VMADMIN_HOME/logs` | `admin.log`, `audit.log` |
| `VMADMIN_HOST` / `VMADMIN_PORT` | `127.0.0.1` / `8767` | listen address |

## Adding a VM

1. Install or upgrade the worker on the VM (`../mcp-vm-worker-agent/deploy.sh user@vm --name web-01`).
2. Create a token for the control centre. Use scope `rest`, or `rest,mcp` to allow one-click upgrades:
   `~/mcp-vm-installer-agent/venv/bin/python ~/mcp-vm-installer-agent/server.py token create control-centre --scopes rest,mcp`
3. In the UI: **Add host**, enter the name and `http://VM:8765`, paste the token, **Test connection**, then **Add host**.

Workers older than 2.1.0 have no file API. Everything else works, and the Files tab offers the upgrade.

## Sign-in

`vmadmin.ui.auth: none` is the default and the only mode built so far. There is no sign-in, so the
control centre only answers requests from this machine; from elsewhere, use
`ssh -L 8767:127.0.0.1:8767 you@admin-host`. API calls must carry `X-VM-Admin: 1` (CSRF), and pages
are served with a strict CSP.

`saml` is reserved for the app-level SAML login that comes later. Until then the control centre
refuses to start with it.

## MCP (Claude Code)

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > ~/.vm-admin/mcp.token && chmod 600 ~/.vm-admin/mcp.token
# config.yml:  vmadmin.mcp.token-ref: path:~/.vm-admin/mcp.token
claude mcp add --transport http vm-admin http://127.0.0.1:8767/mcp --header "Authorization: Bearer $(cat ~/.vm-admin/mcp.token)"
```

Without a token configured, `/mcp` answers 503.

## Requirements on the VMs

* **Shell** needs worker agent 2.2.0+; **Files** need 2.1.0+. Use **Upgrade agent** on the host page
  (the host's token needs the `mcp` scope for that), or reinstall the worker.
* **Services** actions and **Packages** changes run `sudo -n`, so the worker's service user needs
  passwordless sudo for `systemctl` and the package manager, for example in `/etc/sudoers.d/vm-agent`:
  `vmagent ALL=(root) NOPASSWD: /usr/bin/systemctl, /usr/bin/apt-get`. Listing works without sudo.
* **Token rotation** runs the worker's own CLI on the VM, so it works on any install made by `install.sh`.

## Still to do

* **SAML sign-in** and roles: viewer (read-only everywhere), operator (commands, no deletes), admin.
  Until then, keep the control centre on loopback.
* Email alerts (webhook only for now).
