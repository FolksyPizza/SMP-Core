// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the SMP-Core Source License, version 1.0 (see LICENSE). All rights reserved.
package dev.pizzasmp.admin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reads Grim history without changing its database; reviews are kept in a separate local audit. */
final class GrimSusHistory {
    record Flag(UUID playerId, String playerName, String checkName, String description,
                String verbose, double vl, long occurredAt, String serverName) { }

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path worldContainer;
    private final Path reviewDirectory;
    private final Path reviewAudit;

    GrimSusHistory(Path worldContainer, Path pluginDataDirectory) {
        this.worldContainer = worldContainer;
        this.reviewDirectory = pluginDataDirectory.resolve("sus-review");
        this.reviewAudit = reviewDirectory.resolve("events.tsv");
    }

    List<Flag> activeFlags() throws IOException, SQLException {
        Map<UUID, Long> dismissedThrough = loadDismissals();
        Path modern = worldContainer.resolve("plugins/GrimAC/data/history.v1.db");
        Path legacy = worldContainer.resolve("plugins/GrimAC/violations.sqlite");
        if (!Files.isRegularFile(legacy)) {
            legacy = worldContainer.resolve("plugins/AC/GrimAC/violations.sqlite");
        }
        List<Flag> flags = Files.isRegularFile(modern) ? readModern(modern)
            : Files.isRegularFile(legacy) ? readLegacy(legacy) : List.of();
        List<Flag> active = new ArrayList<>(flags.size());
        for (Flag flag : flags) {
            if (flag.occurredAt() > dismissedThrough.getOrDefault(flag.playerId(), 0L)) {
                active.add(flag);
            }
        }
        active.sort(Comparator.comparingLong(Flag::occurredAt).reversed());
        return active;
    }

    int dismiss(UUID playerId, UUID reviewer) throws IOException, SQLException {
        int count = 0;
        for (Flag flag : activeFlags()) {
            if (flag.playerId().equals(playerId)) count++;
        }
        if (count > 0) appendReview("dismiss", playerId, reviewer, System.currentTimeMillis());
        return count;
    }

    void restore(UUID playerId, UUID reviewer) throws IOException {
        appendReview("restore", playerId, reviewer, 0L);
    }

    private List<Flag> readModern(Path db) throws SQLException {
        String sql = "SELECT hex(v.player_uuid), COALESCE(p.current_name,''), "
            + "COALESCE(NULLIF(c.display,''),c.stable_key,'Unknown'), COALESCE(c.description,''), "
            + "v.verbose, v.verbose_format, v.vl, v.occurred_at, COALESCE(s.server_name,'') "
            + "FROM grim_violations v LEFT JOIN grim_players p ON p.uuid=v.player_uuid "
            + "LEFT JOIN grim_checks c ON c.check_id=v.check_id "
            + "LEFT JOIN grim_sessions s ON s.session_id=v.session_id ORDER BY v.occurred_at DESC";
        List<Flag> flags = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro");
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID playerId = uuidFromHex(rows.getString(1));
                if (playerId == null) continue;
                int verboseFormat = rows.getInt(6);
                String verbose = verboseFormat == 0 ? rows.getString(5) : "";
                flags.add(new Flag(playerId, rows.getString(2), rows.getString(3), rows.getString(4),
                    verbose == null ? "" : verbose, rows.getDouble(7), rows.getLong(8), rows.getString(9)));
            }
        }
        return flags;
    }

    private List<Flag> readLegacy(Path db) throws SQLException {
        String sql = "SELECT hex(v.uuid), c.check_name_string, v.verbose, v.vl, v.created_at "
            + "FROM grim_history_violations v "
            + "JOIN grim_history_check_names c ON c.id=v.check_name_id ORDER BY v.created_at DESC";
        List<Flag> flags = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro");
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID playerId = uuidFromHex(rows.getString(1));
                if (playerId == null) continue;
                String verbose = rows.getString(3);
                flags.add(new Flag(playerId, "", rows.getString(2), "", verbose == null ? "" : verbose,
                    rows.getDouble(4), rows.getLong(5), ""));
            }
        }
        return flags;
    }

    private Map<UUID, Long> loadDismissals() throws IOException {
        Map<UUID, Long> result = new HashMap<>();
        if (!Files.exists(reviewAudit)) return result;
        int lineNumber = 0;
        for (String line : Files.readAllLines(reviewAudit, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length != 5 || !(parts[0].equals("dismiss") || parts[0].equals("restore"))) {
                throw new IOException("Invalid suspect review event at line " + lineNumber);
            }
            try {
                UUID subject = UUID.fromString(parts[1]);
                UUID.fromString(parts[2]);
                long timestamp = Long.parseLong(parts[3]);
                long through = Long.parseLong(parts[4]);
                if (timestamp <= 0 || through < 0 || (parts[0].equals("restore") && through != 0)) {
                    throw new IllegalArgumentException("Invalid review timestamp");
                }
                result.put(subject, through);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid suspect review event at line " + lineNumber, ex);
            }
        }
        return result;
    }

    private void appendReview(String action, UUID subject, UUID reviewer, long through) throws IOException {
        Files.createDirectories(reviewDirectory);
        privatePermissions(reviewDirectory, PRIVATE_DIRECTORY);
        String line = action + '\t' + subject + '\t' + reviewer + '\t' + System.currentTimeMillis()
            + '\t' + through + '\n';
        try (FileChannel channel = FileChannel.open(reviewAudit,
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            privatePermissions(reviewAudit, PRIVATE_FILE);
            try (var ignored = channel.lock()) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
        }
    }

    private static void privatePermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX test filesystems use their own access controls.
        }
    }

    private static UUID uuidFromHex(String raw) {
        if (raw == null) return null;
        String hex = raw.replace("-", "").trim();
        if (hex.length() != 32) return null;
        try {
            return UUID.fromString(hex.substring(0, 8) + '-' + hex.substring(8, 12) + '-'
                + hex.substring(12, 16) + '-' + hex.substring(16, 20) + '-' + hex.substring(20));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
