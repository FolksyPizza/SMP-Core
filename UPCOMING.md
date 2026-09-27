# Upcoming

Planned work for SMP-Core. This is direction, not a commitment or a schedule. Open an issue to argue for an item.

## 1.0.0

| Item | Summary |
| --- | --- |
| File storage | Every feature gets two storage backends, Database and File, so a single server can run without MySQL. Networks use the database. |
| Network handoff | A player who arrives before their data is held safe (invulnerable, inventory locked) until it loads. |
| Cross-server RTP | The destination is found before the player moves, then one transfer and one teleport. |
| Staff tools | Manage other players' homes and open their menus (homes, auctions) from the admin console. |
| Version range | Paper 1.21.7 to 26.2 with dialogs, and 1.21.6 through inventory menus. Players can choose menus over dialogs. |
| Portable build | One build that works from a clean clone. |

## Later

| Area | Item |
| --- | --- |
| Scale | Folia support across the suite, then research into spreading Folia regions across machines |
| Scale | Geo-distributed entry points with a shared world and economy behind them |
| Integration | A supported deployment path alongside [Canopy](https://github.com/folksypizza/canopy) |
| Gameplay | Owner switches for optional features (teams, friends, menu style) |
| Gameplay | Visual effects for teleports, sales and milestones, and cosmetic trails |
| Economy | Route sales through the best player order when it beats the base price; sweep matching listings when an order is created |
| Moderation | A detection layer on PizzaRuleGuard: unified violation levels, autoclicker and macro cadence checks, economy and dupe checks. Supplements GrimAC, does not replace it |
| Project | Continuous integration, checksummed releases, tests around money handling, performance work |
