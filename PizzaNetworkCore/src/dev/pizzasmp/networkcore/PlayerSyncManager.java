/*
 * PlayerSyncManager is part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Released under the MIT License (see LICENSE). Provided AS IS, without warranty.
 */
package dev.pizzasmp.networkcore;

import dev.pizzasmp.networkcore.PizzaNetworkCore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

final class PlayerSyncManager {
    private final JavaPlugin plugin;
    private final boolean enabled;
    private final String serverName;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int leaseSeconds;
    private final int heartbeatTicks;
    private final boolean reconnectEnabled;
    private final String lobbyServerName;
    private final Set<String> excludedReconnectTargets;
    private final String reconnectBypassPermission;
    private final int reconnectDelayTicks;
    private final int reconnectMinOfflineSeconds;
    private final boolean restoreLocationOnJoin;
    private final int locationTeleportDelayTicks;
    private final boolean debugStateLogging;
    private final boolean debugPendingActions;
    private static final int LOBBY_SPAWN_MIN_X = -60;
    private static final int LOBBY_SPAWN_MAX_X = 60;
    private static final int LOBBY_SPAWN_MIN_Z = -60;
    private static final int LOBBY_SPAWN_MAX_Z = 60;
    private final Map<UUID, String> activeLeases = new ConcurrentHashMap<UUID, String>();
    private final Set<UUID> applyingJoinState = ConcurrentHashMap.newKeySet();
    private volatile boolean driverLoaded;
    private final boolean foliaRuntime;
    private TaskHandle heartbeatTask;

    PlayerSyncManager(JavaPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("sync.enabled", true);
        String configuredServer = cfg.getString("sync.server-name", "auto");
        this.serverName = configuredServer == null || configuredServer.isBlank() || "auto".equalsIgnoreCase(configuredServer) ? this.detectServerName() : configuredServer.trim().toLowerCase();
        String host = cfg.getString("sync.database.host", "127.0.0.1");
        int port = cfg.getInt("sync.database.port", 3306);
        String database = cfg.getString("sync.database.name", "smpcore");
        String params = cfg.getString("sync.database.parameters", "useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true");
        this.dbUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?" + params;
        this.dbUser = cfg.getString("sync.database.user", "smpcore");
        this.dbPassword = cfg.getString("sync.database.password", "changeme");
        this.leaseSeconds = Math.max(10, cfg.getInt("sync.session.lease-seconds", 45));
        this.heartbeatTicks = Math.max(20, cfg.getInt("sync.session.heartbeat-ticks", 200));
        this.reconnectEnabled = cfg.getBoolean("sync.reconnect.enabled", true);
        this.lobbyServerName = cfg.getString("sync.reconnect.lobby-server-name", "lobby").toLowerCase();
        this.excludedReconnectTargets = new HashSet<String>(cfg.getStringList("sync.reconnect.excluded-servers"));
        this.reconnectBypassPermission = cfg.getString("sync.reconnect.bypass-permission", "pizzasmp.sync.reconnect.bypass");
        this.reconnectDelayTicks = Math.max(1, cfg.getInt("sync.reconnect.delay-ticks", 20));
        this.reconnectMinOfflineSeconds = Math.max(0, cfg.getInt("sync.reconnect.min-offline-seconds", 4));
        this.restoreLocationOnJoin = cfg.getBoolean("sync.location.restore-on-join", true);
        this.locationTeleportDelayTicks = Math.max(1, cfg.getInt("sync.location.teleport-delay-ticks", 5));
        this.debugStateLogging = cfg.getBoolean("sync.debug.log-join-state", false);
        this.debugPendingActions = cfg.getBoolean("sync.debug.log-pending-actions", false);
        this.foliaRuntime = Bukkit.getName().toLowerCase().contains("folia");
    }

    void start() {
        if (!this.enabled) {
            this.plugin.getLogger().info("Cross-server sync disabled in config.");
            return;
        }
        this.plugin.getServer().getMessenger().registerOutgoingPluginChannel((Plugin)this.plugin, "BungeeCord");
        this.runAsync(this::ensureSchema);
        this.heartbeatTask = this.runAsyncRepeating(this::heartbeatActiveLeases, this.heartbeatTicks, this.heartbeatTicks);
        this.plugin.getLogger().info("Cross-server sync enabled for server-name=" + this.serverName);
    }

    void shutdown() {
        if (!this.enabled) {
            return;
        }
        if (this.heartbeatTask != null) {
            this.heartbeatTask.cancel();
            this.heartbeatTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.saveSnapshotAsync(player);
            String leaseToken = this.activeLeases.get(player.getUniqueId());
            this.clearLeaseAsync(player.getUniqueId(), leaseToken);
        }
        this.activeLeases.clear();
    }

