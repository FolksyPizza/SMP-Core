# Plugin APIs and integrations

SMP-Core exposes integrations through existing server APIs. It does not publish a separately versioned Java SDK,
HTTP API or stable external contract for its database tables. Internal public Java methods are not automatically
supported extension points. Pin an SMP-Core revision when integrating and recheck behavior before upgrading.

## Vault economy

PizzaNetworkCore registers its `PizzaEconomy` provider with Bukkit's services manager at `Highest` priority.
Use Vault's `Economy` interface rather than constructing PizzaEconomy or writing the `balances` table yourself.
Install Vault on each gameplay backend that needs the economy and verify the selected provider after startup.
Another plugin can change service registration, so do not assume that installing the jars proves provider selection.

A Bukkit plugin can look up the service with the Vault API on its compile classpath:

```java
RegisteredServiceProvider<Economy> registration =
    Bukkit.getServicesManager().getRegistration(Economy.class);
if (registration == null) {
    // Disable the economy-dependent feature and report the missing provider.
    return;
}
Economy economy = registration.getProvider();
// Inspect economy.getName(); SMP-Core's provider returns "PizzaNetworkCore".
```

Imports: `org.bukkit.Bukkit`, `org.bukkit.plugin.RegisteredServiceProvider` and
`net.milkbowl.vault.economy.Economy`. Declare Vault as a runtime dependency when your plugin requires it.
Prefer OfflinePlayer/UUID-based access. Name-based lookup depends on cached or stored player identity.
Inspect each `EconomyResponse.transactionSuccess()` before delivering goods. Avoid automatic retries of a
mutation after an ambiguous failure: the Vault interface does not give your purchase a durable idempotency key.

The provider declares two fractional digits and no bank support. Access is synchronous and may query SQL;
keep its latency in mind and follow the threading rules of every server API you use. A balance read may return
zero after a database error, so a displayed zero is not proof of an empty account. Inspect server diagnostics.
An economy withdrawal and Bukkit inventory delivery do not form one shared database transaction.

Source: [PizzaEconomy.java](../PizzaNetworkCore/src/dev/pizzasmp/networkcore/PizzaEconomy.java).

## PlaceholderAPI

When PlaceholderAPI is installed, the built-in `pizzasmp` expansion exposes:

| Placeholder | Result |
| --- | --- |
| `%pizzasmp_balance%` | Abbreviated balance without a currency symbol or color |
| `%pizzasmp_balance_raw%` | Unabbreviated balance text from the current implementation |

Formatting is for display; do not parse a placeholder to make a financial decision. The expansion persists across
PlaceholderAPI reloads. Unknown placeholder parameters return no expansion result. TAB can consume these values;
a network-wide tab list also needs proxy-side TAB and an appropriate bridge/configuration.
Source: [PizzaPlaceholders.java](../PizzaNetworkCore/src/dev/pizzasmp/networkcore/PizzaPlaceholders.java).

## Permissions, clients and optional plugins

| Integration | Boundary |
| --- | --- |
| LuckPerms | Permission/rank provider; configure network storage separately. See [commands](../COMMANDS.md) for suite nodes. |
| ProtocolLib | Required packet integration dependency in the standard installation. |
| Geyser / Floodgate | Bedrock connectivity and identity detection; chest-menu fallback differs from Java dialogs. |
| ViaVersion / ViaBackwards | Client protocol compatibility; does not establish every menu or packet path works. |
| Simple Voice Chat | Optional integration for punishment-related voice mutes; match backend/proxy versions as applicable. |

See [setup/deps.txt](../setup/deps.txt) for dependency inputs. Optional third-party plugins are not all required
or bundled, and their licenses remain their own. Do not redistribute manual-only plugins as part of the suite.

## Internal suite interfaces

`pizzasmp:bridge` plugin messages and the `BungeeCord` channel coordinate backend/proxy behavior.
They are internal integration protocols, not a documented general-purpose third-party API. Read both sender and
receiver at the same source revision before modifying them. Restrict backend access to the trusted proxy and
configure authenticated forwarding; never accept player-supplied routing messages as authorization.

PizzaCommon's SuiteStorage and scheduler/menu helpers are embedded in consuming plugin jars. Reuse them within
the suite, but do not assume that another plugin's embedded class is a stable shared service. SQL tables and
local files are persistence internals. Direct writes can bypass leases, validation and caches.
See [architecture](../ARCHITECTURE.md) for ownership and failure boundaries.
