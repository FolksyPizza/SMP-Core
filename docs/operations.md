# Deploying and maintaining SMP-Core

Start with a single Paper server unless you need multiple backends. Use [Getting started](getting-started.md),
[compatibility](compatibility.md) and [configuration](configuration.md). Proxy networks add shared SQL,
authenticated forwarding, routing and additional failure cases; follow the [network guide](../network/).
The examples are a starting point, not proof of a lossless or zero-downtime deployment.

## First deployment

1. Choose a supported Paper version and Java 21+. Use a release or build a recorded source revision.
2. Run setup in a new directory, review EULA/configuration choices, then start through its generated script.
   Install only backend jars on Paper; PizzaProxyGuard belongs on Velocity and PizzaLimbo on a dedicated limbo backend.
3. Verify every intended plugin enabled without dependency or schema errors. Keep credentials outside Git.
4. For one server, verify local storage persists through a clean stop/start. For a network, configure the same
   shared database and matching backend identities, then verify forwarding and the selected Vault provider. Confirm each support plugin selected SQL rather than a local fallback.
5. Test a regular player and an operator: commands, permissions, menus, homes, balance, buy/sell, reconnect and restart.
   For a network, also test state transfer and chat both ways. Check actual balance and inventory contents.
6. Back up the resulting world, player data, plugin data/configuration and, when used, SQL before opening access.

Do not share an embedded database file between running servers. Database credentials and forwarding secrets must
be configured for your installation; example values are not production credentials. Restrict backend ingress so
players cannot bypass the proxy's authentication and moderation path.

## Updating a server

Read [release and upgrade notes](releases.md), compare config/schema changes and test the exact candidate in a
disposable copy. Back up jars, configuration, worlds/player files and SQL as one consistent recovery point.
Record the prior and candidate revisions and artifact hashes.

Announce the window and stop new work/admissions as appropriate. Use the maintenance mechanism actually configured
for your installation; ordinary single-server operators can stop cleanly. Do not use plugin hot reload to update
stateful plugins. Replace the intended jars while the target process is stopped, then start and check logs,
provider selection, ordinary-player permissions and affected workflows before reopening access.

## Network maintenance

The published suite contains database-driven holding/return queues and a separate visual-limbo flow. They have
different setup and persistence assumptions. Neither establishes an automatic crash-safe deployment for an
arbitrary network. Confirm the holding backend is reachable, compatible and correctly configured before moving players.

`network/scripts/backend_maint.sh` separates `plan` from `apply`. Read the script and target definitions before
running either; inspect the emitted plan and its selected backends before applying it. Do not copy operational
scripts into an existing installation and assume their process layout matches yours. See the network guide's
maintenance sections and [architecture](../ARCHITECTURE.md) for limitations.

## Rollback and verification

Keep the prior jars/configs and a tested restore procedure. An older jar may not understand a newer schema;
rollback may require restoring a coordinated database and file backup during downtime. Do not overwrite newer
player progress merely to undo a plugin update. Decide the recovery point before changing persistent state.

After startup, verify logs, database access, stored balances/items, permissions and the changed player flow.
A listening port or Paper's Done message only proves part of startup. Record what was verified and what needs
real clients. Report security problems through [SECURITY.md](../SECURITY.md); scrub logs before sharing them.
