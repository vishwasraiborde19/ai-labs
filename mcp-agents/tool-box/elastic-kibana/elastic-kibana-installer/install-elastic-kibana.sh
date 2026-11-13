#!/usr/bin/env bash
# Install a single-node Elasticsearch + Kibana (same version) on Ubuntu 22.04 from the official Elastic
# apt repository. Security is on: Elasticsearch auto-configures TLS + auth on first install, Kibana
# talks to it with a service-account token and serves HTTPS with a self-signed certificate.
# Idempotent: safe to re-run. Re-running upgrades to the newest release of ES_MAJOR unless ES_VER is pinned.
# Run as a sudo-capable user. Needs 4 GB RAM or more.
#
#   ES_VER=                Elastic version to pin for both, e.g. 9.5.5 (empty = newest of ES_MAJOR)
#   ES_MAJOR=9             apt repository line (9.x), derived from ES_VER when that is set
#   ES_HEAP=               Elasticsearch heap, e.g. 2g (empty = Elasticsearch sizes it from RAM)
#   KB_HOSTNAME=<this IP>  name or IP users browse Kibana at; goes into publicBaseUrl and the certificate
#   KB_PORT=5601
#   ES_EXPOSE=1            0 = no ufw rule for 9200 (only Kibana's port is opened; matters only if ufw is active)
#   ES_ALLOW=<subnet>      networks allowed through ufw (if ufw is active); default: this VM's subnet
set -euo pipefail
ES_VER=${ES_VER:-}
ES_MAJOR=${ES_MAJOR:-${ES_VER%%.*}}
ES_MAJOR=${ES_MAJOR:-9}
ES_HEAP=${ES_HEAP:-}
IP=$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')
KB_HOSTNAME=${KB_HOSTNAME:-$IP}
KB_PORT=${KB_PORT:-5601}
ES_EXPOSE=${ES_EXPOSE:-1}
if [ -z "${ES_ALLOW:-}" ]; then
  dev=$(ip -4 route show default | awk '{print $5; exit}')
  ES_ALLOW=$(python3 -c 'import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)' \
    "$(ip -o -4 addr show dev "$dev" | awk '{print $4; exit}')")
fi
ES_ETC=/etc/elasticsearch
KB_ETC=/etc/kibana
ES_BIN=/usr/share/elasticsearch/bin
KB_BIN=/usr/share/kibana/bin
CA=$ES_ETC/certs/http_ca.crt
TOKEN_NAME=kibana-lab
export DEBIAN_FRONTEND=noninteractive
say() { printf '\n== [%s] %s\n' "$(date +%T)" "$*"; }
[ "$(id -u)" -ne 0 ] || { echo "Run as a normal user with sudo, not root."; exit 1; }
cd /tmp

say "Elasticsearch + Kibana ${ES_VER:-(latest $ES_MAJOR.x)}, Kibana at https://$KB_HOSTNAME:$KB_PORT (allow $ES_ALLOW)"
mem_mb=$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo)
[ "$mem_mb" -ge 3800 ] || echo "WARNING: only ${mem_mb} MB RAM; Elasticsearch + Kibana want 4 GB or more"

say "Elastic apt repository ($ES_MAJOR.x)"
sudo apt-get update -qq
sudo apt-get install -y -qq apt-transport-https curl gnupg openssl ca-certificates >/dev/null
curl -fsSL https://artifacts.elastic.co/GPG-KEY-elasticsearch | sudo gpg --dearmor --yes -o /usr/share/keyrings/elastic.gpg
echo "deb [signed-by=/usr/share/keyrings/elastic.gpg] https://artifacts.elastic.co/packages/$ES_MAJOR.x/apt stable main" \
  | sudo tee /etc/apt/sources.list.d/elastic.list >/dev/null
sudo find /etc/apt/sources.list.d -name 'elastic-*.list' -delete   # older per-major files from manual installs
sudo apt-get update -qq

say "Packages"
FIRST=0; sudo test -d $ES_ETC/certs || FIRST=1
PREV=$(dpkg-query -W -f='${Version}' elasticsearch 2>/dev/null || true)
sudo apt-mark unhold elasticsearch kibana >/dev/null 2>&1 || true
if [ -n "$ES_VER" ]; then
  pkgs=("elasticsearch=$ES_VER" "kibana=$ES_VER")
else
  pkgs=(elasticsearch kibana)
