# Compatibility

**Tested**: passes this release's automated tests. **Beta**: works in the tests that ran, with less coverage.
*Unverified*: expected to work, never checked.

| Platform | Status |
| --- | --- |
| Paper 1.21.11 | **Tested**: every bot test, single server and network; used in production |
| Paper 1.21.7 to 1.21.10 | **Beta**: boots clean; the release, menu lock and command routing bot tests pass |
| Paper 1.21.6 and older | Not supported by the published plugin descriptors; minimum Paper 1.21.7 |
| Paper 26.x, Folia | Not supported yet ([UPCOMING.md](../UPCOMING.md)) |
| Java | 21 or newer |
| Velocity | 3.5.1 **Tested** (network bot tests); other 3.x releases *Unverified* |
| Java Edition clients | The server's version natively; other versions through ViaVersion, used in production |
| Bedrock Edition clients | Through Geyser and Floodgate, with chest menus in place of dialogs; used in production, not in the bot tests |
| Network features | **Beta**: cross-server `/home` is not supported yet; a home on another backend is refused by name |

One set of jars covers every supported Paper version.

| Area | Status |
| --- | --- |
| Single-server gameplay: economy, homes, teleports, settings, moderation, menus | Stable |
| Velocity network: shared inventories, balances, permissions, chat, cross-server RTP | Beta |
| Duels and the duel queue | Preview: playable, still changing |
| Folia | Not supported yet ([UPCOMING.md](../UPCOMING.md)) |

Parts of PizzaNetworkCore and PizzaAdminTools were recovered from compiled builds. A few areas still carry
decompiler structure and are cleaned up as they are touched.
