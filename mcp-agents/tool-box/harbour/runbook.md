# Harbor runbook

The lab's private container registry. Images are pushed here by developers and CI, and the Kubernetes
cluster (proxmox01) pulls them from here.

| | |
|---|---|
| Portal | http://192.168.1.173/harbor/projects |
| Registry host (in image names) | `192.168.1.173` |
| Version | Harbor v2.15.5 + Trivy, database auth (`auth_mode: db_auth`); VM `docker-registry-harbor`, data on `/data` (60G disk) |
| Install / reinstall | Semaphore template `harbor` (playbook `semaphore/playbooks-library/registry/harbor.yml`); admin password: `sudo cat /root/harbor/admin-password` on the VM |
| Protocol | **plain HTTP** (no TLS): every client must be told the registry is insecure, see below |
| Health | `curl -s http://192.168.1.173/api/v2.0/health` (all components `healthy`) |

```
 developer / CI ──docker push / jib──▶ Harbor 192.168.1.173/<project>/<image>:<tag>
                                              │
 Kubernetes node: containerd ◀──pull (HTTP)───┘   with the namespace's imagePullSecret (robot account)
```

Image names are always `192.168.1.173/<project>/<repository>:<tag>`, e.g. `192.168.1.173/infracanary/infra-canary:0.1.0`.

---

## 0. Harbor's own hostname must be 192.168.1.173

Harbor writes its configured `hostname` into the registry's login challenge. Every client (docker,
Jib, containerd) is sent there to get a token before it may push or pull:

```bash
curl -sI http://192.168.1.173/v2/ | grep -i www-authenticate
# good: Bearer realm="http://192.168.1.173/service/token",service="harbor-registry"
# bad:  Bearer realm="http://192.168.0.90/service/token",...   <- old address, unreachable
```

With the old address, `docker login`/`push`, Jib and Kubernetes pulls all fail even though the portal
works; the portal's *Push command* hints also show the old address (`docker tag ... 192.168.0.90/...`).
Fix it on the Harbor VM, in the directory of the Harbor installer (the one with `harbor.yml`,
`prepare` and `docker-compose.yml`):

```bash
cd <harbor installer dir>                       # e.g. ~/harbor or /opt/harbor
grep -nE '^(hostname|external_url):' harbor.yml
sudo cp harbor.yml harbor.yml.bak-$(date +%F)
sudo sed -i 's/^hostname: .*/hostname: 192.168.1.173/' harbor.yml
# if an external_url line is set (uncommented), point it at http://192.168.1.173 too
sudo docker compose down                        # registry is offline from here...
sudo ./prepare                                  # regenerates the configs under ./common/config
sudo docker compose up -d                       # ...to here (about a minute)
curl -sI http://192.168.1.173/v2/ | grep -i www-authenticate      # realm now 192.168.1.173
curl -s http://192.168.1.173/api/v2.0/health                      # all healthy
```

Images, projects, users and robots live in Harbor's data volume (`data_volume` in `harbor.yml`,
default `/data`), which `down` / `prepare` / `up` keep. Give Harbor a DHCP reservation (or a static
IP) so the address does not change again.

---

## 1. Projects and robot accounts

**Project** = a namespace for repositories, with its own members, robots and visibility.

1. Portal → *Projects* → **New Project**: name (e.g. `infracanary`), *Access level* **private** (leave
   *Public* unchecked), storage quota optional.
2. Robot account for CI and for Kubernetes pulls. Two kinds:

   | Kind | Created in | Login name | Use |
   |---|---|---|---|
   | Project robot | *Project → Robot Accounts → New* | `robot$<project>+<name>` | one project (preferred) |
   | System robot | *Administration → Robot Accounts → New* | `robot$<name>` | several projects |

   Permissions: **Repository: push + pull** for CI; a separate pull-only robot for clusters is better.
   Copy the secret when it is shown: Harbor shows it **once** (later only *Refresh secret*).
3. Store the secret in the password manager (**Lab / Harbor / robot$<name>**), never in a repository.

