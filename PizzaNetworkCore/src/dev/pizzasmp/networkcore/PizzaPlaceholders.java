/*
 * PizzaPlaceholders — part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Licensed under the Apache License, Version 2.0 (see LICENSE). No feature is gated or paid.
 */
package dev.pizzasmp.networkcore;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI expansion exposing PNC economy values for the TAB tablist (and any other PAPI
 * consumer). Identifier "pizzasmp":
 *   %pizzasmp_balance%      -> abbreviated money, e.g. "10M" (no $ / colour; the consumer adds those)
 *   %pizzasmp_balance_raw%  -> raw money as a long-ish string
 */
public final class PizzaPlaceholders extends PlaceholderExpansion {
    private final PizzaNetworkCore plugin;

    PizzaPlaceholders(PizzaNetworkCore plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "pizzasmp"; }
    @Override public String getAuthor() { return "FolksyPizza"; }
    @SuppressWarnings("deprecation") // getDescription(): the version is the suite version in plugin.yml
    @Override public String getVersion() { return this.plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }   // survive PAPI reloads

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || params == null) return "";
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "balance":     return plugin.papiBalanceFormatted(player);
            case "balance_raw": return plugin.papiBalanceRaw(player);
            default:            return null;
        }
    }
}
