#!/usr/bin/env bash
# Installs (or upgrades) the NFS CSI driver and a default StorageClass "nfs" backed by the lab NAS, then
# proves it with a PVC and a pod that writes a file. Runs where kubectl and helm point at the cluster.
#
#   NFS_SERVER=192.168.1.13 NFS_SHARE=/srv/nfs/k8s ./install-nfs-storage.sh
#   ./install-nfs-storage.sh --dry-run | --test | --uninstall
set -euo pipefail

NFS_SERVER=${NFS_SERVER:-192.168.1.13}
NFS_SHARE=${NFS_SHARE:-/srv/nfs/k8s}
NAMESPACE=${NAMESPACE:-kube-system}
RELEASE=${RELEASE:-csi-driver-nfs}
CHART_VERSION=${CHART_VERSION:-4.13.6}
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
[[ $NFS_SHARE == /* ]] || die "NFS_SHARE must be an absolute path"

log "Cluster: $(k config current-context)"
k get nodes >/dev/null || die "cannot reach the cluster"

if [[ $MODE == uninstall ]]; then
  helm "${ctx[@]}" uninstall "$RELEASE" -n "$NAMESPACE" --wait || true
  log "Removed the driver and StorageClass. Existing PVs stay bound but can no longer be mounted; data stays on the NAS."
  exit 0
fi

smoke_test() {
  log "Test: PVC + pod writing to the NAS"
  local ns=nfs-storage-test
  k create namespace "$ns" --dry-run=client -o yaml | k apply -f - >/dev/null
  k -n "$ns" apply -f - >/dev/null <<'EOF'
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: smoke}
spec:
  accessModes: [ReadWriteMany]
  storageClassName: nfs
  resources: {requests: {storage: 1Gi}}
---
apiVersion: v1
kind: Pod
metadata: {name: smoke}
spec:
  restartPolicy: Never
  securityContext: {runAsUser: 1000, runAsGroup: 1000}   # non-root, like most apps
  containers:
    - name: writer
      image: busybox:1.36
      command: ["sh", "-c", "date > /data/hello.txt && cat /data/hello.txt && df -h /data | tail -1"]
      volumeMounts: [{name: data, mountPath: /data}]
  volumes:
    - name: data
      persistentVolumeClaim: {claimName: smoke}
EOF
  if ! k -n "$ns" wait --for=jsonpath='{.status.phase}'=Succeeded pod/smoke --timeout=180s >/dev/null; then
    k -n "$ns" describe pod smoke | tail -15
    die "test pod did not succeed"
  fi
  k -n "$ns" logs smoke | sed 's/^/  pod: /'
  echo "  on the NAS: $NFS_SERVER:$NFS_SHARE/$ns/smoke/hello.txt"
  k delete namespace "$ns" --wait=true >/dev/null
  echo "  test namespace removed (its directory is archived on the NAS: archived-$ns...)"
}

if [[ $MODE == test ]]; then smoke_test; exit 0; fi

existing=$(k get sc -o jsonpath='{range .items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")]}{.metadata.name}{" "}{end}')
[[ -z $existing || $existing == "nfs " ]] || echo "WARNING: default StorageClass already set ($existing); two defaults make PVCs ambiguous"

log "Chart repository"
helm repo add csi-driver-nfs https://raw.githubusercontent.com/kubernetes-csi/csi-driver-nfs/master/charts --force-update >/dev/null
helm repo update csi-driver-nfs >/dev/null

args=(upgrade --install "$RELEASE" csi-driver-nfs/csi-driver-nfs --version "$CHART_VERSION"
  --namespace "$NAMESPACE" -f "$HERE/values.yaml"
  --set-string "storageClass.parameters.server=$NFS_SERVER"
  --set-string "storageClass.parameters.share=$NFS_SHARE")

log "Validating against the cluster (server-side dry run)"
helm "${ctx[@]}" "${args[@]}" --dry-run=server >/dev/null
echo ok
[[ $MODE == dry-run ]] && { log "Dry run only, nothing changed"; exit 0; }

log "Installing $RELEASE $CHART_VERSION ($NFS_SERVER:$NFS_SHARE)"
helm "${ctx[@]}" "${args[@]}" --wait --timeout 5m >/dev/null
k -n "$NAMESPACE" rollout status deploy/csi-nfs-controller --timeout 3m
k -n "$NAMESPACE" rollout status ds/csi-nfs-node --timeout 3m
k get sc nfs

smoke_test
log "Done. Use storageClassName: nfs (or none: it is the default)."