The lab robot today: `robot$infracanary+robo` (project robot of `infracanary`). The project `infracanary` (used by
[infra-canary](../../../infra-canary/)) must exist and the robot needs push + pull on it.

**Rotate a robot secret**: robot → ⋮ → *Refresh secret*, then update the password manager, CI
credentials and every `harbor-pull` Secret in Kubernetes (section 4).

**Check what a robot may do** (the registry token lists the granted actions):

```bash
read -rsp 'robot secret: ' HARBOR_TOKEN; echo
curl -s -u 'robot$infracanary+robo':"$HARBOR_TOKEN" \
  'http://192.168.1.173/service/token?service=harbor-registry&scope=repository:infracanary/infra-canary:push,pull' \
  | python3 -c 'import json,sys,base64; t=json.load(sys.stdin)["token"].split(".")[1]; t+="="*(-len(t)%4); print(json.loads(base64.urlsafe_b64decode(t))["access"])'
# [{'type': 'repository', 'name': 'infracanary/infra-canary', 'actions': ['pull', 'push']}]   <- good
# 'actions': []  -> project missing, no permission, or wrong secret: Harbor then answers as anonymous
#                   (still HTTP 200), so an empty list is the only sign
```

---

## 2. Docker: add the registry, log in, push, pull

Docker refuses plain-HTTP registries unless they are listed as insecure. Do this once on every machine
that runs `docker push` / `docker pull` against Harbor (developer laptops, CI agents).

### 2.1 Mark the registry insecure

**Linux (Docker Engine)**: `/etc/docker/daemon.json`. Merge with what is there (the file must stay valid
JSON), then restart Docker:

```bash
sudo test -f /etc/docker/daemon.json && sudo cat /etc/docker/daemon.json     # look first
sudo python3 - <<'EOF'
import json, os
p = "/etc/docker/daemon.json"
d = json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {}
r = d.setdefault("insecure-registries", [])
if "192.168.1.173" not in r: r.append("192.168.1.173")
json.dump(d, open(p, "w"), indent=2)
print(json.dumps(d, indent=2))
EOF
sudo systemctl restart docker      # running containers stop, unless "live-restore": true is set
docker info | grep -A3 'Insecure Registries'
#  Insecure Registries:
#   192.168.1.173
#   127.0.0.0/8
```

On a machine with no `daemon.json`, the result is simply:

```json
{
  "insecure-registries": ["192.168.1.173"]
}
```

**Docker from snap (Ubuntu, `snap list docker` shows it)**: the file is
`/var/snap/docker/current/config/daemon.json` (same content), and the restart is `sudo snap restart docker`.

**Docker Desktop (Windows / macOS)**: *Settings → Docker Engine*, add
`"insecure-registries": ["192.168.1.173"]` to the JSON, *Apply & restart*.

**Podman / Buildah**: `/etc/containers/registries.conf.d/harbor.conf`:

```toml
[[registry]]
location = "192.168.1.173"
insecure = true
```

### 2.2 Log in

**Before the first login**, check the two things that make it fail:

```bash
docker info | grep -A3 'Insecure Registries'                     # 1. lists 192.168.1.173 (2.1)
curl -sI http://192.168.1.173/v2/ | grep -i www-authenticate       # 2. realm="http://192.168.1.173/service/token" (0)
```

**Log in with the bare host**: no `http://`, no trailing `/`, no path. Docker always tries HTTPS on
port 443 first and only uses HTTP for a host that is in `insecure-registries`. Writing `http://` does
not change that, and with a prefix Docker can store the login under a different key than the image
names use.

Interactive, with your own Harbor user (prompts for the password):

```bash
docker login 192.168.1.173
# Username: <your Harbor user>
# Password:
# Login Succeeded
```

Scripts and CI: a robot account, secret on stdin so it never appears in the shell history or `ps`:

```bash
read -rsp 'robot secret: ' HARBOR_TOKEN; echo
echo "$HARBOR_TOKEN" | docker login 192.168.1.173 -u 'robot$infracanary+robo' --password-stdin
# Login Succeeded
```

