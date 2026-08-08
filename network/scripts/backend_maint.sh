#!/usr/bin/env bash
# Rolling backend maintenance — the CLI control surface for the network.
#
# This replaces the in-game /admin and /manage consoles. See
# SMP/docs/notes/MAINTENANCE-MODEL.md for the full model.
#
#   backend_maint.sh plan [backend...]             emit a plan; NO args = ALL backends
#   backend_maint.sh apply <plan-file>             execute a previously emitted plan
#   backend_maint.sh status                        show maintenance flags + health
#   backend_maint.sh clear <backend>               clear a stuck maintenance flag
#
# DESIGN NOTES
#
# plan/apply are split on purpose. The ordering, the units involved and the abort
# points are all decided up front and written to a file you can read before anything
# is touched. No decide-as-you-go.
#
# Every step is independently abortable: if a backend fails to come back or fails its
# health gate, the plan STOPS with that backend still flagged and its players still
# held. It does not advance to the next unit. That is deliberate — readmitting players
# into a half-broken backend loses items, which is worse than losing time.
#
# The relaunch path is verified BEFORE anything is stopped. On 2026-06-29 a stop-then-
# relaunch flow whose relaunch silently never fired left the server down for two days
# (memory/pizzalimbo-restart-bug.md). A tier that stops a backend must be able to prove
# it can bring it back.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"

PLAN_DIR="$ROOT/runtime/maint-plans"

# --- health gate tunables -----------------------------------------------------------
# "Port is open" is NOT "ready for players". A backend can bind its port with a plugin
# having failed to enable, or while chunk/IO work still has TPS on the floor.
READY_TIMEOUT="${READY_TIMEOUT:-300}"      # max seconds to wait for boot
STABILIZE_SECONDS="${STABILIZE_SECONDS:-20}" # settle window AFTER boot, BEFORE players
TPS_FLOOR="${TPS_FLOOR:-18.0}"
TPS_SAMPLES="${TPS_SAMPLES:-3}"            # consecutive good samples required
TPS_SAMPLE_GAP="${TPS_SAMPLE_GAP:-5}"

db() { docker exec -i pizzasmp-mariadb mariadb -u"${MARIADB_USER:-pizzasmp}" -p"${MARIADB_PASSWORD}" "${MARIADB_DATABASE:-pizzasmp}" -N -e "$1"; }

set_maintenance() {
  local backend="$1" on="$2"
  # targets_csv is a generic list of unit names, NOT a fixed set of backend names.
  # Under distributed Folia these become process/slice ids and this code is unchanged.
  local cur; cur="$(db "SELECT targets_csv FROM maintenance_state WHERE id=1;" || echo '')"
  local out=""
  IFS=',' read -ra parts <<< "$cur"
  for p in "${parts[@]}"; do
    p="$(echo "$p" | xargs)"
    [[ -z "$p" || "$p" == "$backend" ]] && continue
    out="${out:+$out,}$p"
  done
  [[ "$on" == "1" ]] && out="${out:+$out,}$backend"
  db "INSERT INTO maintenance_state (id,active,targets_csv) VALUES (1,$([[ -n "$out" ]] && echo 1 || echo 0),'$out')
      ON DUPLICATE KEY UPDATE active=VALUES(active), targets_csv=VALUES(targets_csv);"
  echo "[maint] $backend maintenance=$on (targets now: ${out:-none})"
}

# Ask the running server for its TPS via the console, then read it back from the log.
sample_tps() {
  local backend="$1" session log
  session="$(screen_session_name "$backend")"; log="$(log_file "$backend")"
  screen -S "$session" -p 0 -X stuff "tps$(printf '\r')" 2>/dev/null || { echo "0"; return; }
  sleep 2
  tr -d '\000' < "$log" 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' \
    | grep -aoE 'TPS from last 1m, 5m, 15m: [*]?[0-9]+\.[0-9]+' | tail -1 \
    | grep -oE '[0-9]+\.[0-9]+$' || echo "0"
}

