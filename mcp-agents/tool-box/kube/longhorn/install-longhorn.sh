#!/usr/bin/env bash
# Installs (or upgrades) Longhorn with all volume data on the storage node, its UI on a fixed LAN address,
# and Longhorn as the default StorageClass; then proves it with a PVC and a pod that writes a file.
# Runs where kubectl and helm point at the cluster (e.g. the installer VM). Idempotent.
#
#   ./install-longhorn.sh --dry-run | (no args) | --test | --uninstall
set -euo pipefail

CHART_VERSION=${CHART_VERSION:-1.12.2}          # needs Kubernetes >= 1.25 (1.13+ needs 1.34)
NAMESPACE=${NAMESPACE:-longhorn-system}
RELEASE=${RELEASE:-longhorn}
STORAGE_NODE=${STORAGE_NODE:-nas-server}        # node holding the data (labelled create-default-disk)
UI_IP=${UI_IP:-192.168.1.240}                   # from the MetalLB pool; empty = any free address
UI_ALLOW=${UI_ALLOW:-192.168.1.0/24}            # who may open the UI (it has no login); space-separated CIDRs
WAIT_FOR_NODE=${WAIT_FOR_NODE:-1}               # 0: install before the storage node joins (UI only, no disk
                                                #    yet; Longhorn adds the disk when the node arrives)
KUBE_CONTEXT=${KUBE_CONTEXT:-}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
ctx=(); [[ -n $KUBE_CONTEXT ]] && ctx=(--kube-context "$KUBE_CONTEXT")
kctx=(); [[ -n $KUBE_CONTEXT ]] && kctx=(--context "$KUBE_CONTEXT")
k() { kubectl "${kctx[@]}" "$@"; }

MODE=install
case "${1:-}" in
  "") ;; --dry-run) MODE=dry-run ;; --test) MODE=test ;; --uninstall) MODE=uninstall ;;
  *) die "unknown argument $1 (use --dry-run, --test or --uninstall)" ;;
esac
command -v kubectl >/dev/null || die "kubectl not found"
command -v helm >/dev/null || die "helm not found"
for c in $UI_ALLOW; do [[ $c =~ ^[0-9.]+/[0-9]+$ ]] || die "UI_ALLOW: '$c' is not a CIDR"; done

log "Cluster: $(k config current-context)"
k get nodes >/dev/null || die "cannot reach the cluster"

if [[ $MODE == uninstall ]]; then
  cat <<EOF
Longhorn refuses to uninstall while the setting deleting-confirmation-flag is false. To remove it, and
ALL volumes and their data:
  kubectl -n $NAMESPACE patch settings.longhorn.io deleting-confirmation-flag --type=merge -p '{"value":"true"}'
  helm uninstall $RELEASE -n $NAMESPACE
EOF
  exit 0
fi

smoke_test() {
  log "Test: PVC + pod writing to a Longhorn volume"
  local ns=longhorn-test
  k create namespace "$ns" --dry-run=client -o yaml | k apply -f - >/dev/null
  k -n "$ns" apply -f - >/dev/null <<'EOF'
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: smoke}
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: longhorn
  resources: {requests: {storage: 1Gi}}
---
apiVersion: v1
kind: Pod
metadata: {name: smoke}
spec:
  restartPolicy: Never
  securityContext: {runAsUser: 1000, runAsGroup: 1000, fsGroup: 1000}
  containers:
    - name: writer
      image: busybox:1.36
      command: ["sh", "-c", "date > /data/hello.txt && sync && cat /data/hello.txt && df -h /data | tail -1"]
      volumeMounts: [{name: data, mountPath: /data}]
  volumes:
    - name: data
      persistentVolumeClaim: {claimName: smoke}
EOF
  if ! k -n "$ns" wait --for=jsonpath='{.status.phase}'=Succeeded pod/smoke --timeout=300s >/dev/null; then
    k -n "$ns" describe pod smoke | tail -15
    die "test pod did not succeed"
  fi
  k -n "$ns" logs smoke | sed 's/^/  pod: /'
  local pv; pv=$(k -n "$ns" get pvc smoke -o jsonpath='{.spec.volumeName}')
  echo "  ran on: $(k -n "$ns" get pod smoke -o jsonpath='{.spec.nodeName}')"
  echo "  replica on: $(k -n "$NAMESPACE" get replicas.longhorn.io -l longhornvolume="$pv" -o jsonpath='{range .items[*]}{.spec.nodeID} {end}')"
  k delete namespace "$ns" --wait=true >/dev/null
  # reclaimPolicy is Retain: remove the test volume explicitly
  k delete pv "$pv" --wait=false >/dev/null 2>&1 || true
  k -n "$NAMESPACE" delete volumes.longhorn.io "$pv" --wait=false >/dev/null 2>&1 || true
  echo "  test volume removed"
}

