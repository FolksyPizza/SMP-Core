// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runtime policy for full and economy-only PizzaNetworkCore backends. */
public enum EconomyRuntimeProfile {
    FULL,
    ECONOMY;

    private static final String COMMAND_NAMESPACE = "pizzanetworkcore";

    private static final Set<String> ECONOMY_COMMAND_ROOTS = Set.of(
            "bal",
            "balance",
            "money",
            "pay",
            "baltop",
            "shop",
            "sell",
            "ah",
            "auction",
            "orders",
            "order",
            "worth",
            "economysettings"
    );

    private static final Set<String> ECONOMY_EVENT_HANDLERS = Set.of(
            "onClick",
            "onInventoryDrag",
            "onInventoryClose",
            "onChat",
            "onPayFirst",
            "onCommandAudit",
            "onConsoleCommandAudit",
            "onJoinHealSellMarkers",
            "onContainerOpenHealSellMarkers",
            "onGameModeHealDisplayData",
            "onCreativeStripDisplayData",
            "onHeldShowWorth"
    );

    /**
     * Optional SMP-Core features layered on the economy profile ({@code economy.features}). Each adds its own
     * commands and event handlers; the economy's access, custody and journal rules still apply to everything.
     */
    private static final Map<String, Set<String>> FEATURE_COMMANDS = Map.of(
            "hud", Set.of(),
            "stats", Set.of("stats", "leaderboard", "leaderboards"),
            "settings", Set.of("settings"),
            "eco", Set.of("eco"),
            "sellmulti", Set.of("sellmulti"),
            "ecobot", Set.of("ecobot"),
            "amethyst", Set.of());

    private static final Map<String, Set<String>> FEATURE_HANDLERS = Map.of(
            "stats", Set.of("onDeathRecordStats", "onBlockPlaced", "onBlockBroken", "onMobKilled"),
            "settings", Set.of("onCreatureSpawn", "onDeathDestroyPearls"),
            "amethyst", Set.of("onAmethystHeld"));

    private static volatile Set<String> features = Set.of();
    private static volatile Set<String> commandRoots = ECONOMY_COMMAND_ROOTS;
    private static volatile Set<String> eventHandlers = ECONOMY_EVENT_HANDLERS;

    /** Sets the enabled features; returns the unknown names so the caller can report them. */
    public static List<String> configureFeatures(Collection<String> configured) {
        LinkedHashSet<String> enabled = new LinkedHashSet<>();
        List<String> unknown = new java.util.ArrayList<>();
        if (configured != null) for (String raw : configured) {
            String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (FEATURE_COMMANDS.containsKey(name)) enabled.add(name); else if (!name.isEmpty()) unknown.add(raw);
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>(ECONOMY_COMMAND_ROOTS);
        LinkedHashSet<String> handlers = new LinkedHashSet<>(ECONOMY_EVENT_HANDLERS);
        for (String feature : enabled) {
            roots.addAll(FEATURE_COMMANDS.get(feature));
            handlers.addAll(FEATURE_HANDLERS.getOrDefault(feature, Set.of()));
        }
        features = Set.copyOf(enabled);
        commandRoots = Set.copyOf(roots);
        eventHandlers = Set.copyOf(handlers);
        return unknown;
    }

    public static boolean featureEnabled(String feature) {
        return feature != null && features.contains(feature);
    }

    public static Set<String> enabledFeatures() {
        return features;
    }

    /**
     * Parses the optional runtime profile. Missing or blank values retain the full runtime;
     * an unrecognized value is invalid and must be handled as a startup failure.
     */
    public static Optional<EconomyRuntimeProfile> parse(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return Optional.of(FULL);
        }

        return switch (configuredValue.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> Optional.of(FULL);
            case "economy" -> Optional.of(ECONOMY);
            default -> Optional.empty();
        };
    }

