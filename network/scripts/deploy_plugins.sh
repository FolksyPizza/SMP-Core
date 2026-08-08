#!/usr/bin/env bash
# Deploy built plugins to the right servers, by platform.
#
# THE PROBLEM THIS SOLVES: jars were being copied ad hoc, so backends drifted apart —
# dev ended up on an older PizzaNetworkCore with no quick-actions datapack while
# survival had the current one. Anything that is "the same everywhere" must be applied
# by one command that cannot forget a target.
#
# Platform routing (a Velocity plugin will not load on Paper and vice versa):
#   paper    -> every Paper backend        ($ALL_BACKENDS from targets.env)
#   velocity -> the proxy
#   folia    -> future Folia backends (routed like paper, listed separately so the
#               split already exists when dev-folia arrives)
#
# Usage:
#   deploy_plugins.sh              build nothing, deploy build-output/ everywhere
#   deploy_plugins.sh --build      build from tools/ first, then deploy
#   deploy_plugins.sh --to dev     deploy to one target only (promotion step)
#   deploy_plugins.sh --check      report drift without changing anything
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
OUT="$ROOT/build-output"

# --- what goes where ----------------------------------------------------------------
PAPER_JARS=(PizzaNetworkCore.jar PizzaAdminTools.jar PizzaChatGuard.jar
            PizzaPunishment.jar PizzaRuleGuard.jar PizzaEnderchest.jar)

# Hub-only plugins. PizzaSpawnRules grants creative flight inside its protected zone to
# power the lobby double jump, and its lockAllWorld option makes the WHOLE world that
# zone — which on survival means every player is handed flight. It belongs on the hub
# backends only.
HUB_ONLY_JARS=(PizzaSpawnRules.jar)
HUB_SERVERS=(lobby maintenance)
VELOCITY_JARS=(PizzaProxyGuard.jar)
# Datapacks travel with the Paper backends; a stale one is why dev had no quick menu.
DATAPACK_SRC="$ROOT/SMP/runtime-configs/datapacks-pizzasmp_menu"

BUILD=0; ONLY=""; CHECK=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) BUILD=1; shift ;;
    --to) ONLY="${2:?}"; shift 2 ;;
    --check) CHECK=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

(( BUILD == 1 )) && "$ROOT/scripts/build_from_repo.sh"

paper_targets() {
  if [[ -n "$ONLY" ]]; then
    [[ "$ONLY" == "velocity" ]] && return 0
    echo "$ONLY"
  else
    all_backends
  fi
}
velocity_target() {
  [[ -n "$ONLY" && "$ONLY" != "velocity" ]] && return 0
  echo velocity
}

sha() { [[ -f "$1" ]] && sha256sum "$1" | cut -c1-12 || echo "--------ABSENT"; }

if (( CHECK == 1 )); then
  echo "=== drift check (build-output is the reference) ==="
  for j in "${PAPER_JARS[@]}"; do
    ref="$(sha "$OUT/$j")"
    line="  $(printf '%-26s' "$j") ref=$ref"
    for b in $(all_backends); do
      got="$(sha "$ROOT/$b/plugins/$j")"
      mark=$([[ "$got" == "$ref" ]] && echo "ok" || echo "DRIFT")
      line="$line  $b=$mark"
    done
    echo "$line"
  done
  for j in "${VELOCITY_JARS[@]}"; do
    ref="$(sha "$OUT/$j")"; got="$(sha "$ROOT/velocity/plugins/$j")"
    echo "  $(printf '%-26s' "$j") ref=$ref  velocity=$([[ "$got" == "$ref" ]] && echo ok || echo DRIFT)"
  done
  echo "=== datapack ==="
  for b in $(all_backends); do
    n="$(find "$ROOT/$b/world/datapacks/pizzasmp_menu" -type f 2>/dev/null | wc -l)"
    echo "  $(printf '%-14s' "$b") pizzasmp_menu files=$n$([[ "$n" == "0" ]] && echo '   <-- MISSING' || true)"
  done
  exit 0
fi

echo "=== deploying from $OUT ==="
for b in $(paper_targets); do
  [[ -d "$ROOT/$b/plugins" ]] || { echo "  [skip] $b (no plugins dir)"; continue; }
  for j in "${PAPER_JARS[@]}"; do
    [[ -f "$OUT/$j" ]] && cp -f "$OUT/$j" "$ROOT/$b/plugins/$j"
  done
  # Hub-only jars: install on hub backends, and actively REMOVE from the others so a
  # previous all-servers deploy cannot leave one behind.
  is_hub=0
  for h in "${HUB_SERVERS[@]}"; do [[ "$b" == "$h" ]] && is_hub=1; done
  for j in "${HUB_ONLY_JARS[@]}"; do
    if (( is_hub )); then
      [[ -f "$OUT/$j" ]] && cp -f "$OUT/$j" "$ROOT/$b/plugins/$j"
    else
      rm -f "$ROOT/$b/plugins/$j"
    fi
  done
  # Paper caches remapped jars; a stale cache serves the OLD plugin after an update.
  rm -rf "$ROOT/$b/plugins/.paper-remapped"
  if [[ -d "$DATAPACK_SRC" ]]; then
    mkdir -p "$ROOT/$b/world/datapacks/pizzasmp_menu"
    cp -a "$DATAPACK_SRC/." "$ROOT/$b/world/datapacks/pizzasmp_menu/"
  fi
  echo "  [paper]    $b  (${#PAPER_JARS[@]} jars + datapack)"
done

for v in $(velocity_target); do
  for j in "${VELOCITY_JARS[@]}"; do
    [[ -f "$OUT/$j" ]] && cp -f "$OUT/$j" "$ROOT/$v/plugins/$j" && echo "  [velocity] $v  $j"
  done
done

echo
echo "Deployed. Restart the affected servers for jar changes to take effect;"
echo "datapack changes need /minecraft:reload."
