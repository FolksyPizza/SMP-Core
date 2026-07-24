/*
 * SuiteStorage is part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Released under the MIT License (see LICENSE). Provided AS IS, without warranty.
 */
package dev.pizzasmp.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unified, backend-agnostic persistence for the SMP-Core suite. Each plugin creates ONE instance
 * with a namespace ("admintools", "chatguard", ...). Two storage backends, chosen by config:
 *
 *   storage:
 *     backend: yaml         # or 'mysql'
 *     database:             # only read when backend: mysql
 *       host: 127.0.0.1
 *       port: 3306
 *       name: pizzasmp
 *       user: pizzasmp
 *       password: ""
 *
 * The data MODEL stays YAML everywhere — plugins keep working with YamlConfiguration objects. In
 * mysql mode the serialized YAML text is just stored as a row instead of a file, so wiring an
 * existing YAML plugin onto the DB is a load/save swap, not a rewrite. Reads hit the DB live
 * (no boot-time cache), so multiple backends behind a Velocity proxy stay in sync.
 *
 * Tables (auto-created, MySQL-safe): suite_docs(namespace,name,data), suite_player(namespace,uuid,data),
 * suite_events(id,namespace,kind,data,created_at) for append-only logs.
 */
public final class SuiteStorage {

    private final Plugin plugin;
    private final String ns;
    private final boolean mysql;
    private final File yamlDir;         // yaml mode: base data folder
    private final File playerDir;       // yaml mode: per-player subfolder
    private final HikariDataSource ds;  // mysql mode only

    private SuiteStorage(Plugin plugin, String ns, boolean mysql, HikariDataSource ds) {
        this.plugin = plugin;
        this.ns = ns;
        this.mysql = mysql;
        this.ds = ds;
        this.yamlDir = plugin.getDataFolder();
        this.playerDir = new File(this.yamlDir, "data");
        if (!mysql && !this.playerDir.isDirectory()) this.playerDir.mkdirs();
    }

