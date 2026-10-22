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
| Paper API | 1.21.11 (minimum runtime: Paper 1.21.7) |
| Database | Local storage for a single server; MySQL or MariaDB for network tests |
| Runtime dependencies | ProtocolLib and Vault |

Build the suite from the repository root with the Gradle wrapper:

```sh
./gradlew build
python3 tools/ci/check-licenses.py --jars build/dist
```

Jars appear in `build/dist/`. Test on a local Paper server with disposable data and, for network flows, a
throwaway database. `tools/ci/boot-check.sh` checks startup and deletes its work directory; use a dedicated
disposable directory, never an existing server. Verify gameplay changes beyond startup.

See [Development](docs/development.md) for repeatable build commands, isolated startup checks and gameplay test guidance.
Use [Integrations](docs/integrations.md) when changing external provider behavior.

## Code rules

- Use the Paper/Folia schedulers, not `Bukkit.getScheduler()` or `BukkitRunnable`: the entity scheduler for
  player work, the region scheduler for blocks, the global region scheduler for world-independent ticks, and the
  async scheduler for I/O. PizzaCommon's scheduler facade wraps them.
- Use PizzaNetworkCore's existing economy helpers and standard Vault bridge; do not introduce a second balance store.
- Discuss new dependencies before adding them; check runtime requirements and license compatibility.
- Keep the documented Paper 1.21.7 minimum: newer APIs need a capability check and fallback or an explicitly reviewed support change.
- Player state must be database-backable and behave the same across backends in a network.
- Restore temporary player state (flight, invulnerability, gamemode, inventory locks) on every exit path,
  including disconnect and shutdown.
- Match the surrounding code: naming, structure, error handling and comment density. Comments explain why, not
  what.
- No credentials, server names, hostnames or player data in code, configs or tests. Defaults stay neutral.

## Agent-assisted contributions

Read [AGENTS.md](AGENTS.md) and the [agent contribution guide](docs/agent-contributions.md).
For substantial work, get human review of the proposed approach before implementation. Return to human review
when scope or risk changes, and have a human review the final diff and test evidence before merge.
You are responsible for every contribution you submit, including generated code, dependency choices, licensing,
security and behavior. An agent cannot provide the human approval for its own work.

## Pull requests

- One concern per pull request, based on `main`.
- Describe what changed, why, and exactly how you tested it (server version, single server or network, the
  paths you exercised). Say what you did not test.
- Update `CHANGELOG.md` under `[Unreleased]` and `COMMANDS.md` when commands or permissions change.
- Commit messages use the imperative mood: "Fix duel reconnect grace", not "fixed stuff".

## License

By contributing, you agree that your contributions are licensed under the [Apache License, Version 2.0](LICENSE).
