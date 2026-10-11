#!/usr/bin/env bash
# Copyright (c) 2025-2026 William W. (FolksyPizza).
# Licensed under the Apache License, Version 2.0 (see LICENSE).
#
# SMP-Core server setup: creates a ready-to-run Paper server with the SMP-Core plugins.
#
#   ./setup.sh [server-dir] [--yes] [--accept-eula]
#
#   server-dir      where the server goes (default: ./server, ignored by git)
#   --yes           accept every default instead of asking
#   --accept-eula   accept the Minecraft EULA (https://aka.ms/MinecraftEULA) without asking
#
# Steps: check Java 21, download Paper, build or download the SMP-Core plugins, fetch ProtocolLib and Vault
# (checksums verified) and optionally LuckPerms, install the menu datapack, and write eula.txt, server.properties,
# a brand profile and start.sh. Runs on macOS and Linux. Re-running replaces the plugin and Paper jars and keeps
# worlds, configs and player data.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SUPPORTED="1.21.11 1.21.10 1.21.9 1.21.8 1.21.7"
UA="SMP-Core-setup/1.0 (https://github.com/FolksyPizza/SMP-Core)"
PROTOCOLLIB_URL="https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar"
PROTOCOLLIB_SHA="ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf"
VAULT_URL="https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar"
VAULT_SHA="a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d"

SERVER="" YES=0 EULA=0
for arg in "$@"; do
  case "$arg" in
    --yes) YES=1 ;;
    --accept-eula) EULA=1 ;;
    -h|--help) sed -n '5,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "setup: unknown option $arg" >&2; exit 2 ;;
    *) SERVER="$arg" ;;
  esac
done
SERVER="${SERVER:-$ROOT/server}"

if [ -t 1 ]; then B=$'\033[1m'; D=$'\033[2m'; R=$'\033[0m'; else B="" D="" R=""; fi
say() { printf '%s\n' "${B}==>${R} $*"; }
note() { printf '%s\n' "    $*"; }
die() { printf '%s\n' "setup: $*" >&2; exit 1; }
ask() { # prompt default -> answer (the default with --yes or no terminal)
  local answer=""
  if [ "$YES" = 1 ] || [ ! -t 0 ]; then printf '%s\n' "$2"; return; fi
  read -r -p "    $1 ${D}[$2]${R} " answer </dev/tty || true
  printf '%s\n' "${answer:-$2}"
}
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() { # url destination [sha256]
  curl -fsSL --retry 3 -A "$UA" -o "$2.part" "$1" || die "download failed: $1"
  if [ -n "${3:-}" ] && [ "$(sha256 "$2.part")" != "$3" ]; then rm -f "$2.part"; die "checksum mismatch: $1"; fi
  mv "$2.part" "$2"
}
json_field() { # key json -> first string value of "key"
  printf '%s' "$2" | tr -d '\n' | sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}

printf '%s\n' "${B}SMP-Core server setup${R}  ${D}(server directory: $SERVER)${R}"
command -v curl >/dev/null 2>&1 || die "curl is required"

# ---- 1. Java 21 ------------------------------------------------------------------------------------------------------
JAVA="java"
java_major() { # prints the major version, 0 when java is missing or unusable (macOS ships a stub without a JDK)
  local v; v="$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\)[^"]*".*/\1/p' | head -1)"; printf '%s\n' "${v:-0}"
}
if ! command -v java >/dev/null 2>&1 || [ "$(java_major java)" -lt 21 ]; then
  BREW_JAVA=""
  command -v brew >/dev/null 2>&1 && BREW_JAVA="$(brew --prefix openjdk@21 2>/dev/null)/bin/java"
  if [ -n "$BREW_JAVA" ] && [ -x "$BREW_JAVA" ]; then
    JAVA="$BREW_JAVA"
  elif command -v brew >/dev/null 2>&1 && [ "$(ask 'Java 21 is missing. Install it with Homebrew (brew install openjdk@21)? y/n' y)" = y ]; then
    brew install openjdk@21
    JAVA="$(brew --prefix openjdk@21)/bin/java"
  else
    die "Java 21 or newer is required. macOS: brew install openjdk@21. Debian/Ubuntu: sudo apt install openjdk-21-jre-headless."
  fi
