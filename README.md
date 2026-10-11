# SMP-Core

A plugin suite for survival servers on Paper: a player-driven economy, quality-of-life commands, duels, and staff
tooling, for a single server or a Velocity network. Apache 2.0 licensed.

Current version: **1.0.0**, shared by every plugin. See [CHANGELOG.md](CHANGELOG.md).

- 10 plugins and a shared library, about 49,000 lines of Java, plus two datapacks
- More than 100 commands ([COMMANDS.md](COMMANDS.md))
- Built and tested on Paper 1.21.11, where it has run in production

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
| PizzaSpawnRules | Hub protections and double jump. Hub servers only: its zone grants flight |
| PizzaLimbo | Limbo backend that holds players during maintenance (proxy networks) |
| PizzaProxyGuard | Velocity plugin: holds new joins while the backend is down and turns moderation kicks into clean disconnects |
| PizzaCommon | Shared library compiled into the plugins that use it: storage and a scheduler facade |

## Requirements

| | Minimum | Recommended |
| --- | --- | --- |
| Server | Paper 1.21.6 or newer, Java 21 | Paper 1.21.11 |
| Database | None: a single server stores its data in local files | MySQL or MariaDB; required for a Velocity network |
| Dependencies | ProtocolLib, Vault | LuckPerms, PlaceholderAPI (full list: `setup/deps.txt`) |
| Hardware | 2 cores, 4 GB RAM | 4+ cores, 8 to 12 GB RAM, SSD |

## Installation

### Single server

1. Download the jars from the Releases page into `plugins/`, with ProtocolLib and Vault.
2. Start the server. With no database configured, every plugin stores its data in local files (PizzaNetworkCore uses
   an embedded database file, `plugins/PizzaNetworkCore/data/smpcore.mv.db`).
3. Optional: to use MySQL or MariaDB, set the connection under `sync.database` in
   `plugins/PizzaNetworkCore/config.yml` and restart. The other plugins share that connection, and tables are
   created on first start.

`setup/setup-server.sh` is an interactive alternative: it downloads Paper and the dependencies and writes a brand
profile. It never touches worlds or player data and can be re-run to rebrand.

### Velocity network

Lobby, survival, maintenance and optional dev backends run behind one proxy and share player state (inventory,
balance, rank, settings) through a shared MySQL or MariaDB database. [`network/`](network) has the topology, configs and scripts.
Network support is in beta.

## Configuration

| What | Where |
| --- | --- |
| Branding: name, colors, MOTD, tab list, menus, rank and tier labels | `plugins/PizzaNetworkCore/branding.yml`; switch live with `/branding set <profile>` |
| Economy, gameplay and database | `plugins/PizzaNetworkCore/config.yml`, `shop.yml` |
| Chat filtering | `plugins/PizzaChatGuard/` |
| Punishments and rules | `plugins/PizzaPunishment/`, `plugins/PizzaRuleGuard/` |
| Storage (Database or File) | `storage.backend` in each plugin's config: `auto` (default) uses the database when reachable, sharing PizzaNetworkCore's connection, and files otherwise |

Without `branding.yml` the suite uses neutral defaults. Subscription tiers follow the brand name (a brand called
HappyLand gets HappyLand+ and HappyLand++).

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

Apache 2.0. See [LICENSE](LICENSE). Maintained by FolksyPizza.
