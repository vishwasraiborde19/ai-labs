#!/usr/bin/env bash
# Run a kubespray playbook against an inventory from this library, with the kubespray version and the
# Python/Ansible dependencies that inventory pins. Written for a Semaphore "Bash" template (live output,
# no dependency on the server's own Ansible), but works from any shell.
#
#   kubespray.sh ACTION [ansible-playbook args...]
#
#     preflight    SSH, sudo, OS and kernel on every node; changes nothing
#     cluster      cluster.yml                deploy, or converge an existing cluster (idempotent)
#     scale        scale.yml                  add worker nodes: add them to hosts.yaml, then --limit=<new nodes>
#     upgrade      upgrade-cluster.yml        upgrade to kube_version from the inventory, node by node
#     remove-node  remove-node.yml            needs -e node=<name>
#     recover      recover-control-plane.yml  rebuild broken etcd / control-plane members
#     reset        reset.yml                  DESTROYS the cluster; needs --confirm-reset=<inventory name>
#
# Environment (kubespray.env may also set ANSIBLE_CORE_VERSION to pin ansible-core in the venv):
#   INVENTORY        directory under ../inventories                   (default: proxmox01)
#   KUBESPRAY_HOME   cache for checkouts, venvs, run inventories      (default: ~/.cache/kubespray)
#   ANSIBLE_USER     SSH user on the nodes      (default: from the inventory's kubespray.env)
#   SSH_KEY_FILE     private key for the nodes  (default: ssh's own defaults, e.g. ~/.ssh/id_ed25519)
#
# The inventory directory holds hosts.yaml, kubespray.env (KUBESPRAY_VERSION, ANSIBLE_USER),
# kubespray-vars.yml (passed with -e, highest precedence) and optional group_vars/, host_vars/, patches/.
# group_vars start from kubespray's inventory/sample of the pinned version, then the library's files
# are laid over them. kubespray's generated credentials/ are kept between runs in KUBESPRAY_HOME.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ACTION=${1:-}
[ -n "$ACTION" ] || { sed -n '2,30p' "$0"; exit 2; }
shift

INVENTORY=${INVENTORY:-proxmox01}
INV=$(cd "$HERE/../inventories/$INVENTORY" 2>/dev/null && pwd) \
  || { echo "No inventory '$INVENTORY' in $HERE/../inventories"; exit 2; }
[ -f "$INV/hosts.yaml" ] || { echo "$INV/hosts.yaml is missing"; exit 2; }

KUBESPRAY_VERSION="" KUBESPRAY_REPO=https://github.com/kubernetes-sigs/kubespray.git ENV_USER="" ANSIBLE_CORE_VERSION=""
if [ -f "$INV/kubespray.env" ]; then
  # shellcheck disable=SC1091
  KUBESPRAY_VERSION=$(. "$INV/kubespray.env"; echo "${KUBESPRAY_VERSION:-}")
  KUBESPRAY_REPO=$(. "$INV/kubespray.env"; echo "${KUBESPRAY_REPO:-$KUBESPRAY_REPO}")
  ENV_USER=$(. "$INV/kubespray.env"; echo "${ANSIBLE_USER:-}")
  ANSIBLE_CORE_VERSION=$(. "$INV/kubespray.env"; echo "${ANSIBLE_CORE_VERSION:-}")
fi
[ -n "$KUBESPRAY_VERSION" ] || { echo "Set KUBESPRAY_VERSION in $INV/kubespray.env"; exit 2; }
ANSIBLE_USER=${ANSIBLE_USER:-${ENV_USER:-$(id -un)}}

case $ACTION in
  preflight)   PLAYBOOK="" ;;
  cluster)     PLAYBOOK=cluster.yml ;;
  scale)       PLAYBOOK=scale.yml ;;
  upgrade)     PLAYBOOK=upgrade-cluster.yml ;;
  remove-node) PLAYBOOK=remove-node.yml ;;
  recover)     PLAYBOOK=recover-control-plane.yml ;;
  reset)       PLAYBOOK=reset.yml ;;
  *) echo "Unknown action '$ACTION' (see $0 without arguments)"; exit 2 ;;
esac

ARGS=()
CONFIRMED=0
for a in "$@"; do
  case $a in
    --confirm-reset=*) [ "${a#--confirm-reset=}" = "$INVENTORY" ] && CONFIRMED=1 ;;
    *) ARGS+=("$a") ;;
  esac
done
if [ "$ACTION" = reset ]; then
  [ "$CONFIRMED" = 1 ] || { echo "reset wipes Kubernetes and etcd from every node of '$INVENTORY'."; \
    echo "Add the argument --confirm-reset=$INVENTORY to run it."; exit 2; }
  ARGS+=(-e reset_confirmation=yes)
