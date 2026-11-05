#!/usr/bin/env bash
# Launcher used by systemd. Exit code 3 = restart requested by update_server.
# If a freshly updated server.py crashes within 15s, restore server.py.prev.
#
# The unit uses KillMode=process so background jobs survive restarts. That means systemd signals
# only this script, so forward SIGTERM/SIGINT to the server: otherwise it outlives the unit, keeps
# the port, and every restart fails with "address already in use".
cd "$(dirname "$0")"
start=$(date +%s)
./venv/bin/python server.py &
child=$!
trap 'kill -TERM "$child" 2>/dev/null; wait "$child"; exit 0' TERM INT
wait "$child"
rc=$?
dur=$(( $(date +%s) - start ))
if [ "$rc" -ne 0 ] && [ "$rc" -ne 3 ] && [ "$dur" -lt 15 ] && [ -f server.py.prev ]; then
  echo "$(date '+%F %T') server.py crashed after ${dur}s (rc=$rc) - rolling back to server.py.prev" | tee -a audit.log
  cp server.py "server.py.failed-$(date +%Y%m%d-%H%M%S)"
  mv server.py.prev server.py
fi
exit "$rc"
