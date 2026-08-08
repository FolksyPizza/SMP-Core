/*
 * PizzaPlaceholders — part of the PizzaSMP plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Licensed under the PizzaSMP Non-Commercial Source License v1.0 (see LICENSE).
 * Non-commercial use only; no sale/resale without written permission; AGPL-style
 * share-alike + network source disclosure. Provided AS IS, without warranty.
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
    @Override public String getAuthor() { return "ExampleSMP"; }
    @Override public String getVersion() { return "1.0.0"; }
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
