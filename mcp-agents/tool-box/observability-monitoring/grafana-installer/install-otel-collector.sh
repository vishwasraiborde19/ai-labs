#!/usr/bin/env bash
# Install the OpenTelemetry Collector (contrib distribution) on Ubuntu 22.04 from the official GitHub
# release .deb, sha256-verified. It is the one place apps and hosts send telemetry to over OTLP:
#   metrics -> Prometheus (its OTLP receiver), plus this VM's own host metrics
#   logs + traces -> Elasticsearch, when OTEL_ES_URL and OTEL_ES_API_KEY are set (otherwise not accepted)
# Called by install-grafana.sh; can also run on its own. Idempotent. Bump OTEL_VER to upgrade.
# Run as a sudo-capable user.
#
#   OTEL_VER=0.165.0                  collector release
#   OTEL_PROM_URL=http://127.0.0.1:9090   Prometheus with --web.enable-otlp-receiver
#   OTEL_LISTEN=0.0.0.0               address for the OTLP receivers (gRPC 4317, HTTP 4318)
#   OTEL_ALLOW=<subnet>               networks allowed through ufw (if ufw is active); default: this VM's subnet
#   OTEL_HOSTMETRICS=1                0 = don't collect this VM's CPU / memory / disk / network metrics
#   OTEL_ES_URL=                      e.g. https://10.0.0.20:9200 (logs + traces go there when set)
#   OTEL_ES_API_KEY=                  "encoded" API key with create_doc on logs-*, traces-*, metrics-*
#   OTEL_ES_CA=                       CA file for OTEL_ES_URL; default: /etc/elasticsearch/certs/http_ca.crt
#                                     if Elasticsearch is on this VM
set -euo pipefail
OTEL_VER=${OTEL_VER:-0.165.0}
OTEL_PROM_URL=${OTEL_PROM_URL:-http://127.0.0.1:9090}
OTEL_LISTEN=${OTEL_LISTEN:-0.0.0.0}
OTEL_HOSTMETRICS=${OTEL_HOSTMETRICS:-1}
OTEL_ES_URL=${OTEL_ES_URL:-}
OTEL_ES_API_KEY=${OTEL_ES_API_KEY:-}
OTEL_ES_CA=${OTEL_ES_CA:-}
if [ -z "${OTEL_ALLOW:-}" ]; then
  dev=$(ip -4 route show default | awk '{print $5; exit}')
  OTEL_ALLOW=$(python3 -c 'import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)' \
    "$(ip -o -4 addr show dev "$dev" | awk '{print $4; exit}')")
fi
ARCH=$(dpkg --print-architecture)
ETC=/etc/otelcol-contrib
CONF=$ETC/config.yaml
SVC=otelcol-contrib
export DEBIAN_FRONTEND=noninteractive
say() { printf '\n== [%s] %s\n' "$(date +%T)" "$*"; }
[ "$(id -u)" -ne 0 ] || { echo "Run as a normal user with sudo, not root."; exit 1; }
cd /tmp

ES=0
if [ -n "$OTEL_ES_URL" ] || [ -n "$OTEL_ES_API_KEY" ]; then
  [ -n "$OTEL_ES_URL" ] && [ -n "$OTEL_ES_API_KEY" ] || { echo "Set both OTEL_ES_URL and OTEL_ES_API_KEY."; exit 1; }
  ES=1
  if [ -z "$OTEL_ES_CA" ] && sudo test -s /etc/elasticsearch/certs/http_ca.crt; then
    OTEL_ES_CA=/etc/elasticsearch/certs/http_ca.crt
  fi
fi
say "OpenTelemetry Collector $OTEL_VER: OTLP on $OTEL_LISTEN:4317/4318, metrics -> $OTEL_PROM_URL$([ $ES = 1 ] && echo ", logs+traces -> $OTEL_ES_URL")"
command -v curl >/dev/null || { sudo apt-get update -qq; sudo apt-get install -y -qq curl ca-certificates >/dev/null; }

say "Package"
NOW=$(dpkg-query -W -f='${Version}' otelcol-contrib 2>/dev/null || true)
if [ "$NOW" != "$OTEL_VER" ]; then
  deb=otelcol-contrib_${OTEL_VER}_linux_$ARCH.deb
  base=https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v$OTEL_VER
  curl -fsSLO "$base/$deb"
  echo "$(curl -fsSL "$base/$deb.sha256" | awk '{print $1}')  $deb" | sha256sum -c
  # keep our config.yaml when the package ships a new default one
  sudo apt-get install -y -qq -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "./$deb" >/dev/null
  rm -f "$deb"
  if [ -n "$NOW" ]; then echo "upgraded $NOW -> $OTEL_VER"; else echo "installed $OTEL_VER"; fi
else
  echo "$OTEL_VER already installed"
fi

if [ "$ES" = 1 ] && [ -n "$OTEL_ES_CA" ]; then
  sudo install -o root -g $SVC -m 644 "$OTEL_ES_CA" $ETC/es-ca.crt
fi

say "Config $CONF"
{
cat <<EOF
# Managed by install-otel-collector.sh (re-running rewrites it). Components: otelcol-contrib components
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: $OTEL_LISTEN:4317
      http:
        endpoint: $OTEL_LISTEN:4318
EOF
if [ "$OTEL_HOSTMETRICS" = 1 ]; then cat <<'EOF'
  hostmetrics:
    collection_interval: 30s
    scrapers:
      cpu: {}
      load: {}
      memory: {}
      paging: {}
      disk: {}
      network: {}
      processes: {}
      filesystem:
        exclude_fs_types:
          fs_types: [autofs, binfmt_misc, bpf, cgroup2, configfs, debugfs, devpts, devtmpfs, fusectl,
                     hugetlbfs, mqueue, nsfs, overlay, proc, pstore, rpc_pipefs, securityfs, squashfs,
                     sysfs, tracefs, tmpfs]
          match_type: strict
EOF
fi
cat <<EOF

processors:
  memory_limiter:
    check_interval: 1s
    limit_percentage: 75
    spike_limit_percentage: 20
  batch: {}
  resourcedetection/host:          # only on this VM's own metrics, never on what others send
    detectors: [env, system]
    system:
      hostname_sources: [os]
  resource/host:
    attributes:
      - {key: service.name, value: hostmetrics, action: upsert}

exporters:
  otlphttp/prometheus:
    endpoint: $OTEL_PROM_URL/api/v1/otlp     # metrics go to .../v1/metrics
EOF
if [ "$ES" = 1 ]; then
  echo "  elasticsearch:"
  echo "    endpoint: $OTEL_ES_URL"
  echo "    api_key: \"$OTEL_ES_API_KEY\""
  if [ -n "$OTEL_ES_CA" ]; then
    echo "    tls:"
    echo "      ca_file: $ETC/es-ca.crt"
  fi
fi
cat <<EOF

extensions:
  health_check:
    endpoint: 127.0.0.1:13133

service:
  extensions: [health_check]
  telemetry:
    metrics:                      # the collector's own metrics, scraped by Prometheus (scrape.d/otel-collector.yml)
      readers:
        - pull:
            exporter:
              prometheus:
                host: 127.0.0.1
                port: 8888
  pipelines:
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlphttp/prometheus]
EOF
if [ "$OTEL_HOSTMETRICS" = 1 ]; then cat <<'EOF'
    metrics/host:
      receivers: [hostmetrics]
      processors: [memory_limiter, resourcedetection/host, resource/host, batch]
      exporters: [otlphttp/prometheus]
EOF
fi
if [ "$ES" = 1 ]; then cat <<'EOF'
    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [elasticsearch]
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [elasticsearch]
EOF
fi
} | sudo tee $CONF >/dev/null
sudo chown root:$SVC $CONF
sudo chmod 640 $CONF   # may hold the Elasticsearch API key
sudo -u $SVC /usr/bin/otelcol-contrib validate --config=$CONF
echo "config valid"

