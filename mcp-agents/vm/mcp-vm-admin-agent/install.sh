#!/usr/bin/env bash
# mcp-vm-admin-agent installer / upgrader: runs the control centre as a systemd service.
# Run ON the admin host as the user the control centre should run as (it uses sudo for systemd).
#
#   ./install.sh [options]
#     --port N              listen port                                  (default 8767)
#     --host ADDR           listen address; keep loopback while auth=none (default 127.0.0.1)
#     --dir DIR             install dir for the jar                      (default ~/mcp-vm-admin-agent)
#     --home DIR            data dir: fleet.yml, tokens.yml, logs        (default ~/.vm-admin)
#     --jar FILE            jar to install        (default: ./mcp-vm-admin-agent.jar or ./target/...)
#     --build               run "mvn package" first (source tree only)
#     --java FILE           java 21+ binary                              (default: java on PATH)
#     --java-opts "OPTS"    extra JVM options, e.g. "-Xmx512m"
#     --no-mcp-token        don't generate ~/.vm-admin/mcp.token (MCP endpoint stays off)
#     --import-from DIR     copy hosts, tokens, schedules, metrics and state from an existing data dir
#                           (e.g. ./.run from 'java -jar' runs) into --home; only when --home has no fleet.yml
#
# Hosts, tokens, schedules, metrics and last known state all live in --home, so they survive restarts
# and upgrades. Re-running upgrades the jar (previous one kept as .prev) and never touches --home data.
set -euo pipefail

SRC=$(cd "$(dirname "$0")" && pwd)
SERVICE=mcp-vm-admin-agent
PORT=8767 BIND=127.0.0.1 DIR=$HOME/mcp-vm-admin-agent DATA=$HOME/.vm-admin
JAR="" BUILD=0 JAVA="" JAVA_OPTS="" MCP=1 IMPORT=""
while [ $# -gt 0 ]; do
  case $1 in
    --port) PORT=$2; shift 2 ;;
    --host) BIND=$2; shift 2 ;;
    --dir) DIR=$2; shift 2 ;;
    --home) DATA=$2; shift 2 ;;
    --jar) JAR=$2; shift 2 ;;
    --build) BUILD=1; shift ;;
    --java) JAVA=$2; shift 2 ;;
    --java-opts) JAVA_OPTS=$2; shift 2 ;;
    --no-mcp-token) MCP=0; shift ;;
    --import-from) IMPORT=$2; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)"; exit 2 ;;
  esac
done

say() { printf '\n== %s\n' "$*"; }
[ "$(id -u)" -ne 0 ] || { echo "Run as the normal user the control centre should run as (it uses sudo itself), not root."; exit 1; }
sudo -n true 2>/dev/null || sudo -v || { echo "sudo is required."; exit 1; }
case $BIND in
  127.*|::1|localhost) ;;
  *) echo "WARNING: --host $BIND. The UI has no sign-in, so it still answers only loopback clients;"
     echo "         binding wider only exposes /mcp (bearer token). Prefer an SSH tunnel." ;;
esac

# ------------------------------------------------------------------ java
java_major() { "$1" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit}'; }
java_ok() { [ -n "$1" ] && [ -x "$1" ] && [ "$(java_major "$1" 2>/dev/null || echo 0)" -ge 21 ] 2>/dev/null; }
say "Checking java (21+)"
[ -n "$JAVA" ] || JAVA=$(command -v java || true)
if ! java_ok "$JAVA"; then
  echo "java 21+ not found, installing a headless JRE"
  if command -v apt-get >/dev/null; then sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-21-jre-headless
  elif command -v dnf >/dev/null; then sudo dnf install -y -q java-21-openjdk-headless
  else echo "Install Java 21+ and re-run (or pass --java /path/to/java)."; exit 1; fi
  JAVA=$(command -v java)
fi
JAVA=$(readlink -f "$JAVA")
java_ok "$JAVA" || { echo "$JAVA is not Java 21+"; exit 1; }
echo "$JAVA ($("$JAVA" -version 2>&1 | head -1))"

