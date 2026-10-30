// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.economy;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Adds a generated batch without leaving a partial grant when inventory capacity changes. */
public final class SafeInventoryDelivery {
    private SafeInventoryDelivery() { }

    /** Must run on the inventory owner's scheduler while the Canopy custody lease is held. */
    public static boolean addExactly(Inventory inventory, List<ItemStack> offered) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(offered, "offered");
        if (offered.isEmpty()) return false;
        ItemStack[] before = cloneContents(inventory.getStorageContents());
        ItemStack[] copies = offered.stream().map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        return applyOrRestore(before, () -> {
            Map<Integer, ItemStack> leftovers = inventory.addItem(copies);
            return leftovers != null && leftovers.isEmpty();
        }, snapshot -> restore(inventory, snapshot));
    }

    static <Snapshot> boolean applyOrRestore(Snapshot before, BooleanSupplier apply, Consumer<Snapshot> restore) {
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(restore, "restore");
        try {
            if (apply.getAsBoolean()) return true;
        } catch (RuntimeException failed) {
            // A plugin or inventory implementation can throw after changing one or more slots.
        }
        restore.accept(before);
        return false;
    }

    private static void restore(Inventory inventory, ItemStack[] contents) {
        inventory.setStorageContents(cloneContents(contents));
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        if (contents == null) throw new IllegalStateException("inventory storage snapshot is missing");
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++)
            copy[index] = contents[index] == null ? null : contents[index].clone();
        return copy;
    }
}
