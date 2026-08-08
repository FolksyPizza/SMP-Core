#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGETS_ENV="$ROOT/scripts/targets.env"
VERSIONS_ENV="$ROOT/scripts/versions.env"
RCON_ENV="$ROOT/configs/reused/rcon.env"
DB_ENV="$ROOT/configs/reused/database.env"
USE_SCREEN_FOR_BACKENDS="${USE_SCREEN_FOR_BACKENDS:-1}"

if [[ -f "$TARGETS_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$TARGETS_ENV"
fi
if [[ -f "$VERSIONS_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$VERSIONS_ENV"
fi
if [[ -f "$RCON_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$RCON_ENV"
fi
if [[ -f "$DB_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$DB_ENV"
  export DB_HOST DB_PORT MARIADB_DATABASE MARIADB_USER MARIADB_PASSWORD MARIADB_ROOT_PASSWORD
fi
JAVA_BIN="${JAVA_BIN:-java}"

# One clock across the stack: the JVMs inherit the host zone, so hand the same zone to the
# database container instead of letting it default to UTC. See database/docker-compose.yml.
if [[ -z "${TZ:-}" ]]; then
  if [[ -f /etc/timezone ]]; then
    TZ="$(cat /etc/timezone)"
  elif command -v timedatectl >/dev/null 2>&1; then
    TZ="$(timedatectl show -p Timezone --value 2>/dev/null)"
  fi
fi
export TZ="${TZ:-UTC}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing command: $1" >&2
    exit 1
  }
}

now_epoch() {
  date +%s
}

deployment_mode() {
  echo "${DEPLOYMENT_MODE:-split}"
}

is_single_mode() {
  [[ "$(deployment_mode)" == "single" ]]
}

is_split_mode() {
  [[ "$(deployment_mode)" == "split" ]]
}

version_key() {
  echo "$1" | tr '.' '_'
}

paper_build_for_mc() {
  local key
  key="PAPER_BUILD_$(version_key "$MC_VERSION")"
  echo "${!key:-}"
}

folia_build_for_mc() {
  local key
  key="FOLIA_BUILD_$(version_key "$MC_VERSION")"
  echo "${!key:-}"
}

mkdir_server_dirs() {
  mkdir -p "$ROOT"/{velocity,database,webui,plugins-src,configs,docs,needs_sudo,scripts,assets,jars,runtime}
  for _svc in $(all_backends); do
    mkdir -p "$ROOT/$_svc"/{plugins,world}
  done
  unset _svc
  mkdir -p "$ROOT/velocity/plugins"
  mkdir -p "$ROOT/database/init"
  mkdir -p "$ROOT/plugins-${MC_VERSION}"
}

is_enabled() {
  local key="$1"
  [[ "${!key:-0}" == "1" ]]
}

# Canonical list of Paper backends in this network. Single source of truth is
# ALL_BACKENDS in targets.env; scripts must call this rather than hardcoding names.
all_backends() {
  echo "${ALL_BACKENDS:-lobby survival maintenance}"
}

# Only the backends currently switched on via their ENABLE_<NAME> flag.
enabled_backends() {
  local svc flag
  for svc in $(all_backends); do
    flag="ENABLE_$(echo "$svc" | tr '[:lower:]' '[:upper:]')"
    if is_enabled "$flag"; then
      printf '%s ' "$svc"
    fi
  done
  echo
}

pid_file() { echo "$ROOT/runtime/$1.pid"; }
log_file() { echo "$ROOT/runtime/$1.log"; }
screen_session_name() { echo "pizzasmp_$1"; }
screen_session_exists() {
  local session_name="$1"
  if command -v rg >/dev/null 2>&1; then
    screen -list | rg -q "[.]${session_name}[[:space:]]"
  else
    screen -list | grep -Eq "[.]${session_name}[[:space:]]"
  fi
}

service_port() {
  case "$1" in
    velocity) echo "${VELOCITY_PORT}" ;;
    lobby) echo "${LOBBY_PORT}" ;;
    survival) echo "${SURVIVAL_PORT}" ;;
    maintenance) echo "${MAINTENANCE_PORT}" ;;
    dev) echo "${DEV_PORT}" ;;
    webui) echo "${WEBUI_PORT}" ;;
    *) echo "" ;;
  esac
}

