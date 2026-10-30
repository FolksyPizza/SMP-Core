// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package me.pizzasmp.punishdrop;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Members of the configured LuckPerms groups ({@code protected-groups}, default {@code owner}) cannot be punished by
 * anyone outside those groups: no ban, IP ban, mute, kick or offense, whether issued by command or from a menu. The
 * console is never restricted. Staff with a {@code *} wildcard are not members, so this checks group membership, not
 * a permission. Without LuckPerms no group exists and nobody is protected; if LuckPerms cannot answer, the target is
 * treated as protected and the action is refused.
 */
final class ProtectedTargets {
    private final JavaPlugin plugin;
    private final Set<String> groups;

    ProtectedTargets(JavaPlugin plugin, List<String> groups) {
        this.plugin = plugin;
        this.groups = Set.copyOf(groups.stream().map(g -> g.toLowerCase(Locale.ROOT).trim()).filter(g -> !g.isEmpty()).toList());
    }

    /** True when the sender may act on protected players: the console, or a member of a protected group. */
    boolean actorExempt(CommandSender sender) {
        return !(sender instanceof Player player) || isProtected(player.getUniqueId());
    }

    boolean isProtected(UUID id) {
        if (id == null || groups.isEmpty() || !luckPermsPresent()) return false;
        try {
            var users = net.luckperms.api.LuckPermsProvider.get().getUserManager();
            var user = users.getUser(id);
            if (user == null) user = users.loadUser(id).get(2, TimeUnit.SECONDS);
            var options = user.getQueryOptions();
            return user.getInheritedGroups(options).stream()
                .anyMatch(g -> groups.contains(g.getName().toLowerCase(Locale.ROOT)));
        } catch (Exception | LinkageError failure) {
            plugin.getLogger().warning("Could not read LuckPerms groups; refusing the action: " + failure);
            return true;
        }
    }

    boolean isProtectedName(String name) {
        if (name == null || name.isBlank()) return false;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return isProtected(online.getUniqueId());
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && isProtected(cached.getUniqueId());
    }

    /**
     * True when the address belongs to a protected player: their current address while online, or the last address
     * this plugin recorded for them.
     *
     * @param knownAddress the plugin's last-known address lookup by player name
     */
    boolean isProtectedAddress(String address, Function<String, String> knownAddress) {
        if (address == null || address.isBlank() || groups.isEmpty() || !luckPermsPresent()) return false;
        try {
            for (UUID member : members()) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(member);
                Player online = player.getPlayer();
                if (online != null && online.getAddress() != null && online.getAddress().getAddress() != null
                        && address.equalsIgnoreCase(online.getAddress().getAddress().getHostAddress())) return true;
                String name = player.getName();
                if (name != null && address.equalsIgnoreCase(knownAddress.apply(name))) return true;
            }
            return false;
        } catch (Exception | LinkageError failure) {
            plugin.getLogger().warning("Could not list protected players; refusing the address action: " + failure);
            return true;
        }
    }

    private Set<UUID> members() throws Exception {
        var users = net.luckperms.api.LuckPermsProvider.get().getUserManager();
        Set<UUID> result = new HashSet<>();
        for (String group : groups) {
            result.addAll(users.searchAll(net.luckperms.api.node.matcher.NodeMatcher.key("group." + group))
                .get(2, TimeUnit.SECONDS).keySet());
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (isProtected(online.getUniqueId())) result.add(online.getUniqueId());
        }
        return result;
    }

    private static boolean luckPermsPresent() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }
}
