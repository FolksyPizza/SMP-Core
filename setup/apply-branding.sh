#!/usr/bin/env bash
# apply-branding.sh <profile-key>
# Applies the EXTERNAL brand artifacts that PNC's branding.yml can't touch at runtime:
#   - server-icon.png (proxy + SMP + limbo roots)
#   - velocity.toml MOTD (proxy) + server.properties MOTD (SMP + limbo)
#   - TAB tablist header
#   - pause-menu datapack title
#   - DeluxeMenus (Bedrock fallback) titles / discord / accent
#   - LuckPerms rank prefix colours (owner/co-owner/admin/sradmin/mod/srmod; dev kept)
#
# PNC's /branding set <profile> writes branding.yml AND shells out to this script.
# The visual swap finalises on the next (limbo) restart; LuckPerms recolour is live.
# Profile values below MIRROR branding.yml — keep them in sync.
set -uo pipefail

PROFILE="${1:?usage: apply-branding.sh <profile>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Proxy/limbo dirs are optional and host-specific — override for other deployments.
# They are only touched if the directory actually exists, so a standalone install is safe.
PROXY="${PIZZA_PROXY_DIR:-../PizzaProxy}"
LIMBO="${PIZZA_LIMBO_DIR:-../PizzaLimbo}"
BRANDING="$ROOT/plugins/PizzaNetworkCore/branding.yml"
SCREEN_SMP="3540.pizzasmp"

# ---- profile data (mirror of branding.yml) --------------------------------
case "$PROFILE" in
  example)
    DISPLAY="ExampleSMP"; PRIMARY="00BFFF"; REGION="NA-East"
    DISCORD="discord.gg/example"
    MOTD_MM="<aqua>ExampleSMP <gray>NA-East"
    MOTD_LEGACY=$'\xa7\n\xa7c\xea\x9c\xb1\xe1\xb4\x80\xca\x80\xe1\xb4\xa0\xc9\xaa\xea\x9c\xb1\n\xa7bExampleSMP \xa77NA-East'
    declare -A RANK=( [owner]=00BFFF [co-owner]=00BFFF [sradmin]=FF5555 [admin]=FF5555 [srmod]=55FF55 [mod]=55FF55 )
    ;;
  *)
    # Any other profile: read it straight from branding.yml (data-driven, for setup-server.sh
    # brands). stdlib-only parser (no pyyaml — the target host may not have it).
    [[ -f "$BRANDING" ]] || { echo "Unknown profile '$PROFILE' and no branding.yml at $BRANDING" >&2; exit 2; }
    VARS="$(PROFILE="$PROFILE" BRANDING="$BRANDING" python3 - <<'PY'
import os, sys, shlex
prof=os.environ['PROFILE']; path=os.environ['BRANDING']
lines=open(path).read().splitlines()
def indent(s): return len(s)-len(s.lstrip(' '))
# find the profile block (2-space key under 'profiles:')
i=0; n=len(lines); block=None
while i<n:
    if lines[i].rstrip()=='profiles:':
        j=i+1
        while j<n:
            l=lines[j]
            if l.strip()=='' : j+=1; continue
            if indent(l)==2 and l.strip().rstrip(':')==prof and l.rstrip().endswith(':'):
                # collect its sub-lines (indent>2)
                k=j+1; sub=[]
                while k<n and (lines[k].strip()=='' or indent(lines[k])>2):
                    sub.append(lines[k]); k+=1
                block=sub; break
            j+=1
        break
    i+=1
if block is None: sys.stderr.write("profile not found: %s\n"%prof); sys.exit(3)
def scalar(key, base=4):
    for l in block:
        if indent(l)==base and l.strip().startswith(key+':'):
            return l.split(':',1)[1].strip().strip('"')
    return ''
def nested(section, key, base=6):
    inb=False
    for l in block:
        if indent(l)==4 and l.strip().rstrip()==section+':': inb=True; continue
        if inb and indent(l)<=4 and l.strip(): inb=False
        if inb and indent(l)==base and l.strip().startswith(key+':'):
            return l.split(':',1)[1].strip().strip('"')
    return ''
display=scalar('display') or prof
region=scalar('region') or 'NA-East'
discord=scalar('discord')
primary=nested('colors','primary') or '00BFFF'
motd_mm=nested('motd','minimessage') or ("<#%s>%s <gray>{region}"%(primary,display))
motd_mm=motd_mm.replace('{region}',region)
ranks={}
inb=False
for l in block:
    if indent(l)==4 and l.strip().rstrip()=='ranks:': inb=True; continue
    if inb and indent(l)<=4 and l.strip(): inb=False
    if inb and indent(l)==6 and ':' in l:
        k,v=l.strip().split(':',1); ranks[k.strip()]=v.strip()
