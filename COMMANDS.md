# Commands

Every command in the suite, grouped by what it is for. Aliases are shown in parentheses. Commands under the Staff sections need a permission and are hidden from normal players. Player commands are available to everyone by default.

## Economy and market

| Command | What it does |
| --- | --- |
| `/shop` | Open the server shop. |
| `/sell [all]` | Sell the item in your hand, or your whole inventory with `all`. |
| `/ah` (`/auction`) | Open the auction house. `/ah <item>` filters to that item. |
| `/orders` (`/order`) | Open buy orders. `/orders <item>` filters to that item. |
| `/worth [item]` | Show what an item sells for. |
| `/sellmulti` | Open the sell multiplier progression. |
| `/baltop` | Money leaderboard. |
| `/leaderboard [category]` (`/leaderboards`) | Top players by money, kills, deaths, playtime, and more. |
| `/bounty [player] [amount]` (`/bounties`) | View or place a bounty. |

## Homes and teleportation

| Command | What it does |
| --- | --- |
| `/home [name]` | Teleport to a home, or open the homes menu. |
| `/homes` | Open the homes menu. |
| `/sethome [name]` | Set a home at your location. |
| `/delhome [name]` | Delete a home. |
| `/rtp [east\|nether\|end]` | Random teleport. |
| `/rtpq` (`/rtpqueue`) | Queue for a gear-matched RTP duel: get teleported to a random spot with a matched opponent. |
| `/tpa <player>` | Ask to teleport to a player. |
| `/tpahere <player>` | Ask a player to teleport to you. |
| `/tpaaccept [player]` (`/tpaccept`) | Accept a teleport request. |
| `/tpadeny [player]` (`/tpdeny`) | Deny a teleport request. |
| `/tpacancel` | Cancel your outgoing request. |
| `/tpauto` | Toggle auto-accepting teleport requests. |

## Social

| Command | What it does |
| --- | --- |
| `/friend [add\|accept\|deny\|remove\|list\|requests]` (`/friends`) | Manage friends, or open the friends menu. |
| `/follow <player>` | Follow a player. A mutual follow is a friendship. |
| `/unfollow <player>` | Stop following a player. |

## Info and quality of life

| Command | What it does |
| --- | --- |
| `/stats [player]` | View your stats or another player's. |
| `/settings` | Open your personal settings. |
| `/ping` | Show your ping. |
| `/discord` | Get the server Discord link. |
| `/menu` | Open the main menu. |
| `/guide` | Open the guide menu. |
| `/perks` | Show the subscription tier perks. |
| `/nv` (`/nightvision`) | Toggle night vision (same as the settings toggle). |
| `/kill` | Take your own life. |

## Staff: moderation

| Command | What it does |
| --- | --- |
| `/punish <player> <category\|duration\|reason>` | Punish a player by preset or custom terms. |
| `/ban`, `/permban`, `/ipban`, `/ippermban` | Ban variants (temporary, permanent, by IP). |
| `/unban` (`/unpunish`, `/forgive`), `/idunban <id>` | Reverse a ban, by name, IP, or record id. |
| `/kick <player> <reason>` | Kick an online player. |
| `/mute` / `/unmute` | Mute or unmute a player. |
| `/history <player\|ip\|id> [page]` | View a player's punishment history. |
| `/bancheck [player\|id]`, `/searchid <id>` | Look up bans and records. |
| `/bans` (`/moderation`) | Open the moderation GUI. |
| `/listbans`, `/listmutes` | List active bans or mutes. |
| `/clearbans`, `/clearmutes` | Clear all active bans or mutes (with confirm). |
| `/offend <player>` (`/offense`) | Issue a cheating offense. Three strikes is a long ban. |
| `/offenses [player]` (`/strikes`) | View offenses or top offenders. |
| `/unoffend <player> [count\|all]` | Revoke a cheating strike. |
| `/freeze` / `/unfreeze <player>` | Freeze or unfreeze a player in place. |
| `/gtp <player> [home]` | Teleport to a player's home. |
| `/atrack [player]` | Spectator-track a player in third person. |
| `/stash` (`/spawnstash`) | Drop a small randomized survival camp where you look. |

## Staff: chat

| Command | What it does |
| --- | --- |
| `/clearwarnings <player>` (`/clearwarn`, `/clearwarns`) | Clear a player's chat warnings. |
| `/pizzachatguard [reload]` (`/pcg`) | ChatGuard help and reload. |

## Staff: anti-cheat and diagnostics

| Command | What it does |
| --- | --- |
| `/sus` (`/suspicious`) | Open the anti-cheat review panel. |
| `/pizzasusflag <player> <check> <vl>` | Raise a manual anti-cheat flag. |
| `/diagnostics` | Server diagnostics. |
| `/txlog [player] [page]` | Economy transaction log. |
| `/pizzadebug hud` | Toggle HUD debug output. |

## Staff: server administration

| Command | What it does |
| --- | --- |
| `/admin` | In-game admin console (staff tools). |
| `/manage` | Backend management console (dev and granted admins). |
| `/branding [status\|set <profile>]` | Switch the active brand profile. |
| `/servermaint <start\|end\|status>` | Frozen-in-place maintenance. |
| `/maintenancemotd <text>` (`/maintmotd`) | Set the maintenance server-list MOTD. |
| `/viewdistance <n>`, `/vdthrottle [...]` | View distance controls. |
| `/shards give <player> <amount>` | Grant shards. |
| `/reset <deaths\|kills\|playtime\|money> <player>` | Reset a player stat. |
| `/admindelhome <player> <home>` | Delete another player's home. |
| `/pizzaplus <give\|revoke\|check\|extend> ...` | Manage subscription tiers. |
| `/sfmode` | Toggle staff mode (hide and disable staff commands for yourself). |
| `/gmcbypass <player>` | Console only. Grant a one-time creative-mode bypass. |
| `/queuetest` | Force-enqueue yourself to test the join queue. |
| `/pizzaplugins` | Show the plugin stack. |

## Staff: network and maintenance (Alpha, off by default)

These belong to the cross-server layer, which is off unless you enable `sync.enabled`. See the Status section of the README.

| Command | What it does |
| --- | --- |
| `/limbomaint <start\|stop\|status>` (`/limbo`) | Full-stop maintenance: move players to a limbo server and restart. |
| `/transfer <destination>` | Transfer yourself or a player to another server. |
| `/transfermaintenance <on\|off\|status>` | Internal helper for the maintenance transfer flow. |
