// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the SMP-Core Source License, version 1.0 (see LICENSE). All rights reserved.
package dev.pizzasmp.common;

import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The suite's startup banner. The first SMP-Core plugin to enable prints it once per server start, with the
 * installed modules; the others print nothing extra, so the console shows one banner rather than one per plugin.
 */
public final class SuiteBanner {
    // Every plugin compiles its own copy of this class, so the once-only flag lives in a JVM-wide system property.
    private static final String SHOWN = "smpcore.banner.shown";
    private static final String[] ART = {
        " ___ __  __ ___      ___",
        "/ __|  \\/  | _ \\___ / __|___ _ _ ___",
        "\\__ \\ |\\/| |  _/___| (__/ _ \\ '_/ -_)",
        "|___/_|  |_|_|      \\___\\___/_| \\___|",
    };

    private SuiteBanner() {}

    public static void print(Plugin plugin) {
        if (System.getProperty(SHOWN) != null) return;
        System.setProperty(SHOWN, plugin.getName());
        // Plugins are all loaded before any is enabled, so the module list is complete here.
        String modules = Arrays.stream(plugin.getServer().getPluginManager().getPlugins())
            .filter(p -> p.getClass().getName().matches("(dev|me)\\.pizzasmp\\..*"))
            .map(Plugin::getName)
            .sorted()
            .collect(Collectors.joining(", "));
        for (String line : ART) plugin.getLogger().info(line);
        plugin.getLogger().info("SMP-Core " + plugin.getPluginMeta().getVersion()
            + " | SMP-Core Source License | https://github.com/FolksyPizza/SMP-Core");
        plugin.getLogger().info("Modules: " + modules);
    }
}