# ------------------------------------------------------------------ jar
if [ "$BUILD" = 1 ]; then
  [ -f "$SRC/pom.xml" ] || { echo "--build needs the source tree (pom.xml)"; exit 1; }
  command -v mvn >/dev/null || { echo "--build needs Maven (mvn) on PATH"; exit 1; }
  # Build with the same Java 21+ found above, not whatever JAVA_HOME points to (often an older JDK,
  # which fails with "release version 21 not supported"). A JRE has no javac, so add the JDK if needed.
  JDK_HOME=$(dirname "$(dirname "$JAVA")")
  if [ ! -x "$JDK_HOME/bin/javac" ]; then
    echo "$JDK_HOME has no javac (JRE only), installing the JDK to build"
    if command -v apt-get >/dev/null; then sudo apt-get install -y -qq openjdk-21-jdk-headless
    elif command -v dnf >/dev/null; then sudo dnf install -y -q java-21-openjdk-devel
    fi
    [ -x "$JDK_HOME/bin/javac" ] || { echo "No javac in $JDK_HOME. Install a JDK 21+ or build yourself and drop --build."; exit 1; }
  fi
  say "Building (mvn package, JAVA_HOME=$JDK_HOME)"
  (cd "$SRC" && JAVA_HOME=$JDK_HOME mvn -q package)
fi
if [ -z "$JAR" ]; then
  for c in "$SRC/mcp-vm-admin-agent.jar" "$SRC/target/mcp-vm-admin-agent.jar"; do
    [ -f "$c" ] && { JAR=$c; break; }
  done
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "No jar found. Run with --build, or 'mvn package' first, or pass --jar."; exit 1; }
VER=$(python3 - "$JAR" 2>/dev/null <<'EOF' || echo unknown
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
n = next(n for n in z.namelist() if n.endswith("mcp-vm-admin-agent/pom.properties"))
print(next(l.split("=", 1)[1] for l in z.read(n).decode().splitlines() if l.startswith("version=")))
EOF
)
say "mcp-vm-admin-agent $VER -> $DIR  (listen $BIND:$PORT, data $DATA)"

if ss -ltnH "sport = :$PORT" 2>/dev/null | grep -q . && ! systemctl is-active -q $SERVICE; then
  echo "WARNING: something already listens on port $PORT (a 'java -jar' started by hand?). Stop it first,"
  echo "         or the service will fail to bind."
fi

# ------------------------------------------------------------------ files
say "Copying files"
mkdir -p "$DIR" "$DATA/logs"
chmod 700 "$DIR" "$DATA"
[ -f "$DIR/mcp-vm-admin-agent.jar" ] && cp "$DIR/mcp-vm-admin-agent.jar" "$DIR/mcp-vm-admin-agent.jar.prev"
install -m 0644 "$JAR" "$DIR/mcp-vm-admin-agent.jar"
install -m 0755 "$SRC/uninstall.sh" "$DIR/uninstall.sh"
echo "$VER" > "$DIR/VERSION"

if [ -n "$IMPORT" ]; then
  IMPORT=$(cd "$IMPORT" && pwd) || { echo "--import-from: no such directory"; exit 1; }
  if [ -f "$DATA/fleet.yml" ]; then
    echo "NOTE: $DATA/fleet.yml already exists; not importing from $IMPORT (move it away first to import)."
  elif [ ! -f "$IMPORT/fleet.yml" ]; then
    echo "--import-from $IMPORT: no fleet.yml there"; exit 1
  else
    say "Importing fleet from $IMPORT"
    for f in fleet.yml tokens.yml schedules.yml mcp.token; do
      [ -f "$IMPORT/$f" ] && install -m 0600 "$IMPORT/$f" "$DATA/$f" && echo "  $f"
    done
    for d in metrics state; do
      [ -d "$IMPORT/$d" ] && cp -a "$IMPORT/$d" "$DATA/" && echo "  $d/"
    done
    [ -f "$IMPORT/config.yml" ] && [ ! -f "$DATA/config.yml" ] \
      && sed "s|$IMPORT|$DATA|g" "$IMPORT/config.yml" > "$DATA/config.yml" && chmod 600 "$DATA/config.yml" && echo "  config.yml"
  fi
elif [ ! -f "$DATA/fleet.yml" ] && [ -f "$SRC/.run/fleet.yml" ]; then
  echo "NOTE: $SRC/.run/fleet.yml has hosts from earlier runs. Re-run with --import-from $SRC/.run to keep them."
fi

