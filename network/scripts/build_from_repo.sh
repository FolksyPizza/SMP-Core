#!/usr/bin/env bash
# Build every custom plugin from the repo's tools/ tree and stage the jars.
#
# AUTHORITY: SMP/tools/ is the source of truth. This script never edits that source and
# never "adapts it backward" to whatever the old install happened to have. It compiles
# what the repo ships and stages the result.
#
# Why a script instead of the raw javac lines: PizzaNetworkCore MUST have
# all three of its .java files compiled together, with any OLD PizzaNetworkCore.jar kept
# OFF the classpath. Compiling only PizzaNetworkCore.java silently produced a jar missing
# PlayerSyncManager/MaintenanceQueueManager once the old jar was overwritten. Building
# per-module here makes that mistake impossible to repeat.
#
# Usage: ./scripts/build_from_repo.sh [module ...]   (default: all)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$ROOT/SMP"
TOOLS="$REPO/tools"
OUT="$ROOT/build-output"
JAVAC="${JAVA_HOME:-/opt/.sdkman/candidates/java/current}/bin/javac"
JAR="${JAVA_HOME:-/opt/.sdkman/candidates/java/current}/bin/jar"
G="$HOME/.gradle/caches/modules-2/files-2.1"

[ -d "$TOOLS" ] || { echo "ERROR: $TOOLS not found — is the repo cloned at SMP/?" >&2; exit 1; }

find_jar() { find "$G/$1" -name "$2" 2>/dev/null | grep -v sources | head -1; }

PAPER_API="$(find_jar io.papermc.paper 'paper-api-1.21.11*.jar')"
VAULT_API="$(find_jar com.github.MilkBowl 'VaultAPI-*.jar')"
VELOCITY_API="$(find_jar com.velocitypowered 'velocity-api-*.jar')"
HIKARI="$(find_jar com.zaxxer 'HikariCP-*.jar')"
MARIADB="$(find_jar org.mariadb.jdbc 'mariadb-java-client-*.jar')"
PROTOCOLLIB="$ROOT/plugins-common/ProtocolLib.jar"

# Third-party APIs the plugins compile against come from a live plugins dir.
# The old PizzaNetworkCore.jar is EXCLUDED on purpose (see header).
PLUGIN_CP="$(find "$ROOT/survival/plugins" -maxdepth 1 -name '*.jar' \
  -not -name 'PizzaNetworkCore*.jar' 2>/dev/null | tr '\n' ':')"

# Transitives (adventure/kyori, slf4j, guice, ...) come from Gradle's RESOLVED compile
# classpath, not from globbing the cache. Globbing looked simpler but silently put an
# older paper-api 1.21.8 and adventure 4.17.0 ahead of the correct 1.21.11 / 4.26.1, so
# javac reported "cannot find symbol" for genuinely-present APIs like Player.showDialog.
# First match on a classpath wins, so version order matters.
DEPS_CP=""
if [ -x "$ROOT/plugins-src/gradlew" ]; then
  DEPS_CP="$(cd "$ROOT/plugins-src" && ./gradlew -q :network-core:printCompileClasspath 2>/dev/null | tail -1)"
fi
if [ -z "$DEPS_CP" ]; then
  echo "WARNING: could not resolve the Gradle compile classpath; falling back to a cache glob." >&2
  echo "         If you see 'cannot find symbol' on a Paper API, this is why." >&2
  DEPS_CP="$(find "$G" -name '*.jar' 2>/dev/null | grep -v -- '-sources' | tr '\n' ':')"
fi

BASE_CP="$PAPER_API:$VAULT_API:$HIKARI:$MARIADB:$PROTOCOLLIB:$DEPS_CP:$PLUGIN_CP"

# PizzaProxyGuard is a VELOCITY plugin — it needs guice/@Inject, slf4j and the Velocity
# API, none of which are on the Paper classpath above. Resolve those separately.
VELOCITY_CP=""
if [ -x "$ROOT/plugins-src/gradlew" ]; then
  VELOCITY_CP="$(cd "$ROOT/plugins-src" && ./gradlew -q :velocity-bridge:printCompileClasspath 2>/dev/null | tail -1)"
fi
[ -z "$VELOCITY_CP" ] && VELOCITY_CP="$VELOCITY_API"

mkdir -p "$OUT"
FAILED=()

