#!/usr/bin/env bash
# Install Prometheus (metrics database, with its OTLP receiver on) on Ubuntu 22.04 from the official
# GitHub release, sha256-verified. Called by install-grafana.sh; can also run on its own.
# Idempotent: safe to re-run. Bump PROM_VER to upgrade in place (data is kept).
# Run as a sudo-capable user.
#
#   PROM_VER=3.16.0              Prometheus release
#   PROM_LISTEN=127.0.0.1        address to listen on; Prometheus has no login, so it stays local by
#                                default and is read through Grafana. 0.0.0.0 = all interfaces
#   PROM_PORT=9090
#   PROM_RETENTION=15d           how long to keep samples
#   PROM_RETENTION_SIZE=         optional size cap as well, e.g. 20GB
#   PROM_ALLOW=<subnet>          networks allowed through ufw when not listening on 127.0.0.1
set -euo pipefail
PROM_VER=${PROM_VER:-3.16.0}
PROM_LISTEN=${PROM_LISTEN:-127.0.0.1}
PROM_PORT=${PROM_PORT:-9090}
PROM_RETENTION=${PROM_RETENTION:-15d}
PROM_RETENTION_SIZE=${PROM_RETENTION_SIZE:-}
if [ -z "${PROM_ALLOW:-}" ]; then
  dev=$(ip -4 route show default | awk '{print $5; exit}')
  PROM_ALLOW=$(python3 -c 'import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)' \
    "$(ip -o -4 addr show dev "$dev" | awk '{print $4; exit}')")
fi
ARCH=$(dpkg --print-architecture)   # amd64 / arm64
ETC=/etc/prometheus
DATA=/var/lib/prometheus
HOME_DIR=/opt/prometheus-$PROM_VER
LINK=/opt/prometheus
LOCAL=127.0.0.1; [ "$PROM_LISTEN" = 0.0.0.0 ] || LOCAL=$PROM_LISTEN
export DEBIAN_FRONTEND=noninteractive
say() { printf '\n== [%s] %s\n' "$(date +%T)" "$*"; }
[ "$(id -u)" -ne 0 ] || { echo "Run as a normal user with sudo, not root."; exit 1; }
cd /tmp

say "Prometheus $PROM_VER on $PROM_LISTEN:$PROM_PORT (retention $PROM_RETENTION${PROM_RETENTION_SIZE:+ / $PROM_RETENTION_SIZE})"
command -v curl >/dev/null || { sudo apt-get update -qq; sudo apt-get install -y -qq curl ca-certificates >/dev/null; }

say "System user + directories"
id prometheus >/dev/null 2>&1 || sudo useradd --system --home-dir $DATA --no-create-home --shell /usr/sbin/nologin prometheus
sudo install -d -o prometheus -g prometheus -m 750 $DATA
sudo install -d -o root -g prometheus -m 755 $ETC $ETC/scrape.d

say "Prometheus $PROM_VER -> $HOME_DIR"
if [ ! -x "$HOME_DIR/prometheus" ]; then
  tgz=prometheus-$PROM_VER.linux-$ARCH.tar.gz
  base=https://github.com/prometheus/prometheus/releases/download/v$PROM_VER
  curl -fsSLO "$base/$tgz"
  curl -fsSL "$base/sha256sums.txt" | grep " $tgz\$" | sha256sum -c
  sudo tar -xzf "$tgz" -C /opt
  sudo mv "/opt/prometheus-$PROM_VER.linux-$ARCH" "$HOME_DIR"
  sudo chown -R root:root "$HOME_DIR"
  rm -f "$tgz"
else
  echo "already installed"
fi
PREV=$(readlink -f "$LINK" 2>/dev/null || true)
sudo ln -sfn "$HOME_DIR" "$LINK"
sudo ln -sfn $LINK/prometheus /usr/local/bin/prometheus
sudo ln -sfn $LINK/promtool /usr/local/bin/promtool
[ -n "$PREV" ] && [ "$PREV" != "$HOME_DIR" ] && echo "upgraded from $(basename "$PREV") (kept in $PREV)"
prometheus --version 2>&1 | head -1

say "Config $ETC/prometheus.yml"
sudo tee $ETC/prometheus.yml >/dev/null <<EOF
# Managed by install-prometheus.sh (re-running rewrites it).
# Add your own scrape jobs as files in $ETC/scrape.d/*.yml (each with a top-level scrape_configs: list),
# then: sudo systemctl reload prometheus
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_config_files:
  - $ETC/scrape.d/*.yml

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets: ["$LOCAL:$PROM_PORT"]

# Metrics pushed over OTLP (by the OpenTelemetry Collector) arrive at /api/v1/otlp/v1/metrics.
# service.name / service.instance.id become job / instance; these resource attributes become labels too.
otlp:
  promote_resource_attributes:
    - host.name
    - deployment.environment.name
    - k8s.cluster.name
    - k8s.namespace.name
    - k8s.pod.name
    - k8s.container.name
    - k8s.node.name

storage:
  tsdb:
    out_of_order_time_window: 30m   # OTLP senders batch and retry, so samples can arrive late
EOF
sudo chown root:prometheus $ETC/prometheus.yml
sudo chmod 644 $ETC/prometheus.yml
promtool check config $ETC/prometheus.yml | tail -2

say "systemd service"
SIZE_FLAG=""
[ -n "$PROM_RETENTION_SIZE" ] && SIZE_FLAG="--storage.tsdb.retention.size=$PROM_RETENTION_SIZE"
sudo tee /etc/systemd/system/prometheus.service >/dev/null <<EOF
[Unit]
Description=Prometheus $PROM_VER
Documentation=https://prometheus.io/docs/
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=prometheus
Group=prometheus
ExecStart=$LINK/prometheus \\
  --config.file=$ETC/prometheus.yml \\
  --storage.tsdb.path=$DATA \\
  --storage.tsdb.retention.time=$PROM_RETENTION $SIZE_FLAG \\
  --web.listen-address=$PROM_LISTEN:$PROM_PORT \\
  --web.enable-otlp-receiver
ExecReload=/bin/kill -HUP \$MAINPID
Restart=on-failure
RestartSec=5
TimeoutStopSec=120
LimitNOFILE=65536
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable -q prometheus
sudo systemctl restart prometheus

if [ "$PROM_LISTEN" != 127.0.0.1 ] && command -v ufw >/dev/null && sudo ufw status | grep -q "Status: active"; then
  say "ufw: allow $PROM_ALLOW -> tcp/$PROM_PORT"
  IFS=, read -ra nets <<<"$PROM_ALLOW"
  for n in "${nets[@]}"; do sudo ufw allow from "$n" to any port "$PROM_PORT" proto tcp >/dev/null; done
fi

say "Self-test"
ok=0
for _ in $(seq 1 60); do
  curl -fs "http://$LOCAL:$PROM_PORT/-/ready" >/dev/null 2>&1 && { ok=1; break; }
  systemctl is-active -q prometheus || break
  sleep 2
done
if [ "$ok" != 1 ]; then
  echo "Prometheus did not become ready. Last log lines:"
  sudo journalctl -u prometheus -n 40 --no-pager
  exit 1
fi
echo "ready: $(curl -fs "http://$LOCAL:$PROM_PORT/-/ready")"
echo
echo "Prometheus $PROM_VER: http://$LOCAL:$PROM_PORT  (OTLP: /api/v1/otlp/v1/metrics; jobs: $ETC/scrape.d/)"
