#!/usr/bin/env bash
# pizzactl — cursor-driven browser for the ops scripts.
#
# The CLI is the control surface for this network (the in-game /admin and /manage
# consoles were removed), which left ~60 scripts to remember. This groups them by intent,
# shows what each one does before running it, and never runs anything without Enter.
#
# Navigation: arrows or j/k move, Enter runs, / filters, Esc / Left / h goes back,
# q quits. Number keys jump straight to an entry.
#
# Deliberately plain bash + ANSI: no curses, no Python, works over SSH.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh" 2>/dev/null || true

B=$'\e[1m'; DIM=$'\e[2m'; R=$'\e[0m'
CY=$'\e[36m'; GR=$'\e[32m'; RD=$'\e[31m'; YL=$'\e[33m'; INV=$'\e[7m'

cleanup() { printf '\e[?25h'; }
trap cleanup EXIT INT TERM
hide() { printf '\e[?25l'; }
show() { printf '\e[?25h'; }

# entries: "label|command|description"   (leading ! on the label = destructive)
SERVERS=(
  "Status (all services)|./scripts/status.sh|Ports, PIDs and readiness for every service"
  "Start everything|./scripts/start.sh|DB, proxy, then each enabled backend in order"
  "Start one service|@START|Bring a single service up"
  "Restart one service|@RESTART|Stop then start — use after a jar swap"
  "Stop one service|@STOP|Graceful stop, then verify the port is free"
  "!Stop everything|./scripts/stop.sh|Graceful stop of every enabled service"
  "Screen sessions|./scripts/screen_sessions.sh|List the detached consoles"
  "Tail a server log|@TAIL|Last 60 lines of a service log"
)
PLUGINS=(
  "Build all from tools/|./scripts/build_from_repo.sh|Compile every custom plugin from the repo"
  "Build one module|@BUILD|Compile a single plugin"
  "Deploy to all servers|./scripts/deploy_plugins.sh|Platform-routed copy + datapacks + clear remap cache"
  "Deploy to one server|@DEPLOY|Promotion step (e.g. dev first)"
  "Check for drift|./scripts/deploy_plugins.sh --check|Compare deployed jars against build-output"
  "Install PAPI expansions|./scripts/install_papi_expansions.sh|Fetch from eCloud, verify each is a real jar"
)
MAINT=(
  "Maintenance status|./scripts/backend_maint.sh status|Current flags plus per-backend up/down"
  "Plan a rolling restart|@PLAN|Emit a reviewable plan; changes nothing"
  "Apply a plan|@APPLY|Execute a plan file, one unit at a time"
  "Clear a stuck flag|@CLEAR|Un-flag a backend left in maintenance"
  "!Emergency close|@EMERG|Deny access and disconnect everyone on a backend"
)
DATA=(
  "Database status|./scripts/db_status.sh|Container health and connectivity"
  "Start database|./scripts/db_start.sh|Bring up the MariaDB container"
  "Apply schema|./scripts/db_init_schema.sh|Idempotent; safe to re-run"
  "!Dump database|./scripts/db_dump.sh|Write a timestamped SQL dump"
  "Create backup|./scripts/create_backup.sh|Archive plugins and configs"
  "LuckPerms audit|./scripts/lp_audit.sh|Report the group tree and grants"
  "LuckPerms bootstrap|./scripts/lp_bootstrap.sh|Rebuild the group hierarchy (idempotent)"
)

pause() { show; printf '\n%s[enter] back%s' "$DIM" "$R"; read -r _; hide; }

# readkey -> prints a normalised token: UP DOWN LEFT RIGHT ENTER ESC SLASH CHAR:<c>
readkey() {
  local k b1 b2
  # EOF (piped input) must NOT look like a keypress, or the caller loops forever.
  IFS= read -rsn1 k || { echo EOF; return; }
  if [[ $k == $'\e' ]]; then
    # Read the CSI introducer and the final byte SEPARATELY. Reading two bytes at once
    # was the bug: a bare Esc left the second read waiting, and on some terminals the
    # partial result did not match any arrow pattern, so Esc fell through to the default
    # branch and was treated as a quit instead of "go back".
    if ! IFS= read -rsn1 -t 0.06 b1; then echo ESC; return; fi
    if [[ $b1 != '[' && $b1 != 'O' ]]; then echo ESC; return; fi
    if ! IFS= read -rsn1 -t 0.06 b2; then echo ESC; return; fi
    case "$b2" in
      A) echo UP ;; B) echo DOWN ;; C) echo RIGHT ;; D) echo LEFT ;;
      *) echo ESC ;;
    esac
    return
  fi
  case "$k" in
    '') echo ENTER ;;
    /)  echo SLASH ;;
    *)  echo "CHAR:$k" ;;
  esac
}

