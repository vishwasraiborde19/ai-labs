#!/usr/bin/env bash
# Build a versioned tarball: dist/mcp-vm-installer-agent-<version>.tar.gz
# Install it on any VM:  tar xzf mcp-vm-installer-agent-*.tar.gz && ./mcp-vm-installer-agent-*/install.sh
set -euo pipefail
SRC=$(cd "$(dirname "$0")" && pwd)
VER=$(cat "$SRC/VERSION")
NAME=mcp-vm-installer-agent-$VER
mkdir -p "$SRC/dist"
tar -C "$SRC" --transform "s,^,$NAME/," -czf "$SRC/dist/$NAME.tar.gz" \
  agent/server.py agent/ui.html agent/run.sh install.sh uninstall.sh deploy.sh VERSION README.md
sha256sum "$SRC/dist/$NAME.tar.gz" | tee "$SRC/dist/$NAME.tar.gz.sha256"