# build <module-dir> <src-root> <resource-dir-or-> <jar-name> [extra-classpath]
build() {
  local mod="$1" src="$2" res="$3" name="$4" extra="${5:-}"
  local dir="$TOOLS/$mod"
  [ -d "$dir" ] || { echo "  [skip] $mod (not in repo)"; return 0; }
  local bin="$dir/bin"
  rm -rf "$bin"; mkdir -p "$bin"

  mapfile -t SRCS < <(find "$dir/$src" -name '*.java' 2>/dev/null)
  if [ ${#SRCS[@]} -eq 0 ]; then echo "  [skip] $mod (no sources)"; return 0; fi

  if ! "$JAVAC" -proc:none -nowarn -cp "$BASE_CP${extra:+:$extra}" -d "$bin" "${SRCS[@]}" 2>"$OUT/$mod.log"; then
    echo "  [FAIL] $mod — see $OUT/$mod.log"
    grep -m5 'error:' "$OUT/$mod.log" | sed 's/^/         /'
    FAILED+=("$mod"); return 0
  fi

  # Resources: plugin.yml may live in the resource dir or beside the sources.
  local args=(-C "$bin" .)
  # Shade the embedded PizzaCommon library into consumers so it resolves at runtime.
  if [ -n "$extra" ] && [ "$extra" = "$COMMON_BIN" ] && [ -d "$COMMON_BIN" ]; then
    args+=(-C "$COMMON_BIN" .)
  fi
  if [ "$res" != "-" ] && [ -d "$dir/$res" ]; then args+=(-C "$dir/$res" .); fi
  if [ -f "$dir/$src/plugin.yml" ]; then args+=(-C "$dir/$src" plugin.yml); fi
  # Some modules (pizzachatguard) keep plugin.yml at the MODULE ROOT rather than in
  # src/ or resources/. Without this the jar builds fine but Paper refuses to load it:
  # "does not contain a paper-plugin.yml or plugin.yml".
  if [ -f "$dir/plugin.yml" ]; then args+=(-C "$dir" plugin.yml); fi

  "$JAR" --create --file "$OUT/$name" "${args[@]}"
  echo "  [ok]   $name  ($(du -h "$OUT/$name" | cut -f1), $(unzip -l "$OUT/$name" | grep -c '\.class$') classes)"
}

echo "Building from $TOOLS"
echo "  paper-api: $(basename "$PAPER_API")"

# PizzaCommon is an embedded LIBRARY, not a plugin — it has no plugin.yml and is shaded
# into the plugins that use it (currently only PizzaChatGuard, which imports
# dev.pizzasmp.common.SuiteStorage). Build it first so it can go on their classpath.
COMMON_BIN="$TOOLS/pizzacommon/bin"
if [ -d "$TOOLS/pizzacommon/src" ]; then
  rm -rf "$COMMON_BIN"; mkdir -p "$COMMON_BIN"
  mapfile -t COMMON_SRCS < <(find "$TOOLS/pizzacommon/src" -name '*.java')
  if [ ${#COMMON_SRCS[@]} -gt 0 ] && "$JAVAC" -proc:none -nowarn -cp "$BASE_CP" -d "$COMMON_BIN" "${COMMON_SRCS[@]}" 2>"$OUT/pizzacommon.log"; then
    echo "  [lib]  pizzacommon ($(find "$COMMON_BIN" -name '*.class' | wc -l) classes)"
  else
    echo "  [FAIL] pizzacommon — see $OUT/pizzacommon.log"; FAILED+=(pizzacommon)
  fi
fi

MODULES=("$@")
[ ${#MODULES[@]} -eq 0 ] && MODULES=(pizzanetworkcore pizzaadmintools pizzachatguard pizzapunishment pizzaruleguard pizzaspawnrules pizzaenderchest pizzautils pizzatune pizzaproxyguard)

for m in "${MODULES[@]}"; do
  case "$m" in
    pizzanetworkcore) build pizzanetworkcore src resources PizzaNetworkCore.jar ;;
    pizzaadmintools)  build pizzaadmintools  src -         PizzaAdminTools.jar ;;
    pizzachatguard)   build pizzachatguard   src resources PizzaChatGuard.jar "$COMMON_BIN" ;;
    pizzapunishment)  build pizzapunishment  src resources PizzaPunishment.jar ;;
    pizzaruleguard)   build pizzaruleguard   src resources PizzaRuleGuard.jar ;;
    # Adopted from the old plugins-src tree 2026-08-08: owns the lobby double jump and
    # the spawn-zone rules. It had no counterpart in the repo, so switching the build to
    # tools/-only silently dropped it and double jump stopped working.
    pizzaspawnrules) build pizzaspawnrules  src resources PizzaSpawnRules.jar ;;
    pizzaenderchest)  build pizzaenderchest  src resources PizzaEnderchest.jar ;;
    pizzautils)       build pizzautils       src resources PizzaUtils.jar ;;
    pizzatune)        build pizzatune        src -         PizzaTune.jar ;;
    # Velocity plugin: compiles against the Velocity API, not Paper.
    pizzaproxyguard)
      build pizzaproxyguard  src resources PizzaProxyGuard.jar "$VELOCITY_CP"
      # Velocity has NO runtime library loader (no plugin.yml `libraries:` equivalent), so
      # the MariaDB driver the chat relay needs must be shaded into the jar. Without this
      # the plugin loads and then throws "MariaDB driver missing" on ProxyInitializeEvent.
      if [ -f "$OUT/PizzaProxyGuard.jar" ] && [ -n "$MARIADB" ]; then
        SHADE="$OUT/.shade-proxyguard"; rm -rf "$SHADE"; mkdir -p "$SHADE"
        ( cd "$SHADE" && "$JAR" --extract --file "$MARIADB" && rm -rf META-INF/*.SF META-INF/*.RSA META-INF/*.DSA )
        "$JAR" --update --file "$OUT/PizzaProxyGuard.jar" -C "$SHADE" . 2>/dev/null
        rm -rf "$SHADE"
        echo "         + shaded MariaDB driver ($(unzip -l "$OUT/PizzaProxyGuard.jar" | grep -c 'org/mariadb/jdbc') entries)"
      fi
      ;;
    *) echo "  [skip] unknown module '$m'" ;;
  esac
done

echo
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "FAILED: ${FAILED[*]}"; exit 1
fi
echo "All modules built into $OUT"
