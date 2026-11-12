#!/usr/bin/env bash
# Create the kube-agent service account on a cluster and write what the agent needs to connect:
#   ~/.kube-agent/<name>.token     service account token (mode 600)
#   ~/.kube-agent/<name>-ca.pem    cluster CA
#   ~/.kube-agent/config.yml       cluster entry (only if the file does not exist yet)
#
# Run it once with an admin kubeconfig; the agent itself never uses admin credentials.
#
#   scripts/setup-service-account.sh                        # read + operate, current kubectl context
#   MODE=read scripts/setup-service-account.sh              # read-only service account
#   CLUSTER_NAME=lab KUBECONFIG=~/admin.conf scripts/setup-service-account.sh
#
#   MODE=read|operate   operate (default) also applies deploy/rbac-operate.yaml
#   CLUSTER_NAME        name used by the agent (default: the kubeconfig cluster name)
#   OUT                 output directory (default ~/.kube-agent)
set -euo pipefail
cd "$(dirname "$0")/.."
MODE=${MODE:-operate}
OUT=${OUT:-$HOME/.kube-agent}

command -v kubectl >/dev/null || { echo "kubectl is required"; exit 1; }
CTX_CLUSTER=$(kubectl config view --minify -o jsonpath='{.clusters[0].name}')
SERVER=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
CLUSTER_NAME=${CLUSTER_NAME:-$(echo "$CTX_CLUSTER" | tr -c 'A-Za-z0-9._\n-' '-')}
echo "== cluster $CTX_CLUSTER ($SERVER) -> agent cluster name '$CLUSTER_NAME', mode $MODE"

kubectl apply -f deploy/rbac-read.yaml
if [ "$MODE" = operate ]; then
  kubectl apply -f deploy/rbac-operate.yaml
else
  kubectl delete --ignore-not-found -f deploy/rbac-operate.yaml
fi

echo "== waiting for the token"
for _ in $(seq 1 30); do
  TOKEN=$(kubectl -n kube-agent get secret kube-agent-token -o jsonpath='{.data.token}' 2>/dev/null || true)
  [ -n "$TOKEN" ] && break
  sleep 1
done
[ -n "$TOKEN" ] || { echo "token was not issued"; exit 1; }

umask 077
mkdir -p "$OUT"
echo "$TOKEN" | base64 -d > "$OUT/$CLUSTER_NAME.token"
kubectl -n kube-agent get secret kube-agent-token -o jsonpath='{.data.ca\.crt}' | base64 -d > "$OUT/$CLUSTER_NAME-ca.pem"
chmod 600 "$OUT/$CLUSTER_NAME.token"

if [ ! -f "$OUT/config.yml" ]; then
  cat > "$OUT/config.yml" <<EOF
kube:
  clusters:
    - name: $CLUSTER_NAME
      api-server: $SERVER
      token-ref: path:$OUT/$CLUSTER_NAME.token
      ca-cert-ref: path:$OUT/$CLUSTER_NAME-ca.pem
      read-only: $([ "$MODE" = read ] && echo true || echo false)
      # allowed-namespaces: [default, team-a]
      # protected-namespaces: [kube-system, kube-public, kube-node-lease]
      cluster-scoped-writes: false
EOF
  echo "== wrote $OUT/config.yml"
else
  echo "== $OUT/config.yml exists; add this cluster if it is not there:"
  echo "    - name: $CLUSTER_NAME"
  echo "      api-server: $SERVER"
  echo "      token-ref: path:$OUT/$CLUSTER_NAME.token"
  echo "      ca-cert-ref: path:$OUT/$CLUSTER_NAME-ca.pem"
fi

echo "== check:"
kubectl auth can-i --list --as=system:serviceaccount:kube-agent:kube-agent | head -20
