#!/usr/bin/env bash
# Install the lab's metrics stack on Ubuntu 22.04:
#   Grafana OSS (official apt repo, HTTPS with a self-signed certificate by default)
#   + Prometheus (install-prometheus.sh) + OpenTelemetry Collector (install-otel-collector.sh),
#   with Prometheus provisioned as Grafana's default data source.
# Variables of the two sibling scripts (PROM_*, OTEL_*) are passed through.
# Idempotent: safe to re-run. Re-running upgrades to the newest release unless GF_VER is pinned.
# Run as a sudo-capable user.
#
#   GF_VER=                Grafana version to pin, e.g. 13.3.0 (empty = newest in the repo)
#   GF_HOSTNAME=<this IP>  name or IP users browse to; goes into root_url and the certificate
#   GF_PORT=3000           (Semaphore also uses 3000: pick another port when both share a VM)
#   GF_HTTPS=1             0 = plain HTTP (lab only)
#   GF_ALLOW=<subnet>      networks allowed through ufw (if ufw is active); default: this VM's subnet
#   GF_ADMIN=admin         admin login (its password is set only on first install)
#   WITH_PROMETHEUS=1      0 = skip Prometheus on this VM
#   WITH_OTEL=1            0 = skip the OpenTelemetry Collector on this VM
#   GF_PROM_URL=           Prometheus for the data source; default: the local one when WITH_PROMETHEUS=1,
#                          none otherwise
set -euo pipefail
WITH_PROMETHEUS=${WITH_PROMETHEUS:-1}
WITH_OTEL=${WITH_OTEL:-1}
GF_VER=${GF_VER:-}
IP=$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')
GF_HOSTNAME=${GF_HOSTNAME:-$IP}
GF_PORT=${GF_PORT:-3000}
GF_HTTPS=${GF_HTTPS:-1}
GF_ADMIN=${GF_ADMIN:-admin}
GF_PROM_URL=${GF_PROM_URL:-}
if [ -z "$GF_PROM_URL" ] && [ "$WITH_PROMETHEUS" = 1 ]; then
  GF_PROM_URL=http://127.0.0.1:${PROM_PORT:-9090}
  [ "${PROM_LISTEN:-127.0.0.1}" = 0.0.0.0 ] || GF_PROM_URL=http://${PROM_LISTEN:-127.0.0.1}:${PROM_PORT:-9090}
fi
DIR=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
if [ -z "${GF_ALLOW:-}" ]; then
  dev=$(ip -4 route show default | awk '{print $5; exit}')
  GF_ALLOW=$(python3 -c 'import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)' \
    "$(ip -o -4 addr show dev "$dev" | awk '{print $4; exit}')")
fi
ETC=/etc/grafana
ENVF=$ETC/grafana.env
DB=/var/lib/grafana/grafana.db
SCHEME=https; [ "$GF_HTTPS" = 1 ] || SCHEME=http
URL=$SCHEME://$GF_HOSTNAME:$GF_PORT
export DEBIAN_FRONTEND=noninteractive
say() { printf '\n== [%s] %s\n' "$(date +%T)" "$*"; }
[ "$(id -u)" -ne 0 ] || { echo "Run as a normal user with sudo, not root."; exit 1; }
cd /tmp

say "Grafana ${GF_VER:-(latest)} for $URL (allow $GF_ALLOW)"
echo "stack: prometheus=$WITH_PROMETHEUS otel-collector=$WITH_OTEL data source=${GF_PROM_URL:-none}"

if [ "$WITH_PROMETHEUS" = 1 ]; then "$DIR/install-prometheus.sh"; fi
if [ "$WITH_OTEL" = 1 ]; then
  [ "$WITH_PROMETHEUS" = 1 ] || [ -n "${OTEL_PROM_URL:-}" ] \
    || { echo "WITH_OTEL=1 needs a Prometheus: keep WITH_PROMETHEUS=1 or set OTEL_PROM_URL"; exit 1; }
  if [ "$WITH_PROMETHEUS" = 1 ] && [ -z "${OTEL_PROM_URL:-}" ]; then export OTEL_PROM_URL=$GF_PROM_URL; fi
  "$DIR/install-otel-collector.sh"
fi

