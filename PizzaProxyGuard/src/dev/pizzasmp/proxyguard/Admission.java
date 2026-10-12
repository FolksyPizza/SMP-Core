// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the SMP-Core Source License, version 1.0 (see LICENSE). All rights reserved.
package dev.pizzasmp.proxyguard;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Decides when a player may enter the gameplay backend, and makes them wait until it is safe.
 *
 * Admission is refused while anything would make the join go wrong: the backend is down or still booting, the
 * database is unreachable, the player's synced state is still held by the server they just left, or too many
 * players are already joining. The player is not told; their login (or server switch) simply takes longer, and
 * the checks rerun four times a second. Only when the wait runs out are they shown a screen naming the reason.
 * Waiting players are admitted in arrival order.
 *
 * A login waits at most login-wait-seconds, well inside the client's 30-second timeout; a player switching servers
 * stays where they are and waits up to switch-wait-seconds. A join holds its slot until settle-seconds after the
 * player has fully joined.
 */
final class Admission {
    enum Block { NONE, CAPACITY, BACKEND, DATABASE, HANDOFF, GONE }

    private static final long ADMIT_TIMEOUT_MILLIS = 30_000L;
    private static final long READY_FALLBACK_MILLIS = 30_000L;
    /** Consecutive failed pings before the backend counts as down; one slow ping is not an outage. */
    private static final int DOWN_AFTER_FAILURES = 3;
    /** An outage shorter than this was a blip, not a restart: no ready stamp is needed afterwards. */
    private static final long BLIP_MILLIS = 20_000L;

    private final PizzaProxyGuard plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final ChatRelay db;
    private final Screens screens;
    private final String backend;
    private final int maxConcurrent;
    private final long settleMillis, loginWaitMillis, switchWaitMillis;
    private final Set<String> bypass;
    private final String readyFile;

    private final class Waiter {
        final UUID id;
        final String target;
        final boolean login;
        final long deadline;
        final CompletableFuture<Block> result = new CompletableFuture<>();
        volatile Block last = Block.CAPACITY;
        Waiter(UUID id, String target, boolean login, long waitMillis) {
            this.id = id;
            this.target = target;
            this.login = login;
            this.deadline = System.currentTimeMillis() + waitMillis;
        }
    }

    // Guarded by this.
    private final Map<UUID, long[]> joining = new HashMap<>();          // admitted at, joined at
    private final LinkedHashMap<UUID, Waiter> waiting = new LinkedHashMap<>();

    // Refreshed by probe() once a second.
    private volatile boolean backendReachable = true, backendReady = true, databaseUp = true;
    private volatile Map<UUID, String> heldElsewhere = Map.of();
    private long downSince, recoveredAt;
    private int pingFailures;