- Single quotes around `robot$...` matter: unquoted, the shell expands `$harbor` to an empty string.
- Use `admin` only for administration in the portal, not for pushing; give people their own users and
  CI a robot (section 1).
- `sudo docker login` and `docker login` store credentials for different users (root vs you): log in
  as the same user who will run `docker push`.

Where the login is kept and how to check it:

```bash
grep -A2 '"192.168.1.173"' ~/.docker/config.json     # "auths": {"192.168.1.173": {"auth": "..."}}
docker logout 192.168.1.173                           # removes it
```

`~/.docker/config.json` holds the credential base64-encoded, **not encrypted**: on shared machines log
out afterwards, or install a credential helper (`docker-credential-pass` / `secretservice`) and set
`"credsStore"` in that file.

**Login errors**

| Message | Cause / fix |
|---|---|
| `Get "https://192.168.1.173/v2/": dial tcp 192.168.1.173:443: connect: connection refused` | Docker tried HTTPS: `192.168.1.173` is not in `insecure-registries`, or Docker was not restarted after the change (2.1). Typing `http://` does not help |
| `Error logging in to endpoint, trying next endpoint ... https://192.168.1.173` (info line) followed by success | normal: HTTPS was tried first, then HTTP |
| hangs, then `Get "http://192.168.0.90/service/token...": ... i/o timeout` (or `no route to host`) | Harbor's `hostname` is stale; the token realm points to the old address (0) |
| `unauthorized: authentication required` / `401 Unauthorized` | wrong user or password; for robots, the full name `robot$...` in single quotes and the current secret |
| `http: server gave HTTP response to HTTPS client` | same as the first row |

### 2.3 Tag, push, pull

```bash
docker build -t 192.168.1.173/infracanary/infra-canary:0.1.0 .          # build with the full name, or
docker tag infra-canary:0.1.0 192.168.1.173/infracanary/infra-canary:0.1.0   # tag an existing image
docker push 192.168.1.173/infracanary/infra-canary:0.1.0
docker pull 192.168.1.173/infracanary/infra-canary:0.1.0                 # from another machine
```

The pushed image shows under *Projects → infracanary → Repositories → infra-canary*, with tags, digest, size
and (if a scanner is configured) vulnerabilities.

### 2.4 Without Docker: Jib (Java projects)

Maven can build and push an image with no Docker daemon (used on the installer VM, which has no Docker):

```bash
mvn -B compile jib:build -DsendCredentialsOverHttp=true -Djib.to.auth.username='robot$infracanary+robo' -Djib.to.auth.password="$HARBOR_TOKEN"
# -DsendCredentialsOverHttp=true: Harbor is plain HTTP; without it Jib refuses with
# "Required credentials ... were not sent because the connection was over HTTP".
```

The pom needs `<allowInsecureRegistries>true</allowInsecureRegistries>` for plain HTTP
(see `infra-canary/pom.xml`).

---

## 3. Kubernetes nodes: let containerd pull over HTTP

The nodes run containerd, not Docker, so `daemon.json` does not apply. kubespray writes containerd's
registry list from `containerd_registries_mirrors` in
[`kubespray-vars.yml`](../semaphore/playbooks-library/kubernetes/inventories/proxmox01/kubespray-vars.yml):

```yaml
containerd_registries_mirrors:
  - prefix: docker.io                       # kubespray's default, repeated (this list replaces it)
    mirrors:
      - {host: https://registry-1.docker.io, capabilities: [pull, resolve], skip_verify: false}
  - prefix: 192.168.1.173                    # Harbor, plain HTTP
    mirrors:
      - {host: http://192.168.1.173, capabilities: [pull, resolve]}
```

which renders into `/etc/containerd/config.toml`:

```toml
[plugins."io.containerd.grpc.v1.cri".registry.mirrors."192.168.1.173"]
  endpoint = ["http://192.168.1.173"]
```

**Apply** (Semaphore, project *proxmox01*). The change must be pushed to the branch Semaphore checks out.

