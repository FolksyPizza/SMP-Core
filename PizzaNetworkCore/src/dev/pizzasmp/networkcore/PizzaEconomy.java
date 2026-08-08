package dev.pizzasmp.networkcore;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * Vault economy provider backed by the shared `balances` table.
 *
 * WHY THIS EXISTS
 *
 * Before this class, EssentialsX Economy won the Vault hook. Essentials stores balances
 * in PER-SERVER YAML files (plugins/Essentials/userdata/), so a player's money on the
 * lobby and on survival were different numbers that never reconciled — the reported
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

    private final PizzaNetworkCore plugin;

    PizzaEconomy(PizzaNetworkCore plugin) {
        this.plugin = plugin;
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