fi
# on first install the elasticsearch package generates TLS certificates and turns security on;
# the elastic password it prints is discarded (it is reset below)
sudo apt-get install -y -qq -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "${pkgs[@]}" >/tmp/elastic-apt.log 2>&1 || { tail -30 /tmp/elastic-apt.log; exit 1; }
rm -f /tmp/elastic-apt.log
sudo apt-mark hold elasticsearch kibana >/dev/null   # ES and Kibana must stay on the same version
NOW=$(dpkg-query -W -f='${Version}' elasticsearch)
KB_NOW=$(dpkg-query -W -f='${Version}' kibana)
[ "$NOW" = "$KB_NOW" ] || { echo "version mismatch: elasticsearch $NOW, kibana $KB_NOW"; exit 1; }
if [ -z "$PREV" ]; then echo "installed $NOW"
elif [ "$PREV" != "$NOW" ]; then echo "upgraded $PREV -> $NOW"
else echo "$NOW already installed"; fi
sudo test -s $CA || { echo "missing $CA: security auto-configuration did not run"; exit 1; }
# the generated elastic password is reset once ES is up; remember that across a failed run
[ "$FIRST" = 1 ] && sudo touch $ES_ETC/.lab-reset-elastic-password

say "Elasticsearch settings"
if [ -n "$ES_HEAP" ]; then
  printf -- '-Xms%s\n-Xmx%s\n' "$ES_HEAP" "$ES_HEAP" | sudo tee $ES_ETC/jvm.options.d/heap.options >/dev/null
  echo "heap $ES_HEAP"
else
  sudo rm -f $ES_ETC/jvm.options.d/heap.options
  echo "heap: automatic"
fi
# the package's elasticsearch.yml (with its security auto-configuration block) is left as installed
sudo grep -E '^(cluster\.|http\.host|xpack\.security\.enabled)' $ES_ETC/elasticsearch.yml || true

say "Kibana service-account token"
TOKEN=""
sudo test -s $KB_ETC/.es-token && TOKEN=$(sudo cat $KB_ETC/.es-token)
if [ -n "$TOKEN" ] && sudo $ES_BIN/elasticsearch-service-tokens list 2>/dev/null | grep -qE "^elastic/kibana/$TOKEN_NAME[[:space:]]*$"; then
  echo "elastic/kibana/$TOKEN_NAME exists, kept"
else
  sudo $ES_BIN/elasticsearch-service-tokens delete elastic/kibana $TOKEN_NAME >/dev/null 2>&1 || true
  TOKEN=$(sudo $ES_BIN/elasticsearch-service-tokens create elastic/kibana $TOKEN_NAME | awk '{print $NF}')
  [ -n "$TOKEN" ] || { echo "could not create the service token"; exit 1; }
  echo "$TOKEN" | sudo tee $KB_ETC/.es-token >/dev/null
  sudo chmod 600 $KB_ETC/.es-token
  echo "created elastic/kibana/$TOKEN_NAME"
fi
sudo chown root:elasticsearch $ES_ETC/service_tokens
sudo chmod 660 $ES_ETC/service_tokens

say "Start Elasticsearch"
sudo systemctl daemon-reload
sudo systemctl enable -q elasticsearch
sudo systemctl restart elasticsearch
es() { curl -fsS --cacert "$CA" -H "Authorization: Bearer $TOKEN" "https://localhost:9200$1"; }
ok=0
for _ in $(seq 1 90); do
  es /_cluster/health >/dev/null 2>&1 && { ok=1; break; }
  systemctl is-active -q elasticsearch || break
  sleep 2
done
if [ "$ok" != 1 ]; then
  echo "Elasticsearch did not become ready. Last log lines:"
  sudo journalctl -u elasticsearch -n 30 --no-pager
  sudo tail -n 30 /var/log/elasticsearch/elasticsearch.log 2>/dev/null || true
  exit 1
fi
echo "cluster: $(es '/_cluster/health?filter_path=cluster_name,status,number_of_nodes' | tr -d '\n')"

say "elastic superuser password"
ES_PW=""
if sudo test -e $ES_ETC/.lab-reset-elastic-password; then
  ES_PW=$(sudo $ES_BIN/elasticsearch-reset-password -u elastic -b -s | tail -1)
  [ -n "$ES_PW" ] || { echo "password reset failed"; exit 1; }
  sudo rm -f $ES_ETC/.lab-reset-elastic-password
  echo "generated"
else
  echo "unchanged (reset with: sudo $ES_BIN/elasticsearch-reset-password -u elastic -i)"
fi

say "Kibana TLS + encryption keys"
sudo install -d -o root -g kibana -m 750 $KB_ETC/certs $KB_ETC/tls
sudo install -o root -g kibana -m 644 $CA $KB_ETC/certs/http_ca.crt
if ! sudo test -s $KB_ETC/tls/cert.pem; then
  SAN="DNS:localhost,IP:127.0.0.1,IP:$IP"
  if [[ $KB_HOSTNAME =~ ^[0-9.]+$ ]]; then
    [ "$KB_HOSTNAME" != "$IP" ] && SAN="$SAN,IP:$KB_HOSTNAME"
  else
    SAN="$SAN,DNS:$KB_HOSTNAME"
  fi
  sudo openssl req -x509 -newkey rsa:3072 -nodes -days 825 -subj "/CN=$KB_HOSTNAME" -addext "subjectAltName=$SAN" \
    -keyout $KB_ETC/tls/key.pem -out $KB_ETC/tls/cert.pem 2>/dev/null
  echo "self-signed for $SAN (replace cert.pem/key.pem with CA-signed ones any time)"
