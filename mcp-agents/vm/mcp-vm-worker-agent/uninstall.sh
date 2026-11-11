#!/usr/bin/env bash
# Remove the mcp-vm-worker-agent service. Keeps the data dir unless --purge.
#   ./uninstall.sh [--purge] [--dir DIR] [--port N]
set -euo pipefail
SERVICE=mcp-vm-worker-agent DIR=$HOME/mcp-vm-worker-agent PORT=8765 PURGE=0
while [ $# -gt 0 ]; do
  case $1 in
    --purge) PURGE=1; shift ;;
    --dir) DIR=$2; shift 2 ;;
    --port) PORT=$2; shift 2 ;;
    *) echo "Unknown option: $1"; exit 2 ;;
  esac
done
for unit in $SERVICE mcp-vm-installer-agent; do   # mcp-vm-installer-agent: name before the rename
  sudo systemctl disable --now $unit 2>/dev/null || true
  sudo rm -f /etc/systemd/system/$unit.service
done
sudo systemctl daemon-reload
if command -v ufw >/dev/null && sudo ufw status | grep -q "Status: active"; then
  sudo ufw status numbered | grep -E " $PORT/tcp " | grep -oE '^\[ *[0-9]+\]' | tr -d '[] ' | sort -rn \
    | while read -r n; do yes | sudo ufw delete "$n" >/dev/null; done
fi
if [ "$PURGE" = 1 ]; then
  rm -rf "$DIR"; [ -L "$HOME/mcp-vm-installer-agent" ] && rm -f "$HOME/mcp-vm-installer-agent"
  echo "Removed service and $DIR"
else
  echo "Removed service. Data kept in $DIR (tokens, audit.log, jobs). Use --purge to delete."
fi