# THE READMISSION GATE. All of these must pass before players are let back in.
verify_healthy() {
  local backend="$1" port log deadline
  port="$(service_port "$backend")"; log="$(log_file "$backend")"
  deadline=$(( $(now_epoch) + READY_TIMEOUT ))

  echo "[verify] $backend: waiting for port $port"
  wait_for_port 127.0.0.1 "$port" "$READY_TIMEOUT" || { echo "[verify] FAIL: port never opened"; return 1; }

  echo "[verify] $backend: waiting for boot completion"
  while (( $(now_epoch) < deadline )); do
    grep -qa 'Done (' "$log" 2>/dev/null && break
    sleep 2
  done
  grep -qa 'Done (' "$log" 2>/dev/null || { echo "[verify] FAIL: no 'Done (' for this boot"; return 1; }

  # Settle window: gives late-binding plugins time to finish and, just as importantly,
  # gives errors a chance to surface before anyone is admitted. Not every problem
  # appears at boot, but many appear in the first seconds after it.
  echo "[verify] $backend: stabilizing for ${STABILIZE_SECONDS}s"
  sleep "$STABILIZE_SECONDS"

  # A plugin that failed to enable means "up but broken".
  local errs
  errs="$(tr -d '\000' < "$log" | sed 's/\x1b\[[0-9;]*m//g' \
          | grep -acE '\[ERROR\].*(Could not load|failed to enable|Error occurred while enabling)' || true)"
  if [[ "${errs:-0}" -gt 0 ]]; then
    echo "[verify] FAIL: $errs plugin load/enable error(s) this boot"; return 1
  fi

  echo "[verify] $backend: TPS must hold >= $TPS_FLOOR for $TPS_SAMPLES consecutive samples"
  local good=0 t
  for _ in $(seq 1 $(( TPS_SAMPLES * 3 ))); do
    t="$(sample_tps "$backend")"
    if awk "BEGIN{exit !($t >= $TPS_FLOOR)}" 2>/dev/null; then
      good=$(( good + 1 )); echo "         TPS $t  (ok $good/$TPS_SAMPLES)"
      (( good >= TPS_SAMPLES )) && { echo "[verify] $backend HEALTHY"; return 0; }
    else
      good=0; echo "         TPS $t  (below floor, resetting)"
    fi
    sleep "$TPS_SAMPLE_GAP"
  done
  echo "[verify] FAIL: TPS never held steady"; return 1
}

