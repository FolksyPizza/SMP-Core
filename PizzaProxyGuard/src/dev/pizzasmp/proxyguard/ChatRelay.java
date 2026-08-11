/*
 * Cross-server chat relay, absorbed into PizzaProxyGuard.
 *
 * Was a standalone plugin (PizzaVelocityBridge). Velocity permits one @Plugin per jar, so
 * this is now a plain component that PizzaProxyGuard constructs and registers as a
 * listener — one proxy plugin owning both jobs: gatekeeping and chat.
 *
 * What it does: backends send global chat / DMs / team chat over the "pizzasmp:bridge"
 * plugin-message channel; this fans each message back out to EVERY player on the network,
 * honouring their "public_chat" setting read from the shared database. It also maintains
 * session leases (which player is on which backend), which is what lets a DM find someone
 * on another server.
 *
 * The join-denial and kick-mediation handlers that used to live here were REMOVED —
 * PizzaProxyGuard owns those, and having both set a result on the same event is a bug.
 */
package dev.pizzasmp.proxyguard;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatRelay {

    private static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("pizzasmp:bridge");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeLeaseTokens = new ConcurrentHashMap<>();
    private final Set<String> unavailableNotified = ConcurrentHashMap.newKeySet();
    private ScheduledTask leaseHeartbeatTask;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;
    private int leaseSeconds;

    /** Owning plugin instance — Velocity keys scheduled tasks and plugin messages on it. */
    private final Object owner;

    ChatRelay(Object owner, ProxyServer proxy, Logger logger, Path dataDirectory) {
        this.owner = owner;
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(com.velocitypowered.api.event.proxy.ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
        loadConfig();
        loadDriver();
        ensureSchema();
        leaseHeartbeatTask = proxy.getScheduler()
            .buildTask(owner, this::heartbeatActivePlayers)
            .repeat(15L, TimeUnit.SECONDS)
            .schedule();
        logger.info("Chat relay enabled on channel {}", CHANNEL.getId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        replyTargets.remove(uuid);
        replyTargets.entrySet().removeIf(entry -> entry.getValue().equals(uuid));
        clearLease(uuid, activeLeaseTokens.remove(uuid));
        unavailableNotified.removeIf(entry -> entry.startsWith(uuid + ":"));
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName().toLowerCase();
        String leaseToken = UUID.randomUUID().toString();
        activeLeaseTokens.put(player.getUniqueId(), leaseToken);
        upsertPlayerAndLease(player, serverName, leaseToken);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.getId().equals(event.getIdentifier().getId())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection serverConnection)) {
            return;
        }
        Player sender = serverConnection.getPlayer();

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String action = in.readUTF();
            switch (action) {
                case "CHAT" -> handleGlobalChat(in);
                case "MSG" -> handleDirectMessage(in, false);
                case "REPLY" -> handleDirectMessage(in, true);
                case "TEAM" -> handleTeamChat(in);
                case "RTPREQ" -> handleRtpRequest(in);
                case "RTPRES" -> handleRtpResult(in);
                default -> logger.debug("Unknown bridge action {}", action);
            }
        } catch (IOException ex) {
            logger.warn("Failed to parse bridge payload from {}: {}", sender.getUsername(), ex.getMessage());
        }
    }

    /**
     * Relay an RTP request from whatever backend the player is on to the one that owns
     * the destination world.
     *
     * Only the destination backend can decide whether a spot is safe — it alone has
     * those worlds loaded. Doing this over the proxy (rather than transferring the
     * player first and searching afterwards) is what makes cross-server /rtp land the
     * player directly instead of visibly dropping them at their arrival point first.
     *
     * FUTURE (distributed Folia): the world will be split across several Folia
     * processes. This is the natural place to pick one at random and address the
     * request to it, since the proxy already holds the routing table. The wire format
     * carries a target server name for exactly that reason.
     */
    private void handleRtpRequest(DataInputStream in) throws IOException {
        String uuid = in.readUTF();
        String dimension = in.readUTF();
        String originServer = in.readUTF();
        String targetServer = in.readUTF();

        RegisteredServer target = proxy.getServer(targetServer).orElse(null);
        logger.info("[rtp] stage=proxy_request uuid={} dim={} origin={} target={} targetKnown={} targetPlayers={}",
            uuid, dimension, originServer, targetServer, target != null,
            target == null ? -1 : target.getPlayersConnected().size());
        if (target == null || target.getPlayersConnected().isEmpty()) {
            // A plugin message needs a live connection to that backend. With nobody on
            // it there is no channel, so tell the origin to fall back to its old
            // behaviour: transfer the player, then search on arrival.
            replyRtpUnavailable(uuid, originServer);
            return;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("RTPFIND");
            out.writeUTF(uuid);
            out.writeUTF(dimension);
            out.writeUTF(originServer);
            boolean sent = target.sendPluginMessage(CHANNEL, bytes.toByteArray());
            logger.info("[rtp] stage=proxy_relay_find uuid={} sent={}", uuid, sent);
        }
    }

    /** Destination backend answered with coordinates; hand them back to the origin. */
    private void handleRtpResult(DataInputStream in) throws IOException {
        String uuid = in.readUTF();
        String originServer = in.readUTF();
        String world = in.readUTF();
        double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();

        RegisteredServer origin = proxy.getServer(originServer).orElse(null);
        if (origin == null) {
            logger.warn("[rtp] stage=proxy_result uuid={} origin={} UNKNOWN_SERVER", uuid, originServer);
            return;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("RTPGO");
            out.writeUTF(uuid);
            out.writeUTF(world);
            out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
            boolean sent = origin.sendPluginMessage(CHANNEL, bytes.toByteArray());
            logger.info("[rtp] stage=proxy_result uuid={} origin={} at={} {},{},{} sent={}",
                uuid, originServer, world, (long) x, (long) y, (long) z, sent);
        }
    }

    private void replyRtpUnavailable(String uuid, String originServer) throws IOException {
        RegisteredServer origin = proxy.getServer(originServer).orElse(null);
        if (origin == null) return;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("RTPFALLBACK");
            out.writeUTF(uuid);
            boolean sent = origin.sendPluginMessage(CHANNEL, bytes.toByteArray());
            logger.info("[rtp] stage=proxy_fallback uuid={} origin={} sent={}", uuid, originServer, sent);
        }
    }

    private void handleGlobalChat(DataInputStream in) throws IOException {
        UUID senderUuid = UUID.fromString(in.readUTF());
        String senderName = in.readUTF();
        String server = in.readUTF();
        String prefix = in.readUTF();
        String message = in.readUTF();

        String formatted = colorize(prefix) + "§f" + senderName + " §8[" + server + "] §8» §f" + message;
        Component out = LEGACY.deserialize(formatted);
        for (Player player : proxy.getAllPlayers()) {
            if (!isSettingEnabled(player.getUniqueId(), "public_chat", true)) {
                continue;
            }
            player.sendMessage(out);
        }
        replyTargets.putIfAbsent(senderUuid, senderUuid);
    }

    private void handleDirectMessage(DataInputStream in, boolean isReply) throws IOException {
        UUID senderUuid = UUID.fromString(in.readUTF());
        String senderName = in.readUTF();
        String targetToken = in.readUTF();
        String message = in.readUTF();

        Player sender = proxy.getPlayer(senderUuid).orElse(null);
        if (sender == null) {
            return;
        }

        Player target;
        if (isReply) {
            UUID replyTargetUuid = replyTargets.get(senderUuid);
            if (replyTargetUuid == null) {
                sender.sendMessage(LEGACY.deserialize("§cNo recent conversation to reply to."));
                return;
            }
            target = proxy.getPlayer(replyTargetUuid).orElse(null);
        } else {
            target = findOnlineByName(targetToken).orElse(null);
        }

        if (target == null) {
            sender.sendMessage(LEGACY.deserialize("§cThat player is not online."));
            return;
        }
        if (!isSettingEnabled(target.getUniqueId(), "private_messages", true)) {
            sender.sendMessage(LEGACY.deserialize("§cThat player has private messages disabled."));
            return;
        }

        target.sendMessage(LEGACY.deserialize("§d[From " + senderName + "] §f" + message));
        sender.sendMessage(LEGACY.deserialize("§d[To " + target.getUsername() + "] §f" + message));

        replyTargets.put(senderUuid, target.getUniqueId());
        replyTargets.put(target.getUniqueId(), senderUuid);
    }

    private void handleTeamChat(DataInputStream in) throws IOException {
        UUID senderUuid = UUID.fromString(in.readUTF());
        String senderName = in.readUTF();
        String server = in.readUTF();
        String prefix = in.readUTF();
        String message = in.readUTF();
        String recipientsCsv = in.readUTF();

        Set<String> recipientSet = Set.copyOf(Arrays.asList(recipientsCsv.split(",")));
        String formatted = "§b[Team] " + colorize(prefix) + "§f" + senderName + " §8[" + server + "] §8» §f" + message;
        Component out = LEGACY.deserialize(formatted);
        for (Player player : proxy.getAllPlayers()) {
            if (recipientSet.contains(player.getUniqueId().toString())) {
                player.sendMessage(out);
            }
        }
        replyTargets.putIfAbsent(senderUuid, senderUuid);
    }

    private Optional<Player> findOnlineByName(String token) {
        String needle = token == null ? "" : token.trim().toLowerCase();
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        for (Player player : proxy.getAllPlayers()) {
            if (player.getUsername().equalsIgnoreCase(needle)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    private void heartbeatActivePlayers() {
        for (Player player : proxy.getAllPlayers()) {
            String serverName = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName().toLowerCase())
                .orElse("lobby");
            String leaseToken = activeLeaseTokens.computeIfAbsent(player.getUniqueId(), ignored -> UUID.randomUUID().toString());
            upsertPlayerAndLease(player, serverName, leaseToken);
        }
    }

    private void upsertPlayerAndLease(Player player, String serverName, String leaseToken) {
        if (player == null || serverName == null || leaseToken == null) {
            return;
        }
        String playerSql = "INSERT INTO players (uuid, username, last_seen) VALUES (?, ?, CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE username=VALUES(username), last_seen=CURRENT_TIMESTAMP";
        // Expiry is computed by the DATABASE. A Java-side Timestamp is sent in the JVM's
        // local zone; against a UTC database on a non-UTC host every lease landed hours in
        // the past, so `expires_at > CURRENT_TIMESTAMP` never matched and the whole network
        // read as empty — no cross-backend completion, no cross-backend /tpa or /msg.
        String leaseSql = "INSERT INTO session_leases (uuid, lease_token, server_name, expires_at, heartbeat_at) " +
            "VALUES (?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND), CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE lease_token=VALUES(lease_token), server_name=VALUES(server_name), expires_at=VALUES(expires_at), heartbeat_at=VALUES(heartbeat_at)";
        try (Connection connection = getConnection();
             PreparedStatement playerPs = connection.prepareStatement(playerSql);
             PreparedStatement leasePs = connection.prepareStatement(leaseSql)) {
            playerPs.setString(1, player.getUniqueId().toString());
            playerPs.setString(2, player.getUsername());
            playerPs.executeUpdate();

            leasePs.setString(1, player.getUniqueId().toString());
            leasePs.setString(2, leaseToken);
            leasePs.setString(3, serverName);
            leasePs.setLong(4, Math.max(30, leaseSeconds));
            leasePs.executeUpdate();
        } catch (Exception ex) {
            logger.warn("Failed updating proxy session lease for {}: {}", player.getUsername(), ex.getMessage());
        }
    }

    private void clearLease(UUID uuid, String leaseToken) {
        if (uuid == null) {
            return;
        }
        String sql = "DELETE FROM session_leases WHERE uuid=? " +
            (leaseToken == null || leaseToken.isBlank() ? "" : "AND lease_token=?");
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            if (leaseToken != null && !leaseToken.isBlank()) {
                ps.setString(2, leaseToken);
            }
            ps.executeUpdate();
        } catch (Exception ex) {
            logger.warn("Failed clearing proxy session lease for {}: {}", uuid, ex.getMessage());
        }
    }

    private boolean isSettingEnabled(UUID uuid, String key, boolean defaultValue) {
        if (uuid == null || key == null || key.isBlank()) {
            return defaultValue;
        }
        String sql = "SELECT settings_blob FROM player_settings WHERE uuid=? LIMIT 1";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String blob = rs.getString(1);
                    return parseSettingsBlob(blob).getOrDefault(key.toLowerCase(), defaultValue);
                }
            }
        } catch (Exception ex) {
            logger.warn("Failed reading player setting {} for {}: {}", key, uuid, ex.getMessage());
        }
        return defaultValue;
    }

    private Map<String, Boolean> parseSettingsBlob(String blob) {
        if (blob == null || blob.isBlank()) {
            return Map.of();
        }
        Map<String, Boolean> out = new ConcurrentHashMap<>();
        for (String raw : blob.split("\\R")) {
            if (raw == null || raw.isBlank() || !raw.contains("=")) {
                continue;
            }
            String[] kv = raw.split("=", 2);
            String key = kv[0].trim().toLowerCase();
            String value = kv[1].trim();
            out.put(key, "1".equals(value) || "true".equalsIgnoreCase(value));
        }
        return out;
    }

    private void ensureSchema() {
        String playersSql = "CREATE TABLE IF NOT EXISTS players (" +
            "uuid CHAR(36) PRIMARY KEY," +
            "username VARCHAR(16) NOT NULL," +
            "first_joined TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "last_seen TIMESTAMP NULL DEFAULT NULL," +
            "playtime_seconds BIGINT NOT NULL DEFAULT 0," +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)";
        String sessionSql = "CREATE TABLE IF NOT EXISTS session_leases (" +
            "uuid CHAR(36) PRIMARY KEY," +
            "lease_token CHAR(36) NOT NULL," +
            "server_name VARCHAR(32) NOT NULL," +
            "expires_at TIMESTAMP NOT NULL," +
            "heartbeat_at TIMESTAMP NOT NULL," +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
            "INDEX idx_session_server (server_name)," +
            "INDEX idx_session_expires (expires_at))";
        try (Connection connection = getConnection();
             PreparedStatement ps1 = connection.prepareStatement(playersSql);
             PreparedStatement ps2 = connection.prepareStatement(sessionSql)) {
            ps1.executeUpdate();
            ps2.executeUpdate();
        } catch (Exception ex) {
            logger.warn("Failed ensuring proxy bridge DB schema: {}", ex.getMessage());
        }
    }

    /** Package-visible so PizzaProxyGuard can read maintenance_state over the same
     *  configured connection instead of duplicating DB config on the proxy. */
    Connection openConnection() throws Exception { return getConnection(); }

    private Connection getConnection() throws Exception {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    }

    private void loadDriver() {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("MariaDB driver missing from PizzaVelocityBridge", ex);
        }
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("bridge.properties");
            Properties properties = new Properties();
            if (Files.exists(file)) {
                try (var in = Files.newInputStream(file)) {
                    properties.load(in);
                }
            }
            boolean changed = false;
            changed |= setDefault(properties, "database.host", "127.0.0.1");
            changed |= setDefault(properties, "database.port", "3306");
            changed |= setDefault(properties, "database.name", "pizzasmp");
            changed |= setDefault(properties, "database.user", "pizzasmp");
            changed |= setDefault(properties, "database.password", "CHANGE_ME");
            changed |= setDefault(properties, "database.parameters", "useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true");
            changed |= setDefault(properties, "session.lease-seconds", "45");
            if (changed) {
                try (var out = Files.newOutputStream(file)) {
                    properties.store(out, "PizzaVelocityBridge configuration");
                }
            }
            String host = properties.getProperty("database.host", "127.0.0.1");
            String port = properties.getProperty("database.port", "3306");
            String name = properties.getProperty("database.name", "pizzasmp");
            String parameters = properties.getProperty("database.parameters",
                "useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true");
            dbUrl = "jdbc:mariadb://" + host + ":" + port + "/" + name + "?" + parameters;
            dbUser = properties.getProperty("database.user", "pizzasmp");
            dbPassword = properties.getProperty("database.password", "CHANGE_ME");
            leaseSeconds = Integer.parseInt(properties.getProperty("session.lease-seconds", "45"));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed loading PizzaVelocityBridge config", ex);
        }
    }

    private boolean setDefault(Properties properties, String key, String value) {
        if (properties.containsKey(key)) {
            return false;
        }
        properties.setProperty(key, value);
        return true;
    }

    private String colorize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = HEX_COLOR_PATTERN.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1).toUpperCase();
            StringBuilder expanded = new StringBuilder("&x");
            for (int i = 0; i < hex.length(); i++) {
                expanded.append('&').append(hex.charAt(i));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(expanded.toString()));
        }
        matcher.appendTail(out);
        return out.toString().replace('&', '§');
    }

    private String prettyServerName(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return "That server";
        }
        return switch (serverName.toLowerCase()) {
            case "pvp" -> "PvP";
            case "lobby" -> "Lobby";
            case "survival" -> "Survival";
            case "maintenance" -> "Maintenance";
            default -> Character.toUpperCase(serverName.charAt(0)) + serverName.substring(1);
        };
    }

    /**
     * A backend could not be reached. Tell the player plainly and disconnect them.
     *
     * We deliberately do NOT hunt for another backend to shove them into. Silent
     * rerouting is how a player asking for survival ends up somewhere they did not
     * choose, and — when every backend is down — how the proxy ends up retrying the
     * whole try-list per join. That retry loop is not theoretical: on 2026-08-06 with
     * both backends down it logged ~400 full stack traces per connecting player and
     * grew velocity.log to 1.9 GB in three minutes.
     *
     * A clear disconnect screen is better UX and bounded work. Note this only fires
     * when the player has nowhere they are already connected; the caller keeps a
     * player who IS on a working backend exactly where they are.
     */
}
