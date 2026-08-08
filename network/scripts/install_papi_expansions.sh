#!/usr/bin/env bash
# Install PlaceholderAPI expansions onto every Paper backend.
#
# WHY THIS EXISTS: the expansions directory was empty on every backend, so anything
# needing a PAPI placeholder silently rendered as literal text — most visibly the TAB
# rank prefixes (%luckperms_prefix%) and %player_ping%. Only PNC's own `pizzasmp`
# expansion worked, because it self-registers in code.
#
# THE eCLOUD GOTCHA: https://api.extendedclip.com/v2/?resource=<name> IGNORES the query
# parameter and returns the ENTIRE catalogue as one JSON object keyed by expansion name.
# Downloading that URL straight to a .jar yields a 129KB JSON file that Paper cannot
# load and that fails silently. The real artifact lives at
#   .["<Name>"].versions[0].url
# so the catalogue must be parsed first. That is what this script does.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"

CATALOGUE="https://api.extendedclip.com/v2/"
CACHE="$ROOT/plugins-common/papi-expansions"
mkdir -p "$CACHE"

# Expansions this network actually uses. Names are case-sensitive catalogue keys.
WANT=("LuckPerms" "Player" "Server" "Vault" "Math" "Statistic")
[[ $# -gt 0 ]] && WANT=("$@")

echo "Fetching eCloud catalogue..."
JSON="$(mktemp)"; trap 'rm -f "$JSON"' EXIT
if ! timeout 60 curl -sSL -o "$JSON" "$CATALOGUE"; then
  echo "ERROR: could not reach the eCloud catalogue." >&2; exit 1
fi
# A valid catalogue is a JSON object; anything else means the API shape changed again.
if ! python3 -c "import json,sys; d=json.load(open('$JSON')); sys.exit(0 if isinstance(d,dict) and d else 1)"; then
  echo "ERROR: catalogue did not parse as a JSON object — the API shape may have changed." >&2
  exit 1
fi

for name in "${WANT[@]}"; do
  url="$(python3 -c "
import json
d = json.load(open('$JSON'))
# Catalogue keys are case-sensitive; match case-insensitively to be forgiving.
key = next((k for k in d if k.lower() == '${name}'.lower()), None)
if key:
    vs = d[key].get('versions') or []
    print(vs[0].get('url','') if vs else '')
" 2>/dev/null)"
  if [[ -z "$url" ]]; then
    echo "  [skip] $name — not in catalogue"; continue
  fi
  out="$CACHE/Expansion-${name}.jar"
  if ! timeout 120 curl -sSL -o "$out" "$url"; then
    echo "  [FAIL] $name — download failed"; rm -f "$out"; continue
  fi
  # Guard against the exact failure this script exists to prevent: verify it is a ZIP
  # (jars are zips), not JSON or an HTML error page.
  if ! file "$out" | grep -qi 'zip\|java archive'; then
    echo "  [FAIL] $name — downloaded file is not a jar ($(file -b "$out" | cut -c1-40))"
    rm -f "$out"; continue
  fi
  echo "  [ok]   $name  ($(du -h "$out" | cut -f1))"
done

echo
for b in $(all_backends); do
  d="$ROOT/$b/plugins/PlaceholderAPI/expansions"
  [[ -d "$ROOT/$b/plugins/PlaceholderAPI" ]] || continue
  mkdir -p "$d"
  cp -f "$CACHE"/Expansion-*.jar "$d/" 2>/dev/null || true
  echo "  $b: $(ls "$d"/*.jar 2>/dev/null | wc -l) expansion(s)"
done

echo
echo "Run 'papi reload' on each backend (or restart) to register them."
