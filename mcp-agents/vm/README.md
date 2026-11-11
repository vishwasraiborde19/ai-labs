# VM management

Two components that together let you (in a browser) and Claude Code (over MCP) operate a fleet of
Linux VMs: run commands and jobs, browse and edit files, open a shell, manage services and packages,
tail logs, and watch metrics and alerts.

| Component | Runs on | Stack | Port | Docs |
|---|---|---|---|---|
| [mcp-vm-worker-agent](mcp-vm-worker-agent/) | every VM | Python (FastMCP + uvicorn), systemd | `8765` | [README](mcp-vm-worker-agent/README.md) |
| [mcp-vm-admin-agent](mcp-vm-admin-agent/) | your workstation / admin host | Java 21, Spring Boot | `8767` (loopback) | [README](mcp-vm-admin-agent/README.md) |

The **worker** is a small agent on each VM with an MCP endpoint, a REST API and a token-management UI.
The **admin** (control centre) holds the fleet list and one token per VM, and turns the workers'
REST APIs into a single UI and a fleet-wide MCP endpoint. Nothing else is installed on the VMs.

## Architecture

```mermaid
flowchart LR
    subgraph ws["Workstation / admin host"]
        browser["Browser<br/>http://127.0.0.1:8767/ui"]
        cc["Claude Code"]
        subgraph admin["mcp-vm-admin-agent :8767"]
            ui["Web UI + /ui/api<br/>(UiSecurityFilter: loopback, X-VM-Admin, CSP)"]
            mcpA["/mcp fleet tools<br/>(McpAuthFilter: bearer)"]
            core["FleetService · CommandGuard · Audit<br/>Metrics · Alerts · Schedules · Sync"]
            client["AgentClient<br/>(per-host token)"]
        end
        state[("~/.vm-admin<br/>fleet.yml · tokens.yml<br/>schedules.yml · metrics/ · logs/")]
    end

    secrets["Vault / env / file<br/>(token-ref)"]
    hook["Slack / JSON webhook"]

    subgraph vm1["VM: web-01"]
        w1["mcp-vm-worker-agent :8765<br/>/api · /mcp · /ui · /health"]
        d1[("tokens.json · audit.log<br/>jobs/ · plugins/")]
    end
    subgraph vm2["VM: db-01 (read-only)"]
        w2["mcp-vm-worker-agent :8765"]
    end
    subgraph vm3["VM: lab-vm"]
        w3["mcp-vm-worker-agent :8765"]
    end

    browser --> ui
    cc -- "MCP (HTTP)" --> mcpA
    ui --> core
    mcpA --> core
    core --> client
    core <--> state
    core -. resolve tokens .-> secrets
    core -. alerts .-> hook
    client -- "REST /api/* (scope rest)<br/>MCP update_server (scope mcp)" --> w1
    client --> w2
    client --> w3
    w1 --- d1

    cc -. "optional: direct MCP<br/>to a single VM" .-> w1
```

* Claude Code can talk to the **control centre** (`/mcp` on 8767, fleet-wide tools such as
  `fleet_run`, `host_read_file`) or directly to **one worker** (`/mcp` on 8765, tools such as
  `run_command`, `start_job`).
* The control centre never stores a token in `fleet.yml`. Each host has a `token-ref`:
  `store:` (pasted in the UI, kept in `tokens.yml`, mode 600), `vault:`, `env:` or `path:`.

## Request path and guard rails

Every action from the UI or MCP passes the same checks before it reaches a VM, and is audited on both
sides.

```mermaid
flowchart TD
    A["Request<br/>UI (/ui/api) or MCP (/mcp)"] --> B{"Auth"}
    B -- "UI: loopback only,<br/>X-VM-Admin header" --> C
    B -- "MCP: Bearer = mcp.token" --> C
    B -- fail --> X1["403 / 401 / 503"]
    C{"Host read-only?"} -- "yes and action writes" --> X2["Refused"]
    C -- no --> D{"CommandGuard:<br/>risky pattern?"}
    D -- "yes, not confirmed" --> X3["Needs typed confirmation (UI)<br/>or confirm=true (MCP)"]
    D -- "no / confirmed" --> E["Audit (admin audit.log)<br/>[ui@127.0.0.1] / [mcp] host=... RUN ..."]
    E --> F["AgentClient → worker :8765<br/>Authorization: Bearer mva_..."]
    F --> G{"Worker: token valid<br/>and has scope?"}
    G -- no --> X4["401 / 403 → host shown as<br/>'token rejected'"]
    G -- yes --> H["Execute as the service user<br/>(sudo -n for systemctl / packages)"]
    H --> I["Worker audit.log<br/>[control-centre] RUN ..."]
```

The confirmation is a speed bump against slips, not a sandbox: every worker token is a shell on its VM.

## Lifecycle

### 1. Install a worker on a VM

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator
    participant D as deploy.sh (workstation)
    participant VM as VM
    participant S as systemd
    Op->>D: ./deploy.sh user@vm --name web-01
    D->>VM: tar agent/ install.sh VERSION over SSH
    D->>VM: ssh -t bash install.sh --name web-01
    VM->>VM: python venv + mcp, uvicorn
    VM->>S: write mcp-vm-installer-agent.service<br/>(ExecStart=run.sh, KillMode=process)
    VM->>VM: ufw allow from <subnet> to :8765
    S->>VM: start run.sh → server.py :8765
    VM-->>Op: UI URL, admin password,<br/>bootstrap token + `claude mcp add ...`
