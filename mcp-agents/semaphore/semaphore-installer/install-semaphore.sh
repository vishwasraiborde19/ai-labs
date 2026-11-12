#!/usr/bin/env bash
# Install Semaphore UI + PostgreSQL + Ansible + Terraform + OpenTofu on Ubuntu 22.04.
# Idempotent: safe to re-run. Run as a sudo-capable user.
set -euo pipefail
SEM_VER=${SEM_VER:-2.19.18}
TOFU_VER=${TOFU_VER:-1.13.8}
ADMIN_USER=admin
CONF=/etc/semaphore/config.json
WEB_HOST=${WEB_HOST:-http://$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}'):3000}
export DEBIAN_FRONTEND=noninteractive
say() { printf '\n== [%s] %s\n' "$(date +%T)" "$*"; }
cd /tmp

say "Base packages (postgresql, git, ...)"
sudo apt-get update -qq
sudo apt-get install -y -qq postgresql git curl gnupg lsb-release software-properties-common unzip openssl >/dev/null
psql --version

say "Ansible (official PPA)"
if ! grep -rqs ansible/ansible /etc/apt/sources.list.d/; then sudo add-apt-repository -y ppa:ansible/ansible >/dev/null; fi
sudo apt-get install -y -qq ansible >/dev/null
ansible --version | head -1

say "Terraform (HashiCorp apt repo)"
curl -fsSL https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor --yes -o /usr/share/keyrings/hashicorp.gpg
echo "deb [signed-by=/usr/share/keyrings/hashicorp.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/hashicorp.list >/dev/null
sudo apt-get update -qq
sudo apt-get install -y -qq terraform >/dev/null
terraform version | head -1

say "OpenTofu $TOFU_VER"
if ! tofu version 2>/dev/null | grep -q "v$TOFU_VER"; then
  base=https://github.com/opentofu/opentofu/releases/download/v$TOFU_VER
  curl -fsSLO "$base/tofu_${TOFU_VER}_amd64.deb"
  if curl -fsSLO "$base/tofu_${TOFU_VER}_SHA256SUMS"; then
    grep " tofu_${TOFU_VER}_amd64.deb$" "tofu_${TOFU_VER}_SHA256SUMS" | sha256sum -c
  else echo "WARNING: no SHA256SUMS published, skipping checksum"; fi
  sudo apt-get install -y -qq "./tofu_${TOFU_VER}_amd64.deb" >/dev/null
fi
tofu version | head -1

say "Semaphore $SEM_VER"
if ! semaphore version 2>/dev/null | grep -q "$SEM_VER"; then
  base=https://github.com/semaphoreui/semaphore/releases/download/v$SEM_VER
  curl -fsSLO "$base/semaphore_${SEM_VER}_linux_amd64.deb"
  curl -fsSLO "$base/semaphore_${SEM_VER}_checksums.txt"
  grep " semaphore_${SEM_VER}_linux_amd64.deb$" "semaphore_${SEM_VER}_checksums.txt" | sha256sum -c
  sudo apt-get install -y -qq "./semaphore_${SEM_VER}_linux_amd64.deb" >/dev/null
fi
SEM_BIN=$(command -v semaphore)
echo "binary: $SEM_BIN  version: $(semaphore version)"

say "System user + directories"
id semaphore >/dev/null 2>&1 || sudo useradd --system --home-dir /var/lib/semaphore --create-home --shell /bin/bash semaphore
sudo mkdir -p /var/lib/semaphore/tmp /etc/semaphore
sudo chown -R semaphore:semaphore /var/lib/semaphore
sudo chmod 750 /var/lib/semaphore

say "PostgreSQL role + database"
if ! sudo test -s /etc/semaphore/.dbpass; then
  openssl rand -hex 24 | sudo tee /etc/semaphore/.dbpass >/dev/null
  sudo chmod 600 /etc/semaphore/.dbpass
fi
DBPASS=$(sudo cat /etc/semaphore/.dbpass)
sudo systemctl enable -q --now postgresql
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='semaphore'" | grep -q 1; then
  sudo -u postgres psql -qc "ALTER ROLE semaphore WITH LOGIN PASSWORD '$DBPASS'"
else
  sudo -u postgres psql -qc "CREATE ROLE semaphore LOGIN PASSWORD '$DBPASS'"
fi
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='semaphore'" | grep -q 1 \
  || sudo -u postgres createdb -O semaphore semaphore
echo "database ready"

say "Config $CONF"
if ! sudo test -f "$CONF"; then
  python3 - "$DBPASS" "$WEB_HOST" <<'EOF' | sudo tee "$CONF" >/dev/null
import base64, json, secrets, sys
key = lambda: base64.b64encode(secrets.token_bytes(32)).decode()
print(json.dumps({
    "postgres": {"host": "127.0.0.1:5432", "user": "semaphore", "pass": sys.argv[1],
                 "name": "semaphore", "options": {"sslmode": "disable"}},
    "dialect": "postgres",
    "tmp_path": "/var/lib/semaphore/tmp",
    "cookie_hash": key(), "cookie_encryption": key(), "access_key_encryption": key(),
    "web_host": sys.argv[2],
    "max_parallel_tasks": 2,
    "email_alert": False, "telegram_alert": False, "slack_alert": False, "ldap_enable": False,
}, indent=2))
EOF
  echo "written"
else
  echo "exists, kept"
fi
sudo chown root:semaphore "$CONF"
sudo chmod 640 "$CONF"

say "Database migrations"
sudo -u semaphore "$SEM_BIN" migrate --config "$CONF" 2>&1 | tail -3

say "Admin user"
ADMIN_PW=""
if sudo -u semaphore "$SEM_BIN" user list --config "$CONF" 2>/dev/null | grep -qw "$ADMIN_USER"; then
  echo "admin user exists, password unchanged"
else
  ADMIN_PW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)
  sudo -u semaphore "$SEM_BIN" user add --admin --login "$ADMIN_USER" --name "Administrator" \
    --email admin@semaphore.local --password "$ADMIN_PW" --config "$CONF"
fi

say "systemd service"
sudo tee /etc/systemd/system/semaphore.service >/dev/null <<EOF
[Unit]
Description=Semaphore UI
Documentation=https://docs.semaphoreui.com
Wants=network-online.target
After=network-online.target postgresql.service
Requires=postgresql.service

[Service]
Type=simple
User=semaphore
Group=semaphore
WorkingDirectory=/var/lib/semaphore
ExecStart=$SEM_BIN server --config $CONF
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable -q semaphore
sudo systemctl restart semaphore

say "Self-test"
for i in $(seq 1 30); do curl -fs http://127.0.0.1:3000/api/ping >/dev/null && break; sleep 1; done
echo "ping: $(curl -fsS http://127.0.0.1:3000/api/ping)"
systemctl is-active semaphore
if [ -n "$ADMIN_PW" ]; then
  code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "{\"auth\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PW\"}" http://127.0.0.1:3000/api/auth/login)
  echo "admin login: HTTP $code (expect 204)"
fi

cat <<EOF

=====================================================================
 Semaphore $SEM_VER is running
   URL:    $WEB_HOST
   Login:  $ADMIN_USER
EOF
[ -n "$ADMIN_PW" ] && echo "   Password: $ADMIN_PW   (change it under your profile)"
cat <<EOF
   Service: semaphore  (journalctl -u semaphore -f)
   Config:  $CONF      DB: postgresql/semaphore
   Tools:   $(ansible --version | head -1 | tr -s ' '), $(terraform version | head -1), $(tofu version | head -1)
=====================================================================
EOF
