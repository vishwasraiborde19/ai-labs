# ai-labs

| Project | What it is |
|---|---|
| [mcp-vm-installer-agent](mcp-agents/mcp-vm-installer-agent/) | Agent you install on a VM so Claude Code (MCP) and scripts (REST) can run commands, jobs and file operations there. Includes a web UI for generating and revoking tokens. |
| [semaphore-installer](mcp-agents/semaphore-installer/) | One script that sets up Semaphore UI with PostgreSQL, Ansible, Terraform and OpenTofu on Ubuntu 22.04. |
| [mcp-devops-agent](mcp-agents/mcp-devops-agent/) | Java MCP server for CI/CD: generates and runs Jenkins pipelines (build, Docker Hub push, Kubernetes deploy, health check), with rollback and troubleshooting tools. Credentials come from a configurable access server. |