```

### 2. Register it in the control centre

1. On the VM create a token for the control centre: scope `rest`, or `rest,mcp` to allow one-click
   upgrades (`venv/bin/python server.py token create control-centre --scopes rest,mcp`).
2. In the UI: **Add host** → name, `http://VM:8765`, paste token → **Test connection** → **Add host**.
3. The host appears on the dashboard; the background check (every 30 s) calls `/api/info`.

### 3. Background loops in the control centre

```mermaid
flowchart LR
    subgraph timers["Control centre schedulers"]
        st["Status check<br/>every 30s"]
        me["Metrics sample<br/>every 5m"]
        sc["Schedules<br/>(cron)"]
    end
    st -- "/api/info" --> W["Workers"]
    me -- "/api/run (sysinfo)" --> W
    sc -- "/api/run on hosts / group / tag" --> W
    st --> AL["AlertService<br/>offline, token rejected"]
    me --> AL2["AlertService<br/>disk ≥90%, mem ≥95%,<br/>load ≥2/CPU, failed units"]
    me --> MF[("metrics/&lt;host&gt;.jsonl<br/>7 days")]
    AL & AL2 --> OUT["audit.log · nav badge · webhook"]
    sc --> RUNS[("schedules.yml<br/>last 20 runs")]
```

Offline and read-only hosts are skipped by schedules.

### 4. Upgrade a worker from the UI

The control centre jar bundles the worker (`server.py`, `ui.html`, `VERSION`) from
`mcp-vm-worker-agent/` at build time. Hosts on an older version show **Upgrade agent**.

```mermaid
sequenceDiagram
    autonumber
    participant UI as Control centre
    participant W as Worker server.py
    participant R as run.sh
    participant S as systemd
    UI->>W: MCP tools/call update_server {code, ui_html}<br/>(token needs scope mcp)
    W->>W: compile-check code, keep server.py.prev,<br/>write new server.py (+ ui.html)
    W->>R: exit 3 (restart requested)
    R->>S: exit
    S->>R: restart (RestartSec=2)
    R->>W: start new server.py
    alt crashes within 15s
        R->>R: save server.py.failed-<ts>,<br/>restore server.py.prev
        S->>R: restart → old version runs
    end
    loop until new VERSION or timeout
        UI->>W: GET /api/info
    end
    UI-->>UI: audit UPGRADE, refresh host
```

Background jobs survive the restart because the unit uses `KillMode=process`.

### 5. Interactive shell (worker 2.2.0+)

```mermaid
sequenceDiagram
    participant B as Browser (xterm.js)
    participant A as Control centre
    participant W as Worker /api/pty
    B->>A: open Shell tab
    A->>W: POST /api/pty {cwd, cols, rows}
    W-->>A: {id}  (bash -l in a PTY)
    loop while open
        A->>W: GET /api/pty/{id}/output?offset&wait (long-poll)
        W-->>A: base64 output
        A-->>B: output
        B->>A: keystrokes
        A->>W: POST /api/pty/{id}/input {b64}
    end
    B->>A: close / resize
    A->>W: DELETE /api/pty/{id}  or  POST .../resize
```

Opening and closing are audited; keystrokes are not, and the risky-command check does not apply. The
Shell tab is disabled on read-only hosts. Idle sessions close after 30 min.

## Where things live

| | Control centre (`~/.vm-admin`, `VMADMIN_HOME`) | Worker (`~/mcp-vm-installer-agent`) |
|---|---|---|
| Config | `config.yml` | systemd unit env (`AGENT_*`) |
| Hosts / tokens | `fleet.yml`, `tokens.yml` (600) | `tokens.json` (sha256 hashes), `admin.json` |
| Logs | `logs/admin.log`, `logs/audit.log` | `audit.log` |
| Data | `metrics/*.jsonl`, `schedules.yml`, `state/` (last host status, open alerts, schedule runs) | `jobs/`, `plugins/` |
| Code | `~/mcp-vm-admin-agent/mcp-vm-admin-agent.jar` | `server.py`, `ui.html`, `run.sh`, `venv/` |
| Service | `mcp-vm-admin-agent` (systemd, `install.sh`) | `mcp-vm-installer-agent` (systemd, `install.sh`) |

Both sides keep their data outside the code directory, so restarts, reboots and upgrades keep every
host, token, schedule and alert. Details: [What survives a restart](mcp-vm-admin-agent/README.md#what-survives-a-restart).

## Ports and network

| From | To | Port | Auth |
|---|---|---|---|
| Browser on the admin host | control centre | `127.0.0.1:8767` | none (loopback only; use `ssh -L` from elsewhere) |
| Claude Code | control centre `/mcp` | `8767` | bearer `mcp.token` |
| Control centre | worker `/api`, `/mcp` | `8765` | per-VM bearer token, scopes `rest` / `mcp` |
| Claude Code (optional) | worker `/mcp` | `8765` | worker token, scope `mcp` |
| Browser on the LAN | worker `/ui` | `8765` | admin password + `--allow` CIDR |

Port 8765 is opened in `ufw` only for the `--allow` networks (default: the VM's subnet). Use `--tls` on
the worker for HTTPS, and `ca-cert-ref` in `fleet.yml` so the control centre trusts it.

## Quick start

```bash
# 1. worker on each VM
./mcp-vm-worker-agent/deploy.sh user@192.168.1.50 --name web-01

# 2. control centre on your machine, as a systemd service
./mcp-vm-admin-agent/install.sh --build      # add --import-from mcp-vm-admin-agent/.run to keep earlier hosts
# open http://127.0.0.1:8767/ui → Add host   (or for a quick try: java -jar target/mcp-vm-admin-agent.jar)

# 3. (optional) Claude Code → whole fleet
claude mcp add --transport http vm-admin http://127.0.0.1:8767/mcp \
  --header "Authorization: Bearer $(cat ~/.vm-admin/mcp.token)"
```
