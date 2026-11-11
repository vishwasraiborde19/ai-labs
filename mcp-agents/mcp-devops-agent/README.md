# mcp-devops-agent

An MCP server in Java (Spring Boot 3.5, Spring AI 1.1) that gives Claude a CI/CD toolbox:
- create Jenkins pipelines that build a project, push the image to Docker Hub, deploy it to
  Kubernetes and run a health check;
- run those pipelines and verify the release;
- roll back and troubleshoot when something breaks.

Credentials are pulled at runtime from an **access server** (or Vault, a local file, or the
environment) and are never returned to the model. See [skills.md](skills.md) for what the agent can
do and which tool covers each skill.

## Build

Needs JDK 21 and Maven 3.9+.

```bash
mvn -q package            # runs the unit tests, produces target/mcp-devops-agent.jar
```

## Configure

Two files in `~/.devops-agent/` (override with `DEVOPS_AGENT_HOME`):

| File | Holds | Edited |
|---|---|---|
| `config.yml` | where secrets come from (access server, Vault), admin UI and HTTP settings | by hand, read at startup |
| `systems.yml` | Jenkins servers, Docker Hub accounts, Kubernetes clusters, health-check hosts | in the **admin UI**; running agents reload it within a second |

```bash
mkdir -p ~/.devops-agent
cp config/config.example.yml ~/.devops-agent/config.yml        # access server URL, token env var
# Until the access server exists, keep secrets in a local file and use file: refs:
cp config/secrets.example.yml ~/.devops-agent/secrets.yml && chmod 600 ~/.devops-agent/secrets.yml
```

Each credential is a reference, not a value:

| Ref | Source |
|---|---|
| `access:<key>` | access server (default scheme) |
| `vault:<path>#<field>` | HashiCorp Vault KV v2 |
| `file:<key>` | `~/.devops-agent/secrets.yml` |
| `env:<NAME>` | environment variable |
| `path:<file>` | file contents (CA certs, mounted tokens) |

For Kubernetes, create a namespace-scoped service account with [config/k8s-rbac.yaml](config/k8s-rbac.yaml).

## Admin UI

```bash
java -jar target/mcp-devops-agent.jar --spring.profiles.active=admin       # http://127.0.0.1:8766/admin
```

How people sign in is set by `devops.admin.auth` in `config.yml`:

| `auth` | Sign-in | Who can open the UI |
|---|---|---|
| `none` (default) | none | only this machine (loopback); use an SSH tunnel from elsewhere |
| `password` | local admin password | `devops.admin.allowed-networks` |
| `saml` | planned, not built yet | the agent refuses to start with this value until it exists |

In `password` mode the first start prints a generated password once and stores only a PBKDF2 hash in
`admin.json`. To reset it: `java -jar target/mcp-devops-agent.jar admin-password` (prompts, or reads
`DEVOPS_ADMIN_PASSWORD`). Passwords must be at least 12 characters; `devops.admin.min-password-length`
lowers that for throwaway labs (the command reads `DEVOPS_ADMIN_MIN_PASSWORD_LENGTH` instead).

In the UI you can:
- add, edit, duplicate and remove Jenkins servers, Docker Hub accounts and clusters, and edit the
  health-check host allowlist;
- **Test connection** on any entry, even before saving: it resolves the secrets, calls the system
  and reports who it authenticated as;
- **Check** a secret reference: it confirms the agent can resolve it, without ever showing the value;
- view the access server and Vault settings (read-only, from `config.yml`) and the saved `systems.yml`;
- change the admin password (in `password` mode).

Saves are validated, written atomically (the previous file is kept as `systems.yml.bak`) and
version-checked: if someone else saved first, you are asked to reload instead of overwriting their
change. A stdio agent already connected to Claude picks up the change without a restart.

Access is restricted:
- the server binds to 127.0.0.1 unless `DEVOPS_AGENT_HOST` is set, and with `auth: none` it accepts
  only local clients even then;
- API calls need an `X-Devops-Admin` header, so other websites cannot drive the UI (CSRF protection);
- in `password` mode: only `allowed-networks`, 5 failed logins lock that IP for 5 minutes, and the
  session cookie is HttpOnly and SameSite=Strict;
- a strict Content-Security-Policy applies;
- logins, failures and saves go to `audit.log`.

To reach it from another machine, prefer an SSH tunnel (`ssh -L 8766:127.0.0.1:8766 host`) over
opening the port.

`--spring.profiles.active=http,admin` serves MCP (`/mcp`, bearer token) and the UI from one process.

## Connect Claude Code

stdio (local, simplest):

```bash
claude mcp add devops -- java -jar $PWD/target/mcp-devops-agent.jar
```

Streamable HTTP (shared server; a bearer token is required):

```bash
export DEVOPS_AGENT_TOKEN=$(openssl rand -base64 32)
java -jar target/mcp-devops-agent.jar --spring.profiles.active=http      # 127.0.0.1:8766/mcp
claude mcp add --transport http devops http://HOST:8766/mcp --header "Authorization: Bearer $DEVOPS_AGENT_TOKEN"
```

Set `DEVOPS_AGENT_HOST=0.0.0.0` to listen beyond localhost. Put TLS in front of it (nginx or
Caddy) when it crosses a network.

## Access server contract

The agent calls `GET {url}{path-template}` with `{key}` replaced, for example:

```
GET /api/v1/secrets/jenkins/main/api-token
Authorization: Bearer <agent token>            # from devops.access.server.token-ref (env: or path:)
X-Agent-Name: mcp-devops-agent
X-Access-Purpose: jenkins:main                 # what the secret is for, for the server's audit log

200 {"value": "..."}       JSON; field name set by value-field (dot paths allowed, e.g. data.password)
200 text/plain             whole body is the value
404                        unknown key
401 / 403                  agent not allowed to read this key
```

The path template, the value field and any extra headers are all configurable, so an existing
secrets service can usually be plugged in without code changes. Anything else is one
`SecretProvider` class away.

## Tools

| Area | Tools |
|---|---|
| Access | `devops_list_resources`, `devops_check_access` |
| Jenkins | `jenkins_list_jobs`, `jenkins_job_info`, `jenkins_get_job_config`, `jenkins_trigger_build`, `jenkins_build_status`, `jenkins_console_log`, `jenkins_stop_build`, `jenkins_sync_credential` |
| Pipeline | `pipeline_generate_jenkinsfile`, `pipeline_create_job`, `pipeline_create_scm_job`, `pipeline_run`, `release_verify` |
| Docker Hub | `dockerhub_image_exists`, `dockerhub_list_tags`, `dockerhub_repository_info` |
| Kubernetes | `k8s_list_deployments`, `k8s_deployment_status`, `k8s_pods`, `k8s_pod_logs`, `k8s_events`, `k8s_set_image`, `k8s_restart`, `k8s_scale`, `k8s_rollback` |
| Health | `health_check_http` |

The generated pipelines expect Jenkins agents with `git`, `docker`, `kubectl` and `curl`. They also
expect the Pipeline, Git and Credentials Binding plugins; Pipeline Stage View is optional and only
adds stage summaries.

## Safety

- Secrets: never returned by tools. Every value the agent has resolved is masked as `****` in all
  output, including Jenkins and pod logs.
- `read-only` per Jenkins server and per cluster, and `allowed-namespaces` per cluster.
- `credential-sync-allow` per Jenkins server limits which secrets can be copied into Jenkins.
- `health.allowed-hosts` limits which hosts can be probed.
- Logs live in `~/.devops-agent/logs/`. `audit.log` records every tool call with its arguments
  (redacted), duration and outcome.
