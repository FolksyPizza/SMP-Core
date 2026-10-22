# Getting started

Run commands below from the repository root.

### Quick start (macOS or Linux)

From a clone of this repository:

```sh
./setup.sh            # starts onboarding
./server/start.sh
```

`setup.sh` checks for Java 21 (offering Homebrew's on macOS), downloads Paper, builds the plugins (or downloads the
latest release), installs ProtocolLib, Vault and optionally LuckPerms with checksums where published, adds the menu
datapack, and writes the EULA, `server.properties`, a brand profile and `start.sh`. Re-running setup replaces Paper,
plugin jars and the menu datapack, and regenerates `branding.yml` and `start.sh`. Existing worlds, player data and
other configuration files are kept; `server.properties` is created only when absent. Copy customized branding and
start scripts somewhere safe before rerunning. Setup saves the previous branding file as `branding.yml.bak`, but
the next run overwrites that backup. Review or merge your customizations before starting the server again.
`./setup.sh --help` lists the options.

### Single server

1. Create a supported Paper server. Download jars from [Releases](https://github.com/FolksyPizza/SMP-Core/releases),
   verify them against that release's `SHA256SUMS`, and install ProtocolLib and Vault from their official sources.
   Put gameplay/support jars in Paper's `plugins/`. Keep `PizzaProxyGuard.jar` on Velocity and `PizzaLimbo.jar` on
   a dedicated limbo backend. Install `PizzaSpawnRules.jar` only when intentionally configuring its hub role.
2. Start the server. With no database configured, every plugin stores its data in local files (PizzaNetworkCore uses
   an embedded database file, `plugins/PizzaNetworkCore/data/smpcore.mv.db`).
3. Optional: to use MySQL or MariaDB, set the connection under `sync.database` in
   `plugins/PizzaNetworkCore/config.yml` and restart. SuiteStorage consumers can reuse those settings; confirm each
   startup log selected database storage. Tables are created at startup, but this does not migrate existing data.


### Velocity network

Lobby, survival, maintenance and optional dev backends run behind one proxy and share player state (inventory,
balance, rank, settings) through a shared MySQL or MariaDB database. [`network/`](../network) has the topology, configs and scripts.
Network support is in beta.

## Confirm a successful first start

Check that each intended plugin enabled with no dependency or schema error, then join with a normal player.
Open a menu, try a home command and check the balance. Stop the server cleanly, start it again and confirm the
stored state remains. Startup alone is not a gameplay check. See [operations](operations.md) for the full
admission, backup and network verification procedure.
