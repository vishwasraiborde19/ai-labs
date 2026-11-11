# mcp-vm-installer-agent

> Architecture, request flow and lifecycle diagrams for the whole VM setup: [../README.md](../README.md)

A small agent you install on a VM so Claude Code (over **MCP**) and scripts (over **REST**) can run
commands, start long jobs, and read and write files there. A built-in web UI generates and revokes
the tokens that grant that access.

| Endpoint | Auth | Purpose |
|---|---|---|
| `/mcp` | token with scope `mcp` | MCP streamable HTTP (Claude Code) |
| `/api/*` | token with scope `rest` | REST API |
| `/ui` | admin password, source IP in allowlist | token management UI |
| `/health` | none | liveness |

## Install

Straight from git, on the VM:

```bash
git clone https://github.com/vishwasraiborde19/ai-labs
./ai-labs/mcp-vm-installer-agent/install.sh --name my-vm
```

From a machine that can SSH to the VM:

```bash
./deploy.sh vishwas@192.168.1.42 --name semaphore-vm
```

Or on the VM itself:

```bash
tar xzf mcp-vm-installer-agent-2.1.0.tar.gz
./mcp-vm-installer-agent-2.1.0/install.sh --name semaphore-vm
```

The installer prints three things you need: the UI URL, a generated **admin password**, and a one-time
**bootstrap token** (scopes rest+mcp) together with the `claude mcp add ...` command.

Options: `--port 8765`, `--allow 192.168.1.0/24` (firewall and UI allowlist, defaults to the VM's
subnet), `--name`, `--default-cwd`, `--dir`, `--admin-password`, `--no-bootstrap-token`, `--tls`.
Re-running `install.sh` upgrades the code. Tokens, the admin password, plugins and jobs are kept.

## Tokens

* Format `mva_<43 random chars>`. Only the sha256 hash is stored, in `tokens.json`. The plaintext is
  shown once, when the token is created.
* Each token has a name, scopes (`rest`, `mcp`), and an optional expiry. Revoking a token takes
  effect on the next request.
* `audit.log` tags every action with the name of the token that did it, e.g.
  `[semaphore] RUN cmd=...`.

From a shell on the VM:

```bash
cd ~/mcp-vm-installer-agent
venv/bin/python server.py token list
venv/bin/python server.py token create ci --scopes rest --ttl-days 30
venv/bin/python server.py token revoke <id>
venv/bin/python server.py admin-password          # reset the UI password
```

## REST API

```bash
H="Authorization: Bearer $TOKEN"
curl -H "$H" http://VM:8765/api/info
curl -H "$H" -X POST http://VM:8765/api/run  -d '{"command":"uptime","timeout_seconds":60}'
curl -H "$H" -X POST http://VM:8765/api/jobs -d '{"command":"long-task.sh"}'
curl -H "$H" http://VM:8765/api/jobs/<job_id>?tail=50
curl -H "$H" -X DELETE http://VM:8765/api/jobs/<job_id>
```

## File API (2.1.0+)

Used by the control centre's file browser ([mcp-vm-admin-agent](../mcp-vm-admin-agent/)). Scope `rest`.
Paths are absolute, or relative to `--default-cwd`. Every call is in `audit.log`.

| Call | Does |
|---|---|
| `GET /api/fs/list?path=&hidden=1` | directory entries: name, type, size, mode, owner, mtime, symlink target (max 5000) |
| `GET /api/fs/stat?path=` | one entry |
| `GET /api/fs/read?path=&max_bytes=` | text content (up to 5 MB) or `binary: true` |
| `GET /api/fs/download?path=` | raw bytes |
| `PUT /api/fs/file?path=&overwrite=1&backup=1&mkdirs=0` | write the request body atomically; keeps `<file>.bak-<ts>`, keeps the old mode; max `AGENT_MAX_UPLOAD_MB` (100) |
| `POST /api/fs/mkdir` `{"path"}` | create a directory (and parents) |
| `POST /api/fs/rename` `{"from","to","overwrite"}` | rename or move |
| `DELETE /api/fs?path=&recursive=0` | delete; refuses `/`, top-level system directories, `$HOME` and the agent's own directory |

`/health` and `server_info` now list `"features": ["fs"]` so clients can tell what an agent supports.

## Terminal API (2.2.0+)

Interactive shells for the control centre's Shell tab. Scope `rest`. Each session is `bash -l` in a
pseudo-terminal as the service user. Opening and closing are audited; keystrokes are not.

| Call | Does |
|---|---|
| `POST /api/pty` `{"cwd","cols","rows"}` | start a session, returns `{"id"}` |
| `GET /api/pty` | list sessions |
| `GET /api/pty/{id}/output?offset=&wait=` | output after `offset` (base64), long-polls up to `wait` seconds; also `closed`, `exit_code` |
| `POST /api/pty/{id}/input` `{"b64"}` or `{"data"}` | send keystrokes |
| `POST /api/pty/{id}/resize` `{"cols","rows"}` | resize |
| `DELETE /api/pty/{id}` | close |

`AGENT_PTY_MAX` (default 8) limits concurrent sessions; `AGENT_PTY_IDLE_S` (default 1800) closes idle
ones. `features` now includes `"pty"`.

## MCP tools

`run_command`, `start_job`, `job_status`, `list_jobs`, `kill_job`, `read_file`, `write_file`, `list_dir`,
`install_plugin`, `remove_plugin`, `list_plugins`, `call_plugin`, `update_server`, `server_info`.

## Security notes

* Every token is effectively a shell as the service user. Give each consumer its own token, set an
  expiry, and revoke tokens you no longer use.
* The default transport is plain HTTP, which is fine on a trusted LAN. Use `--tls` for a self-signed
  HTTPS certificate. MCP clients then have to trust it, for example with
  `NODE_EXTRA_CA_CERTS=~/agent-cert.pem`.
* The UI is reachable only from loopback and the `--allow` networks. Failed logins are rate limited,
  and the session cookie is HttpOnly and SameSite=Strict. State-changing calls also require a custom
  header, which blocks CSRF.

## Uninstall

```bash
./uninstall.sh            # remove service, keep data
./uninstall.sh --purge    # also delete ~/mcp-vm-installer-agent
```