fi
say "Java $(java_major "$JAVA") ($JAVA)"

mkdir -p "$SERVER/plugins"
SERVER="$(cd "$SERVER" && pwd)"

# ---- 2. Paper ----------------------------------------------------------------------------------------------------------
say "Paper"
note "Supported: $SUPPORTED (1.21.11 is the most tested)"
MCV="$(ask 'Minecraft version' 1.21.11)"
case " $SUPPORTED " in *" $MCV "*) ;; *) die "Minecraft $MCV is not supported (supported: $SUPPORTED)" ;; esac
build_json="$(curl -fsSL -A "$UA" "https://fill.papermc.io/v3/projects/paper/versions/$MCV/builds/latest")" \
  || die "could not reach the Paper download API"
server_json="$(printf '%s' "$build_json" | tr -d '\n' | sed 's/.*"server:default"//')"
paper_url="$(json_field url "$server_json")"
paper_sha="$(json_field sha256 "$server_json")"
[ -n "$paper_url" ] && [ -n "$paper_sha" ] || die "no Paper download found for $MCV"
fetch "$paper_url" "$SERVER/paper.jar" "$paper_sha"
note "paper.jar: $(basename "$paper_url")"

# ---- 3. SMP-Core plugins ---------------------------------------------------------------------------------------------
say "SMP-Core plugins"
source_default=download
[ -x "$ROOT/gradlew" ] && source_default=build
SOURCE="$(ask 'Build from this checkout or download the latest release? build/download' "$source_default")"
JARS="$ROOT/build/dist"
if [ "$SOURCE" = build ]; then
  [ -x "$ROOT/gradlew" ] || die "no Gradle wrapper here; run from a clone of the repository or choose download"
  note "Building (the first build downloads Gradle and the dependencies)..."
  (cd "$ROOT" && JAVA_HOME="$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')" ./gradlew -q build) \
    || die "build failed"
else
  JARS="$SERVER/.smpcore-download"; rm -rf "$JARS"; mkdir -p "$JARS"
  release="$(curl -fsSL -A "$UA" https://api.github.com/repos/FolksyPizza/SMP-Core/releases/latest)" \
    || die "could not read the latest release"
  printf '%s' "$release" | tr ',' '\n' | sed -n 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"\([^"]*\.jar\)".*/\1/p' \
    | while read -r url; do fetch "$url" "$JARS/$(basename "$url")"; done
fi
installed=""
for jar in "$JARS"/Pizza*.jar; do
  [ -f "$jar" ] || die "no plugin jars in $JARS"
  name="$(basename "$jar")"
  case "$name" in
    PizzaProxyGuard.jar|PizzaLimbo.jar) continue ;;   # Velocity plugin and the limbo backend's plugin
  esac
  cp "$jar" "$SERVER/plugins/$name"; installed="$installed ${name%.jar}"
done
note "installed:$installed"
note "not installed: PizzaProxyGuard (goes on a Velocity proxy) and PizzaLimbo (goes on a limbo backend)"

