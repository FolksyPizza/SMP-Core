# Changelog

All notable changes to SMP-Core are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-10-11

Changes since 1.0.0-beta.

### Added
- Rank priority can be set with `pizzasmp.priority.1` to `pizzasmp.priority.9` (1 is highest) on any server; it
  decides full-server joins, staff checks and per-player view distance. The original group names still work.
- `pizzasmp.chatguard.owner` marks a player as the owner for ChatGuard, independent of group names.
- Every permission the suite checks is declared in its `plugin.yml`, so permission plugins list it with its
  intended default.
- A startup banner: the first SMP-Core plugin to enable prints the suite version, license and installed modules,
  once per server start.
- A documented `branding.yml` (name, tagline, region, Discord link, tier names, color palette and message tokens)
  is written on first start; before, a fresh install had no branding file to edit. `/branding set` runs its
  optional operator script only when the script exists.
- `setup.sh`: one command creates a runnable server on macOS or Linux (Java 21 check, Paper, the plugins built or
  downloaded, checksum-verified ProtocolLib and Vault, optional LuckPerms, the menu datapack, a brand profile and
  `start.sh`). It replaces `setup/setup-server.sh`, which did not work from the published layout.
- A Gradle build that works from a clean clone: `./gradlew build` compiles every plugin with dependencies from public
  repositories and collects the jars in `build/dist/`.
- Continuous integration: every push and pull request builds the suite and boots it on Paper, failing on any plugin
  error (`tools/ci/boot-check.sh`).
- `/sus` opens the inventory review panel by default; its Search button opens a player-name dialog on supported
  Java clients, while `/sus <player>` provides direct lookup on Java and Bedrock. Suspect summaries group retained
  flags by check name, so staff can see reasons such as FastBreak rather than a generic anticheat label.
- PizzaChatGuard chat log: public chat and private messages with player, UUID and UTC time, one file per day,
  blocked messages marked (`chat-log.enabled`, `chat-log.retention-days`, default 90).
- Storage has two backends, Database and File. `storage.backend: auto` (the default) uses the database when one is
  configured and reachable, sharing PizzaNetworkCore's connection when a plugin has no database block of its own,
  and files otherwise. Each plugin imports its files into the database once, never overwriting existing rows.
- PizzaNetworkCore runs without a database server: with no database configured it stores everything in an embedded
  database file. A configured database is always used, and never swapped for the file during an outage.
- EnderchestExpander and PizzaRuleGuard support Database and File storage. In Database mode expanded ender chests
  follow players between servers; the server holding a chest keeps a short hold until its final save, so a fast
  server switch never loads a stale copy. RuleGuard's offense log goes to the shared event table.
- Homes use that storage: the database when connected (shared by every backend), the SetHome files otherwise.
  Existing SetHome files are imported on the first database start, and delete backups follow the same storage.
- Home names can contain spaces (`/sethome my base`, `/home my base`).
- Staff home tools: `/homes <player>` opens any player's homes, online or offline (left-click teleports,
  right-click deletes with a confirmation and a backup); `/admindelhome` and `/gtp` work on the same data.
- Staff auction tools (`/ah admin <player>`): view, return or remove a player's listings. Returned items go
  to an item mailbox, delivered on join or immediately when the player is online.
- The database schema is applied on every start, so a fresh database needs no manual import.
- Mutes apply to Simple Voice Chat when it is installed: a muted player's microphone is silenced in proximity
  and group chat, with a "You are muted" notice.
- Proxy admission control: a login or a switch onto the gameplay backend waits (it looks like a slow login) while
  the backend is down or booting, the database is unreachable, the player's data is still held elsewhere, or three
  players are already joining; waiting players are admitted in arrival order. Too long a wait ends on a screen
  naming the reason.
- Canopy Session Gateway support: `network/scripts/build-network-dialogs.sh` merges the menu and limbo datapacks so every
  backend has identical registries, and `network/scripts/check-backend-parity.sh` checks backends for the
  differences a configuration-free switch cannot tolerate.
