package dev.pizzasmp.spawnrules;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.IOException;

public final class PizzaSpawnRulesPlugin extends JavaPlugin implements Listener {

    private static final int MIN_X = -60;
    private static final int MAX_X = 60;
    private static final int MIN_Z = -60;
    private static final int MAX_Z = 60;
    private static final int BARRIER_MIN_Y = 80;
    private static final int BARRIER_MAX_Y = 141;
    private final Map<UUID, Boolean> jumpedThisAir = new ConcurrentHashMap<>();
    private final Map<UUID, Long> gateCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, PendingGateTeleport> pendingGateTeleports = new ConcurrentHashMap<>();
    private boolean barrierCeilingReady;
    private boolean gateRoutingEnabled;
    private boolean lockAllWorld;
    private final Map<UUID, Long> stationInteractCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> spawnEditUntil = new ConcurrentHashMap<>();
    private static final long SPAWN_EDIT_DEFAULT_MS = 10L * 60L * 1000L;
    private static final int SPAWN_CLEANUP_MAX_BLOCKS_DEFAULT = 30_000;
    private boolean gateDebug;
    private static final int[][] LEGACY_CLEANUP_CUBOIDS = new int[][]{
        {-16, 101, -22, -10, 110, -17},
        {-3, 101, -22, 3, 110, -17},
        {10, 101, -22, 16, 110, -17},
        {8, 101, 33, 16, 110, 41}
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int port = Bukkit.getPort();
        if (port != 25566 && port != 25569) {
            getLogger().info("PizzaSpawnRules idle (lobby/maintenance-only)");
            return;
        }
        // Keep gate behavior identical on lobby and maintenance mirrors.
        gateRoutingEnabled = getConfig().getBoolean("gates.enabled", false);
        lockAllWorld = (port == 25569);
        gateDebug = getConfig().getBoolean("gates.debug_log", false);
        Bukkit.getPluginManager().registerEvents(this, this);
        enforceWorldRules();
        // World instances are not guaranteed to be available during onEnable.
        Bukkit.getScheduler().runTaskLater(this, this::runSpawnCleanupPass, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::enforceWorldRules, 1L, 200L);
        Bukkit.getScheduler().runTaskTimer(this, this::ensureNpcStations, 40L, 200L);
        // Keep client flight permission synced so double-jump never gets stuck.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                updateDoubleJumpState(p);
            }
            expireSpawnEditSessions();
        }, 1L, 1L);
    }

    private boolean canEditProtected(Player player) {
        if (player == null) {
            return false;
        }
        if (player.isOp()) {
            return true;
        }
        long until = spawnEditUntil.getOrDefault(player.getUniqueId(), 0L);
        return until > System.currentTimeMillis();
    }

    private void expireSpawnEditSessions() {
        long now = System.currentTimeMillis();
        spawnEditUntil.entrySet().removeIf(e -> e.getValue() <= now);
    }

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("spawnedit")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only.");
            return true;
        }
        if (!player.isOp() && !player.hasPermission("pizzasmp.spawnedit")) {
            player.sendMessage("§cNo permission.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        long now = System.currentTimeMillis();
        long durationMs = Math.max(60_000L, getConfig().getLong("spawnedit.duration_ms", SPAWN_EDIT_DEFAULT_MS));
        switch (sub) {
            case "on" -> {
                long until = now + durationMs;
                spawnEditUntil.put(player.getUniqueId(), until);
                player.sendMessage("§9[SpawnEdit] §7Enabled for " + (durationMs / 60000L) + "m.");
            }
            case "off" -> {
                spawnEditUntil.remove(player.getUniqueId());
                player.sendMessage("§9[SpawnEdit] §7Disabled.");
            }
            case "status" -> {
                long until = spawnEditUntil.getOrDefault(player.getUniqueId(), 0L);
                if (until <= now) {
                    player.sendMessage("§9[SpawnEdit] §7OFF.");
                } else {
                    long sec = (until - now) / 1000L;
                    player.sendMessage("§9[SpawnEdit] §7ON (" + sec + "s remaining).");
                }
            }
            default -> player.sendMessage("§7Usage: /spawnedit <on|off|status>");
        }
        return true;
    }

    private boolean inSpawn(Location l) {
        int x = l.getBlockX();
        int z = l.getBlockZ();
        return x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z;
    }

    private boolean inProtectedZone(Location l) {
        return lockAllWorld || inSpawn(l);
    }

    private boolean allowProtectedPvp() {
        return Bukkit.getPort() == 25566;
    }

    private boolean isPlayerCombatDamager(Entity damager) {
        if (damager instanceof Player) {
            return true;
        }
        if (damager instanceof Projectile projectile) {
            return projectile.getShooter() instanceof Player;
        }
        return false;
    }

    private Location lobbySpawn(World w) {
        // Face south toward the portal pads.
        return new Location(w, 0.5, 102.0, 10.5, 0f, 0f);
    }

    private void ensureSpawnSafetyPad(World w) {
        // Large fallback pad so players never spawn into void/air even if builds are missing.
        for (int x = -12; x <= 12; x++) {
            for (int z = -2; z <= 22; z++) {
                if (w.getBlockAt(x, 101, z).getType().isAir()) {
                    w.getBlockAt(x, 101, z).setType(Material.SMOOTH_STONE, false);
                }
                for (int y = 102; y <= 104; y++) {
                    if (!w.getBlockAt(x, y, z).getType().isAir()) {
                        w.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
        // Keep lobby utility blocks present even after map resets.
        placeEnderChest(w, -4, 102, 10);
        placeEnderChest(w, 4, 102, 10);
        placeEnderChest(w, -48, 102, 18);
        placeEnderChest(w, 32, 102, 14);
        w.setSpawnLocation(lobbySpawn(w));
    }

    private void ensureBarrierCeiling(World w) {
        if (barrierCeilingReady) {
            return;
        }
        int y = BARRIER_MAX_Y;
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (w.getBlockAt(x, y, z).getType() != Material.BARRIER) {
                    w.getBlockAt(x, y, z).setType(Material.BARRIER, false);
                }
            }
        }
        // Build full perimeter barrier walls so the spawn region is fully enclosed.
        for (int wallY = BARRIER_MIN_Y; wallY <= BARRIER_MAX_Y; wallY++) {
            for (int x = MIN_X; x <= MAX_X; x++) {
                if (w.getBlockAt(x, wallY, MIN_Z).getType() != Material.BARRIER) {
                    w.getBlockAt(x, wallY, MIN_Z).setType(Material.BARRIER, false);
                }
                if (w.getBlockAt(x, wallY, MAX_Z).getType() != Material.BARRIER) {
                    w.getBlockAt(x, wallY, MAX_Z).setType(Material.BARRIER, false);
                }
            }
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (w.getBlockAt(MIN_X, wallY, z).getType() != Material.BARRIER) {
                    w.getBlockAt(MIN_X, wallY, z).setType(Material.BARRIER, false);
                }
                if (w.getBlockAt(MAX_X, wallY, z).getType() != Material.BARRIER) {
                    w.getBlockAt(MAX_X, wallY, z).setType(Material.BARRIER, false);
                }
            }
        }
        barrierCeilingReady = true;
    }

    private void placeEnderChest(World w, int x, int y, int z) {
        if (w.getBlockAt(x, y - 1, z).getType().isAir()) {
            w.getBlockAt(x, y - 1, z).setType(Material.SMOOTH_STONE, false);
        }
        if (w.getBlockAt(x, y, z).getType() != Material.ENDER_CHEST) {
            w.getBlockAt(x, y, z).setType(Material.ENDER_CHEST, false);
        }
        if (!w.getBlockAt(x, y + 1, z).getType().isAir()) {
            w.getBlockAt(x, y + 1, z).setType(Material.AIR, false);
        }
    }

    private void enforceWorldRules() {
        World w = Bukkit.getWorlds().getFirst();
        if (w == null) return;
        ensureSpawnSafetyPad(w);
        ensureBarrierCeiling(w);
        w.setDifficulty(Difficulty.PEACEFUL);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setStorm(false);
        w.setThundering(false);
        w.setWeatherDuration(Integer.MAX_VALUE);
        w.setClearWeatherDuration(Integer.MAX_VALUE);

        // Hard no-mob policy in lobby.
        for (LivingEntity e : w.getLivingEntities()) {
            if (!(e instanceof Player)) {
                e.remove();
            }
        }
    }

    private void ensureNpcStations() {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (world == null) {
            return;
        }
        // Keep station NPCs deterministic and synced between lobby + maintenance.
        ensureStation(world, "rtp", "§bRandom Teleport", 25.5, 102.0, 17.5, -90.0f, "rtp");
        ensureStation(world, "shop", "§bShop", 27.5, 102.0, 17.5, -90.0f, "shop");
        ensureStation(world, "ah", "§bAuction House", 29.5, 102.0, 17.5, -90.0f, "ah");
        ensureStation(world, "orders", "§bOrders", 31.5, 102.0, 17.5, -90.0f, "orders");
        ensureStation(world, "team", "§bTeams", 33.5, 102.0, 17.5, -90.0f, "team gui");
        ensureStation(world, "settings", "§bSettings", 35.5, 102.0, 17.5, -90.0f, "settings");
        ensureStation(world, "stats", "§bStats", 37.5, 102.0, 17.5, -90.0f, "stats");
        ensureStation(world, "guide", "§bGuide", 39.5, 102.0, 17.5, -90.0f, "guide");
    }

    private void ensureStation(World world, String key, String name, double x, double y, double z, float yaw, String command) {
        String tag = "pizzasmp_station_" + key;
        Villager existing = findStation(world, tag);
        Location at = new Location(world, x, y, z, yaw, 0.0f);
        if (existing == null) {
            existing = world.spawn(at, Villager.class, villager -> {
                villager.addScoreboardTag("pizzasmp_station");
                villager.addScoreboardTag(tag);
                villager.setAdult();
            });
        } else {
            existing.teleport(at);
        }

        existing.setCustomName(name);
        existing.setCustomNameVisible(true);
        existing.setAI(false);
        existing.setInvulnerable(true);
        existing.setSilent(true);
        existing.setCollidable(false);
        existing.setCanPickupItems(false);
        existing.setGravity(false);
        existing.setRemoveWhenFarAway(false);
        existing.setPersistent(true);
        existing.setAware(false);
        existing.setProfession(Villager.Profession.NONE);
        existing.getPersistentDataContainer().set(StationKeys.COMMAND_KEY, StationKeys.STRING_TYPE, command);
    }

    private Villager findStation(World world, String tag) {
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Villager villager)) {
                continue;
            }
            if (villager.getScoreboardTags().contains(tag)) {
                return villager;
            }
        }
        return null;
    }

    private boolean supportsDoubleJump(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    private void updateDoubleJumpState(Player p) {
        if (!supportsDoubleJump(p)) return;
        if (inProtectedZone(p.getLocation())) {
            // One boost per airtime: enable trigger while grounded, disable in-air.
            if (p.isOnGround()) {
                jumpedThisAir.put(p.getUniqueId(), false);
                p.setAllowFlight(true);
            } else if (Boolean.TRUE.equals(jumpedThisAir.get(p.getUniqueId()))) {
                p.setAllowFlight(false);
            }
        } else {
            if (!p.isFlying()) {
                p.setAllowFlight(false);
            }
            jumpedThisAir.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.joinMessage(null);
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = e.getPlayer();
            // Join location policy is handled by PizzaNetworkCore sync rules.
            p.setFallDistance(0f);
            p.setFireTicks(0);
            updateDoubleJumpState(p);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(null);
        jumpedThisAir.remove(e.getPlayer().getUniqueId());
        gateCooldown.remove(e.getPlayer().getUniqueId());
        cancelPendingGateTeleport(e.getPlayer(), false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        World w = Bukkit.getWorlds().getFirst();
        if (w == null) return;
        Location spawn = lobbySpawn(w);
        e.setRespawnLocation(spawn);
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = e.getPlayer();
            if (!p.isOnline()) return;
            p.teleport(spawn);
            p.setFallDistance(0f);
            p.setFireTicks(0);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(20.0f);
            p.setExhaustion(0.0f);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().toLowerCase().trim();
        if (msg.equals("/kill") || msg.startsWith("/kill ")) {
            // Deterministic reset to lobby spawn.
            e.setCancelled(true);
            Player p = e.getPlayer();
            World w = Bukkit.getWorlds().getFirst();
            if (w != null) {
                Location spawn = lobbySpawn(w);
                p.teleport(spawn);
                p.setFallDistance(0f);
            }
            p.setFireTicks(0);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(20.0f);
            p.setExhaustion(0.0f);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        // Spawn edit policy override: allow block breaking in protected spawn areas.
        // Placement and fluid protections remain enforced separately.
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!inProtectedZone(e.getBlockPlaced().getLocation())) {
            return;
        }
        if (canEditProtected(e.getPlayer())) {
            return;
        }
        Material type = e.getBlockPlaced().getType();
        if (type == Material.WATER || type == Material.LAVA) {
            e.setCancelled(true);
            return;
        }
        if (e.getBlockPlaced().getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onIgnite(BlockIgniteEvent e) {
        if (inProtectedZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler
    public void onBurn(BlockBurnEvent e) {
        if (inProtectedZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler
    public void onFlow(BlockFromToEvent e) {
        if (inProtectedZone(e.getToBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!inProtectedZone(e.getBlock().getLocation())) {
            return;
        }
        if (!canEditProtected(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!inProtectedZone(e.getBlock().getLocation())) {
            return;
        }
        if (!canEditProtected(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (inProtectedZone(e.getBlock().getLocation())) { e.setCancelled(true); return; }
        e.getBlocks().stream().filter(b -> inProtectedZone(b.getLocation())).findAny().ifPresent(b -> e.setCancelled(true));
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (inProtectedZone(e.getBlock().getLocation())) { e.setCancelled(true); return; }
        e.getBlocks().stream().filter(b -> inProtectedZone(b.getLocation())).findAny().ifPresent(b -> e.setCancelled(true));
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> inProtectedZone(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        if (inProtectedZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (allowProtectedPvp()
            && e instanceof EntityDamageByEntityEvent byEntity
            && isPlayerCombatDamager(byEntity.getDamager())) {
            return;
        }
        if (inProtectedZone(p.getLocation())) {
            e.setCancelled(true);
            if (p.getFireTicks() > 0) p.setFireTicks(0);
        }
    }

    @EventHandler
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (allowProtectedPvp() && isPlayerCombatDamager(e.getDamager())) {
            return;
        }
        if (inProtectedZone(p.getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!inProtectedZone(p.getLocation())) return;
        e.setCancelled(true);
        if (p.getFoodLevel() < 20) p.setFoodLevel(20);
        p.setSaturation(20.0f);
        p.setExhaustion(0.0f);
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        Projectile projectile = e.getEntity();
        if (!(projectile.getShooter() instanceof Player p)) return;
        if (!inProtectedZone(p.getLocation())) return;
        if (projectile.getType() == EntityType.ENDER_PEARL) e.setCancelled(true);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        // CHORUS_FRUIT is deprecated and marked for removal; use name comparison to stay
        // compatible across API versions without a compile-time warning.
        if ("CHORUS_FRUIT".equals(e.getCause().name()) && inProtectedZone(e.getFrom())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (inProtectedZone(p.getLocation())) e.setCancelled(true);
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        // Riptide is only cancellable on some Paper versions; cancel where the API allows it.
        if (inProtectedZone(e.getPlayer().getLocation()) && e instanceof org.bukkit.event.Cancellable c) c.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getItem() == null) return;
        if (!inProtectedZone(e.getPlayer().getLocation())) return;
        Material m = e.getItem().getType();
        if ((m == Material.FIREWORK_ROCKET || m == Material.CHORUS_FRUIT) && !canEditProtected(e.getPlayer())) {
            e.setCancelled(true);
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        // Spawn edit policy override: allow dropping items in protected spawn areas.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractStation(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        if (!villager.getScoreboardTags().contains("pizzasmp_station")) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long nextAllowed = stationInteractCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now < nextAllowed) {
            return;
        }
        stationInteractCooldown.put(player.getUniqueId(), now + 350L);
        String command = villager.getPersistentDataContainer().get(StationKeys.COMMAND_KEY, StationKeys.STRING_TYPE);
        if (command == null || command.isBlank()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(player, command));
    }

    // Silent, unlimited double jump inside spawn. No cooldown, no messages, no sounds.
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (pendingGateTeleports.containsKey(p.getUniqueId())) {
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                cancelPendingGateTeleport(p, true);
            }
        }
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            updateDoubleJumpState(p);
            handleGatePadRouting(p, to);
        }
    }

    private void handleGatePadRouting(Player player, Location to) {
        if (!gateRoutingEnabled) {
            return;
        }
        if (!inSpawn(to)) {
            return;
        }
        int x = to.getBlockX();
        int y = to.getBlockY();
        int z = to.getBlockZ();
        if (y < 99 || y > 104) {
            return;
        }
        long now = System.currentTimeMillis();
        long nextAllowed = gateCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now < nextAllowed) {
            return;
        }
        if (pendingGateTeleports.containsKey(player.getUniqueId())) {
            return;
        }
        if (z >= 39 && z <= 41 && x >= -13 && x <= -11) {
            startGateTeleportCountdown(player, "warp survival");
            return;
        }
        if (z >= 39 && z <= 41 && x >= 11 && x <= 13) {
            startGateTeleportCountdown(player, "warp duels");
        }
    }

    private void startGateTeleportCountdown(Player player, String command) {
        UUID uuid = player.getUniqueId();
        if (gateDebug) {
            getLogger().info("[gate-debug] stage=start uuid=" + uuid + " server_port=" + Bukkit.getPort() + " command=" + command);
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int secondsRemaining = 2;

            @Override
            public void run() {
                Player live = Bukkit.getPlayer(uuid);
                PendingGateTeleport pending = pendingGateTeleports.get(uuid);
                if (live == null || !live.isOnline() || pending == null) {
                    cancelTask(pending);
                    pendingGateTeleports.remove(uuid);
                    return;
                }
                if (secondsRemaining <= 0) {
                    pendingGateTeleports.remove(uuid);
                    gateCooldown.put(uuid, System.currentTimeMillis() + 3500L);
                    cancelTask(pending);
                    if (gateDebug) {
                        getLogger().info("[gate-debug] stage=execute uuid=" + uuid + " command=" + command + " result=dispatch");
                    }
                    Bukkit.dispatchCommand(live, command);
                    return;
                }
                live.sendActionBar("§7Teleporting in §9" + secondsRemaining + "§7s");
                secondsRemaining--;
            }
        }, 0L, 20L);
        pendingGateTeleports.put(uuid, new PendingGateTeleport(task));
    }

    private void cancelPendingGateTeleport(Player player, boolean notify) {
        PendingGateTeleport pending = pendingGateTeleports.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        cancelTask(pending);
        if (gateDebug) {
            getLogger().info("[gate-debug] stage=cancel uuid=" + player.getUniqueId() + " moved=" + notify);
        }
    }

    private void cancelTask(PendingGateTeleport pending) {
        if (pending != null && !pending.task.isCancelled()) {
            pending.task.cancel();
        }
    }

    private void runSpawnCleanupPass() {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (world == null) {
            return;
        }
        File marker = new File(world.getWorldFolder(), ".pizzasmp_spawn_cleaned");
        int preExisting = countLegacyBlocks(world);
        if (preExisting <= 0) {
            if (marker.exists()) {
                getLogger().info("Spawn cleanup check passed; no legacy blocks detected.");
            }
            if (!marker.exists()) {
                try {
                    marker.createNewFile();
                } catch (IOException ignored) {
                }
            }
            return;
        }
        if (marker.exists()) {
            getLogger().warning("Spawn cleanup marker exists but found " + preExisting + " legacy blocks; re-running precise cleanup.");
        } else {
            getLogger().info("Running one-time precise cleanup for legacy spawn structures. blocks=" + preExisting);
        }
        int maxBlocks = Math.max(1_000, getConfig().getInt("spawn_cleanup.max_blocks", SPAWN_CLEANUP_MAX_BLOCKS_DEFAULT));
        if (preExisting > maxBlocks) {
            getLogger().severe("Spawn cleanup aborted: legacy block count " + preExisting
                + " exceeds safety cap " + maxBlocks + ". No blocks were modified.");
            return;
        }
        int cleared = 0;
        for (int[] cuboid : LEGACY_CLEANUP_CUBOIDS) {
            cleared += clearCuboid(world, cuboid[0], cuboid[1], cuboid[2], cuboid[3], cuboid[4], cuboid[5]);
        }
        getLogger().info("Spawn cleanup complete. blocks_cleared=" + cleared);
        try {
            marker.createNewFile();
        } catch (IOException ignored) {
        }
    }

    private int countLegacyBlocks(World world) {
        int nonAir = 0;
        for (int[] cuboid : LEGACY_CLEANUP_CUBOIDS) {
            nonAir += countNonAir(world, cuboid[0], cuboid[1], cuboid[2], cuboid[3], cuboid[4], cuboid[5]);
        }
        return nonAir;
    }

    private int countNonAir(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z).getType() != Material.AIR) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int clearCuboid(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        int changed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() != Material.AIR) {
                        b.setType(Material.AIR, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private boolean isFluidContainerItem(Material material) {
        return switch (material) {
            case WATER_BUCKET, LAVA_BUCKET, POWDER_SNOW_BUCKET, AXOLOTL_BUCKET, COD_BUCKET, SALMON_BUCKET,
                TROPICAL_FISH_BUCKET, PUFFERFISH_BUCKET, TADPOLE_BUCKET -> true;
            default -> false;
        };
    }

    @EventHandler
    public void onTeleportDoubleJumpSync(PlayerTeleportEvent e) {
        Bukkit.getScheduler().runTask(this, () -> updateDoubleJumpState(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (!inProtectedZone(p.getLocation())) return;
        if (!supportsDoubleJump(p)) return;
        if (Boolean.TRUE.equals(jumpedThisAir.get(p.getUniqueId()))) {
            e.setCancelled(true);
            p.setFlying(false);
            p.setAllowFlight(false);
            return;
        }

        e.setCancelled(true);
        p.setFlying(false);
        p.setAllowFlight(false);
        jumpedThisAir.put(p.getUniqueId(), true);

        Vector jump = p.getLocation().getDirection().normalize().multiply(1.0).setY(0.72);
        p.setVelocity(jump);

        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline() && inProtectedZone(p.getLocation()) && supportsDoubleJump(p) && p.isOnGround()) {
                p.setAllowFlight(true);
            }
        });
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && inProtectedZone(p.getLocation()) && supportsDoubleJump(p) && p.isOnGround()) {
                p.setAllowFlight(true);
                p.setFlying(false);
            }
        }, 1L);
    }

    private static final class PendingGateTeleport {
        private final BukkitTask task;

        private PendingGateTeleport(BukkitTask task) {
            this.task = task;
        }
    }

    private static final class StationKeys {
        private static final org.bukkit.NamespacedKey COMMAND_KEY =
            new org.bukkit.NamespacedKey("pizzaspawnrules", "station_command");
        private static final org.bukkit.persistence.PersistentDataType<String, String> STRING_TYPE =
            org.bukkit.persistence.PersistentDataType.STRING;
    }
}
