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
import java.util.function.BiPredicate;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import dev.pizzasmp.common.scheduler.PlatformScheduler;

/**
 * PizzaLimbo v0.1 (alpha) — the holding server players are moved to during full-stop maintenance.
 *
 * Behaviour: a transferred player arrives in a protected copy of the SMP hub, may walk around while
 * maintenance runs, and sees a visual inventory copy read from `limbo_snapshots`. The SMP remains
 * authoritative for player data. When it is ready, Velocity returns the player to the SMP.
 */
public final class PizzaLimbo extends JavaPlugin implements Listener {

    private String dbUrl, dbUser, dbPass;
    private String probeHost; private int probePort;     // SMP backend liveness probe (local)
    private String smpServer;                            // proxy server name to send players back to
    private long graceMs;
    private volatile BrandingTexts brandingTexts;

    private Location spawn;
    private org.bukkit.configuration.file.FileConfiguration hubConfig;
    private final ConcurrentHashMap<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> pendingReturns = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, HeldPlayerState> heldPlayerStates = new ConcurrentHashMap<>();
    private final AtomicBoolean smpDownSeen = new AtomicBoolean(false);
    private final AtomicBoolean probeInProgress = new AtomicBoolean(false);
    private final AtomicLong smpUpSince = new AtomicLong(0L);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(true);
    private final AtomicLong lifecycleGeneration = new AtomicLong(0L);
    private final AtomicLong brandingReloadGeneration = new AtomicLong(0L);
    private volatile long lastControlMs = 0L;     // last processed /limbomaint stop force-return signal
    private volatile long lastBrandingMs = System.currentTimeMillis();   // last processed branding-reload signal (stale ones ignored)
    // Per-world maintenance (/region on the SMP): worlds currently closed + each held player's origin
    // world. Players whose origin world is closed are HELD here even while the SMP is up, and are
    // returned the moment their world leaves the closed set.
    private volatile java.util.Set<String> closedWorlds = java.util.Set.of();
    private final java.util.Map<UUID, String> snapshotWorld = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile long lastSmpReadyMs = 0L;    // most recent SMP "fully booted" signal seen in limbo_control
    private volatile long readyBaselineMs = 0L;   // smp_ready_at value captured when the SMP went down; a newer one = it's back
    private static final long STRAY_HOLD_MS = 300_000L;  // direct-connects while SMP is up: release after 5m
    // Maintenance ambiance: loop C418 "mellohi" directly to every held player (emitted at their own
    // location => constant full volume everywhere). Per-player next-play timestamp so each player hears
    // a clean continuous loop from the moment they arrive, with no global-phase overlap.
    private final java.util.Map<UUID, Long> mellohiNext = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<UUID> rescueTeleports = ConcurrentHashMap.newKeySet();
    private static final long MELLOHI_LOOP_MS = 96_000L;   // mellohi track length (1:36)
    private static final String MELLOHI_SOUND = "minecraft:music_disc.mellohi";

    private static final class HeldPlayerState {
        final GameMode mode;
        final boolean allowFlight;
        final boolean flying;
        final boolean invulnerable;
        final int food;
        final float saturation;
        final Vector velocity;

        HeldPlayerState(Player player) {
            this.mode = player.getGameMode();
            this.allowFlight = player.getAllowFlight();
            this.flying = player.isFlying();
            this.invulnerable = player.isInvulnerable();
            this.food = player.getFoodLevel();
            this.saturation = player.getSaturation();
            this.velocity = player.getVelocity().clone();
        }

        void restore(Player player) {
            this.restore(player, true);
        }

        void restore(Player player, boolean restoreVelocity) {
            player.setGameMode(this.mode);
            player.setInvulnerable(this.invulnerable);
            player.setAllowFlight(this.allowFlight);
            player.setFlying(this.allowFlight && this.flying);
            player.setFoodLevel(this.food);
            player.setSaturation(this.saturation);
            if (restoreVelocity) player.setVelocity(this.velocity);
        }
    }

    private record BrandingTexts(Component tabHeader, Component tabFooter, Component chat,
                                 Component hotbar, Component maintenanceKick) { }

    private record LimboControlSnapshot(Long returnAllAt, Long smpReadyAt, Long brandingReloadAt,
                                        String closedWorldsCsv) { }

