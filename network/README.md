# Running SMP-Core as a proxy network

This directory is **optional**. SMP-Core still runs as a single Paper server exactly as it
always has — see [`../setup/`](../setup) for that path, which is unchanged and remains the
simpler choice. Nothing here is required to use the plugins.

What this adds is the multi-server layout the plugins were actually built for: a Velocity
proxy in front of several Paper backends, with player state shared through one database so
a player carries their inventory, balance, permissions and settings across servers without
noticing the boundary.

## When you want this

Use the single-server setup if you run one survival world and want the least moving parts.

Reach for the network if you want any of:

- a lobby separate from survival, so restarts and world resets don't kick everyone
- a maintenance backend that holds players instead of disconnecting them
- a dev backend where you test plugin builds before promoting them
- Bedrock players via Geyser on the proxy rather than on the survival server
- room to add backends later without redesigning anything

## Topology

```
                   ┌───────────┐
   players ───────►│ Velocity  │  :25565   PizzaProxyGuard, Geyser, TAB, ViaVersion
                   └─────┬─────┘
          ┌──────────────┼──────────────┬──────────────┐
     ┌────▼────┐   ┌─────▼─────┐  ┌─────▼──────┐  ┌────▼────┐
     │  lobby  │   │ survival  │  │maintenance │  │   dev   │
     │  :25566 │   │  :25567   │  │   :25569   │  │ :25568  │
     └────┬────┘   └─────┬─────┘  └─────┬──────┘  └────┬────┘
          └──────────────┴──────────────┴──────────────┘
                                │
                        ┌───────▼────────┐
                        │ MySQL/MariaDB  │  one schema, shared by everything
                        └────────────────┘
```

`dev` is off by default and is never in the player-facing fallback chain. `maintenance` is
where players wait while a backend restarts. There is no separate limbo backend in this
layout because the lobby serves that role; PizzaLimbo remains in the repo for deployments
that have no lobby.

## What is shared, and how

| Concern | Mechanism |
| --- | --- |
| Inventory, ender chest, XP, health, hunger, gamemode | `player_sync_state`, applied on join |
| Balance, auction house, orders, shop | `balances` and friends, via PizzaNetworkCore's Vault provider |
| Permissions, ranks, prefixes | LuckPerms MySQL storage + `messaging-service: sql` |
| Chat, direct messages | `pizzasmp:bridge` plugin messages relayed by PizzaProxyGuard |
| Who is online where | `session_leases`, heartbeated per backend |
| Cross-server RTP | `pizzasmp:bridge` handshake; the destination resolves the spot before the player moves |

### Register PizzaNetworkCore as the Vault economy provider

This matters more than it looks. EssentialsX Economy stores balances in **per-server YAML**,
so if it wins the Vault hook a player's money differs on every backend and never reconciles.
PizzaNetworkCore registers its own `balances`-backed provider at `ServicePriority.Highest`
so the database is the single source of truth. If you run EssentialsX, leave its economy
alone — do not "fix" the ordering.

### Two clock rules worth knowing

1. **Let the database compute expiry times.** Lease and transfer-action rows use
   `DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND)` rather than a Java-side `Timestamp`.
   A JDBC `Timestamp` is sent in the JVM's local zone; against a UTC database on a non-UTC
   host every row lands hours in the past and `expires_at > CURRENT_TIMESTAMP` never
   matches. Nothing errors — the network just behaves as though nobody is online anywhere.
2. **Cap the RTP radius.** `rtp.max-radius` (default 5000) bounds random teleports
   independently of the world border. A world whose border was never set carries vanilla's
   ~60,000,000-wide default, and RTP derived from that drops players into ungenerated
   terrain where nothing streams in — an empty void.

Rule 1 is handled in code, so the network is correct whatever your zones are. Give the
database container the same zone as the host anyway (`configs/database-docker-compose.yml`
takes `TZ` from `scripts/lib.sh`) so log timestamps and row timestamps line up when you
read them side by side.

### A death-screen quirk you would otherwise hit

A player who dies on one backend and leaves before clicking Respawn — by switching servers
or by quitting — leaves that backend holding them dead, because it never received the
respawn packet. Return later and vanilla restores exactly that: the death screen they
walked away from, on a server they just joined. PizzaNetworkCore respawns a player who
arrives dead. Worth knowing about if you write your own join handling, because restoring
health does not clear it — the client is already showing the screen and needs a real
respawn.