1. *Kubespray: deploy / converge* with CLI args `--tags=containerd --limit=kube-node-001` (one node
   first). It rewrites `config.toml` and restarts containerd; running pods keep running.
2. Check that node:
   ```bash
   ssh vishwas@192.168.1.104 "sudo grep -A1 '192.168.1.173' /etc/containerd/config.toml"
   ssh vishwas@192.168.1.104 "sudo crictl pull --creds 'robot\$infracanary+robo:<secret>' 192.168.1.173/infracanary/infra-canary:0.1.0"
   kubectl get node kube-node-001          # Ready
   ```
3. Same template with only `--tags=containerd` for every node.

Nodes added later with *Kubespray: add workers* get the setting automatically.

---

## 4. Kubernetes: pull secret per namespace

A private project needs credentials in every namespace that pulls from it:

```bash
read -rsp 'robot secret: ' HARBOR_TOKEN; echo
kubectl -n <namespace> create secret docker-registry harbor-pull \
  --docker-server=192.168.1.173 --docker-username='robot$infracanary+robo' --docker-password="$HARBOR_TOKEN"
```

Reference it in the pod spec:

```yaml
spec:
  imagePullSecrets:
    - name: harbor-pull
  containers:
    - image: 192.168.1.173/infracanary/infra-canary:0.1.0
```

or attach it to the namespace's default ServiceAccount so every pod gets it:

```bash
kubectl -n <namespace> patch serviceaccount default -p '{"imagePullSecrets":[{"name":"harbor-pull"}]}'
```

After rotating the robot secret, replace the Secret in place:

```bash
kubectl -n <namespace> create secret docker-registry harbor-pull --docker-server=192.168.1.173 \
  --docker-username='robot$infracanary+robo' --docker-password="$HARBOR_TOKEN" --dry-run=client -o yaml | kubectl apply -f -
```

---

## 5. End-to-end check: infra-canary

[`infra-canary`](../../../infra-canary/) exercises the whole path: build → push to Harbor → pull in
Kubernetes → logs on a Longhorn volume (`/app/logs/canary`). In short (details in its README):

```bash
cd infra-canary
docker build -t 192.168.1.173/infracanary/infra-canary:0.1.0 .
docker push 192.168.1.173/infracanary/infra-canary:0.1.0
kubectl apply -f k8s/00-namespace.yaml
kubectl -n infra-canary create secret docker-registry harbor-pull --docker-server=192.168.1.173 \
  --docker-username='robot$infracanary+robo' --docker-password="$HARBOR_TOKEN"
kubectl apply -f k8s/
kubectl -n infra-canary rollout status deploy/infra-canary
kubectl -n infra-canary port-forward svc/infra-canary 8080:8080 & curl -s localhost:8080/canary   # "status":"OK"
```

---

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `http: server gave HTTP response to HTTPS client` from docker | registry not in `insecure-registries`, or Docker not restarted (2.1) |
| same message in `kubectl describe pod` events | containerd on that node has no `http://192.168.1.173` mirror (3) |
| `docker login` fails with `Get "https://192.168.1.173/v2/"` | same as the first row |
| `unauthorized to access repository` on push | robot lacks push on the project, or the project does not exist (1) |
| `denied: requested access to the resource is denied` | wrong project in the image name, or `robot$...` not single-quoted in the shell |
| `ErrImagePull` / `ImagePullBackOff` with `401` / `pull access denied` | no `harbor-pull` Secret in the namespace, not in `imagePullSecrets`, or stale after rotation (4) |
| `not found` / `manifest unknown` | that tag was never pushed: check *Repositories* in the portal |
| robot token check shows `'actions': []` | project missing, no permission, or wrong secret (1) |
| login/push/pull try `192.168.0.90` (or time out) although you used `192.168.1.173` | Harbor's `hostname` is stale: the token realm points to the old address (0) |

```bash
kubectl -n <ns> describe pod <pod> | sed -n '/Events/,$p'        # pull errors
kubectl -n <ns> get secret harbor-pull -o jsonpath='{.data.\.dockerconfigjson}' | base64 -d   # inspect (shows the secret)
```