    void handleJoin(Player player) {
        if (!this.enabled) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String leaseToken = UUID.randomUUID().toString();
        this.activeLeases.put(uuid, leaseToken);
        this.applyingJoinState.add(uuid);
        this.runAsync(() -> {
            try {
                LogoutMeta logoutMeta = this.loadLogoutMeta(uuid);
                this.upsertPlayerRow(uuid, username);
                this.upsertLease(uuid, leaseToken);
                SyncSnapshot snapshot = this.loadSnapshot(uuid);
                String pendingAction = this.consumeOneTimeAction(uuid);
                this.runOnPlayerNow(player, () -> this.applyJoinState(player, snapshot, pendingAction, logoutMeta));
            }
            catch (Exception e) {
                this.applyingJoinState.remove(uuid);
                this.plugin.getLogger().warning("Failed preparing join sync state for " + username + ": " + e.getMessage());
            }
        });
    }

    void handleQuit(Player player) {
        if (!this.enabled) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String leaseToken = this.activeLeases.remove(uuid);
        this.saveSnapshotAsync(player);
        this.saveLogoutMetaAsync(uuid, leaseToken);
        this.clearLeaseAsync(uuid, leaseToken);
    }

    void handleGameModeChange(Player player, GameMode newGameMode) {
        if (!this.enabled || player == null || !player.isOnline()) {
            return;
        }
        this.runOnPlayerLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            this.saveSnapshotAsync(player, newGameMode);
        }, 1L);
    }

    boolean isApplyingJoinState(UUID uuid) {
        return uuid != null && this.applyingJoinState.contains(uuid);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void applyJoinState(Player player, SyncSnapshot snapshot, String pendingAction, LogoutMeta logoutMeta) {
        if (!player.isOnline()) {
            this.applyingJoinState.remove(player.getUniqueId());
            return;
        }
        try {
            World world;
            if (snapshot != null) {
                if (this.shouldReconnectFromLobby(player, snapshot.lastServer, logoutMeta)) {
                    this.runOnPlayerLater(player, () -> this.connectToServer(player, snapshot.lastServer), this.reconnectDelayTicks);
                    return;
                }
                this.applyInventoryAndState(player, snapshot);
                this.debugAppliedState(player, "join_apply");
                if (this.restoreLocationOnJoin && this.serverName.equalsIgnoreCase(snapshot.lastServer) && (world = Bukkit.getWorld((String)snapshot.worldName)) != null) {
                    Location location = new Location(world, snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
                    this.runOnPlayerLater(player, () -> {
                        if (player.isOnline()) {
                            player.teleport(location);
                        }
                    }, this.locationTeleportDelayTicks);
                }
            }
            if ("maintenance".equalsIgnoreCase(this.serverName)) {
                World world2 = world = Bukkit.getWorlds().isEmpty() ? null : (World)Bukkit.getWorlds().getFirst();
                if (world != null) {
                    Location center = this.lobbySpawnCenter(world);
                    this.runOnPlayerLater(player, () -> {
                        if (player.isOnline()) {
                            player.teleport(center);
                        }
                    }, Math.max(2L, (long)this.locationTeleportDelayTicks));
                }
            }
            this.applyPendingAction(player, pendingAction);
        }
        finally {
            this.applyingJoinState.remove(player.getUniqueId());
        }
    }

    private void applyPendingAction(Player player, String pendingAction) {
        if (pendingAction == null || pendingAction.isBlank()) {
            return;
        }
        if (this.debugPendingActions) {
            this.plugin.getLogger().info("[pending-action-debug] stage=apply uuid=" + String.valueOf(player.getUniqueId()) + " server=" + this.serverName + " action=" + pendingAction);
        }
        if (pendingAction.toUpperCase().startsWith("RTP") && "survival".equalsIgnoreCase(this.serverName)) {
            String raw;
            String dimensionArg = "overworld";
            int idx = pendingAction.indexOf(58);
            if (idx > 0 && idx < pendingAction.length() - 1 && ("nether".equals(raw = pendingAction.substring(idx + 1).trim().toLowerCase()) || "end".equals(raw) || "overworld".equals(raw))) {
                dimensionArg = raw;
            }
            String finalDimensionArg = dimensionArg;
            this.runOnPlayerLater(player, () -> {
                JavaPlugin patt0$temp;
                if (player.isOnline() && (patt0$temp = this.plugin) instanceof PizzaNetworkCore) {
                    PizzaNetworkCore core = (PizzaNetworkCore)patt0$temp;
                    core.executeQueuedRtp(player, finalDimensionArg);
                }
            }, 10L);
            return;
        }
        if (pendingAction.startsWith("RUN_CMD:")) {
            String queued = pendingAction.substring("RUN_CMD:".length()).trim();
            if (queued.isBlank()) {
                return;
            }
            this.runOnPlayerLater(player, () -> {
                if (player.isOnline()) {
                    player.performCommand(queued);
                }
            }, 10L);
        }
    }

    void queueOneTimeAction(UUID uuid, String action, int ttlSeconds) {
        this.runAsync(() -> {
            String sql = "INSERT INTO player_transfer_actions (uuid, action, expires_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE action=VALUES(action), expires_at=VALUES(expires_at)";
            Timestamp expiresAt = Timestamp.from(Instant.now().plusSeconds(Math.max(5, ttlSeconds)));
            try (Connection connection = this.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql);){
                ps.setString(1, uuid.toString());
                ps.setString(2, action);
                ps.setTimestamp(3, expiresAt);
                ps.executeUpdate();
                if (this.debugPendingActions) {
                    this.plugin.getLogger().info("[pending-action-debug] stage=queue uuid=" + String.valueOf(uuid) + " server=" + this.serverName + " action=" + action + " ttl=" + ttlSeconds);
                }
            }
            catch (SQLException e) {
                this.plugin.getLogger().warning("Failed queueing transfer action for " + String.valueOf(uuid) + ": " + e.getMessage());
            }
        });
    }

    private String consumeOneTimeAction(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        String selectSql = "SELECT action FROM player_transfer_actions WHERE uuid=? AND expires_at > CURRENT_TIMESTAMP LIMIT 1 FOR UPDATE";
        String deleteSql = "DELETE FROM player_transfer_actions WHERE uuid=?";
        try (Connection connection = this.getConnection();){
            connection.setAutoCommit(false);
            try {
                String action = null;
                try (PreparedStatement select = connection.prepareStatement(selectSql);){
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery();){
                        if (rs.next()) {
                            action = rs.getString("action");
                        }
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(deleteSql);){
                    delete.setString(1, uuid.toString());
                    delete.executeUpdate();
                }
                connection.commit();
                return action == null || action.isBlank() ? null : action;
            }
            catch (SQLException e) {
                connection.rollback();
                throw e;
            }
            finally {
                connection.setAutoCommit(true);
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().warning("Failed consuming transfer action for " + String.valueOf(uuid) + ": " + e.getMessage());
            return null;
        }
    }

    private void saveLogoutMetaAsync(UUID uuid, String previousLeaseToken) {
        this.runAsync(() -> {
            if (this.isLeasedByAnotherServer(uuid, previousLeaseToken)) {
                return;
            }
            String sql = "INSERT INTO player_logout_meta (uuid, last_logout_server, last_logout_at) VALUES (?, ?, CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE last_logout_server=VALUES(last_logout_server), last_logout_at=CURRENT_TIMESTAMP";
            try (Connection connection = this.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql);){
                ps.setString(1, uuid.toString());
                ps.setString(2, this.serverName);
                ps.executeUpdate();
            }
            catch (SQLException e) {
                this.plugin.getLogger().warning("Failed saving logout meta for " + String.valueOf(uuid) + ": " + e.getMessage());
            }
        });
    }

    private boolean isLeasedByAnotherServer(UUID uuid, String previousLeaseToken) {
        if (uuid == null) {
            return false;
        }
        String sql = "SELECT lease_token, server_name FROM session_leases WHERE uuid=? AND expires_at > CURRENT_TIMESTAMP LIMIT 1";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery();){
                if (!rs.next()) {
                    return false;
                }
                String activeLeaseToken = rs.getString("lease_token");
                String activeServerName = rs.getString("server_name");
                if (previousLeaseToken != null && !previousLeaseToken.isBlank() && previousLeaseToken.equals(activeLeaseToken)) {
                    return false;
                }
                if (previousLeaseToken == null || previousLeaseToken.isBlank()) {
                    return activeServerName != null && !this.serverName.equalsIgnoreCase(activeServerName);
                }
                return true;
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().warning("Failed checking lease ownership for " + String.valueOf(uuid) + ": " + e.getMessage());
            return false;
        }
    }

    private LogoutMeta loadLogoutMeta(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        String sql = "SELECT last_logout_server,last_logout_at FROM player_logout_meta WHERE uuid=? LIMIT 1";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery();){
                if (!rs.next()) {
                    return null;
                }
                return new LogoutMeta(rs.getString("last_logout_server"), rs.getTimestamp("last_logout_at"));
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().warning("Failed loading logout meta for " + String.valueOf(uuid) + ": " + e.getMessage());
            return null;
        }
    }

    private boolean shouldReconnectFromLobby(Player player, String targetServer, LogoutMeta logoutMeta) {
        if (!this.reconnectEnabled) {
            return false;
        }
        if (!this.serverName.equalsIgnoreCase(this.lobbyServerName)) {
            return false;
        }
        if (targetServer == null || targetServer.isBlank()) {
            return false;
        }
        if (targetServer.equalsIgnoreCase(this.serverName)) {
            return false;
        }
        if (logoutMeta == null || logoutMeta.lastLogoutAt == null) {
            return false;
        }
        if (logoutMeta.lastLogoutServer == null || !targetServer.equalsIgnoreCase(logoutMeta.lastLogoutServer)) {
            return false;
        }
        long offlineSeconds = Math.max(0L, (System.currentTimeMillis() - logoutMeta.lastLogoutAt.getTime()) / 1000L);
        if (offlineSeconds < (long)this.reconnectMinOfflineSeconds) {
            return false;
        }
        if (this.excludedReconnectTargets.contains(targetServer.toLowerCase())) {
            return false;
        }
        return this.reconnectBypassPermission == null || this.reconnectBypassPermission.isBlank() || !player.hasPermission(this.reconnectBypassPermission);
    }

    private void connectToServer(Player player, String server) {
        if (!player.isOnline()) {
            return;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes);){
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage((Plugin)this.plugin, "BungeeCord", bytes.toByteArray());
        }
        catch (IOException e) {
            this.plugin.getLogger().warning("Failed sending proxy connect for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void saveSnapshotAsync(Player player) {
        this.saveSnapshotAsync(player, null);
    }

    private void saveSnapshotAsync(Player player, GameMode forcedGameMode) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        SyncSnapshot snapshot = SyncSnapshot.capture(player, this.serverName, forcedGameMode);
        this.runAsync(() -> {
            this.upsertPlayerRow(uuid, username);
            this.saveSnapshot(uuid, snapshot);
        });
    }

    private void ensureSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS player_sync_state (uuid CHAR(36) PRIMARY KEY,last_server VARCHAR(32) NOT NULL,world_name VARCHAR(64) NULL,x DOUBLE NULL,y DOUBLE NULL,z DOUBLE NULL,yaw FLOAT NULL,pitch FLOAT NULL,game_mode VARCHAR(16) NOT NULL DEFAULT 'SURVIVAL',inventory_blob LONGBLOB NOT NULL,enderchest_blob LONGBLOB NOT NULL,stats_blob LONGBLOB NULL,health DOUBLE NOT NULL DEFAULT 20,food_level INT NOT NULL DEFAULT 20,saturation FLOAT NOT NULL DEFAULT 20,exhaustion FLOAT NOT NULL DEFAULT 0,exp FLOAT NOT NULL DEFAULT 0,level INT NOT NULL DEFAULT 0,total_experience INT NOT NULL DEFAULT 0,allow_flight TINYINT(1) NOT NULL DEFAULT 0,is_flying TINYINT(1) NOT NULL DEFAULT 0,fly_speed FLOAT NOT NULL DEFAULT 0.1,walk_speed FLOAT NOT NULL DEFAULT 0.2,fire_ticks INT NOT NULL DEFAULT 0,remaining_air INT NOT NULL DEFAULT 300,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)";
        String actionSql = "CREATE TABLE IF NOT EXISTS player_transfer_actions (uuid CHAR(36) PRIMARY KEY,action VARCHAR(255) NOT NULL,expires_at TIMESTAMP NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
        String logoutSql = "CREATE TABLE IF NOT EXISTS player_logout_meta (uuid CHAR(36) PRIMARY KEY,last_logout_server VARCHAR(32) NOT NULL,last_logout_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.executeUpdate();
            this.ensurePlayerSyncSchemaColumns(connection);
            this.ensureTransferActionColumnLength(connection);
            try (PreparedStatement ps2 = connection.prepareStatement(actionSql);){
                ps2.executeUpdate();
            }
            try (PreparedStatement ps3 = connection.prepareStatement(logoutSql);){
                ps3.executeUpdate();
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().severe("Failed creating player_sync_state table: " + e.getMessage());
        }
    }

    private void ensurePlayerSyncSchemaColumns(Connection connection) throws SQLException {
        this.ensureColumnExists(connection, "game_mode", "ALTER TABLE player_sync_state ADD COLUMN game_mode VARCHAR(16) NOT NULL DEFAULT 'SURVIVAL'");
        this.ensureColumnExists(connection, "allow_flight", "ALTER TABLE player_sync_state ADD COLUMN allow_flight TINYINT(1) NOT NULL DEFAULT 0");
        this.ensureColumnExists(connection, "is_flying", "ALTER TABLE player_sync_state ADD COLUMN is_flying TINYINT(1) NOT NULL DEFAULT 0");
        this.ensureColumnExists(connection, "fly_speed", "ALTER TABLE player_sync_state ADD COLUMN fly_speed FLOAT NOT NULL DEFAULT 0.1");
        this.ensureColumnExists(connection, "walk_speed", "ALTER TABLE player_sync_state ADD COLUMN walk_speed FLOAT NOT NULL DEFAULT 0.2");
    }

    private void ensureColumnExists(Connection connection, String columnName, String alterSql) throws SQLException {
        String checkSql = "SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'player_sync_state' AND COLUMN_NAME = ? LIMIT 1";
        try (PreparedStatement check = connection.prepareStatement(checkSql);){
            check.setString(1, columnName);
            try (ResultSet rs = check.executeQuery();){
                if (rs.next()) {
                    return;
                }
            }
        }
        try (PreparedStatement alter = connection.prepareStatement(alterSql);){
            alter.executeUpdate();
        }
    }

    private void ensureTransferActionColumnLength(Connection connection) throws SQLException {
        block18: {
            String checkSql = "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'player_transfer_actions' AND COLUMN_NAME = 'action' LIMIT 1";
            try (PreparedStatement check = connection.prepareStatement(checkSql);
                 ResultSet rs = check.executeQuery();){
                if (!rs.next() || rs.getInt(1) >= 255) break block18;
                try (PreparedStatement alter = connection.prepareStatement("ALTER TABLE player_transfer_actions MODIFY action VARCHAR(255) NOT NULL");){
                    alter.executeUpdate();
                }
            }
        }
    }

    private void upsertPlayerRow(UUID uuid, String username) {
        String sql = "INSERT INTO players (uuid, username, last_seen) VALUES (?, ?, CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE username=VALUES(username), last_seen=CURRENT_TIMESTAMP";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            this.plugin.getLogger().warning("Failed updating players row for " + username + ": " + e.getMessage());
        }
    }

    private void upsertLease(UUID uuid, String leaseToken) {
        String sql = "INSERT INTO session_leases (uuid, lease_token, server_name, expires_at, heartbeat_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE lease_token=VALUES(lease_token), server_name=VALUES(server_name), expires_at=VALUES(expires_at), heartbeat_at=VALUES(heartbeat_at)";
        Instant now = Instant.now();
        Timestamp heartbeatAt = Timestamp.from(now);
        Timestamp expiresAt = Timestamp.from(now.plusSeconds(this.leaseSeconds));
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            ps.setString(2, leaseToken);
            ps.setString(3, this.serverName);
            ps.setTimestamp(4, expiresAt);
            ps.setTimestamp(5, heartbeatAt);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            this.plugin.getLogger().warning("Failed updating session lease for " + String.valueOf(uuid) + ": " + e.getMessage());
        }
    }

    private void heartbeatActiveLeases() {
        if (this.activeLeases.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, String> entry : this.activeLeases.entrySet()) {
            this.upsertLease(entry.getKey(), entry.getValue());
        }
    }

    private void clearLeaseAsync(UUID uuid, String leaseToken) {
        this.runAsync(() -> {
            String sql = leaseToken == null || leaseToken.isBlank() ? "DELETE FROM session_leases WHERE uuid = ? AND server_name = ?" : "DELETE FROM session_leases WHERE uuid = ? AND lease_token = ? AND server_name = ?";
            try (Connection connection = this.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql);){
                ps.setString(1, uuid.toString());
                if (leaseToken == null || leaseToken.isBlank()) {
                    ps.setString(2, this.serverName);
                } else {
                    ps.setString(2, leaseToken);
                    ps.setString(3, this.serverName);
                }
                ps.executeUpdate();
            }
            catch (SQLException e) {
                this.plugin.getLogger().warning("Failed clearing session lease for " + String.valueOf(uuid) + ": " + e.getMessage());
            }
        });
    }

    private void runAsync(Runnable task) {
        if (!this.plugin.isEnabled()) {
            // Shutdown path: the scheduler rejects new tasks once the plugin is disabling,
            // which silently dropped final player snapshots. Run inline (blocking) instead.
            task.run();
            return;
        }
        if (this.foliaRuntime) {
            Bukkit.getAsyncScheduler().runNow((Plugin)this.plugin, scheduledTask -> task.run());
            return;
        }
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin)this.plugin, task);
    }

    private TaskHandle runAsyncRepeating(Runnable task, long initialDelayTicks, long periodTicks) {
        if (this.foliaRuntime) {
            long tickMs = 50L;
            ScheduledTask scheduled = Bukkit.getAsyncScheduler().runAtFixedRate((Plugin)this.plugin, scheduledTask -> task.run(), initialDelayTicks * tickMs, periodTicks * tickMs, TimeUnit.MILLISECONDS);
            return () -> ((ScheduledTask)scheduled).cancel();
        }
        BukkitTask bukkitTask = this.plugin.getServer().getScheduler().runTaskTimerAsynchronously((Plugin)this.plugin, task, initialDelayTicks, periodTicks);
        return () -> ((BukkitTask)bukkitTask).cancel();
    }

    private void runOnPlayerNow(Player player, Runnable task) {
        if (this.foliaRuntime) {
            player.getScheduler().run((Plugin)this.plugin, scheduledTask -> task.run(), null);
            return;
        }
        this.plugin.getServer().getScheduler().runTask((Plugin)this.plugin, task);
    }

    private void runOnPlayerLater(Player player, Runnable task, long delayTicks) {
        if (this.foliaRuntime) {
            player.getScheduler().runDelayed((Plugin)this.plugin, scheduledTask -> task.run(), null, delayTicks);
            return;
        }
        this.plugin.getServer().getScheduler().runTaskLater((Plugin)this.plugin, task, delayTicks);
    }

    private void saveSnapshot(UUID uuid, SyncSnapshot snapshot) {
        String sql = "INSERT INTO player_sync_state (uuid, last_server, world_name, x, y, z, yaw, pitch, game_mode, inventory_blob, enderchest_blob, stats_blob, health, food_level, saturation, exhaustion, exp, level, total_experience, allow_flight, is_flying, fly_speed, walk_speed, fire_ticks, remaining_air) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE last_server=VALUES(last_server), world_name=VALUES(world_name), x=VALUES(x), y=VALUES(y), z=VALUES(z), yaw=VALUES(yaw), pitch=VALUES(pitch), game_mode=VALUES(game_mode), inventory_blob=VALUES(inventory_blob), enderchest_blob=VALUES(enderchest_blob), stats_blob=VALUES(stats_blob), health=VALUES(health), food_level=VALUES(food_level), saturation=VALUES(saturation), exhaustion=VALUES(exhaustion), exp=VALUES(exp), level=VALUES(level), total_experience=VALUES(total_experience), allow_flight=VALUES(allow_flight), is_flying=VALUES(is_flying), fly_speed=VALUES(fly_speed), walk_speed=VALUES(walk_speed), fire_ticks=VALUES(fire_ticks), remaining_air=VALUES(remaining_air)";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            ps.setString(2, snapshot.lastServer);
            ps.setString(3, snapshot.worldName);
            if (snapshot.worldName == null) {
                ps.setNull(4, 8);
                ps.setNull(5, 8);
                ps.setNull(6, 8);
                ps.setNull(7, 6);
                ps.setNull(8, 6);
            } else {
                ps.setDouble(4, snapshot.x);
                ps.setDouble(5, snapshot.y);
                ps.setDouble(6, snapshot.z);
                ps.setFloat(7, snapshot.yaw);
                ps.setFloat(8, snapshot.pitch);
            }
            ps.setString(9, snapshot.gameMode);
            ps.setBytes(10, snapshot.inventoryBlob);
            ps.setBytes(11, snapshot.enderChestBlob);
            ps.setBytes(12, snapshot.statsBlob);
            ps.setDouble(13, snapshot.health);
            ps.setInt(14, snapshot.foodLevel);
            ps.setFloat(15, snapshot.saturation);
            ps.setFloat(16, snapshot.exhaustion);
            ps.setFloat(17, snapshot.exp);
            ps.setInt(18, snapshot.level);
            ps.setInt(19, snapshot.totalExperience);
            ps.setBoolean(20, snapshot.allowFlight);
            ps.setBoolean(21, snapshot.isFlying);
            ps.setFloat(22, snapshot.flySpeed);
            ps.setFloat(23, snapshot.walkSpeed);
            ps.setInt(24, snapshot.fireTicks);
            ps.setInt(25, snapshot.remainingAir);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            this.plugin.getLogger().warning("Failed saving sync snapshot for " + String.valueOf(uuid) + ": " + e.getMessage());
        }
    }

    private SyncSnapshot loadSnapshot(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        String sql = "SELECT * FROM player_sync_state WHERE uuid=? LIMIT 1";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery();){
                if (!rs.next()) {
                    return null;
                }
                return SyncSnapshot.fromResultSet(rs);
            }
        }
        catch (SQLException e) {
            this.plugin.getLogger().warning("Failed loading sync snapshot for " + String.valueOf(uuid) + ": " + e.getMessage());
            return null;
        }
    }

    private void applyInventoryAndState(Player player, SyncSnapshot snapshot) {
        ItemStack[] ender;
        GameMode currentMode;
        if (snapshot.gameMode != null) {
            try {
                if (this.debugStateLogging) {
                    this.plugin.getLogger().info("[sync-debug] stage=set_gamemode uuid=" + String.valueOf(player.getUniqueId()) + " server=" + this.serverName + " gm=" + snapshot.gameMode);
                }
                player.setGameMode(GameMode.valueOf((String)snapshot.gameMode));
            }
            catch (IllegalArgumentException illegalArgumentException) {
                // empty catch block
            }
        }
        boolean modeAllowsFlight = (currentMode = player.getGameMode()) == GameMode.CREATIVE || currentMode == GameMode.SPECTATOR;
        boolean bypassFlightPolicy = player.hasPermission("pizzasmp.gamemode.flight.bypass");
        boolean allowFlight = modeAllowsFlight || bypassFlightPolicy && snapshot.allowFlight;
        boolean isFlying = allowFlight && snapshot.isFlying;
        player.setAllowFlight(allowFlight);
        player.setFlying(isFlying);
        try {
            player.setFlySpeed(snapshot.flySpeed);
        }
        catch (IllegalArgumentException illegalArgumentException) {
            // empty catch block
        }
        try {
            player.setWalkSpeed(snapshot.walkSpeed);
        }
        catch (IllegalArgumentException illegalArgumentException) {
            // empty catch block
        }
        ItemStack[] main = PlayerSyncManager.deserializeItemStacks(snapshot.inventoryBlob);
        if (main != null) {
            player.getInventory().setContents(main);
        }
        if ((ender = PlayerSyncManager.deserializeItemStacks(snapshot.enderChestBlob)) != null) {
            player.getEnderChest().setContents(ender);
        }
        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) == null ? 20.0 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
        player.setHealth(Math.max(1.0, Math.min(maxHealth, snapshot.health)));
        player.setFoodLevel(Math.max(0, Math.min(20, snapshot.foodLevel)));
        player.setSaturation(Math.max(0.0f, snapshot.saturation));
        player.setExhaustion(Math.max(0.0f, snapshot.exhaustion));
        player.setLevel(Math.max(0, snapshot.level));
        player.setExp(Math.max(0.0f, Math.min(1.0f, snapshot.exp)));
        player.setTotalExperience(Math.max(0, snapshot.totalExperience));
        player.setFireTicks(Math.max(0, snapshot.fireTicks));
        player.setRemainingAir(Math.max(0, snapshot.remainingAir));
        Map<Statistic, Integer> stats = PlayerSyncManager.deserializeStats(snapshot.statsBlob);
        for (Map.Entry<Statistic, Integer> entry : stats.entrySet()) {
            try {
                player.setStatistic(entry.getKey(), Math.max(0, entry.getValue()));
            }
            catch (Exception exception) {}
        }
        player.updateInventory();
    }

    private void debugAppliedState(Player player, String stage) {
        if (!this.debugStateLogging || player == null) {
            return;
        }
        this.plugin.getLogger().info("[sync-debug] stage=" + stage + " uuid=" + String.valueOf(player.getUniqueId()) + " server=" + this.serverName + " gm=" + player.getGameMode().name() + " allowFlight=" + player.getAllowFlight() + " flying=" + player.isFlying() + " food=" + player.getFoodLevel() + " health=" + player.getHealth());
    }

    private Connection getConnection() throws SQLException {
        // Share PizzaNetworkCore's HikariCP pool instead of opening a per-op connection.
        if (this.plugin instanceof PizzaNetworkCore pnc) {
            return pnc.openSyncConnection();
        }
        if (!this.ensureDriverLoaded()) {
            throw new SQLException("MariaDB JDBC driver is not loaded");
        }
        return DriverManager.getConnection(this.dbUrl, this.dbUser, this.dbPassword);
    }

    private boolean ensureDriverLoaded() {
        if (this.driverLoaded) {
            return true;
        }
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            this.driverLoaded = true;
            return true;
        }
        catch (ClassNotFoundException e) {
            this.plugin.getLogger().severe("MariaDB JDBC driver is unavailable: " + e.getMessage());
            return false;
        }
    }

    private String detectServerName() {
        int port = Bukkit.getPort();
        if (port == 25566) {
            return "lobby";
        }
        if (port == 25567) {
            return "survival";
        }
        if (port == 25568) {
            return "pvp";
        }
        if (port == 25569) {
            return "maintenance";
        }
        return "unknown-" + port;
    }

    private boolean isLobbyLikeServer(String name) {
        if (name == null) {
            return false;
        }
        return "lobby".equalsIgnoreCase(name) || "maintenance".equalsIgnoreCase(name);
    }

    private boolean isInLobbySpawnIsland(double x, double z) {
        int bx = (int)Math.floor(x);
        int bz = (int)Math.floor(z);
        return bx >= -60 && bx <= 60 && bz >= -60 && bz <= 60;
    }

    private Location lobbySpawnCenter(World world) {
        return new Location(world, 0.5, 102.0, 10.5, 0.0f, 0.0f);
    }

    /*
     * Enabled aggressive exception aggregation
     */
    private static byte[] serializeItemStacks(ItemStack[] stacks) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();){
            byte[] byArray;
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream((OutputStream)bos);){
                out.writeInt(stacks.length);
                for (ItemStack stack : stacks) {
                    out.writeObject((Object)stack);
                }
                out.flush();
                byArray = bos.toByteArray();
            }
            return byArray;
        }
        catch (IOException e) {
            return new byte[0];
        }
    }

    /*
     * Enabled aggressive exception aggregation
     */
    private static ItemStack[] deserializeItemStacks(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);){
            ItemStack[] itemStackArray;
            try (BukkitObjectInputStream in = new BukkitObjectInputStream((InputStream)bis);){
                int size = in.readInt();
                ItemStack[] stacks = new ItemStack[size];
                for (int i = 0; i < size; ++i) {
                    Object raw = in.readObject();
                    stacks[i] = (ItemStack)raw;
                }
                itemStackArray = stacks;
            }
            return itemStackArray;
        }
        catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    /*
     * Enabled aggressive exception aggregation
     */
    private static byte[] serializeStats(Player player) {
        EnumMap<Statistic, Integer> values = new EnumMap<Statistic, Integer>(Statistic.class);
        for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() != Statistic.Type.UNTYPED) continue;
            try {
                values.put(statistic, player.getStatistic(statistic));
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();){
            Object object;
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream((OutputStream)bos);){
                out.writeInt(values.size());
                for (Map.Entry entry : values.entrySet()) {
                    out.writeUTF(((Statistic)entry.getKey()).name());
                    out.writeInt(((Integer)entry.getValue()).intValue());
                }
                out.flush();
                object = bos.toByteArray();
            }
            return (byte[])object;
        }
        catch (IOException e) {
            return new byte[0];
        }
    }

    private static Map<Statistic, Integer> deserializeStats(byte[] bytes) {
        EnumMap<Statistic, Integer> values = new EnumMap<Statistic, Integer>(Statistic.class);
        if (bytes == null || bytes.length == 0) {
            return values;
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream in = new BukkitObjectInputStream((InputStream)bis);){
            int size = in.readInt();
            for (int i = 0; i < size; ++i) {
                String statName = in.readUTF();
                int value = in.readInt();
                try {
                    Statistic statistic = Statistic.valueOf((String)statName);
                    values.put(statistic, value);
                    continue;
                }
                catch (IllegalArgumentException illegalArgumentException) {
                    // empty catch block
                }
            }
        }
        catch (IOException e) {
            values.clear();
        }
        return values;
    }

    private static interface TaskHandle {
        public void cancel();
    }

    private static final class SyncSnapshot {
        private final String lastServer;
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final String gameMode;
        private final byte[] inventoryBlob;
        private final byte[] enderChestBlob;
        private final byte[] statsBlob;
        private final double health;
        private final int foodLevel;
        private final float saturation;
        private final float exhaustion;
        private final float exp;
        private final int level;
        private final int totalExperience;
        private final boolean allowFlight;
        private final boolean isFlying;
        private final float flySpeed;
        private final float walkSpeed;
        private final int fireTicks;
        private final int remainingAir;

        private SyncSnapshot(String lastServer, String worldName, double x, double y, double z, float yaw, float pitch, String gameMode, byte[] inventoryBlob, byte[] enderChestBlob, byte[] statsBlob, double health, int foodLevel, float saturation, float exhaustion, float exp, int level, int totalExperience, boolean allowFlight, boolean isFlying, float flySpeed, float walkSpeed, int fireTicks, int remainingAir) {
            this.lastServer = lastServer;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.gameMode = gameMode;
            this.inventoryBlob = inventoryBlob;
            this.enderChestBlob = enderChestBlob;
            this.statsBlob = statsBlob;
            this.health = health;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
            this.exhaustion = exhaustion;
            this.exp = exp;
            this.level = level;
            this.totalExperience = totalExperience;
            this.allowFlight = allowFlight;
            this.isFlying = isFlying;
            this.flySpeed = flySpeed;
            this.walkSpeed = walkSpeed;
            this.fireTicks = fireTicks;
            this.remainingAir = remainingAir;
        }

        private static SyncSnapshot capture(Player player, String serverName, GameMode forcedGameMode) {
            Location location = player.getLocation();
            String worldName = location.getWorld() == null ? null : location.getWorld().getName();
            GameMode effectiveMode = forcedGameMode == null ? player.getGameMode() : forcedGameMode;
            boolean modeAllowsFlight = effectiveMode == GameMode.CREATIVE || effectiveMode == GameMode.SPECTATOR;
            return new SyncSnapshot(serverName, worldName, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), effectiveMode.name(), PlayerSyncManager.serializeItemStacks(player.getInventory().getContents()), PlayerSyncManager.serializeItemStacks(player.getEnderChest().getContents()), PlayerSyncManager.serializeStats(player), player.getHealth(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(), player.getExp(), player.getLevel(), player.getTotalExperience(), modeAllowsFlight ? true : player.getAllowFlight(), modeAllowsFlight && player.isFlying(), player.getFlySpeed(), player.getWalkSpeed(), player.getFireTicks(), player.getRemainingAir());
        }

        private static SyncSnapshot fromResultSet(ResultSet rs) throws SQLException {
            return new SyncSnapshot(rs.getString("last_server"), rs.getString("world_name"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"), rs.getString("game_mode"), rs.getBytes("inventory_blob"), rs.getBytes("enderchest_blob"), rs.getBytes("stats_blob"), rs.getDouble("health"), rs.getInt("food_level"), rs.getFloat("saturation"), rs.getFloat("exhaustion"), rs.getFloat("exp"), rs.getInt("level"), rs.getInt("total_experience"), rs.getBoolean("allow_flight"), rs.getBoolean("is_flying"), rs.getFloat("fly_speed"), rs.getFloat("walk_speed"), rs.getInt("fire_ticks"), rs.getInt("remaining_air"));
        }
    }

    private static final class LogoutMeta {
        private final String lastLogoutServer;
        private final Timestamp lastLogoutAt;

        private LogoutMeta(String lastLogoutServer, Timestamp lastLogoutAt) {
            this.lastLogoutServer = lastLogoutServer;
            this.lastLogoutAt = lastLogoutAt;
        }
    }
}