    /** Build from the plugin's config `storage:` section. Falls back to yaml on any DB error. */
    public static SuiteStorage fromConfig(Plugin plugin, String namespace) {
        org.bukkit.configuration.ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("storage");
        String backend = cfg != null ? cfg.getString("backend", "yaml") : "yaml";
        if (!"mysql".equalsIgnoreCase(backend)) {
            plugin.getLogger().info("[storage] namespace '" + namespace + "' -> YAML (files)");
            return new SuiteStorage(plugin, namespace, false, null);
        }
        try {
            org.bukkit.configuration.ConfigurationSection db = cfg.getConfigurationSection("database");
            String host = db.getString("host", "127.0.0.1");
            int port = db.getInt("port", 3306);
            String name = db.getString("name", "pizzasmp");
            String user = db.getString("user", "pizzasmp");
            String pass = db.getString("password", "");
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + name + "?useSSL=false&allowPublicKeyRetrieval=true");
            hc.setUsername(user);
            hc.setPassword(pass);
            hc.setMaximumPoolSize(db.getInt("pool.max", 6));
            hc.setMinimumIdle(db.getInt("pool.min", 1));
            hc.setMaxLifetime(db.getLong("pool.max-lifetime-ms", 1_800_000L));
            hc.setPoolName("PizzaCommon-" + namespace);
            hc.setConnectionTimeout(10_000L);
            HikariDataSource ds = new HikariDataSource(hc);
            SuiteStorage st = new SuiteStorage(plugin, namespace, true, ds);
            st.ensureSchema();
            plugin.getLogger().info("[storage] namespace '" + namespace + "' -> MySQL (" + host + ":" + port + "/" + name + ")");
            return st;
        } catch (Throwable t) {
            plugin.getLogger().warning("[storage] MySQL init failed for '" + namespace + "', falling back to YAML: " + t.getMessage());
            return new SuiteStorage(plugin, namespace, false, null);
        }
    }

    public boolean isMysql() { return this.mysql; }

    public void close() { if (this.ds != null && !this.ds.isClosed()) this.ds.close(); }

    private void ensureSchema() throws Exception {
        try (Connection c = this.ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS suite_docs (namespace VARCHAR(48) NOT NULL, name VARCHAR(96) NOT NULL, "
                + "data LONGTEXT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (namespace, name))");
            st.execute("CREATE TABLE IF NOT EXISTS suite_player (namespace VARCHAR(48) NOT NULL, uuid CHAR(36) NOT NULL, "
                + "data LONGTEXT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (namespace, uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS suite_events (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "namespace VARCHAR(48) NOT NULL, kind VARCHAR(48) NOT NULL, data LONGTEXT NULL, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX idx_events_ns (namespace, created_at))");
        }
    }

    // ---- named singleton documents (strikes.yml, pizzaplus-subs.yml, staffmode.yml, offenses.yml) ----

    /** Load a named document. In YAML mode this is `<dataFolder>/<name>.yml`. Always returns non-null. */
    public YamlConfiguration loadDoc(String name) {
        if (!this.mysql) {
            return YamlConfiguration.loadConfiguration(new File(this.yamlDir, name + ".yml"));
        }
        String text = this.dbFetch("SELECT data FROM suite_docs WHERE namespace=? AND name=?", this.ns, name);
        return parse(text);
    }

    public void saveDoc(String name, YamlConfiguration cfg) {
        if (!this.mysql) {
            try { cfg.save(new File(this.yamlDir, name + ".yml")); }
            catch (Exception e) { this.plugin.getLogger().warning("[storage] save " + name + ".yml failed: " + e.getMessage()); }
            return;
        }
        this.dbUpsertDoc(name, cfg.saveToString());
    }

    // ---- per-player documents (homes-style) ----

    public YamlConfiguration loadPlayer(UUID uuid) {
        if (!this.mysql) {
            return YamlConfiguration.loadConfiguration(new File(this.playerDir, uuid + ".yml"));
        }
        String text = this.dbFetch("SELECT data FROM suite_player WHERE namespace=? AND uuid=?", this.ns, uuid.toString());
        return parse(text);
    }

    public void savePlayer(UUID uuid, YamlConfiguration cfg) {
        if (!this.mysql) {
            try { cfg.save(new File(this.playerDir, uuid + ".yml")); }
            catch (Exception e) { this.plugin.getLogger().warning("[storage] save player " + uuid + " failed: " + e.getMessage()); }
            return;
        }
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO suite_player (namespace,uuid,data) VALUES (?,?,?) "
                 + "ON DUPLICATE KEY UPDATE data=VALUES(data)")) {
            ps.setString(1, this.ns); ps.setString(2, uuid.toString()); ps.setString(3, cfg.saveToString());
            ps.executeUpdate();
        } catch (Exception e) { this.plugin.getLogger().warning("[storage] db save player " + uuid + " failed: " + e.getMessage()); }
    }

    /** UUIDs that have a stored player document (mysql: query; yaml: list the data dir). */
    public List<UUID> listPlayers() {
        List<UUID> out = new ArrayList<>();
        if (!this.mysql) {
            File[] files = this.playerDir.listFiles((d, n) -> n.endsWith(".yml"));
            if (files != null) for (File f : files) {
                try { out.add(UUID.fromString(f.getName().substring(0, f.getName().length() - 4))); } catch (Exception ignored) {}
            }
            return out;
        }
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM suite_player WHERE namespace=?")) {
            ps.setString(1, this.ns);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { try { out.add(UUID.fromString(rs.getString(1))); } catch (Exception ignored) {} }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---- append-only events (RuleGuard/Punishment offense logs) ----

    public void appendEvent(String kind, String data) {
        if (!this.mysql) {
            try {
                File log = new File(this.yamlDir, kind + ".log");
                java.nio.file.Files.writeString(log.toPath(), data + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
            return;
        }
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO suite_events (namespace,kind,data) VALUES (?,?,?)")) {
            ps.setString(1, this.ns); ps.setString(2, kind); ps.setString(3, data);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    // ---- one-shot YAML -> MySQL import (flag-file guarded, run once on first mysql boot) ----

    /** If mysql and the migration flag is absent, import existing YAML docs/players into the DB. */
    public void importFromYamlOnce(List<String> docNames, boolean importPlayers) {
        if (!this.mysql) return;
        File flag = new File(this.yamlDir, ".migrated-to-mysql");
        if (flag.exists()) return;
        int docs = 0, players = 0;
        for (String name : docNames) {
            File f = new File(this.yamlDir, name + ".yml");
            if (f.isFile()) { this.dbUpsertDoc(name, YamlConfiguration.loadConfiguration(f).saveToString()); docs++; }
        }
        if (importPlayers && this.playerDir.isDirectory()) {
            File[] files = this.playerDir.listFiles((d, n) -> n.endsWith(".yml"));
            if (files != null) for (File f : files) {
                try {
                    UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                    this.savePlayer(id, YamlConfiguration.loadConfiguration(f));
                    players++;
                } catch (Exception ignored) {}
            }
        }
        try { flag.createNewFile(); } catch (Exception ignored) {}
        this.plugin.getLogger().info("[storage] one-shot YAML->MySQL import: " + docs + " doc(s), " + players + " player file(s).");
    }

    // ---- internals ----

    private static YamlConfiguration parse(String text) {
        if (text == null || text.isEmpty()) return new YamlConfiguration();
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(text);
            return y;
        } catch (Exception e) { return new YamlConfiguration(); }
    }

    private String dbFetch(String sql, String a, String b) {
        try (Connection c = this.ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a); ps.setString(2, b);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (Exception e) { this.plugin.getLogger().warning("[storage] fetch failed: " + e.getMessage()); return null; }
    }

    private void dbUpsertDoc(String name, String data) {
        try (Connection c = this.ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO suite_docs (namespace,name,data) VALUES (?,?,?) "
                 + "ON DUPLICATE KEY UPDATE data=VALUES(data)")) {
            ps.setString(1, this.ns); ps.setString(2, name); ps.setString(3, data);
            ps.executeUpdate();
        } catch (Exception e) { this.plugin.getLogger().warning("[storage] db save doc " + name + " failed: " + e.getMessage()); }
    }
}