rcon_port_for_service() {
  case "$1" in
    lobby) echo "${LOBBY_RCON_PORT:-25576}" ;;
    survival) echo "${SURVIVAL_RCON_PORT:-25577}" ;;
    maintenance) echo "${MAINTENANCE_RCON_PORT:-25579}" ;;
    dev) echo "${DEV_RCON_PORT:-25578}" ;;
    *) echo "" ;;
  esac
}

pid_from_port() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$port" -sTCP:LISTEN | head -n1 || true
  fi
}

cmdline_for_pid() {
  local pid="$1"
  if [[ -r "/proc/$pid/cmdline" ]]; then
    tr '\0' ' ' <"/proc/$pid/cmdline"
    return 0
  fi
  return 1
}

wait_for_port() {
  local host="$1" port="$2" timeout="${3:-60}"
  local deadline=$(( $(now_epoch) + timeout ))
  while (( $(now_epoch) <= deadline )); do
    if command -v ss >/dev/null 2>&1; then
      if ss -ltn "sport = :$port" | tail -n +2 | grep -q .; then
        return 0
      fi
    fi
    if command -v lsof >/dev/null 2>&1; then
      if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        return 0
      fi
    fi
    if command -v nc >/dev/null 2>&1; then
      if nc -z "$host" "$port" >/dev/null 2>&1; then
        return 0
      fi
    elif (exec 3<>"/dev/tcp/$host/$port") >/dev/null 2>&1; then
      exec 3<&- 3>&-
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_log_marker() {
  local file="$1" marker_regex="$2" timeout="${3:-120}" since_timestamp="${4:-0}"
  local deadline=$(( $(now_epoch) + timeout ))
  while (( $(now_epoch) <= deadline )); do
    if [[ -f "$file" ]]; then
      # Runtime service logs are recreated per boot, so marker scan is boot-local.
      if grep -Eq "$marker_regex" "$file"; then
        return 0
      fi
      if [[ "$since_timestamp" =~ ^[0-9]+$ ]] && (( since_timestamp > 0 )); then
        local mtime
        mtime="$(stat -c %Y "$file" 2>/dev/null || echo 0)"
        if (( mtime < since_timestamp )); then
          sleep 1
          continue
        fi
      fi
    fi
    sleep 1
  done
  return 1
}

wait_for_java_pid() {
  local pidfile="$1" jar_fragment="$2" timeout="${3:-60}"
  local deadline=$(( $(now_epoch) + timeout ))
  while (( $(now_epoch) <= deadline )); do
    if [[ -f "$pidfile" ]]; then
      local pid
      pid="$(cat "$pidfile" 2>/dev/null || true)"
      if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        local cmd
        cmd="$(cmdline_for_pid "$pid" || true)"
        if [[ -z "$jar_fragment" || "$cmd" == *"$jar_fragment"* ]]; then
          return 0
        fi
      fi
    fi
    sleep 1
  done
  return 1
}

wait_for_port_closed() {
  local port="$1" timeout="${2:-30}"
  local deadline=$(( $(now_epoch) + timeout ))
  while (( $(now_epoch) <= deadline )); do
    if command -v ss >/dev/null 2>&1; then
      if ! ss -ltn "sport = :$port" | tail -n +2 | grep -q .; then
        return 0
      fi
    elif command -v lsof >/dev/null 2>&1; then
      if ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        return 0
      fi
    elif ! (exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1; then
      return 0
    else
      exec 3<&- 3>&-
    fi
    sleep 1
  done
  return 1
}

tail_service_log() {
  local name="$1" lines="${2:-50}"
  local lf
  lf="$(log_file "$name")"
  if [[ -f "$lf" ]]; then
    tail -n "$lines" "$lf"
  else
    echo "(no log at $lf)"
  fi
}

start_java_service() {
  local name="$1"; shift
  local cwd="$1"; shift
  local jar="$1"; shift
  local port session_name
  port="$(service_port "$name")"
  session_name="$(screen_session_name "$name")"

  if [[ ! -f "$jar" ]]; then
    echo "[$name] Missing jar: $jar" >&2
    return 1
  fi

  # Preflight the JVM flags before handing off to screen. A bad flag (e.g. an
  # experimental -XX option without -XX:+UnlockExperimentalVMOptions) makes the JVM
  # exit instantly; inside a detached screen that death is invisible and the service
  # just silently never appears. Fail loudly here instead.
  if ! "$JAVA_BIN" ${JAVA_OPTS:-} -version >/dev/null 2>&1; then
    echo "[$name] ERROR: the JVM rejected these options:" >&2
    echo "[$name]   ${JAVA_OPTS:-}" >&2
    "$JAVA_BIN" ${JAVA_OPTS:-} -version 2>&1 | sed "s/^/[$name]   /" >&2
    return 1
  fi

  if [[ -n "$port" ]] && lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    pid_from_port "$port" >"$(pid_file "$name")" || true
    echo "[$name] already running (port $port)"
    return 0
  fi

  if [[ "$USE_SCREEN_FOR_BACKENDS" == "1" ]] && command -v screen >/dev/null 2>&1; then
    if screen_session_exists "$session_name"; then
      screen -S "$session_name" -X quit || true
    fi
    (
      cd "$cwd"
      # shellcheck disable=SC2086
      screen -L -Logfile "$(log_file "$name")" -dmS "$session_name" bash -lc "exec '$JAVA_BIN' ${JAVA_OPTS:-'-Xms512M -Xmx1024M'} -jar '$jar' nogui"
    )
    if [[ -n "$port" ]]; then
      for _ in $(seq 1 90); do
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
          pid_from_port "$port" >"$(pid_file "$name")" || true
          echo "[$name] started in screen session $session_name"
          return 0
        fi
        sleep 1
      done
      echo "[$name] started in screen session $session_name (port not ready yet)"
      return 0
    fi
    echo "[$name] started in screen session $session_name"
    return 0
  fi

  (
    cd "$cwd"
    # shellcheck disable=SC2086
    nohup "$JAVA_BIN" ${JAVA_OPTS:-'-Xms512M -Xmx1024M'} -jar "$jar" nogui >"$(log_file "$name")" 2>&1 &
    echo $! >"$(pid_file "$name")"
  )
  echo "[$name] started"
}

stop_service() {
  local name="$1"
  local session_name
  if [[ "$name" == "velocity" ]]; then
    session_name="${VELOCITY_SCREEN_SESSION:-pizzasmp_velocity}"
  else
    session_name="$(screen_session_name "$name")"
  fi
  local pf="$(pid_file "$name")"
  local port
  port="$(service_port "$name")"
  local fallback_pid=""
  if [[ -n "$port" ]]; then
    fallback_pid="$(pid_from_port "$port")"
  fi

  local graceful_cmd=""
  case "$name" in
    velocity) graceful_cmd="shutdown" ;;
    lobby|survival|maintenance|dev) graceful_cmd="stop" ;;
  esac

  # Ask screen-backed services to stop cleanly first so clients see branded shutdown messages.
  if command -v screen >/dev/null 2>&1; then
    if screen_session_exists "$session_name"; then
      if [[ -n "$graceful_cmd" ]]; then
        screen -S "$session_name" -p 0 -X stuff "${graceful_cmd}"$'\r' || true
        if [[ -n "$port" ]]; then
          wait_for_port_closed "$port" 25 || true
        else
          sleep 3
        fi
      fi
      if screen_session_exists "$session_name"; then
        screen -S "$session_name" -X quit || true
      fi
    fi
  fi

  local pid=""
  if [[ -f "$pf" ]]; then
    pid="$(cat "$pf" 2>/dev/null || true)"
  fi
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    sleep 2
    kill -9 "$pid" 2>/dev/null || true
  fi
  if [[ -n "$port" ]]; then
    fallback_pid="$(pid_from_port "$port")"
    if [[ -n "$fallback_pid" ]]; then
      kill "$fallback_pid" 2>/dev/null || true
      sleep 1
      kill -9 "$fallback_pid" 2>/dev/null || true
    fi
  fi
  # Final safety: kill any lingering java process pointing at this service jar.
  local jar_path="$ROOT/$name/server.jar"
  if [[ -f "$jar_path" ]]; then
    local linger
    linger="$(pgrep -f "$jar_path" || true)"
    if [[ -n "$linger" ]]; then
      while read -r lp; do
        [[ -z "$lp" ]] && continue
        kill "$lp" 2>/dev/null || true
      done <<< "$linger"
      sleep 1
      while read -r lp; do
        [[ -z "$lp" ]] && continue
        kill -9 "$lp" 2>/dev/null || true
      done <<< "$(pgrep -f "$jar_path" || true)"
    fi
  fi
  rm -f "$pf"
  if [[ -n "$pid" || -n "$fallback_pid" ]]; then
    echo "[$name] stopped"
  else
    echo "[$name] not running"
  fi
}
