#!/usr/bin/env bash
# Remove the mcp-vm-admin-agent service. Keeps the jar and data unless --purge.
#   ./uninstall.sh [--purge] [--dir DIR] [--home DIR]
set -euo pipefail
SERVICE=mcp-vm-admin-agent DIR=$HOME/mcp-vm-admin-agent DATA=$HOME/.vm-admin PURGE=0
while [ $# -gt 0 ]; do
  case $1 in
    --purge) PURGE=1; shift ;;
    --dir) DIR=$2; shift 2 ;;
    --home) DATA=$2; shift 2 ;;
    *) echo "Unknown option: $1"; exit 2 ;;
  esac
done
sudo systemctl disable --now $SERVICE 2>/dev/null || true
sudo rm -f /etc/systemd/system/$SERVICE.service
sudo systemctl daemon-reload
if [ "$PURGE" = 1 ]; then
  rm -rf "$DIR" "$DATA"; echo "Removed service, $DIR and $DATA (fleet, tokens, logs)"
else
  echo "Removed service. Kept $DIR and $DATA (fleet.yml, tokens.yml, logs). Use --purge to delete."
fi