# generic cursor list. Prints the chosen item; returns 1 when cancelled.
choose_from() {
  local title="$1"; shift
  local items=("$@") sel=0 n=${#items[@]}
  (( n == 0 )) && return 1
  while true; do
    clear
    printf '%s%s%s   %s↑↓ move · enter pick · esc cancel%s\n\n' "$B$CY" "$title" "$R" "$DIM" "$R"
    local i=0
    for it in "${items[@]}"; do
      if (( i == sel )); then printf '%s  %-34s%s\n' "$INV$B" "$it" "$R"
      else printf '   %-34s\n' "$it"; fi
      i=$((i+1))
    done
    hide
    case "$(readkey)" in
      UP)     sel=$(( (sel-1+n) % n )) ;;
      DOWN)   sel=$(( (sel+1) % n )) ;;
      ENTER)  printf '%s' "${items[$sel]}"; return 0 ;;
      ESC|LEFT) return 1 ;;
      EOF) return 1 ;;
      CHAR:k) sel=$(( (sel-1+n) % n )) ;;
      CHAR:j) sel=$(( (sel+1) % n )) ;;
      CHAR:h) return 1 ;;
      CHAR:q) cleanup; clear; exit 0 ;;
    esac
  done
}

pick_service() { choose_from "Pick a service" $(all_backends 2>/dev/null) velocity; }
pick_module()  { choose_from "Pick a module" pizzanetworkcore pizzaadmintools pizzachatguard \
                   pizzapunishment pizzaruleguard pizzaspawnrules pizzaenderchest pizzaproxyguard; }