    /**
     * Checks a Bukkit command root against this profile. Economy accepts unqualified roots
     * and PizzaNetworkCore's own namespaced roots; other namespaces fail closed.
     */
    public boolean allowsCommand(String commandLabel) {
        if (commandLabel == null || commandLabel.isBlank()) {
            return false;
        }
        if (this == FULL) {
            return true;
        }

        String label = commandLabel.trim();
        int namespaceSeparator = label.indexOf(':');
        if (namespaceSeparator >= 0) {
            if (namespaceSeparator == 0
                    || namespaceSeparator == label.length() - 1
                    || namespaceSeparator != label.lastIndexOf(':')
                    || !COMMAND_NAMESPACE.equals(label.substring(0, namespaceSeparator).toLowerCase(Locale.ROOT))) {
                return false;
            }
            label = label.substring(namespaceSeparator + 1);
        }

        if (!isUnqualifiedRoot(label)) {
            return false;
        }
        return commandRoots.contains(label.toLowerCase(Locale.ROOT));
    }

    /** Exact method names whose existing {@code @EventHandler} methods may be registered. */
    public static Set<String> economyEventHandlers() {
        return eventHandlers;
    }

    /** Explicit player command roots and aliases retained by the economy profile. */
    public static Set<String> economyCommandRoots() {
        return commandRoots;
    }

    /**
     * Selects only descriptor command roots this profile may dispatch. Plugin descriptors
     * are shared by both profiles, so unrelated declared roots must be filtered here rather
     * than making an economy-only startup reject the canonical full-suite descriptor.
     */
    public Set<String> commandRootsToRegister(Collection<String> descriptorRoots) {
        if (descriptorRoots == null || descriptorRoots.isEmpty()) return Set.of();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String root : descriptorRoots) {
            if (allowsCommand(root)) selected.add(root);
        }
        return Set.copyOf(selected);
    }

    /**
     * Checks whether an existing annotated handler may be registered. The caller must keep
     * the economy profile's chat intercept forced off when registering {@code onChat};
     * {@code onPayFirst} is included because it is the canonical route into atomic /pay.
     */
    public boolean allowsEventHandler(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return false;
        }
        return this == FULL || eventHandlers.contains(methodName);
    }

    /** Selectively expose only item routes whose Canopy custody transaction is qualified. */
    public boolean allowsEconomyItemMarketCommand(String commandRoot, boolean qualifiedShopRoute,
                                                   boolean qualifiedAuctionRoute) {
        return allowsEconomyItemMarketCommand(commandRoot, qualifiedShopRoute, qualifiedAuctionRoute, false, false);
    }

    /** Checks the separately qualified fixed-worth selling route. */
    public boolean allowsEconomyItemMarketCommand(String commandRoot, boolean qualifiedShopRoute,
                                                   boolean qualifiedAuctionRoute, boolean qualifiedSellRoute) {
        return allowsEconomyItemMarketCommand(commandRoot, qualifiedShopRoute, qualifiedAuctionRoute, qualifiedSellRoute, false);
    }

    /** Checks item order creation, fill, cancellation, and recipient claim as one qualified route. */
    public boolean allowsEconomyItemMarketCommand(String commandRoot, boolean qualifiedShopRoute,
                                                   boolean qualifiedAuctionRoute, boolean qualifiedSellRoute,
                                                   boolean qualifiedOrdersRoute) {
        if (this != ECONOMY || commandRoot == null) return false;
        return switch (commandRoot.toLowerCase(Locale.ROOT)) {
            case "shop" -> qualifiedShopRoute;
            case "ah", "auction" -> qualifiedAuctionRoute;
            case "sell" -> qualifiedSellRoute;
            case "orders", "order" -> qualifiedOrdersRoute;
            default -> false;
        };
    }

    private static boolean isUnqualifiedRoot(String root) {
        for (int i = 0; i < root.length(); i++) {
            char character = root.charAt(i);
            if (!((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-'
                    || character == '_')) {
                return false;
            }
        }
        return true;
    }
}
