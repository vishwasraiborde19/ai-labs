#!/usr/bin/env bash
# mcp-vm-worker-agent installer / upgrader.
# Run ON the target VM as the user the agent should act as (needs sudo for systemd + ufw).
#
#   ./install.sh [options]
#     --port N              listen port                          (default 8765)
#     --allow CIDR[,CIDR]   networks allowed to reach port + UI  (default: this VM's subnet)
#     --name NAME           agent name shown in UI / MCP         (default: hostname)
#     --default-cwd DIR     working dir for commands              (default: $HOME)
#     --dir DIR             install dir                           (default: ~/mcp-vm-worker-agent)
#     --admin-password PW   set/reset the UI admin password       (default: generate on first install)
#     --no-bootstrap-token  don't create the first token
#     --tls                 serve HTTPS with a self-signed cert (MCP clients must trust it)
#
# Re-running upgrades the code and keeps tokens, admin password, plugins and jobs.
# An install made under the old name (mcp-vm-installer-agent, ~/mcp-vm-installer-agent) is migrated:
# the old service is removed and its data dir moved here, with a symlink left at the old path.
set -euo pipefail

SRC=$(cd "$(dirname "$0")" && pwd)
SERVICE=mcp-vm-worker-agent
PORT=8765 ALLOW="" NAME=$(hostname) DCWD=$HOME DIR=$HOME/mcp-vm-worker-agent
ADMIN_PW=${AGENT_ADMIN_PASSWORD:-} BOOT=1 TLS=0
while [ $# -gt 0 ]; do
  case $1 in
    --port) PORT=$2; shift 2 ;;
    --allow) ALLOW=$2; shift 2 ;;
    --name) NAME=$2; shift 2 ;;
    --default-cwd) DCWD=$2; shift 2 ;;
    --dir) DIR=$2; shift 2 ;;
    --admin-password) ADMIN_PW=$2; shift 2 ;;
    --no-bootstrap-token) BOOT=0; shift ;;
    --tls) TLS=1; shift ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)"; exit 2 ;;
  esac
done

say() { printf '\n== %s\n' "$*"; }
[ "$(id -u)" -ne 0 ] || { echo "Run as the normal user the agent should act as (it uses sudo itself), not root."; exit 1; }
# `sudo -v` prompts whenever any sudoers rule for the user needs a password (Ubuntu's %sudo
# default does), even with a NOPASSWD rule; try non-interactive sudo first.
sudo -n true 2>/dev/null || sudo -v || { echo "sudo is required."; exit 1; }
VER=$(cat "$SRC/VERSION")

if [ -z "$ALLOW" ]; then
  dev=$(ip -4 route show default | awk '{print $5; exit}')
  cidr=$(ip -o -4 addr show dev "$dev" | awk '{print $4; exit}')
  ALLOW=$(python3 -c 'import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)' "$cidr")
fi
IP=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')
say "mcp-vm-worker-agent $VER -> $DIR  (port $PORT, allow $ALLOW, name $NAME, ip $IP)"

say "Checking python"
python3 -c 'import sys; assert sys.version_info >= (3,10), "Python 3.10+ required"; print(sys.version.split()[0])'
python3 -m venv --help >/dev/null 2>&1 && python3 -c 'import ensurepip' 2>/dev/null \
  || { sudo apt-get update -qq && sudo apt-get install -y -qq python3-venv; }

# ------------------------------------------------ rename: mcp-vm-installer-agent -> mcp-vm-worker-agent
LEGACY_SERVICE=mcp-vm-installer-agent LEGACY_DIR=$HOME/mcp-vm-installer-agent
if [ -f /etc/systemd/system/$LEGACY_SERVICE.service ]; then
  say "Replacing old service $LEGACY_SERVICE with $SERVICE"
  sudo systemctl disable --now $LEGACY_SERVICE 2>/dev/null || true
  sudo rm -f /etc/systemd/system/$LEGACY_SERVICE.service
  sudo systemctl daemon-reload