## Requirements

- Paper 1.21.x on each backend (built and run against 1.21.11)
- Velocity 3.4.x for the proxy
- MySQL 8 or MariaDB 10.6+, reachable from every backend and the proxy
- Java 21+

## Setup

**1. Database.** Create the schema:

```bash
mysql -u root -p < network/configs/schema.sql
```

**2. Directory layout.** One directory per service, alongside `scripts/`:

```
your-server/
  scripts/            <- copy network/scripts/ here
  velocity/
  lobby/  survival/  maintenance/  dev/
```

**3. Proxy.** Copy `network/configs/proxy/velocity.toml.example` to `velocity/velocity.toml`
and generate a forwarding secret:

```bash
openssl rand -base64 32 > velocity/forwarding.secret
```

Modern forwarding requires the proxy to be `online-mode = true` and every backend to be
`online-mode=false` with `enforce-secure-profile=true`. Getting this backwards either
breaks chat signing or leaves the backends joinable directly — check both.

**4. Backends.** Copy
`network/configs/backend/plugins/PizzaNetworkCore/config.yml.example` into each backend's
`plugins/PizzaNetworkCore/config.yml`, set the database credentials, and set
`sync.server-name` (or leave it `auto` to derive from the port).

**5. Topology.** Edit `scripts/targets.env`. It is the single source of truth for which
backends exist and what heap each gets — the ops scripts read it rather than hardcoding
lists, so adding a backend there is enough to make every script aware of it.

**6. Start.**

```bash
./scripts/start.sh
```

## Day-to-day operations

| Command | What it does |
| --- | --- |
| `./scripts/tui.sh` | Cursor-driven menu over everything below |
| `./scripts/build_from_repo.sh` | Compile every plugin from source |
| `./scripts/deploy_plugins.sh --check` | Report jar drift, missing third-party plugins, and orphaned config folders |
| `./scripts/deploy_plugins.sh` | Deploy jars, platform-routed (Paper vs Velocity) |
| `./scripts/deploy_plugins.sh --to dev` | Deploy to one backend — the promotion step |
| `./scripts/backend_maint.sh plan <backend>` | Print the rolling-restart plan without doing it |
| `./scripts/backend_maint.sh apply <backend>` | Execute it, readmitting only once TPS is stable |
| `./scripts/smoke_test.sh` | Post-restart sanity check |

`deploy_plugins.sh` routes by platform and knows which plugins are hub-only. PizzaSpawnRules
is the example worth understanding: it grants creative flight inside its protected zone to
power the lobby double jump, and its `lockAllWorld` option makes the *whole world* that
zone — so on survival it hands every player flight. It installs on lobby and maintenance
only, and is actively removed elsewhere so an older all-servers deploy cannot leave a copy
behind.

### Check that third-party plugins are actually installed

`scripts/plugin-manifest.env` lists the third-party jars each backend needs, and
`--check` reports any that are absent, plus data folders sitting there with no jar beside
them.

This is worth running after any hand-install. Our own plugins are built and copied from one
place, so they cannot quietly drift; hand-installed third-party jars are exactly the ones
that go missing on a single backend and stay missing. The failure that motivated it: the
amethyst-tools plugin was absent from survival while PizzaNetworkCore still sold its tools
for $1.5M and delivered them by dispatching a command that plugin owns. With the plugin
gone the command did nothing, so the purchase took the money and handed over nothing — and
no error was logged anywhere, because dispatching a non-existent command is not an error.

## Maintenance model

Four tiers, gated at the proxy so a player is stopped before they reach a backend that
cannot serve them. See [`docs/MAINTENANCE-MODEL.md`](docs/MAINTENANCE-MODEL.md).

| Tier | Scope | Player experience |
| --- | --- | --- |
| dev | `dev` only | Non-staff are told the server does not exist |
| backend rolling | one backend | Held in the lobby, returned when it is healthy |
| network | everything | New joins denied with a maintenance message |
| emergency | everything | Everyone disconnected with a clear reason |

A rolling restart refuses to stop a backend whose start script or jar is missing, and will
not readmit players until the backend has answered on its port, logged `Done (`, settled for
20 seconds, reported no plugin errors, and held TPS ≥ 18 across three samples.

## Bedrock

Geyser and Floodgate go on the **proxy**, not the backends. The Bedrock `key.pem` must be
identical on the proxy and every backend, otherwise linked accounts resolve inconsistently.
