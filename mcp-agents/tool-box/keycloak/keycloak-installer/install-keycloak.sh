#!/usr/bin/env bash
# Install Keycloak (OIDC / SAML identity provider) + PostgreSQL + OpenJDK 21 on Ubuntu 22.04.
# HTTPS with a self-signed certificate by default (Kubernetes OIDC needs an https issuer).
# Idempotent: safe to re-run. Bump KC_VER to upgrade in place (the database migrates on start).
# Run as a sudo-capable user.
#
#   KC_VER=26.8.2          Keycloak release
#   KC_HOSTNAME=<this IP>  name or IP clients use; goes into the issuer URL and the certificate
#   KC_HTTPS_PORT=8443
#   KC_HTTP=0              1 = also serve plain HTTP on KC_HTTP_PORT (8080); avoid outside a lab
#   KC_ALLOW=<subnet>      networks allowed through ufw (if ufw is active); default: this VM's subnet
#   KC_ADMIN=admin         bootstrap admin login (created only when the master realm has no users)
set -euo pipefail
KC_VER=${KC_VER:-26.8.2}
IP=$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')
KC_HOSTNAME=${KC_HOSTNAME:-$IP}
KC_HTTPS_PORT=${KC_HTTPS_PORT:-8443}
KC_HTTP=${KC_HTTP:-0}
KC_HTTP_PORT=${KC_HTTP_PORT:-8080}
KC_ADMIN=${KC_ADMIN:-admin}
if [ -z "${KC_ALLOW:-}" ]; then
  dev=$(ip -4 route show default | awk '{print $5; exit}')
  KC_ALLOW=$(python3 -c 'import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)' \
    "$(ip -o -4 addr show dev "$dev" | awk '{print $4; exit}')")
fi
ETC=/etc/keycloak
CONF=$ETC/keycloak.conf
HOME_DIR=/opt/keycloak-$KC_VER
LINK=/opt/keycloak
KC="$LINK/bin/kc.sh --config-file=$CONF"
export DEBIAN_FRONTEND=noninteractive
say() { printf '\n== [%s] %s\n' "$(date +%T)" "$*"; }
[ "$(id -u)" -ne 0 ] || { echo "Run as a normal user with sudo, not root."; exit 1; }
cd /tmp

say "Keycloak $KC_VER for https://$KC_HOSTNAME:$KC_HTTPS_PORT (allow $KC_ALLOW)"

say "Packages (OpenJDK 21, PostgreSQL, ...)"
sudo apt-get update -qq
sudo apt-get install -y -qq openjdk-21-jre-headless postgresql curl openssl ca-certificates tar >/dev/null
java -version 2>&1 | head -1
psql --version

say "System user"
id keycloak >/dev/null 2>&1 || sudo useradd --system --home-dir /var/lib/keycloak --create-home --shell /usr/sbin/nologin keycloak
sudo install -d -o root -g keycloak -m 750 $ETC $ETC/tls

say "Keycloak $KC_VER -> $HOME_DIR"
if [ ! -x "$HOME_DIR/bin/kc.sh" ]; then
  base=https://github.com/keycloak/keycloak/releases/download/$KC_VER
  curl -fsSLO "$base/keycloak-$KC_VER.tar.gz"
  curl -fsSLO "$base/keycloak-$KC_VER.tar.gz.sha1"
  echo "$(cut -d' ' -f1 "keycloak-$KC_VER.tar.gz.sha1")  keycloak-$KC_VER.tar.gz" | sha1sum -c
  sudo tar -xzf "keycloak-$KC_VER.tar.gz" -C /opt
  rm -f "keycloak-$KC_VER.tar.gz" "keycloak-$KC_VER.tar.gz.sha1"
  # "kc.sh build" writes the optimised server into lib/ and runtime data into data/
  sudo chown -R keycloak:keycloak "$HOME_DIR"
  sudo chmod -R o-rwx "$HOME_DIR"
else
  echo "already installed"
fi
PREV=$(readlink -f "$LINK" 2>/dev/null || true)
sudo ln -sfn "$HOME_DIR" "$LINK"
[ -n "$PREV" ] && [ "$PREV" != "$HOME_DIR" ] && echo "upgraded from $(basename "$PREV") (kept in $PREV)"
"$LINK/bin/kc.sh" --version 2>/dev/null | grep -m1 -i keycloak || true

say "PostgreSQL role + database"
if ! sudo test -s $ETC/.dbpass; then
  openssl rand -hex 24 | sudo tee $ETC/.dbpass >/dev/null
  sudo chmod 600 $ETC/.dbpass
fi
DBPASS=$(sudo cat $ETC/.dbpass)
sudo systemctl enable -q --now postgresql
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='keycloak'" | grep -q 1; then
  sudo -u postgres psql -qc "ALTER ROLE keycloak WITH LOGIN PASSWORD '$DBPASS'"
else
  sudo -u postgres psql -qc "CREATE ROLE keycloak LOGIN PASSWORD '$DBPASS'"
fi
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='keycloak'" | grep -q 1 \
  || sudo -u postgres createdb -O keycloak keycloak
echo "database ready"