fi
if [ "$ACTION" = remove-node ] && ! printf '%s\n' "${ARGS[@]}" | grep -q '^node='; then
  echo "remove-node needs -e node=<name> (comma-separated for several)"; exit 2
fi

STATE=${KUBESPRAY_HOME:-$HOME/.cache/kubespray}
SRC=$STATE/src/$KUBESPRAY_VERSION
VENV=$STATE/venv/$KUBESPRAY_VERSION
RUN=$STATE/run/$INVENTORY
mkdir -p "$STATE/src" "$STATE/venv" "$RUN/credentials"
chmod 700 "$STATE" "$RUN" "$RUN/credentials"

exec 9>"$STATE/.lock-$INVENTORY"
flock -n 9 || { echo "Another kubespray run for '$INVENTORY' is in progress."; exit 1; }

say() { printf '\n== %s\n' "$*"; }

# ------------------------------------------------------------------ kubespray checkout (pinned)
if [ ! -f "$SRC/cluster.yml" ]; then
  say "Fetching kubespray $KUBESPRAY_VERSION"
  rm -rf "$SRC.tmp"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$KUBESPRAY_VERSION" "$KUBESPRAY_REPO" "$SRC.tmp"
  mv "$SRC.tmp" "$SRC"
fi

# ------------------------------------------------------------------ Python venv (kubespray's own requirements)
REQ_HASH=$({ cat "$SRC/requirements.txt"; echo "ansible-core=$ANSIBLE_CORE_VERSION"; } | sha256sum | cut -c1-16)
if [ "$(cat "$VENV/.requirements" 2>/dev/null)" != "$REQ_HASH" ]; then
  say "Creating venv for kubespray $KUBESPRAY_VERSION (requirements.txt)"
  rm -rf "$VENV"
  python3 -m venv "$VENV" || { echo "python3 -m venv failed: install the python3-venv package"; exit 1; }
  "$VENV/bin/pip" install -q --upgrade pip
  "$VENV/bin/pip" install -q -r "$SRC/requirements.txt"
  [ -z "$ANSIBLE_CORE_VERSION" ] || "$VENV/bin/pip" install -q "ansible-core==$ANSIBLE_CORE_VERSION"
  echo "$REQ_HASH" > "$VENV/.requirements"
fi

# ------------------------------------------------------------------ run inventory
rm -rf "$RUN/group_vars" "$RUN/host_vars" "$RUN/patches"
cp -r "$SRC/inventory/sample/group_vars" "$RUN/group_vars"
[ -d "$INV/group_vars" ] && cp -r "$INV/group_vars/." "$RUN/group_vars/"
[ -d "$INV/host_vars" ] && cp -r "$INV/host_vars" "$RUN/host_vars"
[ -d "$INV/patches" ] && cp -r "$INV/patches" "$RUN/patches"
cp "$INV/hosts.yaml" "$RUN/hosts.yaml"

VARS=()
[ -f "$INV/kubespray-vars.yml" ] && VARS=(-e "@$INV/kubespray-vars.yml")
KEY=()
[ -n "${SSH_KEY_FILE:-}" ] && KEY=(--private-key "$SSH_KEY_FILE")

export VIRTUAL_ENV=$VENV PATH=$VENV/bin:$PATH
export ANSIBLE_CONFIG=$SRC/ansible.cfg
export ANSIBLE_CALLBACKS_ENABLED=profile_tasks     # kubespray's ansible.cfg also names ara, which is not installed
export ANSIBLE_FORCE_COLOR=${ANSIBLE_FORCE_COLOR:-1}
cd "$SRC"

say "kubespray $KUBESPRAY_VERSION | $(ansible --version | head -1) | inventory $INVENTORY | user $ANSIBLE_USER | $ACTION"

if [ "$ACTION" = preflight ]; then
  ansible -i "$RUN/hosts.yaml" all -u "$ANSIBLE_USER" --become "${KEY[@]}" "${VARS[@]}" "${ARGS[@]}" \
    -m ansible.builtin.shell \
    -a 'echo "$(hostname) | $(. /etc/os-release; echo $PRETTY_NAME) | kernel $(uname -r) | sudo as $(id -un) | kubelet $(kubelet --version 2>/dev/null | cut -d" " -f2 || echo none)"'
  exit $?
fi

exec ansible-playbook -i "$RUN/hosts.yaml" "${VARS[@]}" -u "$ANSIBLE_USER" --become --become-user=root \
  "${KEY[@]}" "$PLAYBOOK" "${ARGS[@]}"