    @Override
    public void onEnable() {
        shuttingDown.set(false);
        lifecycleGeneration.incrementAndGet();
        this.saveDefaultConfig();
        this.loadHubConfig();
        // Force-register the JDBC driver in this plugin's classloader (Paper loads it via plugin.yml
        // libraries, but DriverManager won't auto-discover it without this). PNC does the same.
        try { Class.forName("org.mariadb.jdbc.Driver"); }
        catch (ClassNotFoundException ex) { getLogger().severe("MariaDB driver not found: " + ex.getMessage()); }
        String host = getConfig().getString("db.host", "127.0.0.1");
        int port = getConfig().getInt("db.port", 3306);
        String name = getConfig().getString("db.name", "smpcore");
        this.dbUser = getConfig().getString("db.user", "");
        this.dbPass = getConfig().getString("db.password", "");
        this.dbUrl = "jdbc:mariadb://" + host + ":" + port + "/" + name + "?useSSL=false&allowPublicKeyRetrieval=true";
        this.probeHost = getConfig().getString("smp.probe-host", "127.0.0.1");
        this.probePort = getConfig().getInt("smp.probe-port", 25566);
        this.smpServer = getConfig().getString("smp.server-name", "smp");
        this.graceMs = Math.max(0, getConfig().getInt("smp.return-grace-seconds", 8)) * 1000L;
        // Load the SHARED branding.yml (same file the SMP's PNC uses) so EVERY player-facing limbo
        // surface (tab, hold message, hotbar, kick screen) reflects the active brand. Re-runs live
        // whenever the SMP's /branding set bumps limbo_control.branding_reload_at.
        reloadBranding(null);
        this.lastControlMs = System.currentTimeMillis();   // ignore any stale force-return flag at startup

        World world = Bukkit.getWorld("limbo");
        if (world == null) {
            getLogger().severe("Required world 'limbo' is not loaded; disabling PizzaLimbo.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (world != null) {
            this.spawn = this.configuredHubSpawn(world);
            world.setSpawnLocation(this.spawn.getBlockX(), this.spawn.getBlockY(), this.spawn.getBlockZ());
            try { world.setGameRule(org.bukkit.GameRule.ANNOUNCE_ADVANCEMENTS, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.DO_MOB_LOOT, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.DO_FIRE_TICK, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false); } catch (Throwable ignored) {}
            try { world.setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, 0); } catch (Throwable ignored) {}
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        Bukkit.getPluginManager().registerEvents(this, this);
        PlatformScheduler.asyncRepeating(this, this::probeAndMaybeReturn, 20L, 20L);
        PlatformScheduler.globalRepeating(this, this::refreshHud, 20L, 30L);
        PlatformScheduler.globalRepeating(this, this::retryPendingReturns, 100L, 100L);
        PlatformScheduler.globalRepeating(this, this::tickMellohi, 20L, 20L);   // maintenance music loop
        getLogger().info("PizzaLimbo hub ready. SMP probe " + probeHost + ":" + probePort + " -> server '" + smpServer + "'.");
    }

    @Override
    public void onDisable() {
        shuttingDown.set(true);
        lifecycleGeneration.incrementAndGet();
        probeInProgress.set(false);
        brandingReloadGeneration.incrementAndGet();
        // Folia blocker: there is no proven entity-scheduler drain barrier once disable begins.
        // Scheduling these restores here is not reliable because plugin-owned tasks may be cancelled
        // before they run. Safe design: persist original state before mutating players, then have an
        // independent lifecycle owner restore each record on that player's entity scheduler and clear
        // it only after success. Keep this Paper-era best-effort restore until that owner exists.
        for (Player player : Bukkit.getOnlinePlayers()) {
            HeldPlayerState state = heldPlayerStates.get(player.getUniqueId());
            if (state != null) state.restore(player);
        }
        pendingReturns.clear();
        heldPlayerStates.clear();
        joinedAt.clear();
        snapshotWorld.clear();
        mellohiNext.clear();
        rescueTeleports.clear();
    }

    private boolean isActive(long generation) {
        return !shuttingDown.get() && lifecycleGeneration.get() == generation;
    }

    private void loadHubConfig() {
        java.io.File file = new java.io.File(getDataFolder(), "hub.yml");
        if (!file.isFile()) saveResource("hub.yml", false);
        this.hubConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
    }

    private Location configuredHubSpawn(World world) {
        Location fallback = world.getSpawnLocation();
        double x = hubConfig.getDouble("spawn.x", fallback.getX() + 0.5D);
        double y = hubConfig.getDouble("spawn.y", fallback.getY());
        double z = hubConfig.getDouble("spawn.z", fallback.getZ() + 0.5D);
        float yaw = (float) hubConfig.getDouble("spawn.yaw", fallback.getYaw());
        float pitch = (float) hubConfig.getDouble("spawn.pitch", fallback.getPitch());
        return new Location(world, x, y, z, yaw, pitch);
    }

    // ---- hold + render the transferred player ----
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        long generation = lifecycleGeneration.get();
        if (!isActive(generation)) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        e.joinMessage(null);
        heldPlayerStates.put(id, new HeldPlayerState(p));
        joinedAt.put(id, System.currentTimeMillis());
        // All held players share the protected maintenance hub. Their original position is retained only
        // in the snapshot so the SMP can restore it after Velocity returns them.
        if (spawn != null) {
            p.teleportAsync(spawn).whenComplete((teleported, failure) -> {
                if (!isActive(generation)) return;
                PlatformScheduler.entityNow(this, p, () -> {
                    if (!isActive(generation)) return;
                    if (!p.isOnline()) return;
                    if (failure != null || !Boolean.TRUE.equals(teleported)) {
                        getLogger().warning("Could not move a limbo player to the hub spawn.");
                    }
                    applyHeldState(p);
                }, () -> {
                    if (!isActive(generation)) return;
                    joinedAt.remove(id);
                    heldPlayerStates.remove(id);
                });
            });
            return;
        }
        applyHeldState(p);
    }