say "Grafana apt repository"
sudo apt-get update -qq
sudo apt-get install -y -qq apt-transport-https curl gnupg openssl ca-certificates >/dev/null
curl -fsSL https://apt.grafana.com/gpg.key | sudo gpg --dearmor --yes -o /usr/share/keyrings/grafana.gpg
echo "deb [signed-by=/usr/share/keyrings/grafana.gpg] https://apt.grafana.com stable main" \
  | sudo tee /etc/apt/sources.list.d/grafana.list >/dev/null
sudo apt-get update -qq

say "Package"
PREV=$(dpkg-query -W -f='${Version}' grafana 2>/dev/null || true)
sudo apt-mark unhold grafana >/dev/null 2>&1 || true
if [ -n "$GF_VER" ]; then
  sudo apt-get install -y -qq --allow-downgrades "grafana=$GF_VER" >/dev/null
else
  sudo apt-get install -y -qq grafana >/dev/null
fi
sudo apt-mark hold grafana >/dev/null   # upgrades only happen by re-running this script
NOW=$(dpkg-query -W -f='${Version}' grafana)
if [ -z "$PREV" ]; then echo "installed $NOW"
elif [ "$PREV" != "$NOW" ]; then echo "upgraded $PREV -> $NOW"
else echo "$NOW already installed"; fi

say "TLS certificate"
sudo install -d -o root -g grafana -m 750 $ETC/tls
if [ "$GF_HTTPS" = 1 ]; then
  if ! sudo test -s $ETC/tls/cert.pem; then
    SAN="DNS:localhost,IP:127.0.0.1,IP:$IP"
    if [[ $GF_HOSTNAME =~ ^[0-9.]+$ ]]; then
      [ "$GF_HOSTNAME" != "$IP" ] && SAN="$SAN,IP:$GF_HOSTNAME"
    else
      SAN="$SAN,DNS:$GF_HOSTNAME"
    fi
    sudo openssl req -x509 -newkey rsa:3072 -nodes -days 825 -subj "/CN=$GF_HOSTNAME" -addext "subjectAltName=$SAN" \
      -keyout $ETC/tls/key.pem -out $ETC/tls/cert.pem 2>/dev/null
    echo "self-signed for $SAN (replace cert.pem/key.pem with CA-signed ones any time)"
  else
    echo "exists, kept"
  fi
  sudo chown root:grafana $ETC/tls/cert.pem $ETC/tls/key.pem
  sudo chmod 644 $ETC/tls/cert.pem
  sudo chmod 640 $ETC/tls/key.pem
else
  echo "GF_HTTPS=0: serving plain HTTP"
fi

say "Settings $ENVF"
# Grafana reads GF_<SECTION>_<KEY> variables on top of grafana.ini, so grafana.ini stays the package's.
ADMIN_PW=""
if ! sudo test -s $DB; then
  ADMIN_PW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)
fi
{
  echo "# Managed by install-grafana.sh (re-running rewrites it). Overrides /etc/grafana/grafana.ini."
  echo "GF_SERVER_PROTOCOL=$SCHEME"
  echo "GF_SERVER_HTTP_PORT=$GF_PORT"
  echo "GF_SERVER_DOMAIN=$GF_HOSTNAME"
  echo "GF_SERVER_ROOT_URL=$URL/"
  if [ "$GF_HTTPS" = 1 ]; then
    echo "GF_SERVER_CERT_FILE=$ETC/tls/cert.pem"
    echo "GF_SERVER_CERT_KEY=$ETC/tls/key.pem"
  fi
  echo "GF_SECURITY_ADMIN_USER=$GF_ADMIN"
  echo "GF_SECURITY_COOKIE_SECURE=$([ "$GF_HTTPS" = 1 ] && echo true || echo false)"
  echo "GF_USERS_ALLOW_SIGN_UP=false"
  echo "GF_ANALYTICS_REPORTING_ENABLED=false"
  # only read when the admin user is first created; removed again after the first start
  if [ -n "$ADMIN_PW" ]; then echo "GF_SECURITY_ADMIN_PASSWORD=$ADMIN_PW"; fi
} | sudo tee $ENVF >/dev/null
sudo chown root:grafana $ENVF
sudo chmod 640 $ENVF
sudo install -d /etc/systemd/system/grafana-server.service.d
sudo tee /etc/systemd/system/grafana-server.service.d/lab.conf >/dev/null <<EOF
# Managed by install-grafana.sh
[Service]
EnvironmentFile=$ENVF
# let the grafana user bind ports below 1024 if GF_PORT is e.g. 443
AmbientCapabilities=CAP_NET_BIND_SERVICE
EOF

