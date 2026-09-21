# Changelog

All notable changes to SMP-Core are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project does not yet publish
tagged releases, so changes since the initial open-source release are collected under
**Unreleased** until versioning begins.

## [Unreleased]

### Added
- Optional Velocity proxy-network deployment alongside the single-server setup.
- PizzaProxyGuard, plus an RTP duel queue and vanilla-style ender pearls.
- `/ignore` and `/block`.
- Cross-backend detection when a third-party plugin goes missing from a backend.
- Roadmap document (`UPCOMING.md`), including a planned Canopy integration.

### Changed
- Sidebar HUD redesign and follow-up refinements: kills sourced from the database, shards on a
  global periodic grant, and tidier money and name formatting.
- Subscription, strike, and offence state shared through the database across backends.
- Player flight now follows the active gamemode.
- Branding is no longer shipped in this repository; the interactive setup writes a brand profile on
  install and the plugin themes itself from it on start.
- README introduction refined.

### Fixed
- Maintenance-hold reliability: queue-state reconstruction, corrected hold messaging, and a looping
  hold soundtrack.
- Stale death screens cleared on arrival, with the synced gamemode preserved.
- Order and settings handling, a shop fix, and advancement synchronization.
- Findings from code review addressed.

### Security
- Configuration ships with placeholder credentials rather than real values.

## [0.1.0] - 2026-07-24

### Added
- Initial open-source release of the SMP-Core plugin suite under the MIT License: the core network
  plugin, admin tooling, chat guard, moderation and rule enforcement, the maintenance limbo backend,
  and the shared common library.
