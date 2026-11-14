---
name: security-best-practices
description: Security best practices for the ai-labs homelab — secrets handling, SSH host-key trust, supply chain (pinned versions, checksums, image scanning), Kubernetes pod security and RBAC, Spring Boot app security with Keycloak, Ansible/Semaphore playbooks, Harbor, and the MCP agents that run commands on VMs. Use this whenever code, config, playbooks, manifests or scripts touch credentials, tokens, passwords, keys, SSH, TLS, authentication/authorization, network exposure, downloads/installers, container images, RBAC, or when the user pastes a secret, asks to commit, or asks for a security review — even if they don't say "security".
---

# Security best practices (ai-labs)

Goal: a lab that can be rebuilt, shared and pushed to GitHub without leaking credentials, where every
remote thing we trust (hosts, downloads, images) was verified, and where a compromised component can do
as little as possible. Some lab trade-offs are accepted (Harbor over plain HTTP); they must be explicit,
documented and contained — never silent.

## 1. Secrets — the most common failure

**Where secrets live:** the password manager (Lab / …), Kubernetes Secrets, or files generated on the host
that uses them (mode 0600, root-owned). The Harbor playbook is the pattern: passwords generated on the VM
into `/root/harbor/`, read with `slurp` under `no_log`, never printed.

**Where they never go:** the git repo (including examples, READMEs, test fixtures, commit messages),
ConfigMaps, image layers, command lines (`ps` and shell history see them), logs, MCP tool output, chat.

**Habits that prevent leaks:**
- Before every commit: `git diff --cached | grep -nE '(password|secret|token|BEGIN .*PRIVATE)'`, and grep for
  any specific secret value seen in this session. Don't commit if it matches; ask.
- Pass secrets via stdin or files: `docker login -u 'robot$x' --password-stdin`, `read -rs TOKEN`,
  `kubectl create secret ... --from-file=` or `--dry-run=client -o yaml | kubectl apply -f -`.
- Single-quote names containing `$` (`'robot$infracanary+robo'`) so the shell doesn't expand them.
- In Ansible: `no_log: true` on any task that handles a secret (including `copy` of a rendered config).
- In apps: never log request headers/bodies wholesale; mask tokens; `server.error.include-stacktrace=never`.

**When a secret is exposed** (pasted in chat, committed, logged): say so plainly, keep it out of the repo,
and recommend rotation (Harbor: robot → *Refresh secret*, then update every `harbor-pull` Secret and CI
credential). If it was committed, rotating matters more than rewriting history.

## 2. Trust: hosts, downloads, images

- **SSH host keys.** Never `StrictHostKeyChecking=no`, never blindly `ssh-keyscan >> known_hosts`, never
  `ssh-keygen -R` to make a warning go away. Get the fingerprint from the host's console
  (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`) or the user, then compare:
  `ssh-keyscan -t ed25519 HOST | ssh-keygen -lf -` and add only on an exact match. A "REMOTE HOST
  IDENTIFICATION HAS CHANGED" warning means stop and ask whether the VM was rebuilt.
- **Downloads.** Pin the version and verify a checksum (`get_url: checksum: sha256:...`) or signature;
  apt repos with `signed-by=` keyrings. No `curl | bash` — download, read, then run.
- **Images.** Pin tags (or digests) — never `latest` in deployments; base images from official sources;
  keep Trivy scanning on in Harbor and look at criticals before deploying; build as non-root (uid 10001).
- **Dependencies.** Keep Spring Boot on a supported patch line; `mvn versions:display-dependency-updates`
  periodically; don't add libraries for trivial things.

## 3. Least privilege

- **Kubernetes pods:** restricted Pod Security Standard — non-root, no privilege escalation, drop ALL
  capabilities, read-only root filesystem, seccomp RuntimeDefault (template in the
  `kubernetes-best-practices` skill). Label namespaces
  `pod-security.kubernetes.io/enforce: restricted` where workloads comply.
- **RBAC:** a ServiceAccount per app that needs the API, with a namespaced Role listing exact verbs and
  resources; no `cluster-admin` for apps or agents; `automountServiceAccountToken: false` otherwise.
  mcp-kube-agent's policy (read-only mode, allowed/protected namespaces) sits on top of RBAC — keep both.
- **Network:** default-deny NetworkPolicies per namespace; LoadBalancer IPs only for things people must
  reach from the LAN; actuator limited to `health,info,prometheus`.
- **Hosts:** `become` only on tasks that need root; dedicated service users; Harbor robots per project
  with only push/pull (prefer a separate pull-only robot for clusters).
- **MCP agents:** one token per agent/client, revocable from the admin UI; agents run as an unprivileged
  user; mutating tools audited; destructive operations confirmed by a human.

## 4. Authentication and authorization (apps)

- SSO through Keycloak realm `lab` (OIDC). APIs are resource servers validating JWTs from
  `https://192.168.1.143:8443/realms/lab`; check roles/scopes per endpoint; deny by default.
- Compare secrets and tokens in constant time (`MessageDigest.isEqual`), never `String.equals`.
- Dangerous features (chaos, admin, debug endpoints) default **off**, are enabled per environment by
  config, require a token or role when on, and are logged on every use — infra-canary's `/chaos` does this.
- Validate all input at the boundary; parameterised queries only; bound sizes and counts.
- TLS wherever a service handles credentials (Keycloak, Grafana, Semaphore). Where plain HTTP is
  accepted (Harbor in the lab), say so in the docs and keep it on the trusted LAN; don't expose it.

## 5. Operational safety (this lab's habits)

- **Back up before mutating state you don't own**: `pg_dump` the Semaphore tables before editing its DB
  (backups in `/tmp/semaphore-*.sql`), `cp file file.bak.$(date +%F)` before editing node configs.
- **Confirm before outward-facing or hard-to-reverse actions:** applying to the cluster, `helm upgrade`,
  formatting disks (only when verified blank, as the Harbor playbook's data-disk step checks), deleting
  volumes, pushing to GitHub, rotating credentials others use.
- **Prefer reproducible changes** (playbooks, manifests in git) over hand edits; when a hand edit is
  needed, make the repo match afterwards so the next run doesn't undo or contradict it.

## Review output

For a security review, list findings by severity — **critical** (exposed secret, unauthenticated
mutation, RCE path), **high**, **medium**, **low/hardening** — each with file:line, the concrete risk
(who could do what), and the fix. State explicitly when a risk is an accepted lab trade-off rather than
silently skipping it. If you find a live secret, report its location without repeating its value.
