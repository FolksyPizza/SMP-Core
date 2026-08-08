# Maintenance model — design

Status: agreed design, not yet implemented. Written 2026-08-07.

Supersedes the three overlapping mechanisms inherited from the A1 single-SMP + limbo
deployment (`/servermaint`, `/region`, `/limbomaint`), which assumed a topology we no
longer run.

---

## 1. Operating principle: CLI is the control surface

**All maintenance is driven by `scripts/`. There are no in-game maintenance commands, and
`/admin` and `/manage` are removed.**

Two reasons, in order of weight:

1. **Blast radius under permission leakage.** `/admin` and `/manage` expose maintenance
   toggles, world access, economy controls and feature flags behind a single permission
   node. One mis-set LuckPerms inheritance — and the group chain carries ~271 explicit
   `=false` negations precisely because this has been fiddly before — hands an unprivileged
   player the ability to take the network down. A shell command cannot leak this way.
2. **Rolling restarts are a planning problem, not a button.** The execution plan (§4) has
   ordering, verification and abort semantics. That belongs in a script that can be read,
   dry-run and reviewed, not behind a dialog button.

### On removing `/admin` and `/manage`

Recommended mechanism: **unregister the commands in `plugin.yml`** rather than delete the
handler code. Unregistering makes the console unreachable, which is the entire security
win, without a large deletion pass through a 20k-line class. Specifically:

- Remove `admin:` and `manage:` from `tools/pizzanetworkcore/resources/plugin.yml`.
- Leave `openManageConsole` / `openAdmin*` in place, unreferenced.
- `pizzasmp.manage` and `pizzasmp.admin.console` stop granting anything reachable. Keep
  the nodes; they still gate other things.

Do NOT rip out `/region` while doing this. It is **60 call sites**, not a button — a
world-access engine plus the `closed_worlds` channel and limbo-hold path. A Folia-era
variant will likely reuse it. Hide the command; keep the machinery.

`/servermaint` (4 refs, freeze-in-place, does nothing useful) can go. `frozenmaint` already
does not exist. `/limbomaint` **stays** — downstream server owners without a lobby need the
limbo offload path even though this network does not use it.

---

## 2. Tiers

| Tier | Purpose | What stops | Where players go |
| --- | --- | --- | --- |
| **0 — dev** | plugin iteration | `dev` only | nobody is there; invisible to players |
| **1 — backend** | deploy an update, work on a world | one backend at a time | **maintenance backend**, auto-return when verified healthy |
| **2 — network** | major/structural update needing hours and repeated full restarts | everything except the proxy | **nobody joins at all** |
| **3 — emergency** | exploit, corruption, active damage | access denied immediately | kicked at once |

**Tier 1 is the common case** and is explicitly a *rolling* restart: backends are cycled one
at a time so the network is never fully down.

**Tier 2 is not "a longer Tier 1."** It is for work where the server will be restarted
repeatedly over hours. Kicking players on a loop as each restart lands is a worse
experience than a closed door, so Tier 2 **denies login outright** with an explanatory
message rather than admitting and then kicking.

**Tier 3 kicks immediately** — no drain, no hold.

### Messages

- Tier 2 / backend unreachable:
  `"<Server> is not available right now. It may be restarting or under maintenance.
   Please try again in a few minutes."`
- Tier 3 emergency:
  `"We've encountered an issue while trying to connect you to {server}"`
- Unauthorised `dev` access attempt (§3):
  `"We don't know what happened here, but it looks like you attempted to connect to a
   server that doesn't exist"`

---

## 3. Everything gates at the proxy

**The decision "may this player enter backend X right now" is made by Velocity, before any
connection to X is opened.**

This is the difference between a player never touching a down backend and a player
connecting, being noticed, and being kicked. The latter only works while the backend is
*up* — which during maintenance is exactly what it is not. It is also what produced the
kick-loop behaviour we want gone.

PizzaProxyGuard is the right home: it already gates `LoginEvent` and `ServerPreConnect`,
already polls backend health, and already holds a database connection (inherited when it
absorbed the chat relay). It reads `maintenance_state` and decides.

The proxy is also the only component that survives every backend restart, which is why it
holds both the gate and the routing table (`server name -> host:port`; under distributed
Folia, `coordinates -> process_id -> host:port`).

### Login resolution order during maintenance

Resolve to the first available of: **last-logout server -> lobby -> survival -> any other
available backend.** If none is available, deny with the Tier 2 message.

### dev backend access control

`dev` is registered in `velocity.toml` but kept out of `try` and out of
`sync.reconnect.excluded-servers`, so nobody is routed there by fallback or last-logout
routing.

Access is restricted to **dev / admin / sradmin**. Anyone else attempting it is refused
**at the proxy** with the "server that doesn't exist" message — deliberately uninformative,
so an unprivileged player learns nothing about the backend's existence. A backend-side
check is kept as defence in depth in case the proxy check is ever bypassed.

### kennytv Maintenance plugin: not used

It is installed on **zero** backends and zero on the proxy (it ran on A1; its config
survives in `runtime-configs/plugins/Maintenance/`). PizzaProxyGuard covers the gate;
whitelist-bypass and MOTD control are small additions on top of what we already have.

**Consequence to fix:** `/maintenancemotd` writes directly into
`plugins/Maintenance/config.yml` -> `ping-message.messages[0]` and hot-reloads that plugin
(PNC ~line 19598). With kennytv gone that command targets a file nothing reads. It must be
repointed at the proxy MOTD or removed.

---

## 4. Rolling restart: plan first, then execute

