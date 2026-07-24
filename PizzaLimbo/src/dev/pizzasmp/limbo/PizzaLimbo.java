package dev.pizzasmp.limbo;

/*
 * PizzaLimbo is part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Released under the MIT License (see LICENSE). Provided AS IS, without warranty.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.io.BukkitObjectInputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

/**
 * PizzaLimbo v0.1 (alpha) — the holding server players are moved to during full-stop maintenance.
 *
 * Behaviour: a transferred player is frozen in place (can look around, cannot move, modify, drop,
 * or interact), shown the maintenance message in chat + a persistent hotbar line, and given a visual
 * copy of their inventory (read from the shared `limbo_snapshots` DB). When the SMP backend comes
 * back, the proxy sends them home (BungeeCord Connect -> smp). No native transfers — Velocity routes.
 *
 * v0.2 will rebuild the captured terrain (blocks_blob) so they appear frozen in their real scene
 * rather than on a void platform.
 */
public final class PizzaLimbo extends JavaPlugin implements Listener {

    private String dbUrl, dbUser, dbPass;
    private String probeHost; private int probePort;     // SMP backend liveness probe (local)
    private String smpServer;                            // proxy server name to send players back to
    private long graceMs;
    private Component chatMsg, hotbarMsg, maintenanceKick;

    private Location spawn;
    private final ConcurrentHashMap<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final AtomicBoolean smpDownSeen = new AtomicBoolean(false);
    private final AtomicLong smpUpSince = new AtomicLong(0L);
    private volatile long lastControlMs = 0L;     // last processed /limbomaint stop force-return signal
    private volatile long lastBrandingMs = System.currentTimeMillis();   // last processed branding-reload signal (stale ones ignored)
    // Per-world maintenance (/region on the SMP): worlds currently closed + each held player's origin
    // world. Players whose origin world is closed are HELD here even while the SMP is up, and are
    // returned the moment their world leaves the closed set.
    private volatile java.util.Set<String> closedWorlds = java.util.Set.of();
    private final java.util.Map<UUID, String> snapshotWorld = new java.util.concurrent.ConcurrentHashMap<>();