- One disconnect screen layout for the proxy's own messages, with a brand line and a UTC reference, and restyled
  Velocity error messages (`network/configs/proxy/lang/messages-overrides.properties`).
- Full-stop maintenance (`/limbomaint start`) checks first and refuses, changing nothing, unless the relaunch
  script exists, the database answers and, with players online, the limbo has checked in within 20 seconds
  (a heartbeat PizzaLimbo now writes). A second start while one is running is refused, and players still on the
  server halfway through the transfer are asked to move again.
- Login failsafes for synced player state. Logins are refused while the database is unreachable
  (`sync.failsafe.deny-login-when-database-down`). A player whose state cannot be loaded yet, or whose previous
  server has not finished saving them, is held (no item changes, damage or commands) and retried until it loads;
  nothing is saved for a held player, so a stale local copy never replaces the stored one.
- PizzaChatGuard filter levels (`level`: off, light, normal, strong) with separate word lists, a phrase list and
  exempt players, managed from a `/chatguard` panel (`pizzasmp.chatguard.manage`). `mode: listed` filters only the
  players in `only-players`. Matching folds lookalike letters, leetspeak, accents, invisible characters and
  stretched or spaced-out spellings; slurs of `slur-substring-min-length` letters or more also match inside words.
- Player moderation profile (`/moderation <player>`): status, active punishments, chat log and anticheat flags,
  with unmute, unban, freeze and inventory actions; a Find Player button opens any profile by name.
