#!/usr/bin/env bash
# ============================================================================
# SMP-Core suite — interactive server setup / re-brand installer.
# Copyright (c) 2025-2026 William W. / FolksyPizza. MIT License.
#
# What it does (all interactive, with sane defaults):
#   1. picks/downloads the Paper build you want (or uses the bundled one)
#   2. fetches prerequisite third-party plugins (scripts/deps.txt)
#   3. asks for your brand: name, region, tagline, Discord, palette, MOTD
#   4. writes a NEW profile into plugins/PizzaNetworkCore/branding.yml and sets
#      it active — tiers auto-become "<Brand>+" / "<Brand>++" (e.g. HappyLand+/++)
#   5. configures rank colours + staff group hierarchy (LuckPerms)
#   6. runs apply-branding.sh so icon/MOTD/menus/tab all reflect the brand
#
# Idempotent: re-run any time to re-brand. Non-destructive to worlds/player data.
# Usage:  ./scripts/setup-server.sh          (interactive)
#         ./scripts/setup-server.sh --brand HappyLand --primary 00BFFF --yes
# ============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRANDING="$ROOT/plugins/PizzaNetworkCore/branding.yml"
DEPS="$ROOT/scripts/deps.txt"
C_OK=$'\e[32m'; C_ASK=$'\e[36m'; C_WARN=$'\e[33m'; C_OFF=$'\e[0m'
say(){ echo "${C_OK}[setup]${C_OFF} $*"; }
warn(){ echo "${C_WARN}[setup]${C_OFF} $*"; }

# ---- args ------------------------------------------------------------------
BRAND=""; SHORT=""; REGION="NA-East"; TAGLINE="Survival"; DISCORD=""
PRIMARY="00BFFF"; SUCCESS="55FF55"; DANGER="FF5555"; WARNC="FFAA00"; MUTED="AAAAAA"
PAPER_VERSION=""; ASSUME_YES=0; SKIP_DEPS=0; ACCEPT_EULA=0
while [ $# -gt 0 ]; do case "$1" in
  --brand) BRAND="$2"; shift 2;;
  --short) SHORT="$2"; shift 2;;
  --region) REGION="$2"; shift 2;;
  --tagline) TAGLINE="$2"; shift 2;;
  --discord) DISCORD="$2"; shift 2;;
  --primary) PRIMARY="$2"; shift 2;;
  --paper) PAPER_VERSION="$2"; shift 2;;
  --skip-deps) SKIP_DEPS=1; shift;;
  --accept-eula) ACCEPT_EULA=1; shift;;
  --yes|-y) ASSUME_YES=1; shift;;
  *) warn "unknown arg: $1"; shift;;
esac; done