out=[]
out.append("DISPLAY=%s"%shlex.quote(display))
out.append("PRIMARY=%s"%shlex.quote(primary))
out.append("REGION=%s"%shlex.quote(region))
out.append("DISCORD=%s"%shlex.quote(discord))
out.append("MOTD_MM=%s"%shlex.quote(motd_mm))
# legacy MOTD: safe minimal form (section-code + hex) — mirrors the minimessage line
out.append("MOTD_LEGACY=%s"%shlex.quote("§\n§x"+''.join('§'+c for c in primary.upper())+" "+display+" §7"+region))
rk=' '.join("[%s]=%s"%(k,shlex.quote(v)) for k,v in ranks.items() if k!='dev')
out.append("declare -gA RANK=( %s )"%rk)
print('\n'.join(out))
PY
)" || { echo "Failed reading profile '$PROFILE' from $BRANDING" >&2; exit 2; }
    eval "$VARS"
    ;;
esac

# Known tokens of BOTH brands (for idempotent, reversible replacement).
ALL_DISPLAYS=("ExampleSMP" "Example SMP")
ALL_PRIMARIES=("00BFFF")
ALL_DISCORDS=("discord.gg/example")

say(){ echo "  [brand] $*"; }
echo "Applying brand profile: $PROFILE ($DISPLAY)"

# ---- 1. icons -------------------------------------------------------------
ICON="$ROOT/branding/icons/${PROFILE}.png"
if [[ -f "$ICON" ]]; then
  for d in "$ROOT" "$PROXY" "$LIMBO"; do
    [[ -d "$d" ]] && cp -f "$ICON" "$d/server-icon.png" && say "icon -> $d/server-icon.png"
  done
else
  say "WARN: $ICON missing, skipping icon swap"
fi

# ---- 2-6. text artifacts (python: targeted, reversible replacement) --------
DISPLAY="$DISPLAY" PRIMARY="$PRIMARY" DISCORD="$DISCORD" REGION="$REGION" \
MOTD_MM="$MOTD_MM" ROOT="$ROOT" PROXY="$PROXY" LIMBO="$LIMBO" \
python3 - <<'PY'
import os, re, io, json
DISPLAY=os.environ['DISPLAY']; PRIMARY=os.environ['PRIMARY']
DISCORD=os.environ['DISCORD']; REGION=os.environ['REGION']; MOTD_MM=os.environ['MOTD_MM']
ROOT=os.environ['ROOT']; PROXY=os.environ['PROXY']; LIMBO=os.environ['LIMBO']
DISPLAYS=["ExampleSMP","Example SMP"]
PRIMARIES=["00BFFF","00bfff"]
DISCORDS=["discord.gg/example"]

def sub_brand(text):
    # longest display first so "Example SMP" wins over "ExampleSMP"
    for d in sorted(DISPLAYS, key=len, reverse=True):
        text = text.replace(d, DISPLAY)
    for h in PRIMARIES:
        text = text.replace(h, PRIMARY)
    for dc in DISCORDS:
        text = text.replace(dc, DISCORD)
    return text

def sub_deluxe(text):
    # Full DeluxeMenus rebrand: names + discord + primary hex, PLUS the legacy/named accent
    # colours so nothing stays the old brand's blue.
    text = sub_brand(text)
    text = text.replace('"color":"aqua"', '"color":"#%s"' % PRIMARY)
    text = text.replace('"aqua"', '"#%s"' % PRIMARY)   # tellraw named aqua -> brand
    text = re.sub(r'&[bB]', '&#%s' % PRIMARY, text)     # legacy aqua accent -> brand
    # menu_title leading legacy colour just before the brand name -> brand hex
    text = re.sub(r"(?m)^(menu_title:\s*['\"])&[0-9A-Fa-fk-orK-OR]\s*(?=%s)" % re.escape(DISPLAY),
                  r"\g<1>&#%s" % PRIMARY, text)
    return text

def rewrite(path, fn, label):
    if not os.path.exists(path): print(f"  [brand] skip {label}: not found"); return
    s=io.open(path,encoding='utf-8').read()
    n=fn(s)
    if n!=s:
        io.open(path,'w',encoding='utf-8').write(n); print(f"  [brand] {label}: updated")
    else:
        print(f"  [brand] {label}: already current")

# velocity.toml motd
def vel(s):
    return re.sub(r'(?m)^motd\s*=\s*".*"$', f'motd = "{MOTD_MM}"', s)
rewrite(os.path.join(PROXY,"velocity.toml"), vel, "velocity.toml MOTD")

# TAB tablist header line (the &#...&l<display> entry)
def tab(s):
    return re.sub(r"(?m)^(\s*-\s*')(?:&#[0-9A-Fa-f]{6})?&l(?:ExampleSMP|Example SMP)(')",
                  rf"\g<1>&#{PRIMARY}&l{DISPLAY}\g<2>", s)
rewrite(os.path.join(ROOT,"plugins/TAB/config.yml"), tab, "TAB header")

