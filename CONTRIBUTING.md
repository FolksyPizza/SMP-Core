# Contributing

Contributions are welcome: bug reports, fixes, documentation and features that fit the suite.

## Before you start

- Search existing issues first. For anything larger than a small fix, open an issue to agree on the approach
  before writing code.
- Security problems go through [SECURITY.md](SECURITY.md), never a public issue.

## Development setup

| Need | Version |
| --- | --- |
| Java | 21 |
| Paper API | 1.21.11 (the minimum runtime is 1.21.6) |
| Database | MySQL or MariaDB, for PizzaNetworkCore |
| Runtime dependency | ProtocolLib |

PizzaNetworkCore builds with Maven (`PizzaNetworkCore/pom.xml`). The other plugins compile with `javac` against the
Paper API and their dependencies. Test on a local Paper server with a throwaway database, never on a live one.

## Code rules

- Use the Paper/Folia schedulers, not `Bukkit.getScheduler()` or `BukkitRunnable`: the entity scheduler for
  player work, the region scheduler for blocks, the global region scheduler for world-independent ticks, and the
  async scheduler for I/O. PizzaCommon's scheduler facade wraps them.
- Do not add Essentials or Vault dependencies. Economy access goes through PizzaNetworkCore's helpers.
- APIs newer than Paper 1.21.6 need a runtime capability check and a fallback.
- Player state must be database-backable and behave the same across backends in a network.
- Restore temporary player state (flight, invulnerability, gamemode, inventory locks) on every exit path,
  including disconnect and shutdown.
- Match the surrounding code: naming, structure, error handling and comment density. Comments explain why, not
  what.
- No credentials, server names, hostnames or player data in code, configs or tests. Defaults stay neutral.

## Pull requests

- One concern per pull request, based on `main`.
- Describe what changed, why, and exactly how you tested it (server version, single server or network, the
  paths you exercised). Say what you did not test.
- Update `CHANGELOG.md` under `[Unreleased]` and `COMMANDS.md` when commands or permissions change.
- Commit messages use the imperative mood: "Fix duel reconnect grace", not "fixed stuff".

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