fi
if [ -d "$LEGACY_DIR" ] && [ ! -L "$LEGACY_DIR" ]; then
  if [ ! -e "$DIR" ]; then
    say "Moving $LEGACY_DIR -> $DIR (tokens, admin password, jobs, plugins, TLS kept)"
    mv "$LEGACY_DIR" "$DIR"
    rm -rf "$DIR/venv"               # venv scripts hard-code their path; rebuilt below
    ln -s "$DIR" "$LEGACY_DIR"       # old paths (running jobs, notes, scripts) keep working
  else
    echo "NOTE: both $LEGACY_DIR and $DIR exist; using $DIR. Remove the old one when no longer needed."
  fi
fi

FIRST=1; [ -f "$DIR/tokens.json" ] && FIRST=0
say "Copying agent files"
mkdir -p "$DIR/plugins" "$DIR/jobs"
if [ -f "$DIR/server.py" ]; then cp "$DIR/server.py" "$DIR/server.py.prev"; fi
install -m 0644 "$SRC/agent/server.py" "$SRC/agent/ui.html" "$DIR/"
install -m 0755 "$SRC/agent/run.sh" "$DIR/"
cp "$SRC/VERSION" "$DIR/VERSION"
chmod 700 "$DIR"

say "Python venv + dependencies"
[ -x "$DIR/venv/bin/python" ] || python3 -m venv "$DIR/venv"
"$DIR/venv/bin/pip" install -q --upgrade pip
"$DIR/venv/bin/pip" install -q "mcp>=1.10,<2" "uvicorn>=0.30"
"$DIR/venv/bin/python" -c "import mcp.server.fastmcp, uvicorn; print('deps OK')"
PY="$DIR/venv/bin/python $DIR/server.py"

NEW_PW=""
if [ -n "$ADMIN_PW" ]; then
  AGENT_ADMIN_PASSWORD=$ADMIN_PW $PY admin-password >/dev/null; NEW_PW=$ADMIN_PW
elif [ ! -f "$DIR/admin.json" ]; then
  NEW_PW=$(python3 -c 'import secrets; print(secrets.token_urlsafe(15))')
  AGENT_ADMIN_PASSWORD=$NEW_PW $PY admin-password >/dev/null
fi
[ -n "$NEW_PW" ] && say "UI admin password set"

BOOT_TOKEN=""
if [ "$BOOT" = 1 ] && [ "$FIRST" = 1 ]; then
  say "Creating bootstrap token (scopes rest,mcp)"
  BOOT_TOKEN=$($PY token create bootstrap --scopes rest,mcp --quiet)
fi

TLS_ENV="" SCHEME=http CURLK=""
if [ "$TLS" = 1 ]; then
  say "TLS: self-signed certificate"
  mkdir -p "$DIR/tls"
  if [ ! -f "$DIR/tls/cert.pem" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 825 -subj "/CN=$NAME" \
      -addext "subjectAltName=IP:$IP,DNS:$(hostname),DNS:localhost,IP:127.0.0.1" \
      -keyout "$DIR/tls/key.pem" -out "$DIR/tls/cert.pem" 2>/dev/null
    chmod 600 "$DIR/tls/key.pem"
  fi
  TLS_ENV="Environment=AGENT_TLS_CERT=$DIR/tls/cert.pem
Environment=AGENT_TLS_KEY=$DIR/tls/key.pem"
  SCHEME=https CURLK=-k
fi

say "systemd service $SERVICE"
sudo tee /etc/systemd/system/$SERVICE.service >/dev/null <<EOF
[Unit]
Description=mcp-vm-worker-agent $VER (MCP + REST ops agent)
After=network-online.target
Wants=network-online.target

[Service]
User=$USER
WorkingDirectory=$DIR
Environment=AGENT_NAME=$NAME
Environment=AGENT_PORT=$PORT
Environment=AGENT_DEFAULT_CWD=$DCWD
Environment=AGENT_UI_ALLOW=127.0.0.0/8,::1/128,$ALLOW
$TLS_ENV
ExecStart=$DIR/run.sh
Restart=always
RestartSec=2
# keep background jobs alive across restarts and self-updates
KillMode=process

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable -q $SERVICE
sudo systemctl stop $SERVICE 2>/dev/null || true

# Make sure nothing else holds the port, or the new agent cannot bind and the self-test would talk to
# whatever is there. Agents older than 2.0 (run.sh without the SIGTERM trap) leave server.py running
# after "systemctl stop"; those are stopped here. Anything else is reported and the install stops.
port_pids() { { sudo ss -ltnpH "sport = :$PORT" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u; } || true; }
for _ in 1 2 3 4 5 6 7 8 9 10; do
  pids=$(port_pids)
  [ -z "$pids" ] && break
  for p in $pids; do
    cmd=$(ps -o args= -p "$p" 2>/dev/null || true)
    unit=$(ps -o unit= -p "$p" 2>/dev/null | tr -d ' ' || true)
    case "$unit" in
      ""|"$SERVICE.service"|*.scope|-) ;;
      *) echo "ERROR: port $PORT is held by pid $p ($cmd), started by systemd unit $unit."
         echo "       If that is an older agent: sudo systemctl disable --now $unit   then re-run install.sh,"
         echo "       or install this one on another port with --port."
         exit 1 ;;
    esac
    case "$cmd" in
      *server.py*) echo "Stopping stale agent on port $PORT (pid $p: $cmd)"; sudo kill "$p" 2>/dev/null || true ;;
      *) echo "ERROR: port $PORT is used by pid $p ($cmd). Stop it or use --port."; exit 1 ;;
    esac
  done
  sleep 0.5
