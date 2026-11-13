#!/usr/bin/env bash
# Start, stop and check the local agent stack:
#   devops        mcp-devops-agent, MCP over HTTP        127.0.0.1:8766/mcp
#   kube          mcp-kube-agent,   MCP over HTTP        127.0.0.1:8768/mcp  (8767 is the VM control centre's port)
#   orchestrator  mcp-orchestrator-agent, web UI         http://127.0.0.1:8770/ui
#
# Usage: ./agents.sh start|stop|restart|status|logs [devops|kube|orchestrator ...] [--build]
#
# On first start it creates a bearer token per agent (~/.ai-labs/tokens/*.token, mode 600) and registers both
# agents with the orchestrator through drop-in files (~/.orchestrator/agents.d/{devops,kube}.yml) that point
# at those token files. Drop-ins you have edited (marker line removed) are left alone.
#
# Environment: AI_LABS_HOME (~/.ai-labs), ORCHESTRATOR_HOME (~/.orchestrator), DEVOPS_PORT (8766),
#              KUBE_PORT (8768), ORCHESTRATOR_PORT (8770), JAVA (java), JAVA_OPTS
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE="${AI_LABS_HOME:-$HOME/.ai-labs}"
ORCH_HOME="${ORCHESTRATOR_HOME:-$HOME/.orchestrator}"
DEVOPS_PORT="${DEVOPS_PORT:-8766}"
KUBE_PORT="${KUBE_PORT:-8768}"
ORCHESTRATOR_PORT="${ORCHESTRATOR_PORT:-8770}"
JAVA="${JAVA:-java}"
MARKER="# managed by agents.sh; delete this line to keep your own edits"
ALL=(devops kube orchestrator)

die() { echo "error: $*" >&2; exit 1; }
say() { printf '%-13s %s\n' "$1" "$2"; }

module() { case "$1" in devops) echo mcp-devops-agent ;; kube) echo mcp-kube-agent ;; orchestrator) echo mcp-orchestrator-agent ;; *) die "unknown agent '$1' (devops, kube, orchestrator)" ;; esac; }
port()   { case "$1" in devops) echo "$DEVOPS_PORT" ;; kube) echo "$KUBE_PORT" ;; orchestrator) echo "$ORCHESTRATOR_PORT" ;; esac; }
jar()    { echo "$ROOT/$(module "$1")/target/$(module "$1").jar"; }
pidf()   { echo "$STATE/run/$1.pid"; }
logf()   { echo "$STATE/logs/$1.out"; }
url()    { if [[ $1 == orchestrator ]]; then echo "http://127.0.0.1:$(port "$1")/ui"; else echo "http://127.0.0.1:$(port "$1")/mcp"; fi; }

running() {
  local f; f="$(pidf "$1")"
  [[ -f $f ]] && kill -0 "$(cat "$f")" 2>/dev/null
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

check_java() {
  command -v "$JAVA" >/dev/null || die "java not found; install JDK 21 or set JAVA=/path/to/java"
  local v
  v="$("$JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)"
  [[ -n $v && $v -ge 21 ]] || die "Java 21+ needed, found '$("$JAVA" -version 2>&1 | head -1)'"
}

build() {
  local name=$1 force=$2 dir
  dir="$ROOT/$(module "$name")"
  if [[ $force == 1 || ! -f $(jar "$name") ]]; then
    command -v mvn >/dev/null || die "$(jar "$name") is missing and mvn is not installed"
    say "$name" "building ($(module "$name"))..."
    (cd "$dir" && mvn -q -DskipTests package) || die "build of $name failed"
  fi
}

token() {
  local f="$STATE/tokens/$1.token"
  if [[ ! -s $f ]]; then
    mkdir -p "$STATE/tokens" && chmod 700 "$STATE/tokens"
    (umask 077; head -c 36 /dev/urandom | base64 | tr -d '\n/+=' > "$f")
  fi
  echo "$f"
}

# Registers an agent with the orchestrator unless the user has taken over the file.
dropin() {
  local name=$1 f="$ORCH_HOME/agents.d/$1.yml"
  mkdir -p "$ORCH_HOME/agents.d"
  if [[ -f $f ]] && ! grep -qF "$MARKER" "$f"; then return; fi
  if [[ $name == devops ]]; then
    cat > "$f" <<EOF
$MARKER
id: devops
name: Dev
role: CI/CD engineer
bio: Builds Jenkins pipelines, runs builds, pushes images to Docker Hub, deploys to Kubernetes and verifies releases.
tags: [jenkins, docker]
transport: http
url: $(url devops)
token-ref: path:$(token devops)
confirm-tools: [k8s_rollback, jenkins_sync_credential]
EOF
  else
    cat > "$f" <<EOF
$MARKER
id: kube
name: Kira
role: Kubernetes engineer
bio: Inspects, deploys, scales and troubleshoots workloads on our clusters through a service account.
tags: [k8s]
transport: http
url: $(url kube)
token-ref: path:$(token kube)
confirm-tools: [k8s_delete, k8s_drain, k8s_rollout_undo]
EOF
  fi
}

