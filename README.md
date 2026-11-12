# ai-labs

| Project | What it is |
|---|---|
| [mcp-vm-worker-agent](mcp-agents/vm/mcp-vm-worker-agent/) | Agent you install on a VM so Claude Code (MCP) and scripts (REST) can run commands, jobs and file operations there. Includes a web UI for generating and revoking tokens. |
| [mcp-vm-admin-agent](mcp-agents/vm/mcp-vm-admin-agent/) | Control centre for the worker agents: fleet dashboard, file browser and editor, terminal, background jobs, run-on-many-hosts, audit log and one-click agent upgrades, in a web UI and over MCP. |
| [semaphore-installer](mcp-agents/tool-box/semaphore/semaphore-installer/) | One script that sets up Semaphore UI with PostgreSQL, Ansible, Terraform and OpenTofu on Ubuntu 22.04. |
| [playbooks-library](mcp-agents/tool-box/semaphore/playbooks-library/) | Reusable playbooks for Semaphore, one folder per topic. [kubernetes/](mcp-agents/tool-box/semaphore/playbooks-library/kubernetes/): kubespray runner with a pinned version and its own Ansible (deploy, scale, upgrade, reset), MetalLB, node static IPs and checks, and a script that sets up the Semaphore project. |
| [Keycloak-installer](mcp-agents/tool-box/keycloak/keycloak-installer/) | One script that sets up Keycloak (OIDC/SAML single sign-on) with PostgreSQL and HTTPS on Ubuntu 22.04, for logging in to Semaphore, Kubernetes and the VM control centre. |
| [mcp-devops-agent](mcp-agents/mcp-devops-agent/) | Java MCP server for CI/CD: generates and runs Jenkins pipelines (build, Docker Hub push, Kubernetes deploy, health check), with rollback and troubleshooting tools. Credentials come from a configurable access server. |
| [mcp-kube-agent](mcp-agents/mcp-kube-agent/) | Java MCP server that manages Kubernetes clusters through a service account: list, describe, apply, patch and delete any resource (CRDs too), scale, roll out and roll back, logs, events, a troubleshooting sweep, node usage, cordon and drain. Agent policy (read-only, allowed/protected namespaces) on top of RBAC; Secret values never shown. |
