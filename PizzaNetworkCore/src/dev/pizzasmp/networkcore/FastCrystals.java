// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.pizzasmp.common.scheduler.PlatformScheduler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Fast Crystals: when a player with the setting on hits an end crystal, their client is told it is gone at once
 * instead of one round trip later, so their next click lands on the next crystal rather than the one already
 * broken. The server still breaks the crystal as usual; if it did not (the hit was refused), the crystal is shown
 * to the player again a few ticks later.
 */
final class FastCrystals implements Listener {
    private static final long RESHOW_TICKS = 3L;
    private final Plugin plugin;
    private final Predicate<UUID> enabled;
    private final Map<Integer, EnderCrystal> crystals = new ConcurrentHashMap<>();

    FastCrystals(Plugin plugin, Predicate<UUID> enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
    }

    void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.MONITOR,
                PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;
                Player player = event.getPlayer();
                if (player == null) return;
                PacketContainer packet = event.getPacket();
                Integer id = packet.getIntegers().readSafely(0);
                if (id == null) return;
                EnderCrystal crystal = crystals.get(id);
                if (crystal == null || !isAttack(packet) || !enabled.test(player.getUniqueId())) return;
                PacketContainer remove = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);
                remove.getIntLists().write(0, List.of(id));
                try {
                    ProtocolLibrary.getProtocolManager().sendServerPacket(player, remove);
                } catch (RuntimeException failure) {
                    return;
                }
                PlatformScheduler.entityLater(plugin, crystal, () -> {
                    if (!crystal.isValid() || !player.isOnline()) return;
                    player.hideEntity(plugin, crystal);
                    player.showEntity(plugin, crystal);
                }, null, RESHOW_TICKS);
            }
        });
    }

    private static boolean isAttack(PacketContainer packet) {
        try {
            var action = packet.getEnumEntityUseActions().readSafely(0);
            return action != null && action.getAction() == EnumWrappers.EntityUseAction.ATTACK;
        } catch (RuntimeException layout) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof EnderCrystal crystal) crystals.put(crystal.getEntityId(), crystal);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof EnderCrystal) crystals.remove(event.getEntity().getEntityId());
    }
}