ask(){ # ask VAR "prompt" "default"
  local __v="$1" __p="$2" __d="${3:-}" __in
  [ -n "${!__v}" ] && { eval "$__v=\${!__v}"; return; }   # already set via flag
  if [ "$ASSUME_YES" = 1 ]; then eval "$__v=\"\$__d\""; return; fi
  read -r -p "${C_ASK}$__p${__d:+ [$__d]}:${C_OFF} " __in || true
  eval "$__v=\"\${__in:-\$__d}\""
}
lc(){ echo "$1" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9'; }

echo "==== $(basename "$0") — brand + provision a server from the SMP-Core suite ===="

# ---- 0. legal: EULA + license acceptance (required before anything runs) ---
accept_legal(){
  local lic="$ROOT/LICENSE"
  echo
  echo "${C_OK}This software is open source under the MIT License.${C_OFF}"
  [ -f "$lic" ] && echo "  License: $lic"
  echo "  Use it for anything, including commercial work. Keep the license file."
  echo "  You must also comply with the Mojang EULA to run a Minecraft server."
  echo "  Provided AS IS, without warranty."
  return 0
}
accept_legal

# ---- 1. brand identity -----------------------------------------------------
ask BRAND   "Brand / server name (e.g. HappyLand)" "HappyLand"
ask SHORT   "Short name (tight spaces)" "$BRAND"
ask REGION  "Region label" "$REGION"
ask TAGLINE "Tagline / subtitle" "$TAGLINE"
ask DISCORD "Discord invite (blank ok)" ""
PROFILE="$(lc "$BRAND")"; [ -n "$PROFILE" ] || { warn "brand produced empty id"; exit 1; }

# ---- 2. palette ------------------------------------------------------------
echo "Colours are hex WITHOUT the # (e.g. 00BFFF). Enter = keep default."
ask PRIMARY "Primary / accent colour" "$PRIMARY"
ask SUCCESS "Success colour" "$SUCCESS"
ask DANGER  "Danger / error colour" "$DANGER"
ask WARNC   "Warning colour" "$WARNC"
ask MUTED   "Muted / grey colour" "$MUTED"

# ---- 3. rank colours (staff hierarchy) ------------------------------------
# Group order is the standard suite hierarchy; each gets a prefix colour. dev stays untouched.
declare -A RANKC=( [owner]="$PRIMARY" [co-owner]="$PRIMARY" [sradmin]="$DANGER" \
                   [admin]="$DANGER" [srmod]="$SUCCESS" [mod]="$SUCCESS" [dev]="keep" )

# ---- 4. Paper version ------------------------------------------------------
if [ -n "$PAPER_VERSION" ]; then
  say "Downloading Paper $PAPER_VERSION ..."
  MCV="${PAPER_VERSION%%:*}"; BUILD="${PAPER_VERSION##*:}"
  if [ "$MCV" = "$BUILD" ]; then
    BUILD=$(curl -s "https://api.papermc.io/v2/projects/paper/versions/$MCV" | grep -oE '"builds":\[[0-9,]+\]' | grep -oE '[0-9]+' | tail -1)
  fi
  URL="https://api.papermc.io/v2/projects/paper/versions/$MCV/builds/$BUILD/downloads/paper-$MCV-$BUILD.jar"
  curl -fL --progress-bar -o "$ROOT/paper.jar" "$URL" && say "paper.jar -> $MCV build $BUILD" || warn "Paper download failed; keeping existing paper.jar"
else
  say "Keeping existing paper.jar ($(ls -la "$ROOT/paper.jar" 2>/dev/null | awk '{print $5}') bytes)."
fi

# ---- 5. prerequisite plugins ----------------------------------------------
if [ "$SKIP_DEPS" = 0 ] && [ -f "$DEPS" ]; then
  say "Fetching prerequisite plugins from scripts/deps.txt ..."
  mkdir -p "$ROOT/plugins"
  while IFS='|' read -r name url note; do
    [[ "$name" =~ ^#|^$ ]] && continue
    name="$(echo "$name" | xargs)"; url="$(echo "$url" | xargs)"
    # LuckPerms: pinned build numbers rot (404). Resolve the latest build from the metadata API.
    if [ "$name" = "LuckPerms" ]; then
      url="$(curl -fsSL https://metadata.luckperms.net/data/all 2>/dev/null \
             | grep -oE 'https://download\.luckperms\.net/[0-9]+/bukkit/loader/LuckPerms-Bukkit-[0-9.]+\.jar' | head -1)"
      [ -n "$url" ] || { warn "  LuckPerms: could not resolve latest build (metadata.luckperms.net) — download manually"; continue; }
    fi
    if [ "$url" = "MANUAL" ]; then warn "  $name: $note (download manually into plugins/)"; continue; fi
    if ls "$ROOT/plugins/$name"*.jar >/dev/null 2>&1; then say "  $name already present"; continue; fi
    curl -fL --progress-bar -o "$ROOT/plugins/$name.jar" "$url" && say "  $name installed" || warn "  $name download failed ($url)"
  done < "$DEPS"
fi

# ---- 6. write the branding profile ----------------------------------------
# On a fresh distribution clone plugins/PizzaNetworkCore/ doesn't exist yet; create it
# so the profile write (and PNC's first-boot read) has a home.
mkdir -p "$(dirname "$BRANDING")"
say "Writing brand profile '$PROFILE' into branding.yml ..."
BRAND="$BRAND" SHORT="$SHORT" REGION="$REGION" TAGLINE="$TAGLINE" DISCORD="$DISCORD" \
PROFILE="$PROFILE" PRIMARY="$PRIMARY" SUCCESS="$SUCCESS" DANGER="$DANGER" WARNC="$WARNC" \
MUTED="$MUTED" BRANDING="$BRANDING" \
OWNERC="${RANKC[owner]}" COOWNERC="${RANKC[co-owner]}" SRADMINC="${RANKC[sradmin]}" \
ADMINC="${RANKC[admin]}" SRMODC="${RANKC[srmod]}" MODC="${RANKC[mod]}" \
python3 - <<'PY'
import os, re
p=os.environ['BRANDING']; prof=os.environ['PROFILE']; brand=os.environ['BRAND']
def e(k): return os.environ.get(k,'')
block=f"""  {prof}:
    display: {brand}
    short: {e('SHORT') or brand}
    region: {e('REGION')}
    tagline: {e('TAGLINE')}
    discord: {e('DISCORD')}
    server_icon: server-icon.png
    tiers:
      plus: {brand}+
      plusplus: {brand}++
    colors:
      primary: {e('PRIMARY')}
      info: {e('PRIMARY')}
      success: {e('SUCCESS')}
      warning: {e('WARNC')}
      danger: {e('DANGER')}
      muted: {e('MUTED')}
      text: FFFFFF
    motd:
      minimessage: "<#{e('PRIMARY')}>{brand} <#{e('MUTED')}>{{region}}"
      legacy: |-
        §
        §x§{'§'.join(e('PRIMARY').upper())} {brand} §7{{region}}
    ranks:
      owner: {e('OWNERC')}
      co-owner: {e('COOWNERC')}
      sradmin: {e('SRADMINC')}
      admin: {e('ADMINC')}
      srmod: {e('SRMODC')}
      mod: {e('MODC')}
      dev: keep
"""
s=open(p).read() if os.path.exists(p) else "active: example\n\nprofiles:\n"
# replace an existing block with this profile id, else append under profiles:
pat=re.compile(rf"(?ms)^  {re.escape(prof)}:\n(?:    .*\n|      .*\n|\n)*")
if pat.search(s): s=pat.sub(block+"\n", s)
elif re.search(r'(?m)^profiles:\s*$', s): s=re.sub(r'(?m)^(profiles:\s*)$', r'\1\n'+block, s, count=1)
else: s+= "\nprofiles:\n"+block
s=re.sub(r'(?m)^active:.*$', f'active: {prof}', s, count=1)
open(p,'w').write(s)
print(f"  profile '{prof}' written and set active")
PY

# ---- 7. apply everywhere (icon/MOTD/tab/menus/ranks + proxy reload) --------
if [ -x "$ROOT/scripts/apply-branding.sh" ]; then
  say "Applying brand artifacts (icon, MOTD, TAB, menus, LuckPerms rank colours) ..."
  bash "$ROOT/scripts/apply-branding.sh" "$PROFILE" || warn "apply-branding reported issues (server may be offline — will apply on next boot)"
else
  warn "apply-branding.sh not found/executable; skipping live apply."
fi

cat <<EOF

${C_OK}==== Setup complete ====${C_OFF}
  Brand:    $BRAND  (+ tiers ${BRAND}+ / ${BRAND}++)
  Profile:  $PROFILE  (active)
  Region:   $REGION    Palette primary #$PRIMARY
Next:
  - set your DB credentials in plugins/PizzaNetworkCore/config.yml (+ PizzaLimbo)
  - forwarding.secret must match proxy + backends; share key.pem for Bedrock
  - start order: proxy -> limbo -> SMP  (see docs/DISTRIBUTION.md)
  - re-run this script any time to re-brand.
EOF