if [[ $MODE == test ]]; then smoke_test; exit 0; fi

log "Storage node $STORAGE_NODE"
# In a dry run, or when installing ahead of the node, report node problems instead of stopping.
[[ $MODE == dry-run || $WAIT_FOR_NODE == 0 ]] && die() { printf 'WARNING: %s\n' "$*" >&2; }
k get node "$STORAGE_NODE" >/dev/null 2>&1 || die "node $STORAGE_NODE is not in the cluster yet: add it with kubespray scale (see README)"
[[ $(k get node "$STORAGE_NODE" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}') == True ]] \
  || die "node $STORAGE_NODE is not Ready"
[[ $(k get node "$STORAGE_NODE" -o jsonpath='{.metadata.labels.node\.longhorn\.io/create-default-disk}') == true ]] \
  || die "node $STORAGE_NODE lacks label node.longhorn.io/create-default-disk=true (set in hosts.yaml node_labels)"
echo "checked"
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

others=$(k get sc -o jsonpath='{range .items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")]}{.metadata.name}{" "}{end}')
[[ -z $others || $others == "longhorn " ]] || echo "WARNING: another default StorageClass exists ($others)"

log "Chart repository"
helm repo add longhorn https://charts.longhorn.io --force-update >/dev/null
helm repo update longhorn >/dev/null

args=(upgrade --install "$RELEASE" longhorn/longhorn --version "$CHART_VERSION"
  --namespace "$NAMESPACE" --create-namespace -f "$HERE/values.yaml")
[[ -n $UI_IP ]] && args+=(--set-string "service.ui.annotations.metallb\.universe\.tf/loadBalancerIPs=$UI_IP")

log "Rendering against the cluster"
helm "${ctx[@]}" "${args[@]}" --dry-run=client >/dev/null
echo ok
[[ $MODE == dry-run ]] && { log "Dry run only, nothing changed"; exit 0; }

log "Installing Longhorn $CHART_VERSION"
helm "${ctx[@]}" "${args[@]}" --wait --timeout 15m >/dev/null
k -n "$NAMESPACE" rollout status ds/longhorn-manager --timeout 10m
k -n "$NAMESPACE" rollout status deploy/longhorn-driver-deployer --timeout 10m
for d in csi-attacher csi-provisioner csi-resizer csi-snapshotter; do
  for _ in $(seq 1 60); do k -n "$NAMESPACE" get deploy "$d" >/dev/null 2>&1 && break; sleep 5; done
  k -n "$NAMESPACE" rollout status deploy/"$d" --timeout 10m >/dev/null
done
k -n "$NAMESPACE" rollout status ds/longhorn-csi-plugin --timeout 10m >/dev/null
echo "manager, driver and CSI components running"

if [[ $WAIT_FOR_NODE == 0 ]] && ! k get node "$STORAGE_NODE" >/dev/null 2>&1; then
  log "Skipping the disk check and write test: $STORAGE_NODE has not joined yet"
  SKIP_DISK=1
fi
[[ ${SKIP_DISK:-0} == 1 ]] || {
log "Disk on $STORAGE_NODE"
for _ in $(seq 1 60); do
  ok=$(k -n "$NAMESPACE" get nodes.longhorn.io "$STORAGE_NODE" -o jsonpath='{range .status.diskStatus.*}{.conditions[?(@.type=="Schedulable")].status}{end}' 2>/dev/null || true)
  [[ $ok == True ]] && break; sleep 5
done
[[ ${ok:-} == True ]] || die "Longhorn has no schedulable disk on $STORAGE_NODE (check /var/lib/longhorn there)"
k -n "$NAMESPACE" get nodes.longhorn.io
k -n "$NAMESPACE" get nodes.longhorn.io -o jsonpath='{range .items[*]}{.metadata.name}{": "}{range .status.conditions[?(@.status=="False")]}{.type}={.message}{"; "}{end}{"\n"}{end}' \
  | grep -v ': $' | sed 's/^/  not ready: /' || true
}

log "UI access limited to: $UI_ALLOW"
ranges=$(printf '"%s",' $UI_ALLOW); ranges="[${ranges%,}]"
k -n "$NAMESPACE" patch svc longhorn-frontend --type=merge -p "{\"spec\":{\"loadBalancerSourceRanges\":$ranges}}" >/dev/null
ip=""
for _ in $(seq 1 30); do
  ip=$(k -n "$NAMESPACE" get svc longhorn-frontend -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
  [[ -n $ip ]] && break; sleep 2
done

k get sc

[[ ${SKIP_DISK:-0} == 1 ]] || smoke_test
log "Done. Longhorn UI: http://${ip:-<pending>}/   (no login: reachable only from $UI_ALLOW)"