say "Provisioning"
DS=$ETC/provisioning/datasources/lab-prometheus.yaml
if [ -n "$GF_PROM_URL" ]; then
  sudo tee $DS >/dev/null <<EOF
# Managed by install-grafana.sh (re-running rewrites it)
apiVersion: 1
datasources:
  - name: Prometheus
    uid: lab-prometheus
    type: prometheus
    access: proxy
    url: $GF_PROM_URL
    isDefault: true
    editable: false
    jsonData:
      httpMethod: POST
      prometheusType: Prometheus
EOF
  sudo chown root:grafana $DS
  sudo chmod 640 $DS
  echo "data source Prometheus -> $GF_PROM_URL"
else
  sudo rm -f $DS
  echo "no Prometheus data source (set GF_PROM_URL to add one)"
fi
# Grafana's own metrics, when Prometheus runs on this VM
if [ -d /etc/prometheus/scrape.d ]; then
  sudo tee /etc/prometheus/scrape.d/grafana.yml >/dev/null <<EOF
# Managed by install-grafana.sh
scrape_configs:
  - job_name: grafana
    scheme: $SCHEME
    tls_config:
      insecure_skip_verify: true   # self-signed certificate on the same VM
    static_configs:
      - targets: ["127.0.0.1:$GF_PORT"]
EOF
  sudo systemctl reload prometheus 2>/dev/null || true
fi

say "systemd service"
sudo systemctl daemon-reload
sudo systemctl enable -q grafana-server
sudo systemctl restart grafana-server

if command -v ufw >/dev/null && sudo ufw status | grep -q "Status: active"; then
  say "ufw: allow $GF_ALLOW -> tcp/$GF_PORT"
  IFS=, read -ra nets <<<"$GF_ALLOW"
  for n in "${nets[@]}"; do sudo ufw allow from "$n" to any port "$GF_PORT" proto tcp >/dev/null; done
fi

say "Self-test"
ok=0
for _ in $(seq 1 60); do
  curl -fsk "$SCHEME://127.0.0.1:$GF_PORT/api/health" >/dev/null 2>&1 && { ok=1; break; }
  systemctl is-active -q grafana-server || break
  sleep 2
done
if [ "$ok" != 1 ]; then
  echo "Grafana did not become ready. Last log lines:"
  sudo journalctl -u grafana-server -n 40 --no-pager
  exit 1
fi
echo "health: $(curl -fsk "$SCHEME://127.0.0.1:$GF_PORT/api/health" | tr -d '\n ')"
if [ -n "$ADMIN_PW" ]; then
  if curl -fsk -u "$GF_ADMIN:$ADMIN_PW" "$SCHEME://127.0.0.1:$GF_PORT/api/user" >/dev/null; then
    echo "admin login verified"
  else
    echo "WARNING: could not log in as $GF_ADMIN with the generated password"
  fi
  sudo sed -i '/^GF_SECURITY_ADMIN_PASSWORD=/d' $ENVF
fi

cat <<EOF

=====================================================================
 Grafana $NOW is running
   URL:      $URL/
   Login:    $GF_ADMIN
EOF
if [ -n "$ADMIN_PW" ]; then
  echo "   Password: $ADMIN_PW   (shown once: store it, or change it under Profile)"
else
  echo "   Password: unchanged (existing database)"
fi
[ "$GF_HTTPS" = 1 ] && echo "   Certificate: $ETC/tls/cert.pem   (self-signed unless replaced)"
cat <<EOF
   Service:  grafana-server  (journalctl -u grafana-server -f)
   Settings: $ENVF  (overrides $ETC/grafana.ini)   Data: /var/lib/grafana
EOF
if [ -n "$GF_PROM_URL" ]; then echo "   Prometheus: $GF_PROM_URL  (default data source)"; fi
if [ "$WITH_OTEL" = 1 ]; then echo "   OTLP in:    $IP:4317 (gRPC), $IP:4318 (HTTP)  -> metrics to Prometheus"; fi
cat <<EOF
=====================================================================
EOF
