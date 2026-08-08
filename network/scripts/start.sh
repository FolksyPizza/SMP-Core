#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"

DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
fi

run_step() {
  local cmd="$1"
  if (( DRY_RUN == 1 )); then
    echo "[dry-run] $cmd"
    return 0
  fi
  eval "$cmd"
}

wait_for_service_health() {
  local name="$1" pid_timeout="$2" port_timeout="$3" log_timeout="$4" jar_fragment="$5" port="$6" marker_regex="$7" log_path="$8"
  local boot_started
  boot_started="$(now_epoch)"

  echo "[$name] waiting for port $port ($port_timeout s)"
  if ! wait_for_port 127.0.0.1 "$port" "$port_timeout"; then
    echo "[$name] ERROR: port $port did not open in time"
    tail -n 120 "$log_path" 2>/dev/null || tail_service_log "$name" 120
    return 1
  fi

  if [[ -n "$port" ]]; then
    pid_from_port "$port" >"$(pid_file "$name")" || true
  fi

  echo "[$name] waiting for java pid ($pid_timeout s)"
  if ! wait_for_java_pid "$(pid_file "$name")" "$jar_fragment" "$pid_timeout"; then
    echo "[$name] ERROR: pid/cmdline check failed"
    tail -n 120 "$log_path" 2>/dev/null || tail_service_log "$name" 120
    return 1
  fi

  echo "[$name] waiting for log marker /$marker_regex/ ($log_timeout s)"
  if ! wait_for_log_marker "$log_path" "$marker_regex" "$log_timeout" "$boot_started"; then
    echo "[$name] ERROR: readiness marker not found"
    tail -n 120 "$log_path" 2>/dev/null || tail_service_log "$name" 120
    return 1
  fi
  echo "[$name] ready"
}

start_db_if_enabled() {
  if ! is_enabled ENABLE_DB; then
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1; then
    echo "[db] ERROR: docker is required when ENABLE_DB=1"
    return 1
  fi
  run_step "\"$ROOT/scripts/db_start.sh\""
  if (( DRY_RUN == 0 )); then
    echo "[db] waiting for port ${DB_PORT} (60 s)"
    if ! wait_for_port 127.0.0.1 "${DB_PORT}" 60; then
      echo "[db] ERROR: MariaDB not listening on ${DB_PORT}"
      return 1
    fi
  else
    echo "[dry-run] wait_for_port 127.0.0.1 ${DB_PORT} 60"
  fi
  echo "[db] ready"
}

start_velocity_if_enabled() {
  if ! is_enabled ENABLE_VELOCITY; then
    return 0
  fi
  run_step "\"$ROOT/scripts/start_velocity.sh\""
  if (( DRY_RUN == 0 )); then
    wait_for_service_health "velocity" 20 60 60 "velocity/velocity.jar" "${VELOCITY_PORT}" "Done|Listening on|Ready" "$ROOT/runtime/velocity.log"
  else
    echo "[dry-run] wait_for_java_pid runtime/velocity.pid velocity/velocity.jar 20"
    echo "[dry-run] wait_for_port 127.0.0.1 ${VELOCITY_PORT} 60"
    echo "[dry-run] wait_for_log_marker runtime/velocity.log 'Done|Listening on|Ready' 60"
  fi
}

start_backend_if_enabled() {
  local flag="$1" name="$2" script="$3" timeout="$4" jar_fragment="$5" port="$6"
  if ! is_enabled "$flag"; then
    return 0
  fi
  run_step "\"$script\""
  if (( DRY_RUN == 0 )); then
    wait_for_service_health "$name" 20 "$timeout" "$timeout" "$jar_fragment" "$port" "Done \\(" "$ROOT/$name/logs/latest.log"
  else
    echo "[dry-run] wait_for_java_pid runtime/${name}.pid ${jar_fragment} 20"
    echo "[dry-run] wait_for_port 127.0.0.1 ${port} ${timeout}"
    echo "[dry-run] wait_for_log_marker runtime/${name}.log 'Done \\(' ${timeout}"
  fi
}

start_db_if_enabled
start_velocity_if_enabled
start_backend_if_enabled ENABLE_LOBBY lobby "$ROOT/scripts/start_lobby.sh" 120 "lobby/server.jar" "${LOBBY_PORT}"
start_backend_if_enabled ENABLE_SURVIVAL survival "$ROOT/scripts/start_survival.sh" 180 "survival/server.jar" "${SURVIVAL_PORT}"
start_backend_if_enabled ENABLE_MAINTENANCE maintenance "$ROOT/scripts/start_maintenance.sh" 120 "maintenance/server.jar" "${MAINTENANCE_PORT}"

if is_enabled ENABLE_WEBUI; then
  run_step "\"$ROOT/scripts/start_webui.sh\""
fi