# pause-menu datapack title
dp=os.path.join(ROOT,"world/datapacks/pizzasmp_menu/data/pizzasmp/dialog/menu.json")
def menu(s):
    d=json.loads(s)
    if isinstance(d.get("title"),dict):
        d["title"]["text"]=DISPLAY; d["title"]["color"]="#"+PRIMARY
    d["external_title"]=DISPLAY
    return json.dumps(d,indent=2)+"\n"
rewrite(dp, menu, "datapack menu title")

# DeluxeMenus (Bedrock fallback): display + discord + accent across all gui_menus
import glob
for f in glob.glob(os.path.join(ROOT,"plugins/DeluxeMenus/gui_menus/*.yml")):
    # skip the unrelated DeluxeMenus example menus (advanced/basics/requirements)
    if os.path.basename(f) in ("advanced_menu.yml","basics_menu.yml","requirements_menu.yml"): continue
    rewrite(f, sub_deluxe, "DeluxeMenus "+os.path.basename(f))

# Limbo Esc pause-menu datapack title (limbo tab header is branded at runtime from branding.yml)
lpath=os.path.join(LIMBO,"limbo/datapacks/pizzalimbo_menu/data/pizzalimbo/dialog/maintenance.json")
def limbomenu(s):
    d=json.loads(s)
    if isinstance(d.get("title"),dict):
        d["title"]["text"]=DISPLAY; d["title"]["color"]="#"+PRIMARY
    d["external_title"]=DISPLAY
    return json.dumps(d,indent=2)+"\n"
rewrite(lpath, limbomenu, "limbo datapack title")
PY

# ---- 2b. backend server.properties MOTD (printf to keep the bytes exact) ---
for d in "$ROOT" "$LIMBO"; do
  pf="$d/server.properties"
  [[ -f "$pf" ]] || continue
  tmp="$(mktemp)"
  grep -v '^motd=' "$pf" > "$tmp"
  # server.properties is a single-physical-line format: real newlines corrupt it (each line
  # becomes a bogus key). Encode any newline in the legacy MOTD as an escaped \n so the file
  # stays valid (Minecraft renders \n as a line break on the server-list ping).
  printf 'motd=%s\n' "$(printf '%s' "$MOTD_LEGACY" | sed ':a;N;$!ba;s/\n/\\n/g')" >> "$tmp"
  mv "$tmp" "$pf"
  say "server.properties MOTD -> $d"
done

# ---- 7. LuckPerms rank prefix recolour (live, via console) -----------------
# Reads each group's current prefix+priority from the DB, swaps the leading colour
# token to the brand colour, and re-sets it through the SMP console. dev is untouched.
source "$ROOT/scripts/targets.env" 2>/dev/null || true
if command -v mysql >/dev/null && [[ -n "${DB_HOST:-}" ]]; then
  for g in "${!RANK[@]}"; do
    hex="${RANK[$g]}"
    row="$(mysql -h "$DB_HOST" -u "$DB_USER" "-p${DB_PASS}" "$DB_NAME" -N -B -e \
      "SELECT permission FROM luckperms_group_permissions WHERE name='$g' AND permission LIKE 'prefix.%' LIMIT 1;" 2>/dev/null)"
    [[ -z "$row" ]] && { say "rank $g: no prefix, skip"; continue; }
    prio="$(sed -E 's/^prefix\.([0-9]+)\..*/\1/' <<<"$row")"
    rest="$(sed -E 's/^prefix\.[0-9]+\.//' <<<"$row")"
    # strip ONE leading colour token (hex &#RRGGBB or legacy &x) then prepend the brand hex
    rest="$(sed -E 's/^&#[0-9A-Fa-f]{6}//; t; s/^&[0-9A-Fa-fk-orK-OR]//' <<<"$rest")"
    newpfx="&#${hex}${rest}"
    screen -S "$SCREEN_SMP" -X stuff "lp group $g meta setprefix $prio \"$newpfx\"$(printf '\r')" 2>/dev/null \
      && say "rank $g -> &#$hex (prio $prio)" || say "rank $g: console inject failed (server up?)"
  done
else
  say "WARN: no DB access, skipped LuckPerms recolour"
fi

# ---- hot-apply on the proxy -------------------------------------------------
# Players ping the PROXY, so the MOTD + server icon they see come from velocity.toml + the proxy's
# server-icon.png. `velocity reload` re-reads BOTH live — no proxy restart, nobody disconnected.
if screen -list | grep -q "pizzaproxy"; then
  screen -S pizzaproxy -X stuff 'velocity reload\r' && say "proxy reloaded (MOTD + icon live)"
else
  say "WARN: pizzaproxy screen not found — proxy MOTD/icon apply on next proxy restart"
fi

echo "Done. MOTD/icon are live via the proxy; the SMP's own server.properties copy applies on its next restart."
