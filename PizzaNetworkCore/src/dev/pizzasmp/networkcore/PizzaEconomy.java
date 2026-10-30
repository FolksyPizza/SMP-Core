// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Vault economy provider backed by the shared `balances` table.
 *
 * WHY THIS EXISTS
 *
 * Before this class, EssentialsX Economy won the Vault hook. Essentials stores balances
 * in PER-SERVER YAML files (plugins/Essentials/userdata/), so a player's money on the
 * lobby and on survival were different numbers that never reconciled: the reported
 * "money isn't syncing". PizzaNetworkCore only ever CONSUMED Vault (economy.depositPlayer
 * and friends), so it was writing into whichever per-server file Essentials owned, while
 * the `balances` table it reads for /baltop sat separate.
 *
 * Registering this at ServicePriority.Highest makes `balances` the single source of
 * truth. Every backend points at the same database, so money is genuinely network-wide
 * and PNC's own economy features (auction house, orders, shop, /sell, /pay) and any
 * third-party Vault consumer all agree.
 *
 * NOTES
 *
 * - Extends Vault's AbstractEconomy so the many bank/world-scoped overloads collapse to
 *   the handful that actually matter here.
 * - Banks are unsupported on purpose; nothing in the suite uses them.
 * - Balances are keyed by UUID, never by name. Name-keyed lookups exist only because the
 *   Vault interface demands them, and they resolve through Bukkit's offline-player cache.
 * - All access is synchronous against a pooled connection. Vault's API is synchronous by
 *   design, and callers are already off the main thread where it matters.
 */
public final class PizzaEconomy extends AbstractEconomy {

    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999999.99");

    enum TransferOutcome {
        SUCCESS,
        INSUFFICIENT,
        FAILED,
        UNKNOWN
    }

    record TransferResult(TransferOutcome outcome) { }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    private final PizzaNetworkCore plugin;
    private final ConnectionFactory transferConnections;

    PizzaEconomy(PizzaNetworkCore plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.transferConnections = plugin::openSyncConnection;
    }

    // Package-private seam for the standalone fake-JDBC proof. Production construction still
    // goes through PizzaNetworkCore's shared connection pool above.
    PizzaEconomy(ConnectionFactory transferConnections) {
        this.plugin = null;
        this.transferConnections = Objects.requireNonNull(transferConnections, "transferConnections");
    }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "PizzaNetworkCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }

    @Override
    public String format(double amount) {
        return String.format("$%,.2f", amount);
    }

    @Override public String currencyNamePlural() { return "dollars"; }
    @Override public String currencyNameSingular() { return "dollar"; }

    // ---- balance access ---------------------------------------------------------