    /** True when this held player must stay in the limbo because their origin world is closed. */
    private boolean heldByClosedWorld(Player p) {
        String w = snapshotWorld.get(p.getUniqueId());
        return w != null && closedWorlds.contains(w);
    }
    private volatile long lastSmpReadyMs = 0L;    // most recent SMP "fully booted" signal seen in limbo_control
    private volatile long readyBaselineMs = 0L;   // smp_ready_at value captured when the SMP went down; a newer one = it's back
    private Component tabHeader, tabFooter;
    private static final long STRAY_HOLD_MS = 300_000L;  // direct-connects while SMP is up: release after 5m

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        // Force-register the JDBC driver in this plugin's classloader (Paper loads it via plugin.yml
        // libraries, but DriverManager won't auto-discover it without this). PNC does the same.
        try { Class.forName("org.mariadb.jdbc.Driver"); }
        catch (ClassNotFoundException ex) { getLogger().severe("MariaDB driver not found: " + ex.getMessage()); }
        String host = getConfig().getString("db.host", "127.0.0.1");
        int port = getConfig().getInt("db.port", 3306);
        String name = getConfig().getString("db.name", "smpcore");
        this.dbUser = getConfig().getString("db.user", "smpcore");
        this.dbPass = getConfig().getString("db.password", "");
        this.dbUrl = "jdbc:mariadb://" + host + ":" + port + "/" + name + "?useSSL=false&allowPublicKeyRetrieval=true";
        this.probeHost = getConfig().getString("smp.probe-host", "127.0.0.1");
        this.probePort = getConfig().getInt("smp.probe-port", 25566);
        this.smpServer = getConfig().getString("smp.server-name", "smp");
        this.graceMs = Math.max(0, getConfig().getInt("smp.return-grace-seconds", 8)) * 1000L;
        // Load the SHARED branding.yml (same file the SMP's PNC uses) so EVERY player-facing limbo
        // surface (tab, hold message, hotbar, kick screen) reflects the active brand. Re-runs live
        // whenever the SMP's /branding set bumps limbo_control.branding_reload_at.
        reloadBranding();
        this.lastControlMs = System.currentTimeMillis();   // ignore any stale force-return flag at startup

        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world != null) {
            // CRITICAL: keep the limbo's own spawn FAR from any real player coordinates and stop it
            // pinning spawn chunks — otherwise a player whose real location overlaps the spawn area would
            // see the limbo's cached VOID chunk instead of the copied region file.
            // spawnChunkRadius has no typed GameRule constant in this API version — set it via command.
            try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule spawnChunkRadius 0"); } catch (Throwable ignored) {}
            int sx = 8_000_000, sz = 8_000_000;
            world.setSpawnLocation(sx, 100, sz);
            for (int x = sx - 2; x <= sx + 2; x++) for (int z = sz - 2; z <= sz + 2; z++) {
                world.getBlockAt(x, 99, z).setType(Material.BARRIER, false);
            }
            this.spawn = new Location(world, sx + 0.5, 100, sz + 0.5);
            try { world.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false); } catch (Throwable ignored) {}
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::probeAndMaybeReturn, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshHud, 20L, 30L);
        getLogger().info("PizzaLimbo v0.1 ready. SMP probe " + probeHost + ":" + probePort + " -> server '" + smpServer + "'.");
    }

    // ---- hold + render the transferred player ----
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.joinMessage(null);
        joinedAt.put(p.getUniqueId(), System.currentTimeMillis());
        // Default placement on the void platform; refined to the captured location once the DB read returns.
        if (spawn != null) p.teleport(spawn);
        p.setGameMode(GameMode.ADVENTURE);
        p.setInvulnerable(true);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setFoodLevel(20);
        // The hold UI (chat msg + branded tab) is applied inside loadAndApplySnapshot ONLY for real
        // transferred players (those with a snapshot). A fresh joiner with no snapshot who lands here
        // because the SMP is down (maintenance) is denied with the maintenance message instead.
        loadAndApplySnapshot(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        joinedAt.remove(e.getPlayer().getUniqueId());
        snapshotWorld.remove(e.getPlayer().getUniqueId());
    }

    private void loadAndApplySnapshot(Player p) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Location captured = null;
            ItemStack[] inv = null, ender = null;
            byte[] blocks = null;
            try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass);
                 PreparedStatement ps = c.prepareStatement(
                     // Freshness gate: the SMP now snapshots EVERY online player on ANY shutdown, so a
                     // snapshot merely existing no longer proves this player was just transferred. Only
                     // honor recent captures; older ones = fresh joiner -> maintenance-kick path below.
                     "SELECT world,x,y,z,yaw,pitch,inv_blob,ender_blob,blocks_blob FROM limbo_snapshots "
                     + "WHERE uuid=? AND captured_at > (NOW() - INTERVAL 15 MINUTE)")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                        captured = new Location(w, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                            rs.getFloat("yaw"), rs.getFloat("pitch"));
                        inv = deserialize(rs.getBytes("inv_blob"));
                        ender = deserialize(rs.getBytes("ender_blob"));
                        blocks = rs.getBytes("blocks_blob");
                        // Remember which SMP world they came from: per-world maintenance holds them
                        // here while that world is closed and releases them when it reopens.
                        String origin = rs.getString("world");
                        if (origin != null) snapshotWorld.put(id, origin);
                    }
                }
                // Fallback/refresh: prefer the player's LIVE inventory from PNC's player_sync_state
                // (kept current for every online player), so a held player always sees their real
                // inventory even when the limbo_snapshot is stale or was captured near-empty. Same
                // BukkitObjectStream format as inv_blob, so deserialize() reads it directly.
                try (PreparedStatement ps2 = c.prepareStatement(
                        "SELECT inventory_blob, enderchest_blob FROM player_sync_state WHERE uuid=?")) {
                    ps2.setString(1, id.toString());
                    try (ResultSet rs2 = ps2.executeQuery()) {
                        if (rs2.next()) {
                            ItemStack[] liveInv = deserialize(rs2.getBytes("inventory_blob"));
                            ItemStack[] liveEnder = deserialize(rs2.getBytes("enderchest_blob"));
                            if (liveInv != null) inv = liveInv;
                            if (liveEnder != null) ender = liveEnder;
                        }
                    }
                }
            } catch (Exception ex) {
                getLogger().warning("snapshot load failed for " + p.getName() + ": " + ex.getMessage());
            }
            final Location loc = captured; final ItemStack[] fInv = inv, fEnder = ender; final byte[] fBlocks = blocks;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                if (loc == null) {
                    // No snapshot = NOT a transferred player. They fell here via the proxy's try-list.
                    // If the SMP is down (maintenance) deny them with the maintenance message; if it's
                    // up, this is a stray connect — send them straight to the SMP.
                    if (!tcpOpen(probeHost, probePort, 1000)) {
                        p.kick(maintenanceKick);
                    } else {
                        connectToSmp(p);
                    }
                    return;
                }
                if (loc.getWorld() != null) {
                    // Terrain comes from the region files the SMP copied in. Force a fresh load of the
                    // landing chunk so we don't show a stale void chunk from a previous hold.
                    World lw = loc.getWorld();
                    int tcx = loc.getBlockX() >> 4, tcz = loc.getBlockZ() >> 4;
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        if (lw.isChunkLoaded(tcx + dx, tcz + dz)) lw.unloadChunk(tcx + dx, tcz + dz, false);
                    }
                    p.teleport(loc);
                }
                // Apply the visual inventory copy. This is safe from achievement-toast spam because the SMP
                // copies the player's advancements into the limbo first, so they're already earned here.
                if (fInv != null) p.getInventory().setContents(fInv);     // visual only; SMP keeps the real inv
                if (fEnder != null) p.getEnderChest().setContents(fEnder);
                // Real transferred player — now show the hold UI.
                p.sendMessage(chatMsg);
                p.sendPlayerListHeaderAndFooter(tabHeader, tabFooter);
            });
        });
    }

    // ---- freeze + lockdown ----
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ()) {
            Location to = e.getFrom().clone();
            to.setYaw(e.getTo().getYaw());
            to.setPitch(e.getTo().getPitch());     // looking around stays allowed
            e.setTo(to);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onBreak(BlockBreakEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPlace(BlockPlaceEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDrop(PlayerDropItemEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInteract(PlayerInteractEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onSwap(PlayerSwapHandItemsEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvClick(InventoryClickEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvOpen(InventoryOpenEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onHunger(FoodLevelChangeEvent e) { e.setCancelled(true); }

    private void refreshHud() {
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(hotbarMsg);
    }

    // ---- auto-return ----
    // One poll of the shared control row: refreshes the SMP "ready" signal (smp_ready_at) used for the
    // fast return path, and handles the /limbomaint stop force-return signal (return_all_at).
    private void pollControl() {
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = c.prepareStatement(
                "SELECT UNIX_TIMESTAMP(return_all_at)*1000, UNIX_TIMESTAMP(smp_ready_at)*1000, "
                + "UNIX_TIMESTAMP(branding_reload_at)*1000, closed_worlds FROM limbo_control WHERE id=1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long ret = rs.getLong(1); boolean retNull = rs.wasNull();
                    long ready = rs.getLong(2); boolean readyNull = rs.wasNull();
                    long brand = rs.getLong(3); boolean brandNull = rs.wasNull();
                    String closedCsv = rs.getString(4);
                    if (!readyNull) lastSmpReadyMs = ready;
                    // Per-world maintenance: update the closed set; release anyone whose world reopened.
                    java.util.Set<String> newClosed = (closedCsv == null || closedCsv.isBlank())
                        ? java.util.Set.of()
                        : new java.util.HashSet<>(java.util.Arrays.asList(closedCsv.split(",")));
                    if (!newClosed.equals(closedWorlds)) {
                        java.util.Set<String> reopened = new java.util.HashSet<>(closedWorlds);
                        reopened.removeAll(newClosed);
                        closedWorlds = newClosed;
                        if (!reopened.isEmpty()) {
                            getLogger().info("Region(s) reopened: " + reopened + " — releasing held players.");
                            Bukkit.getScheduler().runTask(this, () -> {
                                for (Player p : Bukkit.getOnlinePlayers()) {
                                    String w = snapshotWorld.get(p.getUniqueId());
                                    if (w != null && reopened.contains(w)) connectToSmp(p);
                                }
                            });
                        }
                    }
                    if (!retNull && ret > lastControlMs) {
                        lastControlMs = ret;
                        if (!Bukkit.getOnlinePlayers().isEmpty()) {
                            getLogger().info("Force-return signal received — sending players back to '" + smpServer + "'.");
                            Bukkit.getScheduler().runTask(this, this::returnAll);
                        }
                    }
                    // Live rebrand: /branding set on the SMP bumped the signal -> re-read branding.yml
                    // and re-push the tab header to everyone held here (no limbo restart).
                    if (!brandNull && brand > lastBrandingMs) {
                        lastBrandingMs = brand;
                        Bukkit.getScheduler().runTask(this, () -> {
                            reloadBranding();
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                p.sendPlayerListHeaderAndFooter(tabHeader, tabFooter);
                            }
                            // Pause-menu (Esc) title lives in a datapack that apply-branding.sh
                            // rewrote on disk — reload datapacks so it applies live too.
                            try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload"); } catch (Throwable ignored2) {}
                        });
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void probeAndMaybeReturn() {
        pollControl();
        boolean up = tcpOpen(probeHost, probePort, 1500);
        long now = System.currentTimeMillis();
        if (!up) {
            // SMP just went offline — remember the ready timestamp so only a NEWER one means it's back.
            if (smpDownSeen.compareAndSet(false, true)) readyBaselineMs = lastSmpReadyMs;
            smpUpSince.set(0L);
            return;
        }
        if (smpDownSeen.get()) {
            // Primary fast path: the SMP stamped a fresh smp_ready_at after rebooting -> it's actually ready.
            if (lastSmpReadyMs > readyBaselineMs) {
                getLogger().info("SMP ready signal observed — returning held players to '" + smpServer + "'.");
                Bukkit.getScheduler().runTask(this, this::returnAll);
                return;
            }
            // Fallback: the port has been reachable for the grace window (covers a missing/failed DB signal).
            smpUpSince.compareAndSet(0L, now);
            if (now - smpUpSince.get() >= graceMs) {
                getLogger().info("SMP port up >= grace — returning held players (fallback).");
                Bukkit.getScheduler().runTask(this, this::returnAll);
            }
        }
        // Stray direct-connects (SMP never went down) get released after a long hold.
        final long n = now;
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (heldByClosedWorld(p)) continue;   // per-world maintenance hold: not a stray
                Long t = joinedAt.get(p.getUniqueId());
                if (t != null && n - t > STRAY_HOLD_MS) connectToSmp(p);
            }
        });
    }

    private void returnAll() {
        // Players whose origin world is under per-world maintenance stay held until it reopens.
        for (Player p : Bukkit.getOnlinePlayers()) { if (!heldByClosedWorld(p)) connectToSmp(p); }
        smpDownSeen.set(false);
        smpUpSince.set(0L);
        readyBaselineMs = lastSmpReadyMs;   // don't re-trigger on the same signal
    }

    private void connectToSmp(Player p) {
        try {
            // No on-screen title: the proxy reconfiguration flash is unavoidable on 1.20.2+, but we
            // don't add to it. Player is sent straight back to the SMP (same overworld dimension).
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(smpServer);
            p.sendPluginMessage((Plugin) this, "BungeeCord", bytes.toByteArray());
        } catch (Exception ex) {
            getLogger().warning("return failed for " + p.getName() + ": " + ex.getMessage());
        }
    }

    // Rebuild the captured scene around (cx,cy,cz): palette + relative non-air blocks.
    private void applyScene(World w, int cx, int cy, int cz, byte[] blob) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(new ByteArrayInputStream(blob))) {
            int pn = in.readInt();
            org.bukkit.block.data.BlockData[] palette = new org.bukkit.block.data.BlockData[pn];
            for (int i = 0; i < pn; i++) {
                String s = in.readUTF();
                try { palette[i] = Bukkit.createBlockData(s); }
                catch (Exception ex) { palette[i] = Material.AIR.createBlockData(); }
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int dx = in.readShort(), dy = in.readShort(), dz = in.readShort(), idx = in.readInt();
                if (idx >= 0 && idx < pn && palette[idx] != null) {
                    w.getBlockAt(cx + dx, cy + dy, cz + dz).setBlockData(palette[idx], false);
                }
            }
        } catch (Exception ex) {
            getLogger().warning("scene rebuild failed: " + ex.getMessage());
        }
    }

    private static ItemStack[] deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream in = new BukkitObjectInputStream(bis)) {
            int n = in.readInt();
            ItemStack[] arr = new ItemStack[Math.max(0, n)];
            for (int i = 0; i < arr.length; i++) {
                Object o = in.readObject();
                arr[i] = (o instanceof ItemStack) ? (ItemStack) o : null;
            }
            return arr;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean tcpOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // (Re)loads the shared branding.yml and rebuilds every player-facing text surface. Called at
    // enable and whenever the SMP's /branding set bumps limbo_control.branding_reload_at. The
    // refreshHud timer re-pushes the tab header to online players, so new fields apply within ~2s.
    private void reloadBranding() {
        String brandDisplay = "ExampleSMP"; String region = "NA-East"; TextColor brandColor = NamedTextColor.AQUA;
        try {
            String bpath = getConfig().getString("branding.file",
                "plugins/PizzaNetworkCore/branding.yml");
            org.bukkit.configuration.file.YamlConfiguration b =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(bpath));
            String active = b.getString("active", "example");
            brandDisplay = b.getString("profiles." + active + ".display", "ExampleSMP");
            region = b.getString("profiles." + active + ".region", "NA-East");
            String hex = b.getString("profiles." + active + ".colors.primary", "00BFFF");
            try { brandColor = TextColor.color(Integer.parseInt(hex, 16)); } catch (Exception ignored) {}
            getLogger().info("[brand] limbo branded as '" + brandDisplay + "' (#" + hex + ")");
        } catch (Exception ex) {
            getLogger().warning("[brand] branding.yml read failed, using ExampleSMP default: " + ex.getMessage());
        }
        // brandTag = the bold brand name in the brand accent colour; the basis for every limbo surface.
        final TextColor accent = brandColor;
        final String brandName = brandDisplay;
        java.util.function.Supplier<Component> brandTag = () ->
            Component.text(brandName, accent).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);

        this.tabHeader = brandTag.get()
            .append(Component.text(" " + region, NamedTextColor.GRAY))
            .append(Component.text("\nMaintenance", NamedTextColor.DARK_GRAY));
        this.tabFooter = getConfig().isString("messages.tab-footer")
            ? legacy(getConfig().getString("messages.tab-footer"))
            : Component.text("You will be returned automatically\nwhen the region restarts.", NamedTextColor.GRAY);
        // Branded defaults; messages.* config keys still override if explicitly set. No em-dashes.
        this.chatMsg = getConfig().isString("messages.text")
            ? legacy(getConfig().getString("messages.text"))
            : brandTag.get().append(Component.text(" Maintenance", accent))
                .append(Component.text("\nYour region is undergoing maintenance. Do not teleport or your"
                    + " location will be lost. You will be returned automatically when your region"
                    + " restarts.", NamedTextColor.GRAY));
        this.hotbarMsg = getConfig().isString("messages.hotbar")
            ? legacy(getConfig().getString("messages.hotbar"))
            : Component.text("Region under maintenance. You will be returned automatically.", NamedTextColor.GRAY);
        this.maintenanceKick = getConfig().isString("messages.kick")
            ? legacy(getConfig().getString("messages.kick"))
            : brandTag.get().append(Component.text(" Maintenance", accent))
                .append(Component.text("\n\nYour region is under maintenance.", NamedTextColor.GRAY))
                .append(Component.text("\nPlease try again in a few minutes.", NamedTextColor.GRAY));
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }
}