start_one() {
  local name=$1 force=$2
  if running "$name"; then say "$name" "already running (pid $(cat "$(pidf "$name")")) $(url "$name")"; return; fi
  if port_open "$(port "$name")"; then die "port $(port "$name") is already in use; set $(echo "$name" | tr a-z A-Z)_PORT"; fi
  build "$name" "$force"
  mkdir -p "$STATE/run" "$STATE/logs"
  local -a args
  case "$name" in
    devops) args=(--spring.profiles.active=http "--server.port=$DEVOPS_PORT"
                  "--devops.http.auth-token-ref=path:$(token devops)") ;;
    kube)   args=(--spring.profiles.active=http "--server.port=$KUBE_PORT"
                  "--kube.http.auth-token-ref=path:$(token kube)") ;;
    orchestrator) args=("--server.port=$ORCHESTRATOR_PORT"); dropin devops; dropin kube ;;
  esac
  # shellcheck disable=SC2086
  nohup "$JAVA" ${JAVA_OPTS:-} -jar "$(jar "$name")" "${args[@]}" >>"$(logf "$name")" 2>&1 &
  local pid=$! i
  echo "$pid" > "$(pidf "$name")"
  for i in $(seq 1 60); do
    if port_open "$(port "$name")"; then say "$name" "up (pid $pid) $(url "$name")"; return; fi
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$(pidf "$name")"
      say "$name" "FAILED to start; last lines of $(logf "$name"):"
      tail -n 15 "$(logf "$name")" | sed 's/^/    /'
      return 1
    fi
    sleep 1
  done
  say "$name" "started (pid $pid) but port $(port "$name") is not open after 60s; see $(logf "$name")"
}

stop_one() {
  local name=$1 f pid i
  f="$(pidf "$name")"
  if ! running "$name"; then rm -f "$f"; say "$name" "not running"; return; fi
  pid="$(cat "$f")"
  kill "$pid"
  for i in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
  rm -f "$f"
  say "$name" "stopped"
}

status_one() {
  local name=$1
  if running "$name"; then
    if port_open "$(port "$name")"; then say "$name" "running  pid $(cat "$(pidf "$name")")  $(url "$name")"
    else say "$name" "starting pid $(cat "$(pidf "$name")")  (port $(port "$name") not open yet)"; fi
  else
    say "$name" "stopped"
  fi
}

cmd="${1:-}"; [[ -n $cmd ]] && shift || true
force=0; names=()
for a in "$@"; do
  case "$a" in
    --build) force=1 ;;
    -h|--help) cmd=help ;;
    *) module "$a" >/dev/null; names+=("$a") ;;
  esac
done
[[ ${#names[@]} -gt 0 ]] || names=("${ALL[@]}")

case "$cmd" in
  start)
    check_java
    rc=0
    for n in "${names[@]}"; do start_one "$n" "$force" || rc=1; done
    if [[ $rc == 0 ]] && printf '%s\n' "${names[@]}" | grep -qx orchestrator; then
      echo
      echo "Open $(url orchestrator); the devops and kube agents appear on the Team page within a minute."
      [[ -n ${ANTHROPIC_API_KEY:-} ]] || echo "No ANTHROPIC_API_KEY in this shell: paste a key in Settings before giving tasks."
    fi
    exit $rc ;;
  stop)
    for (( i=${#names[@]}-1; i>=0; i-- )); do stop_one "${names[$i]}"; done ;;
  restart)
    for (( i=${#names[@]}-1; i>=0; i-- )); do stop_one "${names[$i]}"; done
    check_java
    for n in "${names[@]}"; do start_one "$n" "$force"; done ;;
  status)
    for n in "${names[@]}"; do status_one "$n"; done ;;
  logs)
    files=(); for n in "${names[@]}"; do files+=("$(logf "$n")"); done
    tail -n 40 -F "${files[@]}" ;;
  *)
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    [[ $cmd == help || -z $cmd ]] || exit 1 ;;
esac
