// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Freezes item and money changes for a player whose synced state has not loaded.
 *
 * Until the load lands, the player carries this server's local copy, which on a network is usually
 * stale. Anything gained, dropped or spent in that window would be overwritten or duplicated when the
 * real state arrives, so those actions are refused. Usually the window is a few hundred milliseconds;
 * it only lasts longer while the database is unreachable or the previous server is still saving.
 */
final class ArrivalGuard implements Listener {
    private final PlayerSyncManager sync;

    ArrivalGuard(PlayerSyncManager sync) {
        this.sync = sync;
    }

    private boolean held(Object entity) {
        return entity instanceof Player player && this.sync.isHeld(player.getUniqueId());
    }

    private void cancelIfHeld(Object entity, Cancellable event) {
        if (this.held(entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) { this.cancelIfHeld(e.getEntity(), e); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { this.cancelIfHeld(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) { this.cancelIfHeld(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) { this.cancelIfHeld(e.getWhoClicked(), e); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) { this.cancelIfHeld(e.getWhoClicked(), e); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { this.cancelIfHeld(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { this.cancelIfHeld(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) { this.cancelIfHeld(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) { this.cancelIfHeld(e.getPlayer(), e); }

    // A death would drop the stale local inventory into the world. The void is left alone: nothing
    // survives it, and the void guard handles the rescue.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.VOID) {
            this.cancelIfHeld(e.getEntity(), e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) { this.cancelIfHeld(e.getDamager(), e); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (this.held(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("Your data is still loading.", NamedTextColor.GRAY));
        }
    }
}
