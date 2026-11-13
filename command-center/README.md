# command-center

One entry point for developers to every system, app and agent in the lab: where it is, how to log in,
whether it is up, how to use it, and how it fits with everything else.

```
 developer ──▶ command-center :8780/ui ──┬─ catalog.yml   systems, links, access, dependencies, flows
                                         ├─ health probes (HTTP/TLS) every 60 s
                                         └─ orchestrator :8770 ── agent registry (imported)
```

## What it does

| View | Features |
|---|---|
| **Home** | Search everything (`/` to focus), status tiles (up, down, no URL, agents), *Needs attention* (down systems, certificates expiring within 21 days), favourites (★, kept in your browser), a first-day checklist built from what the catalog contains, and areas at a glance. |
| **Catalog** | Every system as a card grouped by area (identity, delivery, platform, apps, data, observability, infrastructure, agents), filter by text, area, environment and status, one-click *Open*. |
| **System page** | All links (UI, API, docs, health, MCP, metrics, repo) with copy buttons; live status with latency, HTTP code, uptime over the last checks and certificate expiry; **Access**: login method, user, *where the credentials are* (never the secret) and how to get access; *Depends on* / *Used by*; the flows it is part of; and a **usage guide**: the built-in guide for its kind, filled in with this system's own URLs, plus your lab-specific notes. |
| **Agents** | The orchestrator and every MCP agent: status, MCP endpoint and a ready-to-copy `claude mcp add` command. Agents registered with the orchestrator are imported automatically (every 5 min, or *Sync*). |
| **How it fits** | Architecture diagram: areas as lanes from identity (left) to agents (right), dependencies as arrows, live status dots. Hover to see a system's connections. **Flows** (*Ship a change*, *Sign in once*, *Observe a service*, *Operate with AI agents*, *Provision the platform*) highlight their path step by step, with *Play*. Add and edit your own flows. |
| **Settings** | Catalog file and validation problems, download / import YAML (merge or replace), orchestrator status, health-check settings and the audit log. |

Built-in kinds with usage guides: Keycloak, password manager, Git, Jenkins, Semaphore, **Harbor**,
Argo CD, **Kubernetes**, **Spring Cloud Config Server**, Spring Boot service, PostgreSQL, Elasticsearch,
Kibana, OpenTelemetry Collector, Prometheus, Grafana, Proxmox VE, VM control centre, MCP agent,
MCP orchestrator, and *Other*. Guides live in [`kinds.yml`](src/main/resources/kinds.yml); placeholders
such as `{{ui}}`, `{{mcp}}` and `{{host}}` are replaced with each system's own links.

### Rules it keeps

- **No secrets.** Access says *where* a credential is ("Password manager: Lab / Grafana admin").
  A credential reference that looks like a password or token is rejected.
- **Health is reachability.** Any answer means up, including 401/403 (login required) and 404/405 on
  API or MCP endpoints. Down means refused, timed out or 5xx. Systems with self-signed certificates
  are probed with *insecure TLS* only when the entry says so.
- **References stay consistent.** Renaming a system updates every dependency and flow step pointing at
  it; deleting one removes them. Two people editing at once get a conflict instead of overwriting.
- **Edits only from this machine** by default (`edit-from-loopback-only`); others can browse when it
  listens on the LAN. Every change is in the audit log.

## Build and run

Java 21.

```bash
mvn package
java -jar target/command-center.jar          # http://127.0.0.1:8780/ui
```

On first start the lab catalog is copied to `~/.command-center/catalog.yml`. Edit it in the UI, or by
hand (changes are picked up automatically); keep it in Git if you want reviews.

| Env | Default | |
|---|---|---|
| `COMMAND_CENTER_HOME` | `~/.command-center` | `catalog.yml`, `audit.log`, `config.yml` |
| `COMMAND_CENTER_CONFIG` | `$COMMAND_CENTER_HOME/config.yml` | see [config/config.example.yml](config/config.example.yml) |
| `COMMAND_CENTER_HOST` / `COMMAND_CENTER_PORT` | `127.0.0.1` / `8780` | listen address |

To let the team browse it, listen on the LAN (`COMMAND_CENTER_HOST=0.0.0.0`): others get a read-only
view; editing stays on this machine until Keycloak sign-in is added.

The orchestrator's API only answers clients on its own machine, so agent import works when both run on
the same host (the setup `mcp-agents/agents.sh` creates).

## API

All under `/ui/api`, with the header `X-Command-Center: 1`.

| | |
|---|---|
| `GET /session`, `GET /catalog`, `GET /health` | kinds and categories; systems, flows, problems and health; health only |
| `POST /health/check[?id=]` | probe now |
| `POST /systems`, `PUT /systems/{id}`, `DELETE /systems/{id}` | `{system, version}`; the version guards against overwriting someone else's edit |
| `POST /flows`, `PUT /flows/{id}`, `DELETE /flows/{id}` | same for flows |
| `GET /export`, `POST /import?mode=merge\|replace` | catalog as YAML |
| `POST /integrations/orchestrator/sync`, `GET /audit` | |

## Roadmap

What a command center needs next, roughly in order:

1. **Sign in with Keycloak** (OIDC): roles for viewers and editors, so editing works from anywhere and
   the audit log names people.
2. **Password-manager deep links**: open the exact Vaultwarden / Vault entry from *Access*.
3. **More discovery**: VM fleet and Proxmox guests from the VM control centre, Kubernetes ingresses and
   services, Harbor projects, Grafana dashboards per service.
4. **MCP tools** (`cc_find_system`, `cc_how_to`): so Claude Code and the orchestrator's agents can look
   up URLs, owners and guides here instead of being told.
5. **Status history and alerts**: keep uptime across restarts, post state changes to Slack or a webhook,
   a status banner for planned maintenance and incidents.
6. **Environments**: the same system in dev / staging / prod side by side, with an environment switch.
7. **Ownership and on-call**: owner team, contact channel and runbook links per system.
8. **Per-service pages**: which platform pieces a service uses (its Harbor repo, namespace, config files,
   dashboards), generated from the catalog.