# let Prometheus scrape the collector's own metrics when it runs on this VM
if [ -d /etc/prometheus/scrape.d ]; then
  sudo tee /etc/prometheus/scrape.d/otel-collector.yml >/dev/null <<'EOF'
# Managed by install-otel-collector.sh
scrape_configs:
  - job_name: otel-collector
    static_configs:
      - targets: ["127.0.0.1:8888"]
EOF
  sudo systemctl reload prometheus 2>/dev/null || true
fi

say "systemd service"
sudo systemctl enable -q $SVC
sudo systemctl restart $SVC

if [ "$OTEL_LISTEN" != 127.0.0.1 ] && command -v ufw >/dev/null && sudo ufw status | grep -q "Status: active"; then
  say "ufw: allow $OTEL_ALLOW -> tcp/4317,4318"
  IFS=, read -ra nets <<<"$OTEL_ALLOW"
  for n in "${nets[@]}"; do
    sudo ufw allow from "$n" to any port 4317 proto tcp >/dev/null
    sudo ufw allow from "$n" to any port 4318 proto tcp >/dev/null
  done
fi

say "Self-test"
ok=0
for _ in $(seq 1 30); do
  curl -fs http://127.0.0.1:13133/ >/dev/null 2>&1 && { ok=1; break; }
  systemctl is-active -q $SVC || break
  sleep 2
done
if [ "$ok" != 1 ]; then
  echo "Collector did not become healthy. Last log lines:"
  sudo journalctl -u $SVC -n 40 --no-pager
  exit 1
fi
echo "health: $(curl -fs http://127.0.0.1:13133/)"
# a test metric over OTLP/HTTP, which should land in Prometheus as lab_install_check
now=$(date +%s%N)
curl -fs -o /dev/null -H 'Content-Type: application/json' "http://127.0.0.1:4318/v1/metrics" -d "{\"resourceMetrics\":[{
  \"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"install-check\"}}]},
  \"scopeMetrics\":[{\"metrics\":[{\"name\":\"lab_install_check\",\"gauge\":{\"dataPoints\":[{\"asInt\":\"1\",\"timeUnixNano\":\"$now\"}]}}]}]}]}" \
  && echo "OTLP/HTTP accepted a test metric (query lab_install_check in Grafana)" \
  || echo "WARNING: OTLP/HTTP did not accept the test metric"
echo
echo "OpenTelemetry Collector $OTEL_VER: send OTLP to <this VM>:4317 (gRPC) or :4318 (HTTP)  config: $CONF"
