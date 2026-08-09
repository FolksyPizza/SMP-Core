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

  # Third-party plugins. These are installed by hand rather than built, which is precisely
  # why one can go missing on a single backend and stay missing: nothing rebuilds it and
  # nothing complains. aAmethyst was absent from survival while PizzaNetworkCore happily
  # charged $1.5M for tools it then could not deliver.
  echo "=== third-party plugins (scripts/plugin-manifest.env) ==="
  if [[ -f "$ROOT/scripts/plugin-manifest.env" ]]; then
    # shellcheck disable=SC1091
    source "$ROOT/scripts/plugin-manifest.env"
    missing_total=0
    for b in $(all_backends) velocity; do
      [[ -d "$ROOT/$b/plugins" ]] || continue
      if [[ "$b" == "velocity" ]]; then wanted="${REQUIRED_velocity:-}"
      else
        eval "extra=\${REQUIRED_$b:-}"
        wanted="$REQUIRED_ALL $extra"
      fi
      missing=""
      for want in $wanted; do
        ls "$ROOT/$b/plugins/$want"*.jar >/dev/null 2>&1 || missing="$missing $want"
      done
      if [[ -n "$missing" ]]; then
        echo "  $(printf '%-14s' "$b") MISSING:$missing"
        missing_total=$((missing_total + 1))
      else
        echo "  $(printf '%-14s' "$b") ok"
      fi
    done
    # A data folder with no jar beside it is the fingerprint of an uninstalled plugin —
    # which is exactly how aAmethyst hid. Matching has to be case-insensitive (WorldEdit vs
    # worldedit-bukkit.jar) and alias-aware (a plugin's data folder often does not share a
    # name with its jar), otherwise the list is all false positives and gets ignored.
    echo "=== orphaned data folders (config present, jar absent) ==="
    # data-folder-name -> jar-name-stem it actually belongs to
    folder_alias() {
      case "$(printf '%s' "$1" | tr 'A-Z' 'a-z')" in
        enderchestexpander) echo "pizzaenderchest" ;;
        punishdrop)         echo "pizzapunishment" ;;
        aamethyst)          echo "amethyst" ;;
        *)                  echo "$1" ;;
      esac
    }
    for b in $(all_backends) velocity; do
      [[ -d "$ROOT/$b/plugins" ]] || continue
      # Jar stems on this backend, lowercased, for case-insensitive prefix matching.
      jars="$(ls "$ROOT/$b/plugins"/*.jar 2>/dev/null | xargs -r -n1 basename | sed 's/\.jar$//' | tr 'A-Z' 'a-z')"
      orphans=""
      for d in "$ROOT/$b/plugins"/*/; do
        n="$(basename "$d")"
        # Shared library folders and disabled leftovers are not evidence of anything.
        case "$n" in .paper-remapped|bStats|spark|*.disabled.*) continue ;; esac
        key="$(folder_alias "$n" | tr 'A-Z' 'a-z')"
        printf '%s\n' "$jars" | grep -q "^$key" || orphans="$orphans $n"
      done
      [[ -n "$orphans" ]] && echo "  $(printf '%-14s' "$b")$orphans"
    done
    echo
    (( missing_total > 0 )) && echo "  ^ a MISSING third-party plugin means its features silently do nothing."
  else
    echo "  (no manifest — create scripts/plugin-manifest.env to enable this check)"
  fi
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
