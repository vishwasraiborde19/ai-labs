#!/usr/bin/env python3
"""
Create or update a Semaphore project for one Kubernetes inventory of this library: key, repository, inventory,
variable groups, views and task templates. Idempotent: objects are matched by name, so re-running
updates them in place (e.g. after switching the repository from a local path to GitHub).

    SEMAPHORE_URL=http://192.168.1.42:3000 SEMAPHORE_TOKEN=... \\
      python3 bootstrap.py --inventory proxmox01 --repo /opt/ai-labs

    --project NAME      Semaphore project (default: the inventory name)
    --repo URL          git URL, or a local path on the Semaphore server with the ai-labs layout
                        (default: https://github.com/vishwasraiborde19/ai-labs.git)
    --branch NAME       git branch (default: main)
    --dry-run           show what would change

The token is an API token of a Semaphore admin (user menu -> API tokens). Python standard library only.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

LIB = "mcp-agents/tool-box/semaphore/playbooks-library/kubernetes"


def catalog(inv: str):
    """Everything the project should contain. Paths are relative to the repository root."""
    ks = f"{LIB}/kubespray/kubespray.sh"
    return {
        "views": ["Kubespray", "Kubernetes", "Linux"],
        "environments": {
            "default": {"json": "{}", "env": "{}"},
            f"kubespray-{inv}": {"json": "{}", "env": json.dumps({"INVENTORY": inv})},
        },
        "templates": [
            # name, app, playbook, arguments, view, environment, description
            ("Linux: ping", "ansible", f"{LIB}/ping.yml", [], "Linux", "default",
             "SSH, sudo, OS and uptime of every node. Changes nothing."),
            ("Linux: static IP", "ansible", f"{LIB}/static-ip.yml", [], "Linux", "default",
             "Pin each node's inventory IP in netplan, one node at a time. Run with --check first."),
            ("K8s: MetalLB", "ansible", f"{LIB}/metallb.yml", [], "Kubernetes", "default",
             "Install/upgrade MetalLB and apply address pools + L2 advertisements. Idempotent."),
            ("Kubespray: preflight", "bash", ks, ["preflight"], "Kubespray", f"kubespray-{inv}",
             "SSH, sudo, OS and kubelet version on every node with kubespray's pinned Ansible. Changes nothing."),
            ("Kubespray: deploy / converge", "bash", ks, ["cluster"], "Kubespray", f"kubespray-{inv}",
             "cluster.yml: deploy the cluster or converge it to the inventory. Idempotent, takes 20-40 min."),
            ("Kubespray: add workers", "bash", ks, ["scale"], "Kubespray", f"kubespray-{inv}",
             "scale.yml: add the new nodes to hosts.yaml first, then run with CLI args --limit=<new nodes>."),
            ("Kubespray: upgrade", "bash", ks, ["upgrade"], "Kubespray", f"kubespray-{inv}",
             "upgrade-cluster.yml: rolling upgrade to kube_version in kubespray-vars.yml."),
            ("Kubespray: remove node", "bash", ks, ["remove-node"], "Kubespray", f"kubespray-{inv}",
             "remove-node.yml: run with CLI args -e node=<name>."),
            (f"Kubespray: RESET {inv} (destroys cluster)", "bash", ks, ["reset", f"--confirm-reset={inv}"],
             "Kubespray", f"kubespray-{inv}",
             "reset.yml: removes Kubernetes and etcd from every node. There is no undo."),
        ],
    }


class Api:
    def __init__(self, url, token, dry):
        self.url, self.token, self.dry = url.rstrip("/") + "/api", token, dry

    def call(self, method, path, body=None):
        if self.dry and method != "GET":
            print(f"   (dry-run) {method} {path}")
            return {"id": 0}
        req = urllib.request.Request(self.url + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + self.token,
                                              "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                text = r.read().decode()
                return json.loads(text) if text else {}
        except urllib.error.HTTPError as e:
            sys.exit(f"{method} {path}: HTTP {e.code} {e.read().decode()[:300]}")

    def upsert(self, kind, path, name, body, key="name", compare=None):
        """Create {kind} named name under path, or update it when its fields differ."""
        existing = next((o for o in self.call("GET", path) or [] if o.get(key) == name), None)
        if existing is None:
            created = self.call("POST", path, body)
            print(f"+ {kind:11} {name}")
            return created.get("id", 0) if isinstance(created, dict) else 0
        fields = compare or [k for k in body if k not in ("project_id",)]
        if any(existing.get(k) != body.get(k) for k in fields):
            self.call("PUT", f"{path}/{existing['id']}", {**body, "id": existing["id"]})
            print(f"~ {kind:11} {name}")
        else:
            print(f"= {kind:11} {name}")
        return existing["id"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--inventory", default="proxmox01")
    ap.add_argument("--project")
    ap.add_argument("--repo", default="https://github.com/vishwasraiborde19/ai-labs.git")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    url, token = os.environ.get("SEMAPHORE_URL"), os.environ.get("SEMAPHORE_TOKEN")
    if not url or not token:
        sys.exit("Set SEMAPHORE_URL and SEMAPHORE_TOKEN")
    api = Api(url, token, a.dry_run)
    inv, project = a.inventory, a.project or a.inventory
    cat = catalog(inv)

    pid = next((p["id"] for p in api.call("GET", "/projects") if p["name"] == project), None)
    if pid is None:
        pid = api.call("POST", "/projects", {"name": project, "alert": False, "max_parallel_tasks": 1}).get("id", 0)
        print(f"+ project     {project}")
    else:
        print(f"= project     {project} (id {pid})")
    P = f"/project/{pid}"

    key_id = api.upsert("key", f"{P}/keys", "none", {"name": "none", "type": "none", "project_id": pid},
                        compare=["type"])
    repo_id = api.upsert("repository", f"{P}/repositories", "ai-labs",
                         {"name": "ai-labs", "project_id": pid, "git_url": a.repo, "git_branch": a.branch,
                          "ssh_key_id": key_id})
    inv_id = api.upsert("inventory", f"{P}/inventory", inv,
                        {"name": inv, "project_id": pid, "type": "file", "repository_id": repo_id,
                         "inventory": f"{LIB}/inventories/{inv}/hosts.yaml", "ssh_key_id": key_id,
                         "become_key_id": None})
    env_ids = {n: api.upsert("variables", f"{P}/environment", n, {"name": n, "project_id": pid, **e},
                             compare=["json", "env"])
               for n, e in cat["environments"].items()}
    view_ids = {}
    for pos, title in enumerate(cat["views"]):
        view_ids[title] = api.upsert("view", f"{P}/views", title, {"title": title, "project_id": pid,
                                                                      "position": pos}, key="title")

    for name, app, playbook, args, view, env, desc in cat["templates"]:
        api.upsert("template", f"{P}/templates", name, {
            "name": name, "project_id": pid, "app": app, "playbook": playbook, "type": "",
            "inventory_id": inv_id, "repository_id": repo_id, "environment_id": env_ids[env],
            "arguments": json.dumps(args) if args else None, "allow_override_args_in_task": True,
            "description": desc, "view_id": view_ids[view],
        }, compare=["app", "playbook", "inventory_id", "repository_id", "environment_id", "arguments",
                    "allow_override_args_in_task", "description", "view_id"])
    print(f"\nDone: {url.rstrip('/')}/project/{pid}/templates")


if __name__ == "__main__":
    main()