    private void applyHeldState(Player p) {
        if (!p.isOnline()) return;
        p.setGameMode(GameMode.ADVENTURE);
        p.setInvulnerable(true);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setFoodLevel(20);
        // The hold UI (chat msg + branded tab) is applied inside loadAndApplySnapshot ONLY for real
        // transferred players (those with a snapshot). A fresh joiner with no snapshot who lands here
        // because the SMP is down (maintenance) is denied with the maintenance message instead.
        loadAndApplySnapshot(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        HeldPlayerState heldState = heldPlayerStates.remove(id);
        if (heldState != null) {
            // Quit still runs on the player's owner; normalize the limbo profile before it is saved.
            // Do not restore the pre-teleport velocity at the hub spawn.
            heldState.restore(player, false);
        }
        joinedAt.remove(id);
        pendingReturns.remove(id);
        snapshotWorld.remove(id);
        mellohiNext.remove(id);
        rescueTeleports.remove(id);
    }

    /**
     * Loop the maintenance music. Runs from the global scheduler and plays on each player's entity thread:
     * for each online (held) player,
     * (re)start "mellohi" the moment they have no active track or their track has run its length. The
     * disc is emitted at the player's OWN location, so it stays at full volume no matter where they are
     * ("same volume from everywhere, directly to the player"). Per-player timing avoids overlap.
     */
    private void tickMellohi() {
        long generation = lifecycleGeneration.get();
        if (!isActive(generation)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlatformScheduler.entityNow(this, p, () -> {
                if (!isActive(generation) || !p.isOnline()) return;
                UUID id = p.getUniqueId();
                long now = System.currentTimeMillis();
                Long next = mellohiNext.get(id);
                if (next != null && now < next) return;
                try { p.playSound(p.getLocation(), MELLOHI_SOUND, org.bukkit.SoundCategory.RECORDS, 1.0f, 1.0f); }
                catch (Throwable ignored) {}
                mellohiNext.put(id, now + MELLOHI_LOOP_MS);
            }, null);
        }
    }

    private void loadAndApplySnapshot(Player p) {
        UUID id = p.getUniqueId();
        String playerName = p.getName();
        long generation = lifecycleGeneration.get();
        if (!isActive(generation)) return;
        PlatformScheduler.asyncNow(this, () -> {
            if (!isActive(generation)) return;
            boolean hasSnapshot = false;
            byte[] inventorySnapshot = null, enderSnapshot = null;
            String originWorld = null;
            try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass);
                 PreparedStatement ps = c.prepareStatement(
                     // Freshness gate: the SMP now snapshots EVERY online player on ANY shutdown, so a
                     // snapshot merely existing no longer proves this player was just transferred. Only
                     // honor recent captures; older ones = fresh joiner -> maintenance-kick path below.
                     "SELECT world,inv_blob,ender_blob FROM limbo_snapshots "
                     + "WHERE uuid=? AND captured_at > (NOW() - INTERVAL 15 MINUTE)")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        hasSnapshot = true;
                        inventorySnapshot = rs.getBytes("inv_blob");
                        enderSnapshot = rs.getBytes("ender_blob");
                        // Remember which SMP world they came from: per-world maintenance holds them
                        // here while that world is closed and releases them when it reopens.
                        originWorld = rs.getString("world");
                    }
                }
            } catch (Exception ex) {
                if (isActive(generation)) {
                    getLogger().warning("snapshot load failed for " + playerName + ": " + ex.getMessage());
                }
            }
            if (!isActive(generation)) return;
            final boolean captured = hasSnapshot;
            final byte[] fInventorySnapshot = inventorySnapshot, fEnderSnapshot = enderSnapshot;
            final String fOriginWorld = originWorld;
            final boolean smpReachable = !captured && tcpOpen(probeHost, probePort, 1000);
            if (!isActive(generation)) return;
            PlatformScheduler.entityNow(this, p, () -> {
                if (!isActive(generation)) return;
                if (!p.isOnline()) return;
                if (fOriginWorld != null) snapshotWorld.put(id, fOriginWorld);
                if (!captured) {
                    // No snapshot = NOT a transferred player. They fell here via the proxy's try-list.
                    // If the SMP is down (maintenance) deny them with the maintenance message; if it's
                    // up, this is a stray connect — send them straight to the SMP.
                    if (!smpReachable) {
                        p.kick(brandingTexts.maintenanceKick());
                    } else {
                        connectToSmp(p);
                    }
                    return;
                }
                // Apply the visual inventory copy. The SMP retains the authoritative inventory while this
                // server prevents item movement, drops, and interaction.
                ItemStack[] fInv = deserialize(fInventorySnapshot);
                ItemStack[] fEnder = deserialize(fEnderSnapshot);
                if (fInv != null) p.getInventory().setContents(fInv);     // visual only; SMP keeps the real inv
                if (fEnder != null) p.getEnderChest().setContents(fEnder);
                // Real transferred player — now show the hold UI.
                BrandingTexts texts = brandingTexts;
                p.sendMessage(texts.chat());
                p.sendPlayerListHeaderAndFooter(texts.tabHeader(), texts.tabFooter());
                showMaintenanceTitle(p);
            }, () -> {
                if (isActive(generation)) snapshotWorld.remove(id);
            });
        });
    }

    private void showMaintenanceTitle(Player p) {
        p.showTitle(Title.title(
            Component.text("Under maintenance", NamedTextColor.AQUA),
            Component.text("Returning shortly", NamedTextColor.GRAY),
            Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(1))));
    }

    // ---- protected hub ----
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (shuttingDown.get()) return;
        if (e.getTo() == null) return;
        Location to = e.getTo();
        if (this.shouldRescueFromVoid(to) || this.outsideHubBorder(to)) {
            Location rescue = spawn.clone();
            rescue.setYaw(to.getYaw());
            rescue.setPitch(to.getPitch());
            Player player = e.getPlayer();
            UUID id = player.getUniqueId();
            e.setCancelled(true);
            if (!rescueTeleports.add(id)) return;
            try {
                long generation = lifecycleGeneration.get();
                player.teleportAsync(rescue).whenComplete((teleported, failure) -> {
                    if (!isActive(generation)) return;
                    PlatformScheduler.entityNow(this, player, () -> {
                        if (isActive(generation)) rescueTeleports.remove(id);
                    }, () -> {
                        if (isActive(generation)) rescueTeleports.remove(id);
                    });
                });
            } catch (RuntimeException ex) {
                rescueTeleports.remove(id);
                getLogger().warning("Could not start a limbo hub rescue teleport.");
            }
        }
    }

    private boolean shouldRescueFromVoid(Location location) {
        return spawn != null && location.getWorld() == spawn.getWorld()
            && location.getY() <= hubConfig.getDouble("void-rescue-y", location.getWorld().getMinHeight());
    }

    private boolean outsideHubBorder(Location location) {
        if (spawn == null || location.getWorld() != spawn.getWorld()) return false;
        double radius = hubConfig.getDouble("spawn.border-radius", 0.0D);
        if (radius <= 0.0D) return false;
        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        return dx * dx + dz * dz > radius * radius;
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onBreak(BlockBreakEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPlace(BlockPlaceEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDrop(PlayerDropItemEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInteract(PlayerInteractEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onSwap(PlayerSwapHandItemsEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvClick(InventoryClickEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvDrag(InventoryDragEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInvOpen(InventoryOpenEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPickup(EntityPickupItemEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onEntityInteract(PlayerInteractEntityEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onEntityPlace(EntityPlaceEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onHangingPlace(HangingPlaceEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onHangingBreak(HangingBreakEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onEntityChangeBlock(EntityChangeBlockEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onHunger(FoodLevelChangeEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBucketEmpty(PlayerBucketEmptyEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBucketFill(PlayerBucketFillEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onCreatureSpawn(CreatureSpawnEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onEntityExplode(EntityExplodeEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBlockExplode(BlockExplodeEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBlockBurn(BlockBurnEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBlockFade(BlockFadeEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBlockFromTo(BlockFromToEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBlockIgnite(BlockIgniteEvent e) { e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("Commands are unavailable during maintenance.", NamedTextColor.RED));
    }

    private void refreshHud() {
        long generation = lifecycleGeneration.get();
        if (!isActive(generation)) return;
        Component message = brandingTexts.hotbar();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlatformScheduler.entityNow(this, p, () -> {
                if (isActive(generation) && p.isOnline()) p.sendActionBar(message);
            }, null);
        }
    }

    // ---- auto-return ----
    // SQL results cross to the global region as immutable data; that scheduler owns control transitions.
    private LimboControlSnapshot pollControl(long generation) {
        if (!isActive(generation)) return null;
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = c.prepareStatement(
                "SELECT UNIX_TIMESTAMP(return_all_at)*1000, UNIX_TIMESTAMP(smp_ready_at)*1000, "
                + "UNIX_TIMESTAMP(branding_reload_at)*1000, closed_worlds FROM limbo_control WHERE id=1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!isActive(generation) || !rs.next()) return null;
                long returnAllAt = rs.getLong(1);
                Long fReturnAllAt = rs.wasNull() ? null : returnAllAt;
                long smpReadyAt = rs.getLong(2);
                Long fSmpReadyAt = rs.wasNull() ? null : smpReadyAt;
                long brandingReloadAt = rs.getLong(3);
                Long fBrandingReloadAt = rs.wasNull() ? null : brandingReloadAt;
                return new LimboControlSnapshot(fReturnAllAt, fSmpReadyAt, fBrandingReloadAt, rs.getString(4));
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void probeAndMaybeReturn() {
        long generation = lifecycleGeneration.get();
        if (!isActive(generation) || !probeInProgress.compareAndSet(false, true)) return;
        try {
            LimboControlSnapshot control = pollControl(generation);
            boolean up = tcpOpen(probeHost, probePort, 1500);
            if (!isActive(generation)) {
                probeInProgress.set(false);
                return;
            }
            long now = System.currentTimeMillis();
            PlatformScheduler.TaskHandle task = PlatformScheduler.globalNow(this, () -> {
                try {
                    if (isActive(generation)) processProbeSnapshot(generation, control, up, now);
                } finally {
                    probeInProgress.set(false);
                }
            });
            if (!task.wasAccepted()) {
                probeInProgress.set(false);
                getLogger().warning("Could not schedule Limbo control-state processing (scheduler rejected task).");
            }
        } catch (RuntimeException ex) {
            probeInProgress.set(false);
            getLogger().warning("Could not schedule Limbo control-state processing.");
        }
    }

    private void processProbeSnapshot(long generation, LimboControlSnapshot control, boolean up, long now) {
        if (!isActive(generation)) return;
        applyControlSnapshot(generation, control);
        if (!isActive(generation)) return;
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
                returnAll();
                return;
            }
            // Fallback: the port has been reachable for the grace window (covers a missing/failed DB signal).
            smpUpSince.compareAndSet(0L, now);
            if (now - smpUpSince.get() >= graceMs) {
                getLogger().info("SMP port up >= grace — returning held players (fallback).");
                returnAll();
            }
        }
        // Stray direct-connects (SMP never went down) get released after a long hold.
        for (Player p : Bukkit.getOnlinePlayers()) {
            connectToSmp(p, (id, origin) -> {
                if (origin != null && closedWorlds.contains(origin)) return false;
                Long joined = joinedAt.get(id);
                return joined != null && now - joined > STRAY_HOLD_MS;
            });
        }
    }

    private void applyControlSnapshot(long generation, LimboControlSnapshot control) {
        if (!isActive(generation) || control == null) return;
        if (control.smpReadyAt() != null) lastSmpReadyMs = control.smpReadyAt();

        java.util.Set<String> newClosed = (control.closedWorldsCsv() == null || control.closedWorldsCsv().isBlank())
            ? java.util.Set.of()
            : java.util.Set.copyOf(java.util.Arrays.asList(control.closedWorldsCsv().split(",")));
        if (!newClosed.equals(closedWorlds)) {
            java.util.Set<String> reopened = new java.util.HashSet<>(closedWorlds);
            reopened.removeAll(newClosed);
            closedWorlds = newClosed;
            if (!reopened.isEmpty()) {
                getLogger().info("Region(s) reopened: " + reopened + " — releasing held players.");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    connectToSmp(p, (id, origin) -> origin != null && reopened.contains(origin));
                }
            }
        }

        Long returnAllAt = control.returnAllAt();
        if (returnAllAt != null && returnAllAt > lastControlMs) {
            lastControlMs = returnAllAt;
            if (!Bukkit.getOnlinePlayers().isEmpty()) {
                getLogger().info("Force-return signal received; returning held players to the SMP.");
                returnAll();
            }
        }

        Long brandingReloadAt = control.brandingReloadAt();
        if (brandingReloadAt != null && brandingReloadAt > lastBrandingMs) {
            lastBrandingMs = brandingReloadAt;
            reloadBranding(() -> {
                if (!isActive(generation)) return;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    PlatformScheduler.entityNow(this, p, () -> {
                        if (isActive(generation) && p.isOnline()) {
                            BrandingTexts texts = brandingTexts;
                            p.sendPlayerListHeaderAndFooter(texts.tabHeader(), texts.tabFooter());
                        }
                    }, null);
                }
                // Pause-menu title data is applied by a global command after the text update.
                try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload"); } catch (Throwable ignored) {}
            });
        }
    }

    private void returnAll() {
        if (shuttingDown.get()) return;
        // Players whose origin world is under per-world maintenance stay held until it reopens.
        for (Player p : Bukkit.getOnlinePlayers()) {
            connectToSmp(p, (id, origin) -> origin == null || !closedWorlds.contains(origin));
        }
        smpDownSeen.set(false);
        smpUpSince.set(0L);
        readyBaselineMs = lastSmpReadyMs;   // don't re-trigger on the same signal
    }

    private void connectToSmp(Player p) {
        connectToSmp(p, null);
    }

    private void connectToSmp(Player p, BiPredicate<UUID, String> returnFilter) {
        long generation = lifecycleGeneration.get();
        if (!isActive(generation)) return;
        PlatformScheduler.entityNow(this, p, () -> {
            if (!isActive(generation)) return;
            UUID id = p.getUniqueId();
            if (!p.isOnline()) {
                pendingReturns.remove(id);
                return;
            }
            if (returnFilter != null && !returnFilter.test(id, snapshotWorld.get(id))) return;
            pendingReturns.add(id);
            try {
                // No on-screen title: the proxy reconfiguration flash is unavoidable on 1.20.2+, but we
                // don't add to it. Player is sent straight back to the SMP (same overworld dimension).
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF("Connect");
                out.writeUTF(smpServer);
                p.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
            } catch (Exception ex) {
                getLogger().warning("return failed for a held player: " + ex.getMessage());
            }
        }, null);
    }

    private void retryPendingReturns() {
        long generation = lifecycleGeneration.get();
        if (!isActive(generation)) return;
        for (UUID id : java.util.List.copyOf(pendingReturns)) {
            if (!isActive(generation)) return;
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                pendingReturns.remove(id);
            } else {
                connectToSmp(player, (playerId, origin) -> origin == null || !closedWorlds.contains(origin));
            }
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

    // (Re)loads the shared branding file off-thread, then publishes the immutable text snapshot globally.
    // Called at enable and whenever the SMP's /branding set bumps limbo_control.branding_reload_at.
    private void reloadBranding(Runnable afterReload) {
        long lifecycle = lifecycleGeneration.get();
        if (!isActive(lifecycle)) return;
        long generation = brandingReloadGeneration.incrementAndGet();
        String path = getConfig().getString("branding.file",
            "plugins/PizzaNetworkCore/branding.yml");
        String footerText = configuredMessage("messages.tab-footer");
        String chatText = configuredMessage("messages.text");
        String hotbarText = configuredMessage("messages.hotbar");
        String kickText = configuredMessage("messages.kick");
        BrandingTexts defaults = buildBrandingTexts("ExampleSMP", "NA-East", NamedTextColor.AQUA,
            footerText, chatText, hotbarText, kickText);
        if (this.brandingTexts == null) applyBrandingTexts(defaults);

        PlatformScheduler.asyncNow(this, () -> {
            if (!isActive(lifecycle)) return;
            String brandDisplay = "ExampleSMP";
            String region = "NA-East";
            TextColor brandColor = NamedTextColor.AQUA;
            String hex = "00BFFF";
            try {
                org.bukkit.configuration.file.YamlConfiguration b =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(path));
                String active = b.getString("active", "example");
                brandDisplay = b.getString("profiles." + active + ".display", "ExampleSMP");
                region = b.getString("profiles." + active + ".region", "NA-East");
                hex = b.getString("profiles." + active + ".colors.primary", "00BFFF");
                try { brandColor = TextColor.color(Integer.parseInt(hex, 16)); } catch (Exception ignored) {}
            } catch (Exception ex) {
                getLogger().warning("Branding file could not be read; using the default brand.");
            }
            BrandingTexts loaded = buildBrandingTexts(brandDisplay, region, brandColor,
                footerText, chatText, hotbarText, kickText);
            if (!isActive(lifecycle)) return;
            PlatformScheduler.globalNow(this, () -> {
                if (!isActive(lifecycle) || generation != brandingReloadGeneration.get()) return;
                applyBrandingTexts(loaded);
                getLogger().info("Limbo branding loaded.");
                if (afterReload != null) afterReload.run();
            });
        });
    }

    private String configuredMessage(String path) {
        return getConfig().isString(path) ? getConfig().getString(path) : null;
    }

    private static BrandingTexts buildBrandingTexts(String brandName, String region, TextColor accent,
                                                     String footerText, String chatText,
                                                     String hotbarText, String kickText) {
        java.util.function.Supplier<Component> brandTag = () -> Component.text(brandName, accent)
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
        Component tabHeader = brandTag.get()
            .append(Component.text(" " + region, NamedTextColor.GRAY))
            .append(Component.text("\nMaintenance", NamedTextColor.DARK_GRAY));
        Component tabFooter = footerText != null
            ? legacy(footerText)
            : Component.text("You will be returned automatically\nwhen the region restarts.", NamedTextColor.GRAY);
        Component chat = chatText != null
            ? legacy(chatText)
            : brandTag.get().append(Component.text(" Maintenance", accent))
                .append(Component.text("\nYour region is undergoing maintenance. Do not teleport or your"
                    + " location will be lost. You will be returned automatically when your region"
                    + " restarts.", NamedTextColor.GRAY));
        Component hotbar = hotbarText != null
            ? legacy(hotbarText)
            : Component.text("Region under maintenance. You will be returned automatically.", NamedTextColor.GRAY);
        Component maintenanceKick = kickText != null
            ? legacy(kickText)
            : brandTag.get().append(Component.text(" Maintenance", accent))
                .append(Component.text("\n\nYour region is under maintenance.", NamedTextColor.GRAY))
                .append(Component.text("\nPlease try again in a few minutes.", NamedTextColor.GRAY));
        return new BrandingTexts(tabHeader, tabFooter, chat, hotbar, maintenanceKick);
    }

    private void applyBrandingTexts(BrandingTexts texts) {
        this.brandingTexts = texts;
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }
}
