#!/usr/bin/env bash
# Copy the agent package to a VM over SSH and run install.sh there.
#   ./deploy.sh user@host [install.sh options...]
# Example:
#   ./deploy.sh vishwas@192.168.1.42 --name semaphore-vm
set -euo pipefail
TARGET=${1:?usage: deploy.sh user@host [install.sh options]}; shift
SRC=$(cd "$(dirname "$0")" && pwd)
REMOTE=.cache/mcp-vm-installer-agent-src
echo "== Uploading mcp-vm-installer-agent $(cat "$SRC/VERSION") to $TARGET"
tar -C "$SRC" -czf - agent install.sh uninstall.sh VERSION README.md \
  | ssh "$TARGET" "rm -rf ~/$REMOTE && mkdir -p ~/$REMOTE && tar -xzf - -C ~/$REMOTE"
echo "== Running install.sh on $TARGET"
ssh -t "$TARGET" "bash ~/$REMOTE/install.sh $(printf '%q ' "$@")"
