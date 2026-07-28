# Upcoming

Things I want to build for SMP-Core. This is a direction and a wishlist, not a promise or a timeline. Some of it is close, some of it is ambitious, and some of it is a long way off. If you want one of these badly, open an issue and say so.

## Performance and scale

- **Folia support.** The plugins currently use the classic Bukkit scheduler, so they are not Folia-safe yet, and the plugin metadata says so honestly. Folia's region threading is the real way to run a large world across many cores, so making the whole suite region-aware is a major goal.
- **Clustered Folia.** Further out: spread Folia's regions across more than one machine, so a single logical server runs on a cluster of compute instances with the region threads living on real separate hardware, not just separate cores. This is a big research-and-build effort, not a config flag.
- **Multiple connection regions.** Geo-distributed entry points (NA-East, NA-West, EU, Asia) so players connect to something close to them while the world and economy stay shared behind it. This builds on the cross-server layer that is Alpha today.

## Gameplay and UX

- **Owner-configurable features.** Config switches so each server decides what it runs: teams, friends, or both; the legacy inventory menus or the newer dialog menus; and similar toggles. Anything PizzaSMP retired should stay available to everyone else, not forced off.
- **Visual effects.** A player-facing effects layer: subtle ambient effects for teleports and sales, bigger celebratory ones for milestones and advancements, and cosmetic trails as a perk. A few of the big-moment effects are meant to fill the screen, not just be small particles.
- **Local storage.** A flat-file or SQLite option so PizzaNetworkCore can run without a MySQL database. The database requirement is the biggest thing standing between someone and just trying it.
- **Finish the order-matching engine.** Route selling through the best available player order when it beats the base sell price, and sweep matching listings when an order is created.

## Moderation and anti-cheat

- **Hardened moderation and a custom detection layer.** Not a replacement for GrimAC, but a supplemental layer built on PizzaRuleGuard: a unified violation-level system, behavioral pattern detection (autoclicker, and AFK-farm and macro cadence), and mechanic checks tuned to this suite's own economy and dupe surfaces.

## Project health

- A build file for every plugin, continuous integration, checksummed releases, and tests around the money-handling paths.