done
for p in $(port_pids); do echo "Force-stopping pid $p"; sudo kill -9 "$p" 2>/dev/null || true; done
sleep 0.5
[ -z "$(port_pids)" ] || { echo "ERROR: port $PORT is still in use"; exit 1; }

sudo systemctl start $SERVICE

if command -v ufw >/dev/null && sudo ufw status | grep -q "Status: active"; then
  say "ufw: allow $ALLOW -> tcp/$PORT"
  IFS=, read -ra nets <<<"$ALLOW"
  for n in "${nets[@]}"; do sudo ufw allow from "$n" to any port "$PORT" proto tcp >/dev/null; done
fi

say "Self-test"
URL=$SCHEME://127.0.0.1:$PORT
for i in $(seq 1 20); do curl -fsS $CURLK "$URL/health" >/dev/null 2>&1 && break; sleep 0.5; done
HEALTH=$(curl -fsS $CURLK "$URL/health" 2>/dev/null || true)
echo "$HEALTH"
RUNNING=$(printf '%s' "$HEALTH" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("version",""))' 2>/dev/null || true)
if [ "$RUNNING" != "$VER" ]; then
  echo
  echo "ERROR: expected version $VER on port $PORT, got '${RUNNING:-no answer}'. The new agent is not the one running."
  sudo ss -ltnpH "sport = :$PORT" 2>/dev/null || true
  sudo journalctl -u $SERVICE -n 20 --no-pager || true
  exit 1
fi
code=$(curl -s $CURLK -o /dev/null -w '%{http_code}' "$URL/ui"); echo "UI: HTTP $code"
code=$(curl -s $CURLK -o /dev/null -w '%{http_code}' "$URL/api/info"); echo "REST without token: HTTP $code (expect 401)"
if [ -n "$BOOT_TOKEN" ]; then
  curl -fsS $CURLK -H "Authorization: Bearer $BOOT_TOKEN" "$URL/api/info" | head -c 300; echo
fi

MCP_NAME=$(echo "$NAME" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9_\n-' '-')
cat <<EOF

=====================================================================
 mcp-vm-worker-agent $VER is running on $NAME
   Service:   $SERVICE   (journalctl -u $SERVICE -f)
   Audit log: $DIR/audit.log
   Token UI:  $SCHEME://$IP:$PORT/ui   (from $ALLOW)
EOF
[ -n "$NEW_PW" ] && cat <<EOF

   UI admin password: $NEW_PW
   (Change it in the UI under "Admin password".)
EOF
if [ -n "$BOOT_TOKEN" ]; then cat <<EOF

   Bootstrap token (rest+mcp, shown once):
   $BOOT_TOKEN

   Add to Claude Code on your PC:
   claude mcp add --transport http --scope user $MCP_NAME $SCHEME://$IP:$PORT/mcp --header "Authorization: Bearer $BOOT_TOKEN"
EOF
fi
cat <<EOF

 Tokens grant a shell as $USER. Revoke unused ones in the UI.
 Uninstall: $SRC/uninstall.sh   (add --purge to delete $DIR)
=====================================================================
EOF
