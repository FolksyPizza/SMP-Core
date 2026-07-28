/*
 * MaintenanceQueueManager is part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Released under the MIT License (see LICENSE). Provided AS IS, without warranty.
 */
package dev.pizzasmp.networkcore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class MaintenanceQueueManager {
    private final JavaPlugin plugin;
    private final String serverName;
    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final boolean maintenanceMusicEnabled;
    private final Sound maintenanceMusicSound;
    private final long maintenanceMusicIntervalMillis;
    private final float maintenanceMusicVolume;
    private final float maintenanceMusicPitch;
    private final boolean maintenanceMusicDebug;
    private final boolean maintenanceQueueDebug;
    private final int returnBatchSize;
    private final long returnDrainIntervalTicks;
    private volatile boolean active;
    private volatile String message = "This server is currently under maintenance. Please try again later.";
    private volatile Set<String> targets = Set.of("survival", "pvp");
    private volatile boolean previousActive;
    private final ConcurrentHashMap<UUID, Long> nextMellohiAt = new ConcurrentHashMap();
    private final ConcurrentHashMap<UUID, Long> nextLobbyTransferAttemptAt = new ConcurrentHashMap();
    private BukkitTask refreshTask;
    private BukkitTask announceTask;
    private BukkitTask drainTask;

    MaintenanceQueueManager(JavaPlugin plugin, String serverName) {
        Sound resolvedSound;
        this.plugin = plugin;
        this.serverName = serverName;
        String host = plugin.getConfig().getString("sync.database.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("sync.database.port", 3306);
        String database = plugin.getConfig().getString("sync.database.name", "smpcore");
        String params = plugin.getConfig().getString("sync.database.parameters", "useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true");
        this.dbUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?" + params;
        this.dbUser = plugin.getConfig().getString("sync.database.user", "smpcore");
        this.dbPassword = plugin.getConfig().getString("sync.database.password", "changeme");
        this.maintenanceMusicEnabled = plugin.getConfig().getBoolean("maintenance_music.enabled", true);
        String configuredSound = plugin.getConfig().getString("maintenance_music.sound", Sound.MUSIC_DISC_MELLOHI.name());
        try {
            resolvedSound = Sound.valueOf((String)(configuredSound == null ? Sound.MUSIC_DISC_MELLOHI.name() : configuredSound.toUpperCase()));
        }
        catch (IllegalArgumentException ignored) {
            resolvedSound = Sound.MUSIC_DISC_MELLOHI;
        }
        this.maintenanceMusicSound = resolvedSound;
        long intervalSeconds = Math.max(30L, plugin.getConfig().getLong("maintenance_music.interval_seconds", 190L));
        this.maintenanceMusicIntervalMillis = intervalSeconds * 1000L;
        this.maintenanceMusicVolume = (float)plugin.getConfig().getDouble("maintenance_music.volume", 0.6);
        this.maintenanceMusicPitch = (float)plugin.getConfig().getDouble("maintenance_music.pitch", 1.0);
        this.maintenanceMusicDebug = plugin.getConfig().getBoolean("maintenance_music.debug_log", false);
        this.maintenanceQueueDebug = plugin.getConfig().getBoolean("maintenance_queue.debug_log", false);
        this.returnBatchSize = Math.max(1, plugin.getConfig().getInt("maintenance_queue.return_batch_size", 2));
        this.returnDrainIntervalTicks = Math.max(1L, plugin.getConfig().getLong("maintenance_queue.return_drain_interval_ticks", 20L));
    }

    void start() {
        this.ensureSchema();
        this.refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously((Plugin)this.plugin, this::refreshState, 20L, 20L);
        if ("lobby".equalsIgnoreCase(this.serverName) || "maintenance".equalsIgnoreCase(this.serverName)) {
            this.announceTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, this::broadcastHoldState, 20L, 20L);
        }
        if ("lobby".equalsIgnoreCase(this.serverName) || "maintenance".equalsIgnoreCase(this.serverName)) {
            this.drainTask = Bukkit.getScheduler().runTaskTimerAsynchronously((Plugin)this.plugin, this::drainQueueIfAllowed, 40L, this.returnDrainIntervalTicks);
        }
    }

    void shutdown() {
        if (this.refreshTask != null) {
            this.refreshTask.cancel();
        }
        if (this.announceTask != null) {
            this.announceTask.cancel();
        }
        if (this.drainTask != null) {
            this.drainTask.cancel();
        }
    }

    boolean isActive() {
        return this.active;
    }

    boolean isServerUnderMaintenance(String targetServer) {
        if (!this.active || targetServer == null || targetServer.isBlank()) {
            return false;
        }
        return this.targets.contains(targetServer.toLowerCase());
    }

    String getMaintenanceMessage() {
        return this.message;
    }

    void handleJoin(Player player) {
        String desired;
        if ("maintenance".equalsIgnoreCase(this.serverName) && this.isQueued(player.getUniqueId())) {
            this.enqueue(player);
            return;
        }
        if (!this.active) {
            return;
        }
        if (!"maintenance".equalsIgnoreCase(this.serverName) && this.isServerUnderMaintenance(this.serverName)) {
            this.enqueue(player);
            this.connectToServer(player, "maintenance");
            return;
        }
        if ("lobby".equalsIgnoreCase(this.serverName) && this.shouldHoldPlayer(player.getUniqueId())) {
            this.enqueue(player);
            this.transferLobbyPlayerToMaintenance(player);
            return;
        }
        if ("lobby".equalsIgnoreCase(this.serverName) && (desired = this.resolvePostMaintenanceTarget(player.getUniqueId())) != null && !desired.isBlank() && !"lobby".equalsIgnoreCase(desired) && !this.isServerUnderMaintenance(desired)) {
            this.connectToServer(player, desired);
            return;
        }
        if ("maintenance".equalsIgnoreCase(this.serverName)) {
            this.enqueue(player);
        }
    }

    private void refreshState() {
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT active, message, targets_csv FROM maintenance_state WHERE id=1");
             ResultSet rs = ps.executeQuery();){
            if (rs.next()) {
                boolean nowActive = rs.getBoolean("active");
                this.message = rs.getString("message");
                this.targets = this.parseTargetsCsv(rs.getString("targets_csv"));
                if (this.previousActive && !nowActive) {
                    Bukkit.getScheduler().runTask((Plugin)this.plugin, this::stopMellohiForAll);
                }
                this.previousActive = nowActive;
                this.active = nowActive;
                if (nowActive) {
                    Bukkit.getScheduler().runTask((Plugin)this.plugin, this::offloadMaintainedServerPlayers);
                }
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
    }

    private void broadcastHoldState() {
        if (!this.active) {
            if ("maintenance".equalsIgnoreCase(this.serverName)) {
                this.stopMellohiForAll();
            }
            return;
        }
        if ("lobby".equalsIgnoreCase(this.serverName)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!this.shouldHoldPlayer(player.getUniqueId())) continue;
                this.enqueue(player);
                this.transferLobbyPlayerToMaintenance(player);
            }
            return;
        }
        if (!"maintenance".equalsIgnoreCase(this.serverName)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.enqueue(player);
            int pos = this.queuePosition(player.getUniqueId());
            String holdMessage = this.message == null ? "This server is currently under maintenance. Please try again later." : this.message;
            player.sendActionBar((Component)Component.text((String)(holdMessage + " \u00a78| \u00a77Queue: \u00a79#" + pos)));
            if (!this.maintenanceMusicEnabled) continue;
            player.stopSound(SoundCategory.MUSIC);
            player.stopSound(SoundCategory.AMBIENT);
            long next = this.nextMellohiAt.getOrDefault(player.getUniqueId(), 0L);
            if (now < next) continue;
            player.stopSound(this.maintenanceMusicSound);
            try {
                player.playSound((Entity)player, this.maintenanceMusicSound, SoundCategory.RECORDS, this.maintenanceMusicVolume, this.maintenanceMusicPitch);
            }
            catch (Throwable ignored) {
                player.playSound(player.getLocation(), this.maintenanceMusicSound, SoundCategory.RECORDS, this.maintenanceMusicVolume, this.maintenanceMusicPitch);
            }
            this.nextMellohiAt.put(player.getUniqueId(), now + this.maintenanceMusicIntervalMillis);
            if (!this.maintenanceMusicDebug) continue;
            this.plugin.getLogger().info("[maintenance-music] play uuid=" + String.valueOf(player.getUniqueId()) + " next_at_ms=" + (now + this.maintenanceMusicIntervalMillis));
        }
    }

    private void transferLobbyPlayerToMaintenance(Player player) {
        this.transferPlayerToMaintenance(player, 5000L);
    }

    private void offloadMaintainedServerPlayers() {
        if (!this.active) {
            return;
        }
        if ("maintenance".equalsIgnoreCase(this.serverName) || "lobby".equalsIgnoreCase(this.serverName)) {
            return;
        }
        if (!this.isServerUnderMaintenance(this.serverName)) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.enqueue(player);
            this.transferPlayerToMaintenance(player, 3000L);
        }
    }

    private void transferPlayerToMaintenance(Player player, long cooldownMs) {
        long nextAllowed;
        long now = System.currentTimeMillis();
        if (now < (nextAllowed = this.nextLobbyTransferAttemptAt.getOrDefault(player.getUniqueId(), 0L).longValue())) {
            return;
        }
        this.nextLobbyTransferAttemptAt.put(player.getUniqueId(), now + Math.max(1000L, cooldownMs));
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            this.connectToServer(player, "maintenance");
        });
    }

    private void enqueue(Player player) {
        int priority = this.priorityFor(player);
        String desired = this.resolvePreferredServerForMaintenance(player.getUniqueId());
        if (desired == null || desired.isBlank()) {
            desired = this.resolveTargetServer(player.getUniqueId());
        }
        if ((desired = this.normalizeServerName(desired)).isBlank() || "maintenance".equalsIgnoreCase(desired)) {
            desired = "lobby";
        }
        String reason = this.active ? "maintenance_active" : "maintenance_return";
        String sql = "INSERT INTO maintenance_queue (uuid, player_name, desired_server, desired_reason, priority, status) VALUES (?, ?, ?, ?, ?, 'WAITING') ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), desired_server=VALUES(desired_server), desired_reason=VALUES(desired_reason), priority=VALUES(priority), status='WAITING'";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.setString(3, desired);
            ps.setString(4, reason);
            ps.setInt(5, priority);
            ps.executeUpdate();
            this.upsertReturnQueue(connection, player.getUniqueId(), player.getName(), desired, reason);
            if (this.maintenanceQueueDebug) {
                this.plugin.getLogger().info("[maintenance-queue-debug] stage=enqueue uuid=" + String.valueOf(player.getUniqueId()) + " server=" + this.serverName + " desired=" + desired + " reason=" + reason + " priority=" + priority);
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
    }

    private int priorityFor(Player player) {
        if (player.hasPermission("pizzasmp.queue.staff") || player.hasPermission("group.staff") || player.hasPermission("group.admin")) {
            return 300;
        }
        if (player.hasPermission("pizzasmp.queue.plus") || player.hasPermission("group.pizzaplus")) {
            return 200;
        }
        return 100;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private int queuePosition(UUID uuid) {
        String q = "SELECT 1 + COUNT(*) FROM maintenance_queue q1 JOIN maintenance_queue q2 ON (q2.priority > q1.priority OR (q2.priority = q1.priority AND q2.enqueued_at < q1.enqueued_at)) WHERE q1.uuid=? AND q1.status='WAITING' AND q2.status='WAITING'";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(q);){
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery();){
                if (!rs.next()) return 1;
                int n = Math.max(1, rs.getInt(1));
                return n;
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return 1;
    }

    // True if this player has a WAITING entry in the maintenance queue. Reconstructed after the
    // original source for this method was lost; mirrors the queuePosition/enqueue queries.
    private boolean isQueued(UUID uuid) {
        String sql = "SELECT 1 FROM maintenance_queue WHERE uuid=? AND status='WAITING' LIMIT 1";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException sQLException) {
            return false;
        }
    }

    private boolean shouldHoldPlayer(UUID uuid) {
        if (!this.active) {
            return false;
        }
        String preferred = this.resolvePreferredServerForMaintenance(uuid);
        return preferred != null && !preferred.isBlank() && this.isServerUnderMaintenance(preferred);
    }

    private void drainQueueIfAllowed() {
        if (this.active) {
            return;
        }
        List<QueueEntry> entries = this.nextEntries(this.returnBatchSize);
        for (QueueEntry entry : entries) {
            Player player = Bukkit.getPlayer((UUID)entry.uuid);
            if (player == null || !player.isOnline()) {
                this.removeEntry(entry.uuid);
                continue;
            }
            String target = this.resolveTargetServer(entry.uuid);
            if (entry.desiredServer != null && !entry.desiredServer.isBlank()) {
                target = entry.desiredServer;
            }
            if ("maintenance".equalsIgnoreCase(this.serverName)) {
                target = entry.desiredServer != null && !entry.desiredServer.isBlank() && !"maintenance".equalsIgnoreCase(entry.desiredServer) ? entry.desiredServer : this.resolvePostMaintenanceTarget(entry.uuid);
            }
            if (target == null || target.isBlank() || "maintenance".equalsIgnoreCase(target)) {
                target = "lobby";
            }
            this.markAttempt(entry.uuid);
            if (this.maintenanceQueueDebug) {
                this.plugin.getLogger().info("[maintenance-queue-debug] stage=return_attempt uuid=" + String.valueOf(entry.uuid) + " from=" + this.serverName + " target=" + target + " attempts=" + (entry.attempts + 1));
            }
            this.connectToServer(player, target);
            this.removeEntry(entry.uuid);
        }
    }

    private List<QueueEntry> nextEntries(int limit) {
        ArrayList<QueueEntry> out = new ArrayList<QueueEntry>();
        String sql = "SELECT uuid, desired_server, attempts FROM maintenance_queue WHERE status='WAITING' AND (last_attempt_at IS NULL OR TIMESTAMPDIFF(SECOND, last_attempt_at, CURRENT_TIMESTAMP) >= LEAST(30, 5 + attempts * 5)) ORDER BY priority DESC, enqueued_at ASC LIMIT ?";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery();){
                while (rs.next()) {
                    out.add(new QueueEntry(UUID.fromString(rs.getString("uuid")), rs.getString("desired_server"), rs.getInt("attempts")));
                }
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return out;
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private String resolveTargetServer(UUID uuid) {
        String sql = "SELECT last_server FROM player_sync_state WHERE uuid = ?";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery();){
                if (!rs.next()) return "survival";
                String string = rs.getString("last_server");
                return string;
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return "survival";
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private String resolveLastLogoutServer(UUID uuid) {
        String sql = "SELECT last_logout_server FROM player_logout_meta WHERE uuid = ?";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery();){
                if (!rs.next()) return "";
                String string = this.normalizeServerName(rs.getString("last_logout_server"));
                return string;
            }
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        return "";
    }

    private String resolvePreferredServerForMaintenance(UUID uuid) {
        String lastLogout = this.resolveLastLogoutServer(uuid);
        if (!lastLogout.isBlank() && this.isServerUnderMaintenance(lastLogout)) {
            return lastLogout;
        }
        String lastKnown = this.normalizeServerName(this.resolveTargetServer(uuid));
        if (!lastKnown.isBlank() && this.isServerUnderMaintenance(lastKnown)) {
            return lastKnown;
        }
        return "";
    }

    private String resolvePostMaintenanceTarget(UUID uuid) {
        String lastLogout = this.resolveLastLogoutServer(uuid);
        if (!lastLogout.isBlank() && !"maintenance".equalsIgnoreCase(lastLogout)) {
            return lastLogout;
        }
        String lastKnown = this.normalizeServerName(this.resolveTargetServer(uuid));
        if (!lastKnown.isBlank() && !"maintenance".equalsIgnoreCase(lastKnown)) {
            return lastKnown;
        }
        return "lobby";
    }

    private String normalizeServerName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase();
        if ("main".equals(normalized)) {
            return "survival";
        }
        return normalized;
    }

    private void removeEntry(UUID uuid) {
        String sql = "DELETE FROM maintenance_queue WHERE uuid = ?";
        String returnSql = "DELETE FROM maintenance_return_queue WHERE uuid = ?";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             PreparedStatement ps2 = connection.prepareStatement(returnSql);){
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
            ps2.setString(1, uuid.toString());
            ps2.executeUpdate();
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
        this.nextMellohiAt.remove(uuid);
        this.nextLobbyTransferAttemptAt.remove(uuid);
        if (this.maintenanceQueueDebug) {
            this.plugin.getLogger().info("[maintenance-queue-debug] stage=remove uuid=" + String.valueOf(uuid) + " server=" + this.serverName);
        }
    }

    private void markAttempt(UUID uuid) {
        String sql = "UPDATE maintenance_queue SET attempts = attempts + 1, last_attempt_at = CURRENT_TIMESTAMP WHERE uuid = ?";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
    }

    private void connectToServer(Player player, String serverName) {
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream(bytes);){
                out.writeUTF("Connect");
                out.writeUTF(serverName);
                player.sendPluginMessage((Plugin)this.plugin, "BungeeCord", bytes.toByteArray());
            }
            catch (IOException iOException) {
                // empty catch block
            }
        });
    }

    private void ensureSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS maintenance_queue (uuid CHAR(36) PRIMARY KEY,player_name VARCHAR(16) NOT NULL,desired_server VARCHAR(32) NOT NULL DEFAULT 'lobby',desired_reason VARCHAR(64) NOT NULL DEFAULT 'maintenance',priority INT NOT NULL DEFAULT 100,attempts INT NOT NULL DEFAULT 0,last_attempt_at TIMESTAMP NULL,status ENUM('WAITING','RETURNED') NOT NULL DEFAULT 'WAITING',enqueued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)";
        String returnSql = "CREATE TABLE IF NOT EXISTS maintenance_return_queue (uuid CHAR(36) PRIMARY KEY,username_snapshot VARCHAR(32) NOT NULL,desired_server VARCHAR(32) NOT NULL,desired_reason VARCHAR(64) NOT NULL DEFAULT 'maintenance',attempts INT NOT NULL DEFAULT 0,last_attempt_at TIMESTAMP NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)";
        try (Connection connection = this.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);){
            ps.executeUpdate();
            try (PreparedStatement ps2 = connection.prepareStatement(returnSql);){
                ps2.executeUpdate();
            }
            this.ensureQueueColumnExists(connection, "desired_server", "ALTER TABLE maintenance_queue ADD COLUMN desired_server VARCHAR(32) NOT NULL DEFAULT 'lobby'");
            this.ensureQueueColumnExists(connection, "desired_reason", "ALTER TABLE maintenance_queue ADD COLUMN desired_reason VARCHAR(64) NOT NULL DEFAULT 'maintenance'");
            this.ensureQueueColumnExists(connection, "attempts", "ALTER TABLE maintenance_queue ADD COLUMN attempts INT NOT NULL DEFAULT 0");
            this.ensureQueueColumnExists(connection, "last_attempt_at", "ALTER TABLE maintenance_queue ADD COLUMN last_attempt_at TIMESTAMP NULL");
        }
        catch (SQLException sQLException) {
            // empty catch block
        }
    }

    private void ensureQueueColumnExists(Connection connection, String columnName, String alterSql) throws SQLException {
        String checkSql = "SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='maintenance_queue' AND COLUMN_NAME=? LIMIT 1";
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

    private void upsertReturnQueue(Connection connection, UUID uuid, String username, String desiredServer, String desiredReason) throws SQLException {
        String sql = "INSERT INTO maintenance_return_queue (uuid, username_snapshot, desired_server, desired_reason, attempts) VALUES (?, ?, ?, ?, 0) ON DUPLICATE KEY UPDATE username_snapshot=VALUES(username_snapshot), desired_server=VALUES(desired_server), desired_reason=VALUES(desired_reason)";
        try (PreparedStatement ps = connection.prepareStatement(sql);){
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, desiredServer);
            ps.setString(4, desiredReason);
            ps.executeUpdate();
        }
    }

    private Connection getConnection() throws SQLException {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        }
        catch (ClassNotFoundException e) {
            throw new SQLException("mariadb driver missing", e);
        }
        return DriverManager.getConnection(this.dbUrl, this.dbUser, this.dbPassword);
    }

    private Set<String> parseTargetsCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of("survival", "pvp");
        }
        HashSet<String> out = new HashSet<String>();
        for (String part : csv.split(",")) {
            String target;
            String string = target = part == null ? "" : part.trim().toLowerCase();
            if (target.isEmpty()) continue;
            out.add(target);
        }
        if (out.isEmpty()) {
            out.add("survival");
            out.add("pvp");
        }
        return Set.copyOf(out);
    }

    private void stopMellohi(Player player) {
        player.stopSound(this.maintenanceMusicSound);
        this.nextMellohiAt.remove(player.getUniqueId());
        if (this.maintenanceMusicDebug) {
            this.plugin.getLogger().info("[maintenance-music] stop uuid=" + String.valueOf(player.getUniqueId()));
        }
    }

    private void stopMellohiForAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.stopMellohi(player);
        }
    }

    private static final class QueueEntry {
        private final UUID uuid;
        private final String desiredServer;
        private final int attempts;

        private QueueEntry(UUID uuid, String desiredServer, int attempts) {
            this.uuid = uuid;
            this.desiredServer = desiredServer;
            this.attempts = attempts;
        }
    }
}