    Admission(PizzaProxyGuard plugin, ProxyServer proxy, Logger logger, ChatRelay db, Screens screens, String backend,
              java.util.Properties props) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.db = db;
        this.screens = screens;
        this.backend = backend;
        this.maxConcurrent = Math.max(1, intProp(props, "admission.max-concurrent", 3));
        this.settleMillis = 1000L * Math.max(0, intProp(props, "admission.settle-seconds", 6));
        this.loginWaitMillis = 1000L * Math.min(25, Math.max(1, intProp(props, "admission.login-wait-seconds", 20)));
        this.switchWaitMillis = 1000L * Math.max(1, intProp(props, "admission.switch-wait-seconds", 60));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String n : props.getProperty("admission.bypass", "").split(",")) if (!n.isBlank()) names.add(n.trim().toLowerCase(Locale.ROOT));
        this.bypass = Set.copyOf(names);
        this.readyFile = props.getProperty("admission.ready-file", "").trim();
    }

    private static int intProp(java.util.Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim()); } catch (NumberFormatException e) { return def; }
    }

    void start() {
        this.proxy.getEventManager().register(this.plugin, this);
        this.proxy.getScheduler().buildTask(this.plugin, this::probe).repeat(1, TimeUnit.SECONDS).schedule();
        this.proxy.getScheduler().buildTask(this.plugin, this::admitPass).repeat(250, TimeUnit.MILLISECONDS).schedule();
        this.logger.info("Admission control on for '{}': {} joining at a time, logins wait up to {}s.",
            this.backend, this.maxConcurrent, this.loginWaitMillis / 1000L);
    }

    // ---- events ----

    @Subscribe(order = PostOrder.LATE)
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed() || this.bypassed(event.getPlayer())) return null;
        Waiter w = this.enqueue(event.getPlayer().getUniqueId(), this.firstServer(event.getPlayer()), true, this.loginWaitMillis);
        if (w == null) return null;
        return EventTask.resumeWhenComplete(w.result.thenAccept(block -> {
            if (block != Block.NONE && block != Block.GONE) {
                this.logger.info("Admission: {} not admitted after waiting ({})", event.getPlayer().getUsername(), block);
                event.setResult(ResultedEvent.ComponentResult.denied(this.screenFor(block)));
            }
        }));
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) return null;
        Player player = event.getPlayer();
        String name = target.getServerInfo().getName();
        String current = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(null);
        // The first connection was admitted at login; only switches onto the backend are gated here.
        if (current == null || current.equalsIgnoreCase(name) || !this.backend.equalsIgnoreCase(name)) return null;
        if (this.bypassed(player)) return null;
        synchronized (this) { if (this.joining.containsKey(player.getUniqueId())) return null; }
        Waiter w = this.enqueue(player.getUniqueId(), name, false, this.switchWaitMillis);
        if (w == null) return null;
        return EventTask.resumeWhenComplete(w.result.thenAccept(block -> {
            if (block == Block.NONE || block == Block.GONE) return;
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text(this.switchMessage(block), NamedTextColor.GRAY));
        }));
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        String name = event.getPlayer().getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        boolean first = event.getPreviousServer() == null;
        synchronized (this) {
            long[] entry = this.joining.get(event.getPlayer().getUniqueId());
            if (entry != null && (first || this.backend.equalsIgnoreCase(name))) entry[1] = System.currentTimeMillis();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Waiter w;
        synchronized (this) {
            this.joining.remove(event.getPlayer().getUniqueId());
            w = this.waiting.remove(event.getPlayer().getUniqueId());
        }
        if (w != null) w.result.complete(Block.GONE);
    }

    // ---- admission ----

    /** Queue a player and try to admit them at once; null when admitted immediately. */
    private Waiter enqueue(UUID id, String target, boolean login, long waitMillis) {
        Waiter w;
        synchronized (this) {
            w = this.waiting.get(id);
            if (w == null) {
                w = new Waiter(id, target, login, waitMillis);
                this.waiting.put(id, w);
            }
        }
        // The probe refreshes holds once a second; a new login must not be admitted on data older than itself.
        if (login) {
            String holder = this.holderOf(id);
            synchronized (this) {
                Map<UUID, String> holds = new HashMap<>(this.heldElsewhere);
                if (holder != null) holds.put(id, holder); else holds.remove(id);
                this.heldElsewhere = Map.copyOf(holds);
            }
        }
        this.admitPass();
        return w.result.isDone() && w.result.getNow(null) == Block.NONE ? null : w;
    }

    private void admitPass() {
        List<Map.Entry<Waiter, Block>> done = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (this) {
            this.joining.entrySet().removeIf(e -> {
                long[] t = e.getValue();
                if (this.proxy.getPlayer(e.getKey()).isEmpty()) return true;
                return t[1] > 0L ? now - t[1] >= this.settleMillis : now - t[0] >= ADMIT_TIMEOUT_MILLIS;
            });
            var it = this.waiting.values().iterator();
            while (it.hasNext()) {
                Waiter w = it.next();
                Block block = this.check(w);
                if (block == Block.NONE) {
                    this.joining.put(w.id, new long[] { now, 0L });
                    it.remove();
                    done.add(Map.entry(w, Block.NONE));
                } else {
                    w.last = block;
                    if (now >= w.deadline) {
                        it.remove();
                        done.add(Map.entry(w, block));
                    }
                }
            }
        }
        for (Map.Entry<Waiter, Block> e : done) e.getKey().result.complete(e.getValue());
    }

    /** Caller holds the lock. Waiters are checked in arrival order, so a free slot goes to the earliest. */
    private Block check(Waiter w) {
        if (this.db.databaseConfigured() && !this.databaseUp) return Block.DATABASE;
        boolean toBackend = this.backend.equalsIgnoreCase(w.target);
        if (toBackend && !(this.backendReachable && this.backendReady)) return Block.BACKEND;
        // Only a new login waits for another server's hold: on a switch, the server being left holds the player's
        // state until the switch completes, and releases it in its final save.
        String holder = w.login ? this.heldElsewhere.get(w.id) : null;
        if (toBackend && holder != null && !holder.equalsIgnoreCase(this.backend)) return Block.HANDOFF;
        return this.joining.size() < this.maxConcurrent ? Block.NONE : Block.CAPACITY;
    }

    // ---- health probe ----

    private void probe() {
        RegisteredServer server = this.proxy.getServer(this.backend).orElse(null);
        boolean reachable = false;
        if (server != null) {
            try { reachable = server.ping().get(2, TimeUnit.SECONDS) != null; } catch (Exception ignored) { }
        }
        long now = System.currentTimeMillis();
        boolean dbUp = true;
        long readyAt = -1L;
        Map<UUID, String> holds = new HashMap<>();
        if (this.db.databaseConfigured()) {
            List<UUID> ids;
            synchronized (this) { ids = new ArrayList<>(this.waiting.keySet()); }
            try (Connection c = this.db.openConnection()) {
                dbUp = c.isValid(2);
                try (PreparedStatement ps = c.prepareStatement("SELECT UNIX_TIMESTAMP(smp_ready_at)*1000 FROM limbo_control WHERE id=1");
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) readyAt = rs.getLong(1);
                } catch (Exception ignored) { }
                if (!ids.isEmpty()) {
                    String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
                    try (PreparedStatement ps = c.prepareStatement("SELECT uuid, holder_server FROM player_sync_state WHERE uuid IN ("
                            + in + ") AND holder_server IS NOT NULL AND held_until > CURRENT_TIMESTAMP")) {
                        for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i).toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) holds.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                        }
                    } catch (Exception ignored) { }   // older schema without the hold columns
                }
            } catch (Exception ex) {
                dbUp = false;
            }
        }
        // A local backend can publish ServerLoad readiness without a network database.
        // The attempt must match and the stamp must be newer than this outage.
        if (!this.db.databaseConfigured() && !this.readyFile.isEmpty()) {
            try {
                java.nio.file.Path stamp = java.nio.file.Path.of(this.readyFile);
                String ready = java.nio.file.Files.readString(stamp).trim();
                String active = java.nio.file.Files.readString(stamp.resolveSibling("active")).trim();
                if (UUID.fromString(ready).toString().equals(active))
                    readyAt = java.nio.file.Files.getLastModifiedTime(stamp).toMillis();
            } catch (java.io.IOException | IllegalArgumentException ignored) { }
        }
        synchronized (this) {
            // After an outage the port answers before the server has finished starting. Wait for its ready stamp
            // (written when it finishes booting), or a fallback delay when it does not write one.
            this.pingFailures = reachable ? 0 : this.pingFailures + 1;
            if (!reachable && this.pingFailures < DOWN_AFTER_FAILURES) {
                reachable = this.backendReachable;   // not an outage yet
            } else if (!reachable) {
                if (this.downSince == 0L) this.downSince = now - 1000L * (DOWN_AFTER_FAILURES - 1);
                this.recoveredAt = 0L;
                this.backendReady = false;
            } else if (this.downSince != 0L) {
                if (this.recoveredAt == 0L) this.recoveredAt = now;
                if (readyAt >= this.downSince || this.recoveredAt - this.downSince < BLIP_MILLIS
                        || now - this.recoveredAt >= READY_FALLBACK_MILLIS) {
                    this.downSince = 0L;
                    this.backendReady = true;
                }
            } else {
                this.backendReady = true;
            }
            this.backendReachable = reachable;
            this.databaseUp = dbUp;
            this.heldElsewhere = Map.copyOf(holds);
        }
    }

    // ---- helpers ----

    private String holderOf(UUID id) {
        if (!this.db.databaseConfigured()) return null;
        try (Connection c = this.db.openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT holder_server FROM player_sync_state WHERE uuid=? "
                 + "AND holder_server IS NOT NULL AND held_until > CURRENT_TIMESTAMP")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean bypassed(Player player) {
        return this.bypass.contains(player.getUsername().toLowerCase(Locale.ROOT));
    }

    /** The server a new player is sent to first: their forced host's list, else the proxy's try order. */
    private String firstServer(Player player) {
        String host = player.getVirtualHost().map(a -> a.getHostString().toLowerCase(Locale.ROOT)).orElse("");
        List<String> order = this.proxy.getConfiguration().getForcedHosts().get(host);
        if (order == null || order.isEmpty()) order = this.proxy.getConfiguration().getAttemptConnectionOrder();
        return order.isEmpty() ? this.backend : order.get(0);
    }

    private Component screenFor(Block block) {
        return switch (block) {
            case BACKEND -> this.screens.starting();
            case DATABASE, HANDOFF -> this.screens.dataUnavailable();
            default -> this.screens.busy();
        };
    }

    private String switchMessage(Block block) {
        return switch (block) {
            case BACKEND -> "That server is starting up. Please try again in a moment.";
            case DATABASE, HANDOFF -> "Your player data is not ready yet. Please try again in a moment.";
            default -> "That server is busy. Please try again in a moment.";
        };
    }
}