else
  echo "certificate exists, kept"
fi
sudo chown root:kibana $KB_ETC/tls/cert.pem $KB_ETC/tls/key.pem
sudo chmod 644 $KB_ETC/tls/cert.pem
sudo chmod 640 $KB_ETC/tls/key.pem
# the keys must survive re-runs: changing them makes saved objects (alerts, connectors, ...) unreadable
if ! sudo test -s $KB_ETC/.keys; then
  for _ in 1 2 3; do openssl rand -hex 32; done | sudo tee $KB_ETC/.keys >/dev/null
  sudo chmod 600 $KB_ETC/.keys
fi
mapfile -t KEYS < <(sudo cat $KB_ETC/.keys)

say "Config $KB_ETC/kibana.yml"
sudo tee $KB_ETC/kibana.yml >/dev/null <<EOF
# Managed by install-elastic-kibana.sh (re-running rewrites it). Options: https://www.elastic.co/docs/reference/kibana/configuration-reference
server.host: "0.0.0.0"
server.port: $KB_PORT
server.publicBaseUrl: "https://$KB_HOSTNAME:$KB_PORT"
server.ssl.enabled: true
server.ssl.certificate: $KB_ETC/tls/cert.pem
server.ssl.key: $KB_ETC/tls/key.pem

elasticsearch.hosts: ["https://localhost:9200"]
elasticsearch.serviceAccountToken: "$TOKEN"
elasticsearch.ssl.certificateAuthorities: ["$KB_ETC/certs/http_ca.crt"]

xpack.encryptedSavedObjects.encryptionKey: "${KEYS[0]}"
xpack.reporting.encryptionKey: "${KEYS[1]}"
xpack.security.encryptionKey: "${KEYS[2]}"

logging:
  appenders:
    file:
      type: file
      fileName: /var/log/kibana/kibana.log
      layout:
        type: json
  root:
    appenders: [default, file]
pid.file: /run/kibana/kibana.pid
EOF
sudo chown root:kibana $KB_ETC/kibana.yml
sudo chmod 640 $KB_ETC/kibana.yml

say "Start Kibana"
sudo systemctl enable -q kibana
sudo systemctl restart kibana

if command -v ufw >/dev/null && sudo ufw status | grep -q "Status: active"; then
  say "ufw: allow $ES_ALLOW -> tcp/$KB_PORT$([ "$ES_EXPOSE" = 1 ] && echo ", tcp/9200")"
  IFS=, read -ra nets <<<"$ES_ALLOW"
  for n in "${nets[@]}"; do
    sudo ufw allow from "$n" to any port "$KB_PORT" proto tcp >/dev/null
    if [ "$ES_EXPOSE" = 1 ]; then sudo ufw allow from "$n" to any port 9200 proto tcp >/dev/null; fi
  done
fi

say "Self-test (Kibana takes a minute or two on first start)"
ok=0
for _ in $(seq 1 150); do
  [ "$(curl -sk -o /dev/null -w '%{http_code}' "https://127.0.0.1:$KB_PORT/login")" = 200 ] && { ok=1; break; }
  systemctl is-active -q kibana || break
  sleep 2
done
if [ "$ok" != 1 ]; then
  echo "Kibana did not become ready. Last log lines:"
  sudo journalctl -u kibana -n 40 --no-pager
  exit 1
fi
echo "kibana: login page up"
if [ -n "$ES_PW" ]; then
  st=$(curl -fsk -u "elastic:$ES_PW" "https://127.0.0.1:$KB_PORT/api/status" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"]["overall"]["level"])' 2>/dev/null || echo "?")
  echo "kibana status: $st"
fi

cat <<EOF

=====================================================================
 Elasticsearch + Kibana $NOW are running
   Kibana:         https://$KB_HOSTNAME:$KB_PORT/
   Elasticsearch:  https://$IP:9200   (CA: $CA)
   Login:          elastic
EOF
if [ -n "$ES_PW" ]; then
  echo "   Password:       $ES_PW   (shown once: store it, then create named users in Kibana)"
else
  echo "   Password:       unchanged"
fi
cat <<EOF
   Certificates:   $KB_ETC/tls/cert.pem (Kibana, self-signed unless replaced)
   Services:       elasticsearch, kibana  (journalctl -u kibana -f; /var/log/elasticsearch/)
   Config:         $ES_ETC/elasticsearch.yml   $KB_ETC/kibana.yml
=====================================================================
EOF
