/*
 * MenuLock is part of the SMP-Core plugin suite (PizzaCommon shared library).
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Licensed under the Apache License, Version 2.0 (see LICENSE). No feature is gated or paid.
 */
package dev.pizzasmp.common.menu;

import java.util.function.IntPredicate;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Inventory menus whose items can never be taken out. Create every chest-style menu through {@link #create}: the menu
 * gets a {@link Locked} holder, and a listener in the owning plugin cancels any click or drag that would move an item
 * out of it (or into it), whichever button handler ran first. Menus that collect items from the player (selling,
 * order delivery) declare their deposit slots; those slots behave like a normal container and the plugin's own
 * handler decides what to accept.
 *
 * <p>The listener runs at {@link EventPriority#MONITOR} and only ever adds a cancellation, so existing click handlers
 * (including ones that ignore cancelled events) keep working unchanged.
 */
public final class MenuLock implements Listener {
    /** Holder of a locked menu. Plugins with their own holder classes implement this to get the same protection. */
    public interface Locked extends InventoryHolder {
        /** Whether a player may place or take items in this top-inventory slot. */
        default boolean depositSlot(int slot) { return false; }
    }

    private static final class Holder implements Locked {
        private final IntPredicate deposit;
        private Inventory inventory;
        Holder(IntPredicate deposit) { this.deposit = deposit; }
        @Override public boolean depositSlot(int slot) { return deposit.test(slot); }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static volatile boolean registered;

    private MenuLock() { }

    /** A menu with no deposit slots: nothing can be taken out or put in. */
    public static Inventory create(int size, String title) {
        return create(size, title, slot -> false);
    }

    /** A menu whose {@code deposit} slots accept and return the player's own items; every other slot is locked. */
    @SuppressWarnings("deprecation") // String titles: the suite still builds legacy-coloured menu titles
    public static Inventory create(int size, String title, IntPredicate deposit) {
        register();
        Holder holder = new Holder(deposit);
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        return inventory;
    }

    /**
     * Registers the listener for the plugin whose jar holds this copy of PizzaCommon (each plugin compiles its own),
     * once. Called by {@link #create}; plugins whose holders implement {@link Locked} directly call it on enable.
     */
    public static synchronized void register() {
        if (registered) return;
        JavaPlugin plugin = JavaPlugin.getProvidingPlugin(MenuLock.class);
        Bukkit.getPluginManager().registerEvents(new MenuLock(), plugin);
        registered = true;
    }

    /** Whether this view's top inventory is a locked menu. */
    public static boolean isMenu(InventoryView view) {
        return view.getTopInventory().getHolder(false) instanceof Locked;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (event.isCancelled() || !(event.getView().getTopInventory().getHolder(false) instanceof Locked menu)) return;
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (raw >= 0 && raw < topSize) {
            // Any action on a menu slot: pick up, place, swap, number key, offhand swap, drop, creative clone.
            if (!menu.depositSlot(raw)) event.setCancelled(true);
            return;
        }
        // In the player's own inventory: a double-click gathers matching items from the menu too, and shift-click
        // moves an item into the first free menu slot. Plugin handlers that accept shift-clicked deposits cancel the
        // event and place the item themselves, so a shift-click still uncancelled here would go to an arbitrary slot.
        InventoryAction action = event.getAction();
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent event) {
        if (event.isCancelled() || !(event.getView().getTopInventory().getHolder(false) instanceof Locked menu)) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && !menu.depositSlot(raw)) { event.setCancelled(true); return; }
        }
    }
}