The system **computes and emits a complete execution plan before doing anything.** The plan
is a printable, reviewable ordering — not a decide-as-you-go loop.

```
./scripts/backend_maint.sh plan survival lobby      # emit the plan, change nothing
./scripts/backend_maint.sh apply <plan-file>        # execute it
./scripts/backend_maint.sh status
```

Per backend, in order:

1. Mark the backend in-maintenance in `maintenance_state` (proxy stops admitting to it).
2. Move its players to the **maintenance backend**.
3. Stop the backend. **Verify the relaunch is staged before stopping anything** — see §6.
4. Start it.
5. **Verify healthy** (§5) before readmitting anyone.
6. Return the held players; clear the maintenance flag.
7. Only then move to the next backend.

Properties, all required:

- **Each step independently abortable.** A failure at step 3 of 5 stops in a valid state.
  Never leave the system requiring plan completion to be consistent.
- **No global transition.** One unit at a time; the rest keep serving.
- **Ordering is fixed at plan time**, so it can be reviewed before execution.

---

## 5. Readmission gate — "up" is not "ready"

A backend that has bound its port is not necessarily fit for players. Readmission requires
**all** of:

- process alive and port listening
- `Done (` in the log for *this* boot
- **TPS stable** — sustained above a floor (suggest ≥18.0) across several consecutive
  samples, not a single reading
- no ERROR-level plugin failures during this boot (a plugin that failed to enable means
  the backend is up but broken)

### The discriminator

If a backend comes back up but fails any health check, **do not readmit players.** Hold
them and surface it to the operator. Specifically:

- Health checks fail -> backend stays flagged in-maintenance, players stay held, plan
  **aborts** rather than continuing to the next unit.
- Backend fails to come back at all -> same, plus alert.

This exists because of a real incident: the limbo full-stop flow moved players off, the
relaunch never fired, and **the server stayed down for two days**
(see `memory/pizzalimbo-restart-bug.md`). Readmitting into a half-broken backend is the
same class of failure with a worse outcome — players lose items rather than just time.

Held players get the Tier 2 message and a retry, never a silent drop.

---

## 6. Hard safety rule

**Stage and verify the relaunch path before stopping anything.** The 2-day outage was
caused by stop-then-relaunch where the relaunch silently never fired. A tier that stops a
backend must refuse to start if it cannot prove it can bring it back.

A watchdog is the backstop, not the primary mechanism.

---

## 7. Forward compatibility with distributed Folia

Under distributed Folia the world is split across multiple JVMs, potentially on multiple
hosts. Two facts shape this design now:

- **Restart granularity equals process granularity.** Folia regions are threads sharing one
  heap and one classloader; they cannot be restarted individually. "Restart region by
  region" means "restart process by process" — structurally identical to the rolling
  backend restart above.
- **Adding compute raises real player capacity.** Past a point no single box — however many
  Folia JVMs it runs — has the cores, memory bandwidth or NIC for the population. This is
  how large survival networks scale. (Distinct from the single-hotspot case: one saturated
  region is still one thread wherever it runs, and is fixed by per-region work budgeting,
  not by adding machines.)

Therefore, build now so the Folia version is a data change rather than a redesign:

- Keep `maintenance_state.targets_csv` **generic** — a list of unit names. Today
  `survival`, `lobby`. Later, process/slice IDs.
- Keep the plan/apply split and the abort semantics. They are the same constraints as
  §5.2 slice-transfer ordering in the distributed-Folia plan.
- Keep the gate at the proxy. It already holds the routing table Folia will extend.

### Repartitioning: static, with manual rebalance

Not "never repartition" — **do not automate it yet.**

The purpose is load balancing between compute instances: if host A runs four Folia
processes and two are under heavy load, a rebalance hands one off to host B (trading
regions) to even things out.

Automating the trigger is deferred because a naive threshold flaps, migration cost
perturbs the metric being reacted to, and — sharpest — the region most needing migration
(a flood, a large farm) is exactly the one whose dirty set never converges, so pre-copy
cannot move it. Automation would fire when it can least succeed.

Manual rebalance during a maintenance window uses the same rolling-restart machinery
described here, and gets most of the value.

### Seam corridors (informational)

The boundary between two processes' territory is a **seam**. Block mechanics do not work
across it — redstone, pistons and fluids would have to write into territory another process
owns, which is where duplication bugs live.

The corridor is the cheap answer: a protected strip along each seam where **building is
disabled, redstone is inert, fluids do not spread, and contraptions cannot cross.** Only
entity handoff and read-only rendering cross it. There is a deliberate **slight overlap** —
a narrow band identically accessible from both regions — so a player crossing has somewhere
consistent to stand during handoff rather than a hard discontinuity.

Requires seams placed in dead space (ocean, badlands, unclaimed wilderness); incompatible
with equal-area quadrant splitting, which would drive a corridor through spawn.

Corridors and halo invalidation are quality-of-life, not correctness. Without them the seam
is ugly, not broken.

---

## 8. Implementation order

1. `scripts/backend_maint.sh` — `plan` / `apply` / `status`, with the §5 health gate.
2. PizzaProxyGuard: read `maintenance_state`; login-resolution order; dev access control;
   Tier 2 and Tier 3 messages.
3. Unregister `/admin` and `/manage`; drop `/servermaint`; hide `/region`.
4. Repoint or remove `/maintenancemotd`.
5. Uniform-volume mellohi in the maintenance area (play per-player non-positionally so it
   does not attenuate with distance).
6. Advancement sync through `player_sync_state`, so an advancement earned on survival does
   not re-announce on arriving at the lobby.
