# DevOps Agent: Skills

The DevOps agent owns CI/CD. It reaches Jenkins, Docker Hub and Kubernetes through configured,
named resources. It gets every credential at runtime from the access server and never shows one
to the model.

Every skill below maps to MCP tools in `mcp-devops-agent` (Java 21, Spring AI MCP server).

| # | Skill | Requirement | Tools |
|---|---|---|---|
| 1 | [Access & credentials](#1-access--credentials) | access Jenkins and other resources, configurable, passwords from an access server | `devops_list_resources`, `devops_check_access` |
| 2 | [Jenkins access](#2-jenkins-access) | the agent can access Jenkins | `jenkins_list_jobs`, `jenkins_job_info`, `jenkins_get_job_config`, `jenkins_trigger_build`, `jenkins_build_status`, `jenkins_console_log`, `jenkins_stop_build` |
| 3 | [Build a pipeline](#3-build-a-pipeline) | build a pipeline: build, Docker Hub, Kubernetes, health check | `pipeline_generate_jenkinsfile`, `pipeline_create_job`, `pipeline_create_scm_job` |
| 4 | [Build project](#4-build-project) | build project | `pipeline_run` (Build and Test stages) |
| 5 | [Deploy to Docker Hub](#5-deploy-to-docker-hub) | deploy to Docker Hub | pipeline stage *Docker Build & Push*, `dockerhub_image_exists`, `dockerhub_list_tags`, `dockerhub_repository_info` |
| 6 | [Deploy to Kubernetes](#6-deploy-to-kubernetes) | deploy to Kubernetes cluster | pipeline stage *Deploy to Kubernetes*, `k8s_set_image`, `k8s_deployment_status`, `k8s_list_deployments` |
| 7 | [Run health check](#7-run-health-check) | run health check | pipeline stage *Health Check*, `health_check_http`, `release_verify` |
| 8 | [Rollback & recovery](#8-rollback--recovery) | *added* | `k8s_rollback`, `k8s_restart`, `k8s_scale`, pipeline `post { failure }` |
| 9 | [Troubleshooting](#9-troubleshooting) | *added* | `jenkins_console_log`, `k8s_pods`, `k8s_pod_logs`, `k8s_events` |
| 10 | [Credential provisioning](#10-credential-provisioning) | *added* | `jenkins_sync_credential` |

---

## 1. Access & credentials

**Goal:** connect to every resource through configuration, with no secrets in code, prompts or
tool output.

- Resources are named entries in `~/.devops-agent/systems.yml`: `jenkins[]`, `dockerhub[]`,
  `kubernetes[]`. They are managed in the **admin UI** (`--spring.profiles.active=admin`, `/admin`),
  which can test a connection and check secret references before saving. Running agents reload the
  file on change. Tools take the name; leaving it out picks the first one.
- Credentials are **secret references**, resolved when needed:

  | Ref | Source |
  |---|---|
  | `access:jenkins/main/api-token` | **access server** (default) |
  | `vault:apps/jenkins#token` | HashiCorp Vault KV v2 |
  | `file:jenkins/main/api-token` | local `secrets.yml` (until the access server exists) |
  | `env:JENKINS_TOKEN` | environment variable |
  | `path:/etc/ssl/k8s-ca.pem` | file contents |

- Values are cached for `cache-ttl` (default 5m), so a password rotated on the access server takes
  effect without a restart. `devops_check_access refresh=true` forces a re-fetch.
- **Guardrails:** no tool returns a secret. Every resolved value is also masked as `****` in all
  tool output and error messages. The agent's own token for the access server can only come from
  `env:` or `path:`.

**Done when:** `devops_check_access` reports `ok: true` for every resource.

## 2. Jenkins access

**Goal:** read and operate Jenkins jobs.

- List jobs and folders, inspect a job and its parameters, read `config.xml`.
- Trigger builds with parameters, follow the queue to a build number, read status and stages, read
  the console log, stop a build.
- Folder jobs are written `team/service`.
- **Auth:** a Jenkins user with an API token (`token-ref`). CSRF crumbs are handled automatically.
- **Guardrails:** `read-only: true` on a Jenkins server blocks trigger, stop, job and credential changes.

## 3. Build a pipeline

**Goal:** create a working CI/CD pipeline for a repository in a single step.

Inputs (`PipelineSpec`): `gitUrl`, `image`, plus optional branch, build tool
(`maven|gradle|npm|go|none`), custom build/test commands, Dockerfile, namespace, deployment,
container, manifests path, health URL and retries, rollback flag, and agent label.

Generated declarative pipeline:

```
Checkout -> Build -> Test -> Docker Build & Push -> Deploy to Kubernetes -> Health Check
                                                        \__ post failure: kubectl rollout undo
```

- Image tag: `BUILD_NUMBER-<git short sha>`, with `latest` optional.
- Two ways to install it:
  - `pipeline_create_job` stores the Jenkinsfile inline, sandboxed.
  - `pipeline_generate_jenkinsfile` returns the text to commit; then `pipeline_create_scm_job`
    points a job at the repo.
- **Guardrails:** every input is validated (DNS names, image refs, URLs, credential ids). Values are
  passed as single-quoted Groovy literals, never interpolated. Secrets reach the pipeline only as
  Jenkins credential bindings.

## 4. Build project

**Goal:** compile and test the project in Jenkins.

- `pipeline_run` triggers the job, waits for it to leave the queue and finish (up to 1h), and
  returns the result and stage timings. If the build did not succeed, it also returns the last 120
  lines of the console log.
- Defaults per tool: Maven (`./mvnw` if present), Gradle (`./gradlew`), npm (`npm ci`), Go.

**Done when:** result is `SUCCESS` and the Build and Test stages are green.

## 5. Deploy to Docker Hub

**Goal:** publish the image and prove it is there.

- Jenkins builds and pushes using the `dockerhub` credential (username + access token).
- `dockerhub_image_exists repository tag` confirms the push: digest, size, platforms and time.
- `dockerhub_list_tags` shows recent tags, for example to pick a version to roll back to.
- Private repos need a `dockerhub[]` entry with `password-ref` (a Docker Hub personal access token).

## 6. Deploy to Kubernetes

**Goal:** roll the new image out and wait until it is live.

- The pipeline runs `kubectl apply -f <manifests>` (optional), then `kubectl set image` and
  `kubectl rollout status --timeout=300s`.
- The agent can also deploy directly: `k8s_set_image` patches the container image and waits for the
  rollout; `k8s_deployment_status` reports `complete | progressing | failed | timeout`.
- **Auth:** a service account token per cluster (see `config/k8s-rbac.yaml`) and the cluster CA.
- **Guardrails:** `allowed-namespaces` per cluster; `read-only: true` clusters (for example prod)
  can be inspected but not changed.

## 7. Run health check

**Goal:** decide whether the release is healthy.

- In the pipeline: curl the health URL N times until it returns the expected status, else fail and
  roll back.
- `health_check_http`: retries, expected status, optional body text (for example `"UP"`), and
  latency per attempt.
- `release_verify` gives the complete verdict: rollout complete, all pods ready, expected image
  running, and the health URL green, then `HEALTHY` or `NOT HEALTHY`.
- **Guardrails:** `health.allowed-hosts` limits which hosts can be probed.

## 8. Rollback & recovery

- `k8s_rollback` goes to the previous revision, or to `toRevision`, exactly like
  `kubectl rollout undo`.
- `k8s_restart` does a rolling restart; `k8s_scale` sets the replica count (0 to 50).
- The pipeline rolls back automatically when the deploy or health check fails.

## 9. Troubleshooting

When a pipeline fails, follow the evidence:

1. `jenkins_build_status` tells you which stage failed.
2. `jenkins_console_log` shows the end of that stage's output.
3. If the deploy is stuck: `k8s_pods` shows `CrashLoopBackOff`, `ImagePullBackOff` and similar.
4. `k8s_events` and `k8s_pod_logs` (with `previous=true` for crashed containers) show why.
5. Fix forward, or use `k8s_rollback`, then `release_verify`.

## 10. Credential provisioning

**Goal:** give Jenkins the credentials a pipeline needs without a human pasting secrets.

- `jenkins_sync_credential` pulls a secret from the access server and creates or updates a Jenkins
  credential (`usernamePassword` or `secretText`). The value is never returned.
- **Guardrails:** only refs listed in that Jenkins server's `credential-sync-allow` can be synced.
  An empty list disables the tool.

---

## Typical flow

```
devops_list_resources
devops_check_access
pipeline_create_job      jenkins=main job=shop spec={gitUrl, image: acme/shop, namespace: dev, healthUrl}
pipeline_run             job=shop
dockerhub_image_exists   repository=acme/shop tag=<BUILD-sha>
release_verify           cluster=dev namespace=dev deployment=shop expectedImage=acme/shop:<BUILD-sha>
```

## Roadmap (not built yet)

- Approval gate for prod: a tool needs an approval id from a human, or Jenkins `input` steps.
- Multi-environment promotion: dev, then staging, then prod using the same image digest.
- Server-side apply of manifests and Helm/Kustomize support from the agent itself.
- Image scanning (Trivy) and SBOM as a pipeline stage, plus a policy check before deploy.
- Other registries (Harbor, ECR, GHCR) and GitHub/GitLab webhooks.
- Short-lived credentials: have the access server mint scoped, expiring tokens per request.
