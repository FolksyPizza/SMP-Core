<p align="center">
  <a href="https://github.com/FolksyPizza/SMP-Core/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/FolksyPizza/SMP-Core"></a>
  <a href="https://github.com/FolksyPizza/SMP-Core/actions/workflows/build.yml"><img alt="Build" src="https://github.com/FolksyPizza/SMP-Core/actions/workflows/build.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-blue"></a>
  <img alt="Paper 1.21.11" src="https://img.shields.io/badge/Paper-1.21.11-informational">
</p>

# SMP-Core

A plugin suite for survival servers on Paper: a player-driven economy, quality-of-life commands, duels, and staff
tooling, for a single server or a Velocity network. Apache 2.0 licensed.

Current version: **1.0.0**, shared by every plugin. See [CHANGELOG.md](CHANGELOG.md).

- 10 plugins and a shared library, about 51,000 lines of Java, plus two datapacks
- More than 100 commands ([COMMANDS.md](COMMANDS.md))
- Runs on Paper 1.21.7 to 1.21.11; most tested on 1.21.11, where it has run in production ([Supported versions](#supported-versions))

## Plugins

| Plugin | Purpose |
| --- | --- |
| PizzaNetworkCore | Core gameplay: auction house, buy orders, shop, homes, random teleport, teleport requests, friends and follows, `/ignore` and `/block`, duels and the duel queue, spawn and AFK hubs, chat item icons, per-player settings, leaderboards and the scoreboard |
| PizzaAdminTools | Staff tooling: admin menus, home administration, staff mode with an audit log, `/atrack`, maintenance freeze, transfers, `/stash`, `/nuke`, subscription tiers |
| PizzaPunishment | Bans, mutes (chat, private messages and Simple Voice Chat), strikes and death-drop penalties |
| PizzaChatGuard | Chat rate limits, duplicate detection and word lists |
| PizzaRuleGuard | Rule enforcement and abuse detection |
| EnderchestExpander | 54-slot ender chests with persistent storage and staff inspection (`/endersee`) |
| PizzaTune | Live view-distance and chunk-rate tuning |
| PizzaSpawnRules | Hub protections and double jump around 0,0. Active only on server port 25566 (hub) or 25569 (maintenance mirror); idle elsewhere |
| PizzaLimbo | Limbo backend that holds players during maintenance (proxy networks) |
| PizzaProxyGuard | Velocity plugin: holds new joins while the backend is down and turns moderation kicks into clean disconnects |
| PizzaCommon | Shared library compiled into the plugins that use it: storage, menu locking, the startup banner and a facade over Paper's schedulers |

## Requirements

| | Minimum | Recommended |
| --- | --- | --- |
| Server | Paper 1.21.7, Java 21 | Paper 1.21.11 |
| Database | None: a single server stores its data in local files | MySQL or MariaDB; required for a Velocity network |
| Dependencies | ProtocolLib, Vault | LuckPerms, PlaceholderAPI (full list: `setup/deps.txt`) |
| Hardware | 2 cores, 4 GB RAM | 4+ cores, 8 to 12 GB RAM, SSD |

## Supported versions

**Tested**: passes this release's automated tests. **Beta**: works in the tests that ran, with less coverage.
*Unverified*: expected to work, never checked.

| Platform | Status |
| --- | --- |
| Paper 1.21.11 | **Tested**: every bot test, single server and network; used in production |
| Paper 1.21.7 to 1.21.10 | **Beta**: boots clean; the release, menu lock and command routing bot tests pass |
| Paper 1.21.6 and older | Not supported: no dialog API. The plugins refuse to load |
| Paper 26.x, Folia | Not supported yet ([UPCOMING.md](UPCOMING.md)) |
| Java | 21 or newer |
| Velocity | 3.5.1 **Tested** (network bot tests); other 3.x releases *Unverified* |
| Java Edition clients | The server's version natively; other versions through ViaVersion, used in production |
| Bedrock Edition clients | Through Geyser and Floodgate, with chest menus in place of dialogs; used in production, not in the bot tests |
| Network features | **Beta**: cross-server `/home` is not supported yet; a home on another backend is refused by name |

One set of jars covers every supported Paper version.

## Installation

### Quick start (macOS or Linux)

From a clone of this repository:

```sh
./setup.sh            # asks a few questions; creates ./server
./server/start.sh
```

`setup.sh` checks for Java 21 (offering Homebrew's on macOS), downloads Paper, builds the plugins (or downloads the
latest release), installs ProtocolLib, Vault and optionally LuckPerms with checksums where published, adds the menu
datapack, and writes the EULA, `server.properties`, a brand profile and `start.sh`. Re-run it to update the plugins
or change the brand; worlds, configs and player data are kept. `./setup.sh --help` lists the options.

### Single server

1. Download the jars from the Releases page into `plugins/`, with ProtocolLib and Vault.
2. Start the server. With no database configured, every plugin stores its data in local files (PizzaNetworkCore uses
   an embedded database file, `plugins/PizzaNetworkCore/data/smpcore.mv.db`).
3. Optional: to use MySQL or MariaDB, set the connection under `sync.database` in
   `plugins/PizzaNetworkCore/config.yml` and restart. The other plugins share that connection, and tables are
   created on first start.


### Velocity network

Lobby, survival, maintenance and optional dev backends run behind one proxy and share player state (inventory,
balance, rank, settings) through a shared MySQL or MariaDB database. [`network/`](network) has the topology, configs and scripts.
Network support is in beta.

## Configuration

| What | Where |
| --- | --- |
| Branding: name, tagline, region, Discord link, tier names and color palette | `plugins/PizzaNetworkCore/branding.yml` ([Branding](#branding)) |
| Economy, gameplay and database | `plugins/PizzaNetworkCore/config.yml`, `shop.yml` |
| Chat filtering | `plugins/PizzaChatGuard/` |
| Punishments and rules | `plugins/PizzaPunishment/`, `plugins/PizzaRuleGuard/` |
| Storage (Database or File) | `storage.backend` in each plugin's config: `auto` (default) uses the database when reachable, sharing PizzaNetworkCore's connection, and files otherwise |

### Branding

One file themes the whole suite. PizzaNetworkCore writes a documented `branding.yml` on first start (`setup.sh`
fills it in with your server's name and color); PizzaAdminTools, PizzaPunishment and PizzaLimbo read the same file.

| Key | Used for |
| --- | --- |
| `display`, `short` | The server name in messages, menus, ban screens and the limbo |
| `tagline`, `region`, `discord` | Message tokens; the limbo shows the region, ban screens the Discord link |
| `tiers.plus`, `tiers.plusplus` | Subscription tier names; by default the name plus "+" and "++" (HappyLand+, HappyLand++) |
| `colors.primary` | The accent color in messages, menus, dialogs and the scoreboard |
| `colors.info`, `success`, `warning`, `danger`, `muted`, `text` | Color roles for message tokens |

Messages and config text can use the tokens `{brand}`, `{brand_short}`, `{tagline}`, `{region}`, `{discord}`,
`{plus}`, `{plusplus}` and the color tokens `{primary}`, `{info}`, `{success}`, `{warning}`, `{danger}`, `{muted}`
and `{text}`. A file can hold several profiles; `active` picks one, and `/branding set <profile>` switches live.
The server icon, MOTD and tab list are not themed by the suite.

### Datapacks

| Datapack | Install into | Adds |
| --- | --- | --- |
| `datapacks/pizzasmp-menu` | The main world's `datapacks/` | The `/menu` pause-screen dialogs |
| `datapacks/pizzalimbo-menu` | The limbo world's `datapacks/` | The maintenance dialog |

## Building

Prebuilt jars are on the Releases page. To build from source you need Java 21 or newer; Gradle comes with the
wrapper and fetches every dependency from public repositories:

```sh
./gradlew build
```

The jars land in `build/dist/`. `tools/ci/boot-check.sh` starts a throwaway Paper server with them and fails on any
plugin error; CI runs both on every push and pull request.

## Status

| Area | Status |
| --- | --- |
| Single-server gameplay: economy, homes, teleports, settings, moderation, menus | Stable |
| Velocity network: shared inventories, balances, permissions, chat, cross-server RTP | Beta |
| Duels and the duel queue | Preview: playable, still changing |
| Folia | Not supported yet ([UPCOMING.md](UPCOMING.md)) |

Parts of PizzaNetworkCore and PizzaAdminTools were recovered from compiled builds. A few areas still carry
decompiler structure and are cleaned up as they are touched.

## Documentation

| Document | Contents |
| --- | --- |
| [COMMANDS.md](COMMANDS.md) | Every command and permission |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Plugin lifecycles, routing, persistence, gameplay flows, Velocity integration |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [UPCOMING.md](UPCOMING.md) | Planned work |
| [network/](network) | Network topology and operations |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
| [SECURITY.md](SECURITY.md) | Reporting vulnerabilities |

## License

The current SMP-Core source code, documentation and build scripts use the Apache License, Version 2.0:
see [LICENSE](LICENSE) and [NOTICE](NOTICE). Releases before 1.0.0 retain the MIT License.
Third-party components retain their own licenses. PizzaProxyGuard bundles MariaDB Connector/J under the LGPL-2.1;
its license ships in the jar. Maintained by FolksyPizza.

CI checks the source licensing and each built jar's license metadata with
`python3 tools/ci/check-licenses.py --jars build/dist`.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
