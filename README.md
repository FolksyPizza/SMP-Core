# SMP-Core

SMP-Core is a plugin suite for a survival Minecraft server on Paper. If you run a Paper survival server and want a player-driven economy, a tidy set of quality-of-life commands, and staff tooling that stays out of players' way, most of that work is already done here.

It is fully open source under the MIT license. You can use it, change it, and ship it in commercial projects. The only ask is that you keep the license file.

A full list of commands is in [COMMANDS.md](COMMANDS.md). What I want to build next is in [UPCOMING.md](UPCOMING.md).

## Architecture

[ARCHITECTURE.md](ARCHITECTURE.md) explains plugin lifecycles, command and event routing, persistence, gameplay flows, and Velocity integration. It includes source references, data-flow diagrams, and implementation constraints for developers joining the project.

## About

- Around 47,000 lines of Java across 11 plugins and a shared library
- More than 100 commands
- Built and run on Paper 1.21.x (1.21.6 or newer), currently 1.21.11
- Made by one person
  
One honest note. I lost the source for part of PizzaNetworkCore at some point and had to recover it by decompiling an old build, then rewrite it back into normal code. A few spots still look like they came out of a decompiler, mostly some awkward labeled loops, and I clean those up as I touch them. It all builds and runs fine, it is just not pretty yet in those corners.

## Plugins

| Plugin | Version | What it does |
| --- | --- | --- |
| PizzaNetworkCore | 1.0.0 | The core plugin. Economy (auction house, player buy orders, shop), homes, random teleport with an arrival animation, teleport requests, a follow/friends social system, `/ignore` and `/block`, duels (`/duel`, the RTP duel queue, spectating), protected spawn and AFK hub worlds, chat item and player icons, per-player settings, leaderboards, and the scoreboard HUD. |
| PizzaAdminTools | 1.1.0 | Staff tooling. Admin console GUIs, home administration, opt-in staff mode, moderation helpers, and subscription tier management. |
| PizzaChatGuard | 2.0.0 | Chat protection. Rate limiting, duplicate and near-duplicate detection, and configurable word lists. |
| PizzaPunishment | 1.0.0 | Punishment system with bans, mutes, strike tracking, and death-drop mechanics. |
| PizzaRuleGuard | 1.0.0 | Rule enforcement and anti-abuse guard. |
| PizzaLimbo | 0.2.0 | A lightweight limbo backend that holds players during maintenance when you run behind a Velocity proxy. |
| PizzaProxyGuard | 1.0.0 | A small Velocity proxy plugin: denies new joins while the backend is down (with a maintenance message) and turns moderation kicks/bans into a clean disconnect instead of dropping the player into the limbo fallback. |
| PizzaCommon | 1.0.0 | Shared library, compiled into the plugins that use it: storage (YAML or MySQL) and a scheduler facade over Paper's region-aware schedulers. |
| PizzaSpawnRules | 1.0.0 | Lobby spawn protections and a silent unlimited double jump. Hub servers only — its protected zone grants creative flight, and with `lockAllWorld` the zone is the whole world, so on a survival server it hands everyone flight. |
| EnderchestExpander | 1.0.0 | Expands the ender chest from 27 to 54 slots, with persistent storage and exclusive staff inspection (`/endersee`). |
| PizzaUtils | 1.0.0 | `/ping`. Night vision lives in PizzaNetworkCore and view distance in PizzaTune. |
| PizzaTune | 1.0.0 | Live-tunes view distance and chunk send/load rates without a restart. |

## Status

This repository is a snapshot of the plugins as they actually run on the live server, so it is worth being clear about what is solid and what is not.

The single-server survival features are stable and well tested in production: the economy, homes, teleports, settings, moderation, and GUIs.

One thing to know up front: PizzaNetworkCore stores its economy and player data in a MySQL or MariaDB database, not in flat files. You need a database for it to run. That can be a local MySQL on the same box or a remote one. The other plugins (chat, punishment, rules) use plain YAML.

