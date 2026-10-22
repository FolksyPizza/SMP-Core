# Configuration

| What | Where |
| --- | --- |
| Branding: name, tagline, region, Discord link, tier names and color palette | `plugins/PizzaNetworkCore/branding.yml` ([Branding](#branding)) |
| Economy, gameplay and database | `plugins/PizzaNetworkCore/config.yml`, `shop.yml` |
| Chat filtering | `plugins/PizzaChatGuard/` |
| Punishments and rules | `plugins/PizzaPunishment/`, `plugins/PizzaRuleGuard/` |
| Storage (Database or File) | PNC: `storage.backend` chooses embedded H2 or shared SQL. Support plugins use their own SuiteStorage selection; see below |

## Storage modes and failures

PizzaNetworkCore `storage.backend: auto` chooses local H2 when database credentials are absent or still set to
`CHANGE_ME`. With real credentials configured, it selects shared SQL. A database outage does not switch PNC's
balance storage to a separate local file. `file` forces local storage; `mysql` forces the external database.

Support plugins using PizzaCommon SuiteStorage are different: they may use their own database settings or reuse
PNC's settings, and initialization failure can make them fall back to local YAML. Check each plugin's startup
storage message rather than assuming the whole suite selected SQL. Expanded ender chests use SuiteStorage SQL
when selected, or their own local files otherwise; their contents are separate from PNC's player-sync snapshot.
Shared PNC SQL does not make every plugin's data network-wide.

Do not treat changing a connection setting as migration of existing data. Back up the source and destination,
choose one authoritative dataset, and test the transfer before enabling writes. A storage-mode change can expose
an empty dataset while the original data remains in its previous location.

## Branding

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

## Datapacks

| Datapack | Install into | Adds |
| --- | --- | --- |
| `datapacks/pizzasmp-menu` | The main world's `datapacks/` | The `/menu` pause-screen dialogs |
| `datapacks/pizzalimbo-menu` | The limbo world's `datapacks/` | The maintenance dialog |