# ---- 4. dependencies ---------------------------------------------------------------------------------------------------
say "Dependencies"
fetch "$PROTOCOLLIB_URL" "$SERVER/plugins/ProtocolLib.jar" "$PROTOCOLLIB_SHA"; note "ProtocolLib 5.4.0"
fetch "$VAULT_URL" "$SERVER/plugins/Vault.jar" "$VAULT_SHA"; note "Vault 1.7.3"
if [ "$(ask 'Install LuckPerms (recommended for ranks and permissions)? y/n' y)" = y ]; then
  lp="$(json_field bukkit "$(curl -fsSL -A "$UA" https://metadata.luckperms.net/data/downloads)")"
  [ -n "$lp" ] || die "could not resolve the latest LuckPerms build"
  rm -f "$SERVER"/plugins/LuckPerms-Bukkit-*.jar
  fetch "$lp" "$SERVER/plugins/$(basename "$lp")"; note "$(basename "$lp") (LuckPerms publishes no checksum)"
fi

# ---- 5. world datapack -------------------------------------------------------------------------------------------------
mkdir -p "$SERVER/world/datapacks"
rm -rf "$SERVER/world/datapacks/pizzasmp-menu"
cp -R "$ROOT/datapacks/pizzasmp-menu" "$SERVER/world/datapacks/"
say "Datapack: pizzasmp-menu (the /menu dialogs)"

# ---- 6. EULA, server.properties, brand ---------------------------------------------------------------------------------
if [ "$EULA" != 1 ] && ! grep -q '^eula=true' "$SERVER/eula.txt" 2>/dev/null; then
  if [ "$YES" != 1 ] && [ -t 0 ] && [ "$(ask 'Do you accept the Minecraft EULA (https://aka.ms/MinecraftEULA)? y/n' n)" = y ]; then EULA=1; fi
fi
if [ "$EULA" = 1 ]; then echo "eula=true" > "$SERVER/eula.txt"; say "EULA accepted"
elif ! grep -q '^eula=true' "$SERVER/eula.txt" 2>/dev/null; then
  note "EULA not accepted: set eula=true in $SERVER/eula.txt before the first start"
fi

BRAND="$(ask 'Server name (shown in menus, the tab list and the MOTD)' ExampleSMP)"
PRIMARY="$(ask 'Brand color, hex without # ' 00BFFF)"
PROFILE="$(printf '%s' "$BRAND" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"; PROFILE="${PROFILE:-server}"
if [ ! -f "$SERVER/server.properties" ]; then
  PORT="$(ask 'Server port' 25565)"
  cat > "$SERVER/server.properties" <<EOF
server-port=$PORT
motd=$BRAND
online-mode=true
spawn-protection=0
EOF
  say "server.properties: port $PORT"
fi
BRANDING="$SERVER/plugins/PizzaNetworkCore/branding.yml"
mkdir -p "$(dirname "$BRANDING")"
[ -f "$BRANDING" ] && cp "$BRANDING" "$BRANDING.bak"
# The documented template the plugin itself writes on first start, filled in with this brand.
esc() { printf '%s' "$1" | tr -d '"' | sed 's/[\/&]/\\&/g'; }
sed -e "s/ExampleSMP/$(esc "$BRAND")/g" -e "s/^active: example$/active: $PROFILE/" -e "s/^  example:$/  $PROFILE:/" \
    -e "s/\"00BFFF\"/\"$(esc "$PRIMARY")\"/g" "$ROOT/PizzaNetworkCore/resources/branding-default.yml" > "$BRANDING"
say "Brand profile '$PROFILE' (tiers $BRAND+ and $BRAND++)"

# ---- 7. start script ---------------------------------------------------------------------------------------------------
MEM="$(ask 'Server memory (for example 4G)' 4G)"
cat > "$SERVER/start.sh" <<EOF
#!/usr/bin/env bash
# Starts the server; written by SMP-Core setup.sh. Flags: Aikar's G1 settings for Paper.
cd "\$(dirname "\$0")"
exec "$JAVA" -Xms$MEM -Xmx$MEM -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \\
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 \\
  -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 \\
  -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \\
  -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \\
  -jar paper.jar --nogui
EOF
chmod +x "$SERVER/start.sh"

cat <<EOF

${B}Done.${R} Start the server with:

    $SERVER/start.sh

  - With no database configured, every plugin stores its data in local files.
  - MySQL or MariaDB: after the first start, set sync.database in plugins/PizzaNetworkCore/config.yml and restart.
  - Velocity networks: see network/README.md.
  - Re-run ./setup.sh to update the plugins or change the brand; worlds, configs and player data are kept.
EOF
