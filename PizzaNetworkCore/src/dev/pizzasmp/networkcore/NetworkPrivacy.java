// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Teleport and message privacy on a network whose teleports and messages belong to another plugin (named by
 * economy.network-plugin): the settings menu reads and changes that plugin's own values (Anyone, Friends, Off), so
 * the toggle governs the real feature. Unavailable (and the caller keeps its own setting) when no plugin is named,
 * it is missing, or its internals changed.
 */
final class NetworkPrivacy {
    private final Logger logger;
    private Plugin boundTo;
    private Object settings;
    private Method read;
    private Method cycle;
    private Method sidebarEnabled;
    private Method toggleSidebar;
    private boolean warned;

    private final java.util.function.Supplier<String> pluginName;

    NetworkPrivacy(Logger logger, java.util.function.Supplier<String> pluginName) {
        this.logger = logger;
        this.pluginName = pluginName;
    }

    /** The configured network plugin when it is installed and enabled; null otherwise. */
    private Plugin networkPlugin() {
        String name = this.pluginName.get();
        if (name == null || name.isBlank()) return null;
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    /** The network plugin's key for a settings-menu key, or null when it is not a network privacy setting. */
    static String networkKey(String key) {
        if (key == null) return null;
        return switch (key) {
            case "tpa_requests" -> "tpa";
            case "tpahere_requests" -> "tpahere";
            case "private_messages" -> "message";
            default -> null;
        };
    }

    private synchronized boolean bind() {
        Plugin staff = networkPlugin();
        if (staff == null) { settings = null; boundTo = null; return false; }
        if (staff == boundTo && settings != null) return true;
        try {
            Field field = staff.getClass().getDeclaredField("playerSettings");
            field.setAccessible(true);
            Object value = field.get(staff);
            Method r = value.getClass().getDeclaredMethod("privacy", UUID.class, String.class);
            Method c = value.getClass().getDeclaredMethod("cyclePrivacy", Player.class, String.class);
            r.setAccessible(true);
            c.setAccessible(true);
            Method enabled = value.getClass().getDeclaredMethod("sidebarEnabled", UUID.class);
            Method toggle = value.getClass().getDeclaredMethod("toggleSidebar", Player.class);
            enabled.setAccessible(true);
            toggle.setAccessible(true);
            settings = value; read = r; cycle = c; sidebarEnabled = enabled; toggleSidebar = toggle; boundTo = staff;
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            settings = null; boundTo = null;
            if (!warned) { warned = true; logger.warning("Network privacy settings unavailable (" + failure + "); using SMP-Core's own"); }
            return false;
        }
    }

    /**
     * The account name behind a network nickname (the network plugin's own resolution: online and known names win
     * over nicknames), or the input unchanged when that plugin is missing or the name is not a nickname.
     */
    String accountName(String name) {
        Plugin staff = networkPlugin();
        if (name == null || staff == null) return name;
        try {
            Method real = staff.getClass().getDeclaredMethod("realName", String.class);
            real.setAccessible(true);
            Object value = real.invoke(staff, name);
            return value instanceof String account && !account.isBlank() ? account : name;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return name;
        }
    }

    /** "anyone", "friends" or "off"; null when unavailable. */
    String mode(UUID player, String networkKey) {
        if (networkKey == null || !bind()) return null;
        try {
            Object value = read.invoke(settings, player, networkKey);
            return value instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Turns the network plugin's own sidebar off for this player (saved there, so once per player): SMP-Core's
     * HUD is the only sidebar, and two plugins swapping boards made rows vanish or repeat. On the player's thread.
     */
    void disableNetworkSidebar(Player player) {
        if (player == null || !bind()) return;
        try {
            if (Boolean.TRUE.equals(sidebarEnabled.invoke(settings, player.getUniqueId()))) toggleSidebar.invoke(settings, player);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The network sidebar stays; the HUD still draws on its own board.
        }
    }

    /** Steps the setting once (Anyone, Friends, Off) on the player's thread; false when unavailable. */
    boolean cycle(Player player, String networkKey) {
        if (networkKey == null || !bind()) return false;
        try {
            cycle.invoke(settings, player, networkKey);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }
}
