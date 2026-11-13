#!/usr/bin/env bash
# Provisions the Kubernetes and node dashboards into the central Grafana (folder "Kubernetes").
# Run on the Grafana VM as a user with sudo. Idempotent: re-running refreshes the dashboards.
#
#   ./grafana-dashboards.sh                          default data source uid: prometheus
#   DS_UID=lab-prometheus ./grafana-dashboards.sh    if Grafana was set up by grafana-installer
set -euo pipefail

DS_UID=${DS_UID:-prometheus}
DIR=${DIR:-/var/lib/grafana/dashboards/kubernetes}
PROVIDER=/etc/grafana/provisioning/dashboards/lab-kubernetes.yaml

# grafana.com id:revision ("latest" follows the newest). dotdc's set has a cluster variable.
DASHBOARDS=(
  "1860:latest:node-exporter-full"        # Node Exporter Full: per-node CPU, memory, disk, network
  "15757:latest:k8s-views-global"         # Kubernetes / Views / Global
  "15758:latest:k8s-views-namespaces"     # Kubernetes / Views / Namespaces
  "15759:latest:k8s-views-nodes"          # Kubernetes / Views / Nodes
  "15760:latest:k8s-views-pods"           # Kubernetes / Views / Pods
  "16888:latest:longhorn"                 # Longhorn: volumes, nodes, disks, backups
)

[[ -d /etc/grafana/provisioning/dashboards ]] || { echo "Grafana not found (/etc/grafana)"; exit 1; }
sudo install -d -o root -g grafana -m 0755 "$DIR"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

for d in "${DASHBOARDS[@]}"; do
  IFS=: read -r id rev name <<<"$d"
  url="https://grafana.com/api/dashboards/$id/revisions/$rev/download"
  echo "==> $name ($id)"
  curl -fsSL -m 30 "$url" -o "$tmp/$name.json"
  # Dashboards exported for import reference ${DS_...} inputs; file provisioning does not resolve them.
  sed -E -i "s/\\$\\{DS_[A-Za-z0-9_]+\\}/$DS_UID/g" "$tmp/$name.json"
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d.pop("__inputs",None); d["id"]=None; json.dump(d,open(sys.argv[1],"w"))' "$tmp/$name.json"
  sudo install -o root -g grafana -m 0644 "$tmp/$name.json" "$DIR/$name.json"
done

sudo tee "$PROVIDER" >/dev/null <<EOF
# Managed by ai-labs/mcp-agents/tool-box/kube/cluster-monitoring/grafana-dashboards.sh
apiVersion: 1
providers:
  - name: lab-kubernetes
    folder: Kubernetes
    type: file
    disableDeletion: true
    allowUiUpdates: false
    updateIntervalSeconds: 60
    options:
      path: $DIR
EOF
sudo chmod 0644 "$PROVIDER"
sudo systemctl restart grafana-server
echo "==> Done: Grafana → Dashboards → Kubernetes"
