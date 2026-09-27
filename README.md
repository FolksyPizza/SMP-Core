# SMP-Core

A plugin suite for survival servers on Paper: a player-driven economy, quality-of-life commands, duels, and staff
tooling, for a single server or a Velocity network. MIT licensed.

Current version: **1.0.0-beta**, shared by every plugin. See [CHANGELOG.md](CHANGELOG.md).

- 10 plugins and a shared library, about 49,000 lines of Java, plus two datapacks
- More than 100 commands ([COMMANDS.md](COMMANDS.md))
- Runs in production on Paper 1.21.11

## Plugins

| Plugin | Purpose |
| --- | --- |
| PizzaNetworkCore | Core gameplay: auction house, buy orders, shop, homes, random teleport, teleport requests, friends and follows, `/ignore` and `/block`, duels and the duel queue, spawn and AFK hubs, chat item icons, per-player settings, leaderboards and the scoreboard |
| PizzaAdminTools | Staff tooling: admin menus, home administration, staff mode with an audit log, `/atrack`, maintenance freeze, transfers, `/stash`, `/nuke`, subscription tiers |
| PizzaPunishment | Bans, mutes, strikes and death-drop penalties |
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
| Database | MySQL or MariaDB (local or remote), for PizzaNetworkCore | Same host or low-latency network |
| Dependencies | ProtocolLib | Vault, LuckPerms, PlaceholderAPI (full list: `setup/deps.txt`) |
| Hardware | 2 cores, 4 GB RAM | 4+ cores, 8 to 12 GB RAM, SSD |

## Installation

### Single server

1. Download the jars from the Releases page into `plugins/`, with ProtocolLib.
2. Create an empty MySQL or MariaDB database and a user for it.
3. Start Paper once to generate the configs, then stop it.
4. Set the connection under `sync.database` in `plugins/PizzaNetworkCore/config.yml`.
5. Start the server. PizzaNetworkCore creates its tables on first start.

`setup/setup-server.sh` is an interactive alternative: it downloads Paper and the dependencies and writes a brand
profile. It never touches worlds or player data and can be re-run to rebrand.

### Velocity network

Lobby, survival, maintenance and optional dev backends run behind one proxy and share player state (inventory,
balance, rank, settings) through the database. [`network/`](network) has the topology, configs and scripts.
Network support is in beta.

## Configuration

| What | Where |
| --- | --- |
| Branding: name, colors, MOTD, tab list, menus, rank and tier labels | `plugins/PizzaNetworkCore/branding.yml`; switch live with `/branding set <profile>` |
| Economy, gameplay and database | `plugins/PizzaNetworkCore/config.yml`, `shop.yml` |
| Chat filtering | `plugins/PizzaChatGuard/` |
| Punishments and rules | `plugins/PizzaPunishment/`, `plugins/PizzaRuleGuard/` |

Without `branding.yml` the suite uses neutral defaults. Subscription tiers follow the brand name (a brand called
HappyLand gets HappyLand+ and HappyLand++).

### Datapacks

| Datapack | Install into | Adds |
| --- | --- | --- |
| `datapacks/pizzasmp-menu` | The main world's `datapacks/` | The `/menu` pause-screen dialogs |
| `datapacks/pizzalimbo-menu` | The limbo world's `datapacks/` | The maintenance dialog |

## Building

Java 21 and the Paper API are required. PizzaNetworkCore builds with Maven (`PizzaNetworkCore/pom.xml`). The
other plugins compile with `javac` against the Paper API and their dependencies. Prebuilt jars are on the
Releases page.

## Status

| Area | Status |
| --- | --- |
| Single-server gameplay: economy, homes, teleports, settings, moderation, menus | Stable, in production |
| Velocity network: shared inventories, balances, permissions, chat, cross-server RTP | Beta, in production |
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

MIT. See [LICENSE](LICENSE). Maintained by FolksyPizza.
