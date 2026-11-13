#!/usr/bin/env bash
# Installs (or upgrades) cluster monitoring: node-exporter DaemonSet, kube-state-metrics and a small
# Prometheus that pushes everything to the lab's central Prometheus with remote_write.
# Runs where kubectl and helm point at the cluster (e.g. the installer VM). Idempotent.
#
#   CLUSTER_NAME=proxmox01 REMOTE_WRITE_URL=http://192.168.1.137:9090/api/v1/write ./install-monitoring.sh
#   ./install-monitoring.sh --dry-run        render and validate against the cluster, change nothing
#   ./install-monitoring.sh --uninstall      remove the release (CRDs stay, see README)
set -euo pipefail

CLUSTER_NAME=${CLUSTER_NAME:-proxmox01}
REMOTE_WRITE_URL=${REMOTE_WRITE_URL:-http://192.168.1.137:9090/api/v1/write}
NAMESPACE=${NAMESPACE:-monitoring}
RELEASE=${RELEASE:-kube-monitoring}
CHART_VERSION=${CHART_VERSION:-93.3.0}     # kube-prometheus-stack; needs Kubernetes >= 1.25
KUBE_CONTEXT=${KUBE_CONTEXT:-}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

MODE=install
case "${1:-}" in
  "") ;;
  --dry-run) MODE=dry-run ;;
  --uninstall) MODE=uninstall ;;
  *) die "unknown argument $1 (use --dry-run or --uninstall)" ;;
esac

ctx=(); [[ -n $KUBE_CONTEXT ]] && ctx=(--kube-context "$KUBE_CONTEXT")
kctx=(); [[ -n $KUBE_CONTEXT ]] && kctx=(--context "$KUBE_CONTEXT")
k() { kubectl "${kctx[@]}" "$@"; }

command -v kubectl >/dev/null || die "kubectl not found"
command -v helm >/dev/null || die "helm not found"
[[ $CLUSTER_NAME =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "CLUSTER_NAME must be lowercase letters, digits, - or _"
[[ $REMOTE_WRITE_URL =~ ^https?://[^/]+/.+ ]] || die "REMOTE_WRITE_URL must be a full http(s) URL"

log "Cluster: $(k config current-context) (labelled cluster=$CLUSTER_NAME)"
k get nodes -o wide >/dev/null || die "cannot reach the cluster"

if [[ $MODE == uninstall ]]; then
  helm "${ctx[@]}" uninstall "$RELEASE" -n "$NAMESPACE" --wait || true
  log "Removed. CRDs (*.monitoring.coreos.com) and the namespace are kept; see README to remove them."
  exit 0
fi

log "Chart repository"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update >/dev/null
helm repo update prometheus-community >/dev/null

# The central Prometheus must accept remote write (--web.enable-remote-write-receiver).
base=${REMOTE_WRITE_URL%/api/v1/write}
log "Checking the central Prometheus at $base"
if curl -fsS -m 5 "$base/-/ready" >/dev/null 2>&1; then
  code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$REMOTE_WRITE_URL" || true)
  # 400/415 = receiver on (empty body rejected); 404 = receiver off
  [[ $code == 404 ]] && die "$REMOTE_WRITE_URL answers 404: start Prometheus with --web.enable-remote-write-receiver"
  echo "ready, remote-write endpoint answers $code"
else
  echo "WARNING: $base is not reachable from this machine; the cluster's pods may still reach it"
fi

args=(upgrade --install "$RELEASE" prometheus-community/kube-prometheus-stack
  --version "$CHART_VERSION" --namespace "$NAMESPACE" --create-namespace
  -f "$HERE/values.yaml" --skip-crds
  --set-string "prometheus.prometheusSpec.externalLabels.cluster=$CLUSTER_NAME"
  --set-string "prometheus.prometheusSpec.remoteWrite[0].url=$REMOTE_WRITE_URL")

# Helm installs CRDs only once and never upgrades them, so they are applied here, server-side, on every
# run: the chart's documented way to keep them in step with the operator version.
chart_dir=$(mktemp -d); trap 'rm -rf "$chart_dir"' EXIT
helm pull prometheus-community/kube-prometheus-stack --version "$CHART_VERSION" --untar -d "$chart_dir"
crds=$chart_dir/kube-prometheus-stack/charts/crds/crds
[[ -d $crds ]] || die "CRDs not found in chart $CHART_VERSION"

if [[ $MODE == dry-run ]]; then
  log "Validating CRDs against the cluster (server-side dry run)"
  k apply --server-side --force-conflicts --dry-run=server -f "$crds" >/dev/null
  log "Rendering the chart (client-side: its resources need the CRDs, which a dry run does not create)"
  helm "${ctx[@]}" "${args[@]}" --dry-run=client >/dev/null
  echo "ok"
  log "Dry run only, nothing changed"; exit 0
fi

log "Applying Prometheus Operator CRDs"
k apply --server-side --force-conflicts -f "$crds" | sed 's/^/  /'
# The API serves new kinds only once each CRD is Established; Helm resolves kinds right away.
k wait --for=condition=Established --timeout 120s -f "$crds" >/dev/null
for _ in $(seq 1 30); do
  k api-resources --api-group=monitoring.coreos.com 2>/dev/null | grep -q '^prometheuses ' && break
  sleep 2
done

log "Validating against the cluster (server-side dry run)"
helm "${ctx[@]}" "${args[@]}" --dry-run=server >/dev/null
echo "ok"

log "Installing $RELEASE (kube-prometheus-stack $CHART_VERSION) in namespace $NAMESPACE"
helm "${ctx[@]}" "${args[@]}" --wait --timeout 10m

log "Waiting for node-exporter on every node and Prometheus"
k -n "$NAMESPACE" rollout status ds/"$RELEASE"-prometheus-node-exporter --timeout 5m
k -n "$NAMESPACE" wait --for=condition=Ready pod -l app.kubernetes.io/name=prometheus --timeout 5m
k -n "$NAMESPACE" get pods -o wide

log "Checking that samples reach the central Prometheus"
for _ in $(seq 1 24); do
  n=$(curl -fsS -m 5 "$base/api/v1/query" --data-urlencode "query=count(up{cluster=\"$CLUSTER_NAME\"})" 2>/dev/null \
      | grep -o '"value":\[[^]]*\]' | grep -o '"[0-9]*"\]' | tr -dc 0-9 || true)
  if [[ -n $n && $n != 0 ]]; then
    echo "central Prometheus has $n targets for cluster=$CLUSTER_NAME"
    log "Done. In Grafana: Explore → up{cluster=\"$CLUSTER_NAME\"}; dashboards: see README"
    exit 0
  fi
  sleep 5
done
echo "WARNING: no samples for cluster=$CLUSTER_NAME at $base after 2 minutes."
echo "Check: kubectl -n $NAMESPACE logs sts/prometheus-$RELEASE-prometheus -c prometheus | grep -i remote"
exit 1