    private UUID resolve(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(playerName);
        if (op != null) return op.getUniqueId();
        // Fall back to the players table, which is authoritative across the network and
        // will know names this backend has never seen.
        String sql = "SELECT uuid FROM players WHERE LOWER(username)=LOWER(?) LIMIT 1";
        try (Connection c = this.plugin.openSyncConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString(1));
            }
        } catch (Exception ignored) { }
        return null;
    }

    private double balanceOf(UUID uuid) {
        if (uuid == null) return 0.0;
        String sql = "SELECT money FROM balances WHERE uuid=? LIMIT 1";
        try (Connection c = this.plugin.openSyncConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (Exception ex) {
            this.plugin.getLogger().warning("economy: balance read failed for " + uuid + ": " + ex.getMessage());
        }
        return 0.0;
    }

    /**
     * Apply a signed delta atomically.
     *
     * The UPDATE carries its own guard (money + delta >= 0) so a concurrent withdraw on
     * another backend cannot drive a balance negative between our read and our write.
     * Returns the new balance, or null if the guard rejected it.
     */
    private Double applyDelta(UUID uuid, double delta) {
        if (uuid == null) return null;
        String upsert = "INSERT INTO balances (uuid, money) VALUES (?, GREATEST(0, ?)) "
                      + "ON DUPLICATE KEY UPDATE money = money + ?";
        String guarded = "UPDATE balances SET money = money + ? WHERE uuid = ? AND money + ? >= 0";
        try (Connection c = this.plugin.openSyncConnection()) {
            if (delta < 0) {
                try (PreparedStatement ps = c.prepareStatement(guarded)) {
                    ps.setDouble(1, delta);
                    ps.setString(2, uuid.toString());
                    ps.setDouble(3, delta);
                    if (ps.executeUpdate() == 0) return null;   // insufficient funds
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(upsert)) {
                    ps.setString(1, uuid.toString());
                    ps.setDouble(2, delta);
                    ps.setDouble(3, delta);
                    ps.executeUpdate();
                }
            }
        } catch (Exception ex) {
            this.plugin.getLogger().warning("economy: balance write failed for " + uuid + ": " + ex.getMessage());
            return null;
        }
        return balanceOf(uuid);
    }

    /**
     * Transfer money and write the matching PAY audit row as one database transaction.
     *
     * A commit exception is ambiguous: the database may have committed before the
     * acknowledgment was lost. Callers must treat UNKNOWN as pending/manual reconciliation
     * and must never retry it automatically.
     */
    TransferResult transfer(UUID source, String sourceName, UUID target, String targetName,
                            double amount, String serverName) {
        BigDecimal cents = transferAmount(amount);
        if (source == null || target == null || source.equals(target) || cents == null
                || sourceName == null || sourceName.isBlank()
                || targetName == null || targetName.isBlank()
                || serverName == null || serverName.isBlank()) {
            return new TransferResult(TransferOutcome.FAILED);
        }

        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitAttempted = false;
        try {
            connection = this.transferConnections.open();
            if (connection == null) return new TransferResult(TransferOutcome.FAILED);
            connection.setAutoCommit(false);
            transactionStarted = true;

            // Create and lock both rows in one stable UUID order. Transfers in opposite
            // directions therefore acquire the same row locks in the same order.
            UUID first = source.toString().compareTo(target.toString()) <= 0 ? source : target;
            UUID second = first.equals(source) ? target : source;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT IGNORE INTO balances (uuid, money) VALUES (?, 0)")) {
                insert.setString(1, first.toString());
                insert.executeUpdate();
                insert.setString(1, second.toString());
                insert.executeUpdate();
            }

            java.util.Map<UUID, BigDecimal> balances = new java.util.HashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT money FROM balances WHERE uuid = ? FOR UPDATE")) {
                for (UUID account : List.of(first, second)) {
                    select.setString(1, account.toString());
                    try (ResultSet rows = select.executeQuery()) {
                        if (!rows.next()) throw new SQLException("locked balance row disappeared");
                        BigDecimal balance = rows.getBigDecimal(1);
                        if (balance == null || balance.signum() < 0 || balance.compareTo(MAX_BALANCE) > 0) {
                            throw new SQLException("balance is outside the supported range");
                        }
                        balances.put(account, balance.setScale(2, RoundingMode.UNNECESSARY));
                    }
                }
            }

            BigDecimal sourceBalance = balances.get(source);
            BigDecimal targetBalance = balances.get(target);
            if (sourceBalance == null || targetBalance == null) throw new SQLException("locked balance missing");
            if (sourceBalance.compareTo(cents) < 0) {
                rollbackQuietly(connection, "insufficient funds");
                return new TransferResult(TransferOutcome.INSUFFICIENT);
            }
            if (targetBalance.add(cents).compareTo(MAX_BALANCE) > 0) {
                rollbackQuietly(connection, "target balance overflow");
                return new TransferResult(TransferOutcome.FAILED);
            }

            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE balances SET money = money - ? WHERE uuid = ? AND money >= ?")) {
                debit.setBigDecimal(1, cents);
                debit.setString(2, source.toString());
                debit.setBigDecimal(3, cents);
                if (debit.executeUpdate() != 1) {
                    rollbackQuietly(connection, "debit rejected");
                    return new TransferResult(TransferOutcome.FAILED);
                }
            }

            try (PreparedStatement credit = connection.prepareStatement(
                    "UPDATE balances SET money = money + ? WHERE uuid = ? AND money <= ?")) {
                credit.setBigDecimal(1, cents);
                credit.setString(2, target.toString());
                credit.setBigDecimal(3, MAX_BALANCE.subtract(cents));
                if (credit.executeUpdate() != 1) {
                    rollbackQuietly(connection, "credit rejected");
                    return new TransferResult(TransferOutcome.FAILED);
                }
            }

            try (PreparedStatement audit = connection.prepareStatement(
                    "INSERT INTO transaction_audit_log "
                    + "(player_uuid, player_name, event_type, item_key, amount, unit_price, total_price, extra_info, server_name) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                audit.setString(1, source.toString());
                audit.setString(2, sourceName);
                audit.setString(3, "PAY");
                audit.setString(4, targetName);
                audit.setInt(5, 1);
                audit.setDouble(6, cents.doubleValue());
                audit.setDouble(7, cents.doubleValue());
                audit.setString(8, "to=" + target);
                audit.setString(9, serverName);
                if (audit.executeUpdate() != 1) throw new SQLException("PAY audit insert was not applied");
            }

            commitAttempted = true;
            connection.commit();
            return new TransferResult(TransferOutcome.SUCCESS);
        } catch (Exception ex) {
            if (commitAttempted) {
                logTransferProblem("commit outcome unknown", source, target, ex);
                return new TransferResult(TransferOutcome.UNKNOWN);
            }
            if (transactionStarted && connection != null) rollbackQuietly(connection, "transfer failed");
            logTransferProblem("transfer failed", source, target, ex);
            return new TransferResult(TransferOutcome.FAILED);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ex) {
                    logTransferProblem("connection close failed", source, target, ex);
                }
            }
        }
    }

    private static BigDecimal transferAmount(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) return null;
        try {
            BigDecimal cents = BigDecimal.valueOf(amount).setScale(2, RoundingMode.UNNECESSARY);
            return cents.signum() > 0 && cents.compareTo(MAX_BALANCE) <= 0 ? cents : null;
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private void rollbackQuietly(Connection connection, String phase) {
        try {
            connection.rollback();
        } catch (Exception ex) {
            logTransferProblem(phase + " rollback failed", null, null, ex);
        }
    }

    private void logTransferProblem(String phase, UUID source, UUID target, Exception ex) {
        if (this.plugin == null) return;
        String players = source == null ? "" : " for " + source + (target == null ? "" : " -> " + target);
        this.plugin.getLogger().warning("economy: " + phase + players + " (" + ex.getClass().getSimpleName() + ")");
    }

    // ---- Vault surface ----------------------------------------------------------

    @Override
    public boolean hasAccount(String playerName) { return resolve(playerName) != null; }
    @Override
    public boolean hasAccount(String playerName, String worldName) { return hasAccount(playerName); }

    @Override
    public double getBalance(String playerName) { return balanceOf(resolve(playerName)); }
    @Override
    public double getBalance(String playerName, String world) { return getBalance(playerName); }

    @Override
    public boolean has(String playerName, double amount) { return getBalance(playerName) >= amount; }
    @Override
    public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        if (amount < 0) return new EconomyResponse(0, 0, ResponseType.FAILURE, "Cannot withdraw a negative amount");
        UUID uuid = resolve(playerName);
        if (uuid == null) return new EconomyResponse(0, 0, ResponseType.FAILURE, "No such player");
        Double now = applyDelta(uuid, -amount);
        if (now == null) {
            return new EconomyResponse(0, balanceOf(uuid), ResponseType.FAILURE, "Insufficient funds");
        }
        return new EconomyResponse(amount, now, ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        if (amount < 0) return new EconomyResponse(0, 0, ResponseType.FAILURE, "Cannot deposit a negative amount");
        UUID uuid = resolve(playerName);
        if (uuid == null) return new EconomyResponse(0, 0, ResponseType.FAILURE, "No such player");
        Double now = applyDelta(uuid, amount);
        if (now == null) return new EconomyResponse(0, balanceOf(uuid), ResponseType.FAILURE, "Deposit failed");
        return new EconomyResponse(amount, now, ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        UUID uuid = resolve(playerName);
        if (uuid == null) return false;
        try (Connection c = this.plugin.openSyncConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT IGNORE INTO balances (uuid, money) VALUES (?, 0)")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) { return createPlayerAccount(playerName); }

    // ---- banks: unsupported -----------------------------------------------------

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Banks are not supported");
    }
    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }
}