cmd_plan() {
  # DEFAULT IS EVERY BACKEND. An update should land everywhere or the network ends up
  # running mixed plugin versions, which is exactly the drift we just spent time undoing.
  # Naming units explicitly is the exception (one-off restart), not the normal path.
  # The maintenance backend is filtered out below - it is the hold destination.
  if [[ $# -eq 0 ]]; then
    set -- $(all_backends)
    echo "[plan] no units given - defaulting to ALL backends: $*"
  fi
  mkdir -p "$PLAN_DIR"
  local stamp plan; stamp="$(date +%Y%m%d-%H%M%S)"; plan="$PLAN_DIR/plan-$stamp.txt"
  {
    echo "# Rolling maintenance plan generated $stamp"
    echo "# Apply with: ./scripts/backend_maint.sh apply $plan"
    echo "# One unit at a time. Any failure aborts the plan with that unit still flagged."
    echo "#"
    echo "# Health gate per unit: port open + 'Done (' + ${STABILIZE_SECONDS}s settle"
    echo "#                       + no plugin enable errors + TPS >= $TPS_FLOOR x$TPS_SAMPLES"
    echo "#"
    for b in "$@"; do
      if [[ ! -d "$ROOT/$b" ]]; then echo "# !! UNKNOWN BACKEND: $b (aborting)"; echo "ABORT"; break; fi
      if [[ "$b" == "maintenance" ]]; then echo "# (skipped $b - it is the hold destination players are moved to)"; continue; fi
      echo "UNIT $b"
    done
  } > "$plan"
  echo "--- plan ---"; cat "$plan"; echo "------------"
  grep -q '^ABORT' "$plan" && { echo "plan contains errors; not runnable" >&2; exit 1; }
  echo "Wrote $plan"
}

cmd_apply() {
  local plan="${1:-}"
  [[ -f "$plan" ]] || { echo "usage: backend_maint.sh apply <plan-file>" >&2; exit 1; }
  grep -q '^ABORT' "$plan" && { echo "plan is marked ABORT; refusing" >&2; exit 1; }

  mapfile -t units < <(grep '^UNIT ' "$plan" | awk '{print $2}')
  echo "[apply] ${#units[@]} unit(s): ${units[*]}"

  for b in "${units[@]}"; do
    echo; echo "=== $b ==="
    # SAFETY: prove we can bring it back BEFORE stopping it.
    local starter="$ROOT/scripts/start_${b}.sh"
    if [[ ! -x "$starter" ]]; then
      echo "[abort] no executable start script at $starter — refusing to stop $b"; exit 1
    fi
    if [[ ! -f "$ROOT/$b/server.jar" ]]; then
      echo "[abort] $b/server.jar missing — refusing to stop $b"; exit 1
    fi

    set_maintenance "$b" 1          # proxy stops admitting; players already on are moved
    sleep 2
    "$ROOT/scripts/stop_only.sh" "$b" || true
    sleep 5
    "$starter" || { echo "[abort] $b failed to start"; exit 1; }

    if ! verify_healthy "$b"; then
      echo "[abort] $b failed the health gate."
      echo "        It stays flagged in maintenance and players stay held ON PURPOSE."
      echo "        Investigate, then: ./scripts/backend_maint.sh clear $b"
      exit 1
    fi
    set_maintenance "$b" 0          # readmit
    echo "=== $b complete ==="
  done
  echo; echo "[apply] plan complete — all units healthy and readmitting."
}

# TIER 3 — emergency. Close a backend NOW and kick everyone on it.
#
# Distinct from a normal maintenance flag: maintenance holds players and returns them,
# emergency evicts them. The proxy reads the "!" prefix on a unit name to mean emergency
# and disconnects rather than redirecting.
cmd_emergency() {
  local backend="${1:?usage: backend_maint.sh emergency <backend> [on|off]}"
  local mode="${2:-on}"
  if [[ "$mode" == "off" ]]; then
    db "UPDATE maintenance_state SET targets_csv=REPLACE(targets_csv,'!$backend','$backend') WHERE id=1;"
    set_maintenance "$backend" 0
    echo "[emergency] $backend cleared"
    return
  fi
  set_maintenance "$backend" 1
  # Mark it emergency by prefixing the unit name.
  db "UPDATE maintenance_state SET targets_csv=REPLACE(targets_csv,'$backend','!$backend') WHERE id=1;"
  echo "[emergency] $backend CLOSED — players on it will be disconnected."
  echo "            Clear with: ./scripts/backend_maint.sh emergency $backend off"
}

cmd_status() {
  echo "=== maintenance_state ==="
  db "SELECT CONCAT('active=',active,'  targets=[',targets_csv,']') FROM maintenance_state WHERE id=1;" || echo "  (db unreachable)"
  echo "=== backends ==="
  for b in $(all_backends); do
    local port up; port="$(service_port "$b")"; up="down"
    ss -ltn 2>/dev/null | grep -q ":$port " && up="UP"
    printf "  %-14s port %-6s %s\n" "$b" "$port" "$up"
  done
}

case "${1:-}" in
  plan)   shift; cmd_plan "$@" ;;
  apply)  shift; cmd_apply "$@" ;;
  status) cmd_status ;;
  clear)  shift; set_maintenance "${1:?backend required}" 0 ;;
  emergency) shift; cmd_emergency "$@" ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
