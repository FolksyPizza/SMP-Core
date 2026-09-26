# Changelog

All notable changes to SMP-Core are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow
[Semantic Versioning](https://semver.org/) from 1.0.0 on.

## [Unreleased]

## [1.0.0] - 2026-09-26

First stable release. Supports Paper 1.21.6 and newer on Java 21; tested on Paper 1.21.11.

### Added
- Duels: `/duel` challenges with wagers, rounds and loadout options, a private generated arena world
  with arena rollback between rounds, a return-to-start transition, death spectating, and reconnect
  handling. The RTP duel queue (`/rtpq`) matches players by gear.
- `/spectate` for watching players and duels.
- Protected spawn and AFK hub worlds (`/spawn`, `/warp`, `/afk`) with void rescue, a spawn money
  leaderboard, and staff hub-edit bypass.
- Chat item and player icons (`:diamond_sword:`, `:PlayerName:`), rendered as sprites on 1.21.9+ clients and as
  readable text elsewhere.
- RTP arrival animation with destination chunk preloading.
- Optional performance profiles (`optimizations.profile`); every optimization can be turned off.
- Optional Velocity proxy-network deployment alongside the single-server setup, with PizzaProxyGuard.
- `/ignore` and `/block`, cross-server `/tpa` arrival, and cross-server RTP.
- EnderchestExpander `/endersee` with exclusive, ordered inspection.
- PizzaCommon scheduler facade over Paper's entity/region/global/async schedulers.
- Compatibility adapters so the suite runs on Paper 1.21.6 and newer.
- Roadmap document (`UPCOMING.md`).
- PizzaAdminTools: `/nuke`, persistent `/atrack` sessions, frozen-maintenance recovery after a restart,
  a staff-mode audit log, and forgiving `/home` name matching (unique prefix or up to two typos).
- PizzaProxyGuard: a `maintenance.flag` operator lockout with a per-name bypass list.
- Pause-screen menu and limbo maintenance datapacks.

### Changed
- RTP cooldown is tiered and configurable (`rtp.cooldown-seconds` defaults to 15; subscriber tiers
  and staff are shorter or exempt).
- Passive shards accrue per minute online with a periodic playtime bonus.
- Bandwidth-aware view distance is off by default (`network.egress-cap-mbps: 0`).
- `/spawn` routing to a lobby backend is opt-in (`network.route-spawn-to-lobby`) and enabled in the
  network example config.
- PizzaUtils is reduced to `/ping`; its other commands live in PizzaNetworkCore and PizzaTune.
- Punishment offences and records, and subscription state, can be shared across backends through
  MySQL-backed storage.
- Branding is not shipped in this repository; the setup writes a brand profile on install. Every
  player-facing server name, Discord link and menu title now follows the active brand profile.

### Fixed
- Team homes could not be set because of a stub left by source recovery; the real implementation is
  restored.
- Maintenance-hold reliability: queue-state reconstruction, corrected hold messaging, and a looping
  hold soundtrack.
- Stale death screens are cleared on arrival, with the synced gamemode preserved.
- Spectators no longer lose flight and sink through the world.
- `/servermaint` is registered.
- PizzaSpawnRules compiles and runs on Paper versions where riptide cannot be cancelled.
- Order, settings, shop and advancement synchronization fixes.

### Security
- No database credentials are built in. PizzaNetworkCore and PizzaLimbo fail closed until a user and
  password are configured.

## [0.1.0] - 2026-07-24

### Added
- Initial open-source release of the SMP-Core plugin suite under the MIT License: the core network
  plugin, admin tooling, chat guard, moderation and rule enforcement, the maintenance limbo backend,
  and the shared common library.