- `/sus` keeps anticheat suspect history until staff review it: flags stay listed after a player logs out,
  `pizzasmp.sus.manage` dismisses or restores them (Grim's own history is never changed), and staff in spectator
  can teleport to a suspect from the review. `/sus <player>` opens retained details by name, and `/sus` works
  without PizzaNetworkCore.
- The mining audit records each distinct hidden ore discovery with its location for review (`cluster-radius-blocks`
  groups one vein, `log-discoveries`). It is low-confidence evidence, never an automatic punishment.
- `/atrack` frames its target smoothly and returns the staff member to their exact starting point, also after a
  crash or disconnect during the session.
- Configurable kick and ban screens (`kick-screen`, `ban-screen`). Without a brand configured, titles and ban
  screens are neutral, and Discord and appeal lines appear only when a brand Discord is set.
- `death.force-drops` (default true) makes items drop on death even when another plugin would keep them, unless the
  keepInventory game rule is on. `pizzasmp.flight` keeps survival flight granted by other plugins.
- `join-quit-messages.silence` hides the game's join and quit messages when another plugin announces them.
- `/nv` (alias `/nightvision`) is registered by PizzaAdminTools.
- `homes.show-locked-slots` (default false): the home menus show only the slots a player can use; set it to show
  locked slots and the Show More control.
- PizzaProxyGuard: a maintenance MOTD (`plugins/pizzaproxyguard/maintenance.motd`) while the lockout is active,
  optional disconnection of players already online during it (`maintenance.evict-online`), a restarting screen,
  `kick-fallback-to-limbo` to disconnect instead of holding players in the limbo when a backend goes away, and a
  readiness file (`admission.ready-file`) so admission works without a network database.

### Changed
- Every plugin declares `api-version: '1.21.7'`, the first Paper release with the dialog API the suite uses. On
  Paper 1.21.6 the plugins are refused with an API version message instead of failing with a missing class.
- Region maintenance shows one green warning everywhere a held player sees it (PizzaNetworkCore's hold hotbar,
  PizzaLimbo's hotbar and PizzaAdminTools' frozen notice): "WARNING: Your Region Is Undergoing Maintenance. Do Not
  Teleport Or Your Location Will Be Lost." Existing `maintenance_hold.message` settings are kept.
- Homes record the server they were set on (PizzaNetworkCore `sync.server-name`). On a network, `/home` to a home
  on another backend names that server instead of teleporting to the same coordinates in this server's world.
  Cross-server `/home` is not supported yet; homes from earlier versions carry no server and work as before.
- Remaining legacy permission names are gone: `mycommand.punish`, `mycommand.unpunish`, `mycommand.forgive` and
  `mycommand.bancheck` (use `pizzasmp.punish`, `pizzasmp.unpunish`, `pizzasmp.forgive`, `pizzasmp.bancheck`),
  `maintenance.bypass` (use `pizzasmp.maintenance.stay`), and `pizzaruleguard.alerts` (now
  `pizzasmp.ruleguard.alerts`).
- The command map is read through the Paper API instead of reflection.
- License changed from MIT to Apache 2.0. Releases before 1.0.0 remain under MIT.
- Every permission the suite checks for its own features uses the `pizzasmp.` prefix. Renamed: `sethome.use` to
  `pizzasmp.home.use`, `sethome.admin` to `pizzasmp.home.admin`, `sethome.maxhomes.5` to `pizzasmp.homes.5`,
  `essentials.eco` (and its `give`, `take`, `set`, `reset` nodes) to `pizzasmp.admin.eco`, and `essentials.fly` to
  `pizzasmp.flight`; `essentials.invsee` no longer grants `/invsee`. Move LuckPerms grants to the new names when
  upgrading. `pizzasmp.commandfilter.teleport` keeps `/tp` past the non-staff command filter.
- Every inventory menu is locked by one shared mechanism instead of per-menu title checks: no click or drag can
  take an item out of a menu or put one in, except the deposit slots of `/sell`, order delivery and the auction
  listing slot.
- Duels are marked as a preview feature in their menus, challenge notices and documentation.
- The duel loot window is timed from the moment the loser's items drop, shows a live countdown, and `/leave` ends it
  early. Duels with nothing to collect (snapshot loadouts) end at once instead of holding the winner.
- The join send ramp is off by default: its view-distance steps made clients re-render every chunk several times on
  join. Enable `optimizations.join-send-ramp` only on a constrained uplink.
- `/warp` without a destination shows its usage.
- Every plugin carries the suite version (`1.0.0-beta`) instead of its own version number.
- RTP arrival animations are off by default (`rtp.animation.enabled`).
- `/ah sell <price>` lists the item without opening the auction menu.
- The Totem Particles setting hides only other players' totem particles; totem sounds and your own pop
  animation stay.
- ProtocolLib is a required dependency of PizzaNetworkCore.
- PizzaUtils is retired: `/ping` lives in PizzaNetworkCore.

### Removed
- PizzaAdminTools no longer replaces the client brand shown in F3. It reflected into server internals and showed
  one server's name on every install; the server's own brand now shows.
- `PizzaNetworkCore/pom.xml`: it resolved its dependencies from a server's `plugins/` folder and could not
  build from a clean clone.
- PizzaNetworkCore's second homes implementation and its unused `homes` table. PizzaAdminTools owns every home
  command.
- About 60 unreachable methods in PizzaNetworkCore (roughly 1,300 lines).
- The unfinished join queue and `/queuetest`, `/pizzadebug`, and the placeholder plugin list in
  PizzaAdminTools.

### Fixed
- A fresh install had no sell prices, so `/sell` paid nothing. PizzaNetworkCore ships default prices and writes
  them to `plugins/PizzaNetworkCore/sell-prices.yml` on first start; `configs/sell/prices.yml` still overrides.
- `pizzasmp.spawnedit` was declared by two plugins, which logged a warning at startup.
- A fresh single-server install logged warnings for normal conditions: local file storage, no branding profiles,
  the bundled shop and absent hub worlds. They are informational now; the storage warning remains on servers behind
  a proxy, where unshared data matters. The shop message no longer names a script that is not part of the suite.
- Subscription tier names follow `branding.yml` (`tiers.plus`, `tiers.plusplus`, otherwise the brand name plus
  "+" and "++") everywhere, including PizzaAdminTools' perks, subscription menus and messages, which showed one
  server's tier names. Without `branding.yml`, the short name and tiers default to the neutral brand.
- Items dragged into a PizzaPunishment moderation menu were lost when it closed; drags into those menus are now
  blocked like clicks.
- PizzaSpawnRules declared Folia support but scheduled through the Bukkit scheduler, which fails on Folia. It now
  uses Paper's global region and player schedulers (no plugin in the suite uses the Bukkit scheduler) and no longer
  declares Folia support, since its zone checks read players from the global tick.
- Server switches no longer race the final save. The server a player is on holds their state row and releases it
  in the same write as the final save; the next server waits for that release (or for the hold to expire after a
  crash) before loading.
- Joining with no database answer no longer starts the player from the server's local copy and then saves that
  copy over their real state.
- The Vault balance mirror no longer writes PizzaEconomy's own reading back to the balance table, which could store
  0 after a failed read.
- Staff can punish a player who has only played on another backend: names resolve through the shared players table.
- PizzaPunishment enforces mutes and bans itself on every server: mutes block chat and private messages without
  LibertyBans, bans are checked at login, and a player banned elsewhere is kicked. In Database mode records are
  merged on save, so two servers punishing at the same time no longer overwrite each other.
- Without LibertyBans, `/unban`, `/unmute`, `/kick` and `/history` re-dispatched themselves until the server's
  command limit (65,536 runs) stopped them.
- Cross-server `/msg`, `/tell`, `/tpa`, `/tpahere` and their replies: a PizzaAdminTools gate answered "The user is not
  online" for anyone on another backend before PizzaNetworkCore's network lookup ran.
- The economy silently did nothing without Vault, and Vault was not declared as a load dependency. Vault is now a
  soft dependency, a missing Vault is reported at startup, and the README lists it as required.
- PizzaProxyGuard: the jar now bundles the MariaDB driver (Velocity has no library loader), so the cross-server chat
  relay and maintenance gating can start; without database credentials it no longer attempts connections.
- The network example config is regenerated from the current defaults (it still carried the removed join queue).
- Ender chest changes made since the last autosave were lost at server shutdown: queued saves were cancelled and
  open chests were never saved. Shutdown now runs queued saves and writes open chests.
- The staff transaction history (`/txlog`) queried columns that do not exist, so audited market events never showed.
- On a server without the SetHome plugin, players could not use `/home` or `/sethome`: the home permission was
  never declared. It is now `pizzasmp.home.use` (default true), with `pizzasmp.home.admin` for staff (op).
- A home file that cannot be read is never overwritten by the next save, and a database outage cannot erase homes.
- Typed and dispatched commands (for example from menu plugins) now run the same code. Previously about 30
  commands had two diverging implementations.
- `/shop reload` is reachable; typed `/shop reload` used to open the shop.
- `/ah <item> <enchantment>` searches work: spell correction no longer turns enchantment words into item names.
- `/ah <player>` and `/orders <player>` filter by seller or creator, and bare `/ah` and `/orders` open the
  default view.
- `/bal`, `/msg`, `/reply`, `/invsee`, `/wild`, `/randomtp` and the hub setup commands are registered, so they work
  without Essentials.
- `/afk` checks `pizzasmp.use.afk` on every path, and teleport requests are refused during a duel for
  cross-server targets too.
- `/invsee` accepts `pizzasmp.admin.invsee`, the node the admin console checks.
- A duel opponent no longer stays frozen after the other player disconnects and reconnects mid-match.
- Unmuting by name or UUID releases every overlapping active mute for that player, not only the newest.
- `/home`, `/sethome` and the home menus no longer reveal themselves to players without home access, and `/moderation`
  completes player names.
- PizzaProxyGuard no longer takes session leases when no network database is configured.

## [1.0.0-beta] - 2026-09-26

Feature-complete beta of the 1.0.0 suite. Supports Paper 1.21.6 and newer on Java 21; tested on Paper 1.21.11.

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