say "TLS certificate"
if ! sudo test -s $ETC/tls/cert.pem; then
  SAN="DNS:localhost,IP:127.0.0.1,IP:$IP"
  if [[ $KC_HOSTNAME =~ ^[0-9.]+$ ]]; then
    [ "$KC_HOSTNAME" != "$IP" ] && SAN="$SAN,IP:$KC_HOSTNAME"
  else
    SAN="$SAN,DNS:$KC_HOSTNAME"
  fi
  sudo openssl req -x509 -newkey rsa:3072 -nodes -days 825 -subj "/CN=$KC_HOSTNAME" -addext "subjectAltName=$SAN" \
    -keyout $ETC/tls/key.pem -out $ETC/tls/cert.pem 2>/dev/null
  echo "self-signed for $SAN (replace cert.pem/key.pem with CA-signed ones any time)"
else
  echo "exists, kept"
fi
sudo chown root:keycloak $ETC/tls/cert.pem $ETC/tls/key.pem
sudo chmod 644 $ETC/tls/cert.pem
sudo chmod 640 $ETC/tls/key.pem

say "Config $CONF"
HTTP_LINES="http-enabled=false"
[ "$KC_HTTP" = 1 ] && HTTP_LINES="http-enabled=true
http-port=$KC_HTTP_PORT"
sudo tee $CONF >/dev/null <<EOF
# Managed by install-keycloak.sh (re-running rewrites it). Options: https://www.keycloak.org/server/all-config
db=postgres
db-url=jdbc:postgresql://127.0.0.1:5432/keycloak
db-username=keycloak
db-password=$DBPASS

hostname=https://$KC_HOSTNAME:$KC_HTTPS_PORT
https-port=$KC_HTTPS_PORT
https-certificate-file=$ETC/tls/cert.pem
https-certificate-key-file=$ETC/tls/key.pem
$HTTP_LINES

health-enabled=true
metrics-enabled=true
log=console
EOF
sudo chown root:keycloak $CONF
sudo chmod 640 $CONF

say "Build (optimise for this config)"
sudo systemctl stop keycloak 2>/dev/null || true
sudo -u keycloak $KC build 2>&1 | tail -3

say "Admin user"
ADMIN_PW=""
users=$(sudo -u postgres psql -d keycloak -tAc \
  "SELECT count(*) FROM user_entity u JOIN realm r ON r.id = u.realm_id WHERE r.name = 'master'" 2>/dev/null || echo 0)
if [ "${users:-0}" -gt 0 ] 2>/dev/null; then
  echo "master realm already has $users user(s), password unchanged"
else
  ADMIN_PW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)
  sudo -u keycloak env KC_BOOTSTRAP_USER="$KC_ADMIN" KC_BOOTSTRAP_PW="$ADMIN_PW" \
    $KC bootstrap-admin user --username:env KC_BOOTSTRAP_USER --password:env KC_BOOTSTRAP_PW --no-prompt 2>&1 | tail -3
fi

say "systemd service"
sudo tee /etc/systemd/system/keycloak.service >/dev/null <<EOF
[Unit]
Description=Keycloak $KC_VER
Documentation=https://www.keycloak.org/documentation
Wants=network-online.target
After=network-online.target postgresql.service
Requires=postgresql.service

[Service]
Type=exec
User=keycloak
Group=keycloak
WorkingDirectory=/var/lib/keycloak
ExecStart=$LINK/bin/kc.sh --config-file=$CONF start --optimized
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
TimeoutStartSec=300
LimitNOFILE=102642
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable -q keycloak
sudo systemctl restart keycloak

if command -v ufw >/dev/null && sudo ufw status | grep -q "Status: active"; then
  say "ufw: allow $KC_ALLOW -> tcp/$KC_HTTPS_PORT"
  IFS=, read -ra nets <<<"$KC_ALLOW"
  for n in "${nets[@]}"; do
    sudo ufw allow from "$n" to any port "$KC_HTTPS_PORT" proto tcp >/dev/null
    [ "$KC_HTTP" = 1 ] && sudo ufw allow from "$n" to any port "$KC_HTTP_PORT" proto tcp >/dev/null
  done
fi

say "Self-test"
ok=0
for _ in $(seq 1 90); do
  curl -fsk https://127.0.0.1:9000/health/ready >/dev/null 2>&1 && { ok=1; break; }
  systemctl is-active -q keycloak || break
  sleep 2
done
if [ "$ok" != 1 ]; then
  echo "Keycloak did not become ready. Last log lines:"
  sudo journalctl -u keycloak -n 40 --no-pager
  exit 1
fi
echo "health: $(curl -fsk https://127.0.0.1:9000/health/ready)"
ISSUER=$(curl -fsk --resolve "$KC_HOSTNAME:$KC_HTTPS_PORT:127.0.0.1" \
  "https://$KC_HOSTNAME:$KC_HTTPS_PORT/realms/master/.well-known/openid-configuration" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["issuer"])' 2>/dev/null || echo "?")
echo "OIDC issuer: $ISSUER"

cat <<EOF

=====================================================================
 Keycloak $KC_VER is running
   Admin console: https://$KC_HOSTNAME:$KC_HTTPS_PORT/admin/
   Login:         $KC_ADMIN
EOF
[ -n "$ADMIN_PW" ] && cat <<EOF
   Password:      $ADMIN_PW
                  (temporary bootstrap admin: create a permanent admin, then delete this one)
EOF
cat <<EOF
   Issuer (OIDC): https://$KC_HOSTNAME:$KC_HTTPS_PORT/realms/<realm>
   Certificate:   $ETC/tls/cert.pem   (clients must trust it; self-signed unless replaced)
   Service:       keycloak  (journalctl -u keycloak -f)     Health/metrics: :9000
   Config:        $CONF      DB: postgresql/keycloak
=====================================================================
EOF