The cross-server functionality is Beta, and it is now the setup I actually run: a Velocity proxy in front of lobby, survival and maintenance backends sharing one database. Shared inventories, balances, permissions, chat and cross-server RTP work. It is still younger than the single-server code and has had less exposure to a crowd, so treat it as Beta rather than settled.

It stays dormant unless you set `sync.enabled: true` in the core config, and a single server never touches that path. If you do want it, [`network/`](network) has the full topology, configuration and ops tooling.

## Requirements

Paper 1.21.6 or newer; tested on Paper 1.21.11. Java 21 is required. PizzaNetworkCore also needs a MySQL or MariaDB database (local or remote); point it at one in the core config.

Minimum: 2 CPU cores, 4 GB RAM (allocate roughly 3 GB to the server), and any modern disk.

Recommended: 4 or more CPU cores, 8 to 12 GB RAM, and SSD storage. That is close to what the live server uses and leaves headroom for a full world and a busy economy.

The core plugin does more per tick than a stock server, so give it real CPU and an SSD if you expect a crowd. Most of the RAM goes to the world and players rather than the plugins.

## Install

### Single server, or a proxy network

Both are supported, and the network path is purely additive — nothing about the
single-server setup changed.

**Single Paper server** is the simpler choice and what most people want. Everything below
describes it, and it needs nothing from the `network/` directory.

**Velocity proxy network** puts a lobby, survival, maintenance and optional dev backend
behind one proxy, sharing player state through the database, so a player keeps their
inventory, balance, rank and settings as they move between servers. That is the layout the
plugins were designed around and the one I run. See [`network/`](network) for the topology,
configs and ops scripts.

### Getting the plugins in place

There are two ways to run it.

The simplest path is the standalone jars. Download them from the Releases page, drop them into your server's `plugins` folder, and start Paper once to generate the config files. Every plugin is configured from plain YAML, so you can rebrand and tune everything without any extra tooling. See Customizing below.

Guided setup is `setup/setup-server.sh`, an interactive installer that downloads Paper, fetches the third-party plugins these depend on, and writes a full brand profile for you (name, colors, MOTD, Discord, ranks). Re-run it any time to rebrand. It does not touch worlds or player data.

## Customizing without the setup script

You do not need the setup script to rebrand. Everything that players see is data-driven, so you can make the whole server your own from YAML after the first start.

Branding lives in `plugins/PizzaNetworkCore/branding.yml`. It ships with an example profile. Copy it and change the display name, colors, region, tagline, Discord link, and the subscription tier labels, then set `active:` to your profile and restart. The whole server re-themes at boot: the server name, rank and tier labels, chat prefixes, the scoreboard, the tab list, menus and GUIs, dialog text, the server-list MOTD, join and leave messages, and the server icon. You can also switch profiles live with `/branding set <key>`. The two subscription tiers automatically follow your brand name, so a brand called HappyLand gives HappyLand+ and HappyLand++.

The setup script does the same thing interactively and also configures the LuckPerms rank colors and staff hierarchy for you. Either path gets you a fully rebranded server; the config route just means editing `branding.yml` by hand.

Economy, shop, and gameplay tuning live in `plugins/PizzaNetworkCore/config.yml` (and `shop.yml` for the shop). Set the database connection there under `sync.database`. Leave `sync.enabled` false for a single server; turn it on only to run the Beta cross-server network.

Chat filtering is configured in `plugins/PizzaChatGuard/` (config plus the word list files). Punishment and rule settings live in their own plugin folders the same way.

## Building from source

You need Java 21 and the Paper API. PizzaNetworkCore has a Maven `pom.xml`. The others compile with `javac` against the Paper API and their few third-party APIs on the classpath. If you would rather not build, the Releases page has prebuilt jars for every plugin at the versions listed above.

## Third-party plugins

The suite integrates with, and in some cases needs, common third-party plugins such as ProtocolLib, Vault, LuckPerms, PlaceholderAPI, and EssentialsX. The full list the setup script installs is in `setup/deps.txt`.

## License

MIT. Use it for anything, including commercial work, and keep the license file.

Author: William W. (FolksyPizza).