run_entry() {
  local cmd="$1" label="$2" s
  case "$cmd" in
    @START)   s="$(pick_service)" || return; cmd="./scripts/start_only.sh $s" ;;
    @RESTART) s="$(pick_service)" || return; cmd="./scripts/start_only.sh $s --restart" ;;
    @STOP)    s="$(pick_service)" || return; cmd="./scripts/stop_only.sh $s" ;;
    @DEPLOY)  s="$(pick_service)" || return; cmd="./scripts/deploy_plugins.sh --to $s" ;;
    @CLEAR)   s="$(pick_service)" || return; cmd="./scripts/backend_maint.sh clear $s" ;;
    @EMERG)   s="$(pick_service)" || return; cmd="./scripts/backend_maint.sh emergency $s on" ;;
    @BUILD)   s="$(pick_module)"  || return; cmd="./scripts/build_from_repo.sh $s" ;;
    @TAIL)    s="$(pick_service)" || return; cmd="tr -d '\\000' < runtime/$s.log | tail -n 60" ;;
    @PLAN)
      show; printf '\n%s[enter] = ALL backends%s\n' "$DIM" "$R"
      local units; read -r -p 'Units: ' units; hide
      cmd="./scripts/backend_maint.sh plan $units" ;;
    @APPLY)
      local dir="$ROOT/runtime/maint-plans" plans=() pf
      mapfile -t plans < <(ls -1t "$dir" 2>/dev/null | head -12)
      (( ${#plans[@]} == 0 )) && { show; echo "No plans yet."; pause; return; }
      pf="$(choose_from 'Pick a plan' "${plans[@]}")" || return
      cmd="./scripts/backend_maint.sh apply $dir/$pf" ;;
  esac

  if [[ "$label" == '!'* ]]; then
    show
    printf '\n%sThis is destructive:%s %s\n' "$RD" "$R" "$cmd"
    printf 'Type %syes%s to proceed: ' "$B" "$R"
    local a; read -r a; hide
    [[ "$a" == yes ]] || { show; echo "Cancelled."; pause; return; }
  fi

  clear; show
  printf '%s>>> %s%s\n\n' "$GR" "$cmd" "$R"
  ( cd "$ROOT" && eval "$cmd" ) || printf '\n%s(exited non-zero)%s\n' "$YL" "$R"
  pause
}

show_menu() {
  local title="$1"; shift
  local all=("$@") entries=() sel=0 filter="" e label rest desc mark
  while true; do
    entries=()
    for e in "${all[@]}"; do
      [[ -z "$filter" || "${e,,}" == *"${filter,,}"* ]] && entries+=("$e")
    done
    local n=${#entries[@]}
    (( n > 0 && sel >= n )) && sel=$(( n - 1 ))
    clear
    printf '%sops%s / %s%s%s' "$DIM" "$R" "$B$CY" "$title" "$R"
    [[ -n "$filter" ]] && printf '   %sfilter: %s%s' "$YL" "$filter" "$R"
    printf '\n%s%s%s\n\n' "$DIM" "$(printf '─%.0s' {1..70})" "$R"
    local i=0
    for e in "${entries[@]}"; do
      label="${e%%|*}"; rest="${e#*|}"; desc="${rest#*|}"
      mark=' '; if [[ "$label" == '!'* ]]; then mark="$RD!$R"; label="${label#!}"; fi
      if (( i == sel )); then
        printf '%s %s %-26s %s%s\n' "$INV$B" "$mark" "$label" "$desc" "$R"
      else
        printf ' %s %-26s %s%s%s\n' "$mark" "$label" "$DIM" "$desc" "$R"
      fi
      i=$((i+1))
    done
    (( n == 0 )) && printf '  %s(no matches — esc clears the filter)%s\n' "$DIM" "$R"
    printf '\n%s ↑↓/jk move · enter run · / filter · esc back · q quit%s\n' "$DIM" "$R"
    hide

    local k; k="$(readkey)"
    case "$k" in
      UP)    (( n )) && sel=$(( (sel-1+n) % n )) ;;
      DOWN)  (( n )) && sel=$(( (sel+1) % n )) ;;
      ENTER) (( n )) || continue
             e="${entries[$sel]}"; rest="${e#*|}"
             run_entry "${rest%%|*}" "${e%%|*}" ;;
      ESC|LEFT) if [[ -n "$filter" ]]; then filter=""; sel=0; else return; fi ;;
      EOF) return ;;
      SLASH) show; printf '/'; read -r filter; sel=0; hide ;;
      CHAR:k) (( n )) && sel=$(( (sel-1+n) % n )) ;;
      CHAR:j) (( n )) && sel=$(( (sel+1) % n )) ;;
      CHAR:h) if [[ -n "$filter" ]]; then filter=""; sel=0; else return; fi ;;
      CHAR:q) cleanup; clear; exit 0 ;;
      CHAR:[1-9])
             # Number keys jump straight to an entry.
             local idx=$(( ${k#CHAR:} - 1 ))
             if (( idx >= 0 && idx < n )); then
               sel=$idx; e="${entries[$idx]}"; rest="${e#*|}"
               run_entry "${rest%%|*}" "${e%%|*}"
             fi ;;
    esac
  done
}

TOP=("Servers" "Plugins" "Maintenance" "Data" "All scripts")
top_sel=0
while true; do
  clear
  printf '%s  PizzaSMP ops%s   %s%s%s\n' "$B$CY" "$R" "$DIM" "$ROOT" "$R"
  printf '%s%s%s\n' "$DIM" "$(printf '─%.0s' {1..70})" "$R"
  for s in velocity $(all_backends 2>/dev/null); do
    p="$(service_port "$s" 2>/dev/null)"
    if [[ -n "$p" ]] && ss -ltn 2>/dev/null | grep -q ":$p "; then
      printf '  %s●%s %-12s %s%s%s\n' "$GR" "$R" "$s" "$DIM" "$p" "$R"
    else
      printf '  %s○%s %-12s %s%s down%s\n' "$RD" "$R" "$s" "$DIM" "${p:-?}" "$R"
    fi
  done
  printf '\n'
  for i in "${!TOP[@]}"; do
    if (( i == top_sel )); then printf '%s  %-16s%s\n' "$INV$B" "${TOP[$i]}" "$R"
    else printf '   %-16s\n' "${TOP[$i]}"; fi
  done
  printf '\n%s ↑↓/jk move · enter open · q quit%s\n' "$DIM" "$R"
  hide

  case "$(readkey)" in
    UP)   top_sel=$(( (top_sel-1+${#TOP[@]}) % ${#TOP[@]} )) ;;
    DOWN) top_sel=$(( (top_sel+1) % ${#TOP[@]} )) ;;
    CHAR:k) top_sel=$(( (top_sel-1+${#TOP[@]}) % ${#TOP[@]} )) ;;
    CHAR:j) top_sel=$(( (top_sel+1) % ${#TOP[@]} )) ;;
    CHAR:q|EOF) cleanup; clear; exit 0 ;;
    ENTER|RIGHT)
      case "$top_sel" in
        0) show_menu "Servers"     "${SERVERS[@]}" ;;
        1) show_menu "Plugins"     "${PLUGINS[@]}" ;;
        2) show_menu "Maintenance" "${MAINT[@]}" ;;
        3) show_menu "Data"        "${DATA[@]}" ;;
        4) clear; show; printf '%sAll scripts%s\n\n' "$B$CY" "$R"
           ls -1 "$ROOT/scripts"/*.sh | xargs -n1 basename | sed 's/^/  /'
           printf '\n%sRetired (scripts/retired/README.md):%s\n' "$DIM" "$R"
           ls -1 "$ROOT/scripts/retired"/*.sh 2>/dev/null | xargs -n1 basename | sed 's/^/  /'
           pause ;;
      esac ;;
  esac
done