NEW_MCP=""
if [ "$MCP" = 1 ] && [ ! -s "$DATA/mcp.token" ]; then
  say "Generating MCP token ($DATA/mcp.token)"
  (umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$DATA/mcp.token")
  NEW_MCP=1
fi

if [ ! -f "$DATA/config.yml" ]; then
  say "Writing $DATA/config.yml"
  MCP_LINE="    token-ref: path:$DATA/mcp.token"
  [ -s "$DATA/mcp.token" ] || MCP_LINE="    # token-ref: path:$DATA/mcp.token"
  cat > "$DATA/config.yml" <<EOF
# mcp-vm-admin-agent config. All options: config.example.yml in the source tree.
# Hosts are added in the UI (they go to fleet.yml). Restart after editing: sudo systemctl restart $SERVICE
vmadmin:
  ui:
    auth: none
  mcp:
$MCP_LINE
  status:
    interval: 30s
    timeout: 5s
EOF
  chmod 600 "$DATA/config.yml"
else
  echo "Keeping existing $DATA/config.yml"
  if [ -n "$NEW_MCP" ] && ! grep -q 'token-ref: *path:.*mcp.token' "$DATA/config.yml"; then
    echo "NOTE: add 'vmadmin.mcp.token-ref: path:$DATA/mcp.token' to config.yml to enable /mcp"
  fi
fi

# ------------------------------------------------------------------ systemd
say "systemd service $SERVICE"
sudo tee /etc/systemd/system/$SERVICE.service >/dev/null <<EOF
[Unit]
Description=mcp-vm-admin-agent $VER (VM fleet control centre)
After=network-online.target
Wants=network-online.target

[Service]
User=$USER
WorkingDirectory=$DIR
Environment=VMADMIN_HOME=$DATA
Environment=VMADMIN_CONFIG=$DATA/config.yml
Environment=VMADMIN_LOG_DIR=$DATA/logs
Environment=VMADMIN_HOST=$BIND
Environment=VMADMIN_PORT=$PORT
ExecStart=$JAVA $JAVA_OPTS -jar $DIR/mcp-vm-admin-agent.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable -q $SERVICE
sudo systemctl restart $SERVICE

# ------------------------------------------------------------------ self-test
say "Self-test"
case $BIND in 0.0.0.0|::) TEST=127.0.0.1 ;; *) TEST=$BIND ;; esac
URL=http://$TEST:$PORT
[ "${TEST#*:}" != "$TEST" ] && URL="http://[$TEST]:$PORT"
ok=0
for _ in $(seq 1 60); do
  curl -fsS -o /dev/null "$URL/ui" 2>/dev/null && { ok=1; break; }
  systemctl is-active -q $SERVICE || break
  sleep 1
done
if [ "$ok" != 1 ]; then
  echo "The control centre did not come up. Last log lines:"
  sudo journalctl -u $SERVICE -n 30 --no-pager
  [ -f "$DIR/mcp-vm-admin-agent.jar.prev" ] && echo "Previous jar: $DIR/mcp-vm-admin-agent.jar.prev"
  exit 1
fi
echo "UI: HTTP $(curl -s -o /dev/null -w '%{http_code}' "$URL/ui")"
echo "MCP without token: HTTP $(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/mcp") (expect 401, or 503 if MCP is off)"

cat <<EOF

=====================================================================
 mcp-vm-admin-agent $VER is running
   Service:  $SERVICE   (journalctl -u $SERVICE -f)
   UI:       $URL/ui   (this machine only;
             elsewhere: ssh -L $PORT:127.0.0.1:$PORT $USER@$(hostname))
   Data:     $DATA   (config.yml, fleet.yml, tokens.yml, schedules.yml, metrics/, state/, logs/)
   Hosts:    $( [ -f "$DATA/fleet.yml" ] && grep -c '^- name:' "$DATA/fleet.yml" || echo 0 ) configured; kept across restarts and upgrades
EOF
if [ -s "$DATA/mcp.token" ]; then cat <<EOF

   Add to Claude Code on this machine:
   claude mcp add --transport http --scope user vm-admin $URL/mcp --header "Authorization: Bearer \$(cat $DATA/mcp.token)"
EOF
fi
cat <<EOF

 Rollback: cp $DIR/mcp-vm-admin-agent.jar.prev $DIR/mcp-vm-admin-agent.jar && sudo systemctl restart $SERVICE
 Uninstall: $DIR/uninstall.sh   (add --purge to delete $DIR and $DATA)
=====================================================================
EOF
