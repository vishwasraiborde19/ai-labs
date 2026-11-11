#!/usr/bin/env bash
# Copy the built control centre to an admin host over SSH and run install.sh there.
#   ./deploy.sh user@host [install.sh options...]
# Build first (mvn package). The UI then answers only on that host: ssh -L 8767:127.0.0.1:8767 user@host
set -euo pipefail
TARGET=${1:?usage: deploy.sh user@host [install.sh options]}; shift
SRC=$(cd "$(dirname "$0")" && pwd)
JAR=$SRC/target/mcp-vm-admin-agent.jar
[ -f "$JAR" ] || { echo "No $JAR; run 'mvn package' first."; exit 1; }
REMOTE=.cache/mcp-vm-admin-agent-src
echo "== Uploading mcp-vm-admin-agent to $TARGET"
tar -czf - -C "$SRC" install.sh uninstall.sh README.md config/config.example.yml \
    -C "$SRC/target" mcp-vm-admin-agent.jar \
  | ssh "$TARGET" "rm -rf ~/$REMOTE && mkdir -p ~/$REMOTE && tar -xzf - -C ~/$REMOTE"
echo "== Running install.sh on $TARGET"
ssh -t "$TARGET" "bash ~/$REMOTE/install.sh $(printf '%q ' "$@")"
