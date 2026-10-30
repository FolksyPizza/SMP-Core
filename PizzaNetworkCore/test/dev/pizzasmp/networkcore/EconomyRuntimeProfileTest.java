// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class EconomyRuntimeProfileTest {

    private static int assertions;

    public static void main(String[] args) {
        parsesDefaultAndSupportedProfiles();
        rejectsUnknownProfiles();
        exposesEconomyRootsAndRestrictsCommands();
        rejectsUnknownNamespacesAndMalformedRoots();
        exposesAndRestrictsEconomyEventHandlers();
        retainsFullProfileBehavior();
        System.out.println("Economy runtime profile: " + assertions + " assertions passed");
    }

    private static void parsesDefaultAndSupportedProfiles() {
        equal(Optional.of(EconomyRuntimeProfile.FULL), EconomyRuntimeProfile.parse(null));
        equal(Optional.of(EconomyRuntimeProfile.FULL), EconomyRuntimeProfile.parse(""));
        equal(Optional.of(EconomyRuntimeProfile.FULL), EconomyRuntimeProfile.parse("  "));
        equal(Optional.of(EconomyRuntimeProfile.FULL), EconomyRuntimeProfile.parse(" full "));
        equal(Optional.of(EconomyRuntimeProfile.ECONOMY), EconomyRuntimeProfile.parse("economy"));
        equal(Optional.of(EconomyRuntimeProfile.ECONOMY), EconomyRuntimeProfile.parse(" ECONOMY "));
    }

    private static void rejectsUnknownProfiles() {
        equal(Optional.empty(), EconomyRuntimeProfile.parse("economy-only"));
        equal(Optional.empty(), EconomyRuntimeProfile.parse("disabled"));
        equal(Optional.empty(), EconomyRuntimeProfile.parse("other: economy"));
    }

    private static void exposesEconomyRootsAndRestrictsCommands() {
        Set<String> roots = Set.of(
                "bal", "balance", "money", "pay", "baltop", "shop", "sell", "ah",
                "auction", "orders", "order", "worth", "economysettings");
        equal(roots, EconomyRuntimeProfile.economyCommandRoots());
        for (String root : roots) {
            check(EconomyRuntimeProfile.ECONOMY.allowsCommand(root));
        }
        check(EconomyRuntimeProfile.ECONOMY.allowsCommand("BAL"));
        check(EconomyRuntimeProfile.ECONOMY.allowsCommand("pizzanetworkcore:bal"));
        check(EconomyRuntimeProfile.ECONOMY.allowsCommand("PIZZANETWORKCORE:balance"));
        check(EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("shop", true, false));
        check(!EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("shop", false, true));
        check(EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("ah", false, true));
        check(EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("AUCTION", false, true));
        check(EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("sell", false, false, true));
        check(!EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("sell", false, false, false));
        check(EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("orders", false, false, false, true));
        check(EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("order", false, false, false, true));
        check(!EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand("orders", false, false, false, false));
        for (String root : List.of("sell", "orders", "order"))
            check(!EconomyRuntimeProfile.ECONOMY.allowsEconomyItemMarketCommand(root, true, true));

        Set<String> descriptorRoots = Set.of("admin", "settings", "bal", "shop", "sell", "ah", "orders", "worth");
        equal(Set.of("bal", "shop", "sell", "ah", "orders", "worth"),
                EconomyRuntimeProfile.ECONOMY.commandRootsToRegister(descriptorRoots));
        equal(descriptorRoots, EconomyRuntimeProfile.FULL.commandRootsToRegister(descriptorRoots));

        for (String root : List.of(
                "admin", "settings", "tpa", "spawn", "rtp", "duel", "invsee", "nick",
                "home", "homes", "friends", "tp", "teleport")) {
            check(!EconomyRuntimeProfile.ECONOMY.allowsCommand(root));
        }
    }

    private static void rejectsUnknownNamespacesAndMalformedRoots() {
        for (String root : List.of(
                "essentials:bal", "minecraft:bal", "otherplugin:pay", "unknown:shop",
                "pizzanetworkcore:", ":bal", "plugin:other:bal", "/bal", "bal player",
                "bal;op", "bal.extra", "bal\\n")) {
            check(!EconomyRuntimeProfile.ECONOMY.allowsCommand(root));
        }
        check(!EconomyRuntimeProfile.ECONOMY.allowsCommand(null));
        check(!EconomyRuntimeProfile.ECONOMY.allowsCommand(" "));
    }

    private static void exposesAndRestrictsEconomyEventHandlers() {
        Set<String> handlers = Set.of(
                "onClick", "onInventoryDrag", "onInventoryClose", "onChat", "onPayFirst",
                "onCommandAudit", "onConsoleCommandAudit",
                "onJoinHealSellMarkers", "onContainerOpenHealSellMarkers", "onGameModeHealDisplayData", "onCreativeStripDisplayData", "onHeldShowWorth");
        equal(handlers, EconomyRuntimeProfile.economyEventHandlers());
        for (String methodName : handlers) {
            check(EconomyRuntimeProfile.ECONOMY.allowsEventHandler(methodName));
        }

        for (String methodName : List.of(
                "onJoin", "onQuit", "onMove", "onCommand", "onAsyncChat", "onChatIconRender",
                "onPayFirstExtra", "dev.pizzasmp.networkcore.PizzaNetworkCore.onPayFirst")) {
            check(!EconomyRuntimeProfile.ECONOMY.allowsEventHandler(methodName));
        }
        check(!EconomyRuntimeProfile.ECONOMY.allowsEventHandler(null));
        check(!EconomyRuntimeProfile.ECONOMY.allowsEventHandler(" "));
    }

    private static void retainsFullProfileBehavior() {
        check(EconomyRuntimeProfile.FULL.allowsCommand("tpa"));
        check(EconomyRuntimeProfile.FULL.allowsCommand("minecraft:help"));
        check(EconomyRuntimeProfile.FULL.allowsEventHandler("onJoin"));
        check(EconomyRuntimeProfile.FULL.allowsEventHandler("onQuit"));
        check(!EconomyRuntimeProfile.FULL.allowsCommand(null));
        check(!EconomyRuntimeProfile.FULL.allowsEventHandler(null));
    }

    private static void check(boolean condition) {
        assertions++;
        if (!condition) {
            throw new AssertionError("assertion " + assertions + " failed");
        }
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError("assertion " + assertions + " expected " + expected + " but got " + actual);
        }
    }
}
