// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.networkcore;

import dev.pizzasmp.common.menu.MenuLock;
import dev.pizzasmp.common.scheduler.PlatformScheduler;
import dev.pizzasmp.economy.InventoryCustody;
import dev.pizzasmp.economy.VanillaItemRules;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Removes non-vanilla item state at player, container and world-item boundaries. */
final class VanillaItemSanitizer implements Listener {
    private static final int MAX_NESTING = 8;
    static final String EXEMPT_PERMISSION = "pizzasmp.itemsanitizer.exempt";
    private static final double MAX_ATTRIBUTE_MODIFIER = 20.0;

    record Destruction(String playerUuid, String playerName, String material, int amount, String reason, String context) { }

    private final JavaPlugin plugin;
    private final NamespacedKey legacySellWorthMarker;
    private final BiConsumer<Destruction, Player> audit;
    private final Set<String> creativeWorlds;
    private final AtomicBoolean custodyQueryFailureLogged = new AtomicBoolean();

    VanillaItemSanitizer(JavaPlugin plugin, BiConsumer<Destruction, Player> audit) {
        this.plugin = plugin;
        this.audit = audit;
        this.legacySellWorthMarker = new NamespacedKey(plugin, "sell_worth_marker");
        this.creativeWorlds = plugin.getConfig().getStringList("item-sanitizer.creative-worlds").stream()
            .map(String::trim).filter(name -> !name.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PlatformScheduler.globalRepeating(plugin, this::sweepOnlinePlayers, 20L, 100L);
        PlatformScheduler.globalLater(plugin, this::scanCurrentlyLoadedChunks, 40L);
    }

    private void sweepOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlatformScheduler.entityNow(plugin, player, () -> sanitizePlayer(player), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlatformScheduler.entityLater(plugin, player, () -> sanitizePlayer(player), null, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.SURVIVAL && event.getNewGameMode() != GameMode.ADVENTURE) return;
        Player player = event.getPlayer();
        PlatformScheduler.entityLater(plugin, player, () -> sanitizePlayer(player), null, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (custodyBlocks(player)) return;
        boolean changed = sanitizePlayerContents(player);
        changed |= sanitizeInventory(event.getInventory(), player, "opened_container");
        if (changed) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (custodyBlocks(player)) return;
        boolean changed = sanitizePlayerContents(player);
        changed |= sanitizeInventory(event.getView().getTopInventory(), player, "inventory_click");
        ItemStack originalCursor = event.getCursor();
        ItemStack cursor = sanitizeStack(originalCursor, player, "cursor", 0);
        if (cursor != originalCursor) {
            player.setItemOnCursor(cursor == null ? new ItemStack(Material.AIR) : cursor);
            changed = true;
        }
        if (changed) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (custodyBlocks(player)) return;
        boolean changed = sanitizePlayerContents(player);
        changed |= sanitizeInventory(event.getView().getTopInventory(), player, "inventory_drag");
        ItemStack originalCursor = event.getOldCursor();
        ItemStack cursor = sanitizeStack(originalCursor, player, "cursor", 0);
        if (cursor != originalCursor) {
            player.setItemOnCursor(cursor == null ? new ItemStack(Material.AIR) : cursor);
            changed = true;
        }
        if (changed) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        boolean sourceRestrictions = survivalRestrictionsForInventory(event.getSource(), null);
        boolean destinationRestrictions = survivalRestrictionsForInventory(event.getDestination(), null);
        boolean changed = sanitizeInventory(event.getSource(), null, "hopper_source", sourceRestrictions);
        changed |= sanitizeInventory(event.getDestination(), null, "hopper_destination", destinationRestrictions);
        ItemStack originalMoved = event.getItem();
        ItemStack moved = sanitizeStack(originalMoved, null, "hopper_transfer", 0,
            sourceRestrictions || destinationRestrictions);
        if (moved == null || moved != originalMoved || changed) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!sanitizeWorldItem(event.getEntity(), "item_spawn", true, null,
                survivalRestrictionsAt(event.getEntity().getWorld().getName()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onItemPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        Player picker = event.getEntity() instanceof Player player ? player : null;
        if (!sanitizeWorldItem(item, "item_pickup", false, picker,
                picker == null ? survivalRestrictionsAt(item.getWorld().getName()) : survivalRestrictionsFor(picker))) {
            event.setCancelled(true);
            item.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (custodyBlocks(event.getPlayer())) return;
        sanitizeWorldItem(event.getItemDrop(), "player_drop", false, event.getPlayer(), survivalRestrictionsFor(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChunkLoad(ChunkLoadEvent event) {
        scanChunk(event.getChunk());
    }

    private void scanCurrentlyLoadedChunks() {
        for (var world : Bukkit.getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                var location = new org.bukkit.Location(world, chunk.getX() << 4, world.getMinHeight(), chunk.getZ() << 4);
                PlatformScheduler.regionNow(plugin, location, () -> {
                    if (chunk.isLoaded()) scanChunk(chunk);
                });
            }
        }
    }

    private void scanChunk(org.bukkit.Chunk chunk) {
        boolean survivalRestrictions = survivalRestrictionsAt(chunk.getWorld().getName());
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof InventoryHolder holder) sanitizeInventory(holder.getInventory(), null, "chunk_container", survivalRestrictions);
        }
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item) sanitizeWorldItem(item, "chunk_item", false, null, survivalRestrictions);
            if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) {
                sanitizeInventory(holder.getInventory(), null, "chunk_entity_inventory", survivalRestrictions);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (sanitizePlayerContents(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (sanitizePlayerContents(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (sanitizePlayerContents(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && sanitizePlayerContents(player)) event.setCancelled(true);
    }

    private void sanitizePlayer(Player player) {
        if (!player.isOnline() || custodyBlocks(player)) return;
        sanitizePlayerContents(player);
        var view = player.getOpenInventory();
        if (view != null) sanitizeInventory(view.getTopInventory(), player, "open_container_sweep");
    }

    private boolean sanitizePlayerContents(Player player) {
        if (custodyBlocks(player)) return false;
        boolean changed = sanitizeInventory(player.getInventory(), player, "player_inventory");
        changed |= sanitizeInventory(player.getEnderChest(), player, "ender_chest");
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean armorChanged = sanitizeArray(armor, player, "armor");
        if (armorChanged) player.getInventory().setArmorContents(armor);
        changed |= armorChanged;
        ItemStack[] extra = player.getInventory().getExtraContents();
        boolean extraChanged = sanitizeArray(extra, player, "offhand");
        if (extraChanged) player.getInventory().setExtraContents(extra);
        changed |= extraChanged;
        ItemStack originalCursor = player.getItemOnCursor();
        ItemStack cursor = sanitizeStack(originalCursor, player, "cursor", 0);
        if (cursor != originalCursor) {
            player.setItemOnCursor(cursor == null ? new ItemStack(Material.AIR) : cursor);
            changed = true;
        }
        return changed;
    }

    private boolean sanitizeArray(ItemStack[] items, Player player, String context) {
        boolean changed = false;
        for (int i = 0; i < items.length; i++) {
            ItemStack current = items[i];
            ItemStack clean = sanitizeStack(current, player, context + "_slot_" + i, 0);
            if (clean != current) {
                items[i] = clean;
                changed = true;
            }
        }
        return changed;
    }

    private boolean sanitizeInventory(Inventory inventory, Player player, String context) {
        return sanitizeInventory(inventory, player, context, survivalRestrictionsForInventory(inventory, player));
    }

    private boolean sanitizeInventory(Inventory inventory, Player player, String context, boolean survivalRestrictions) {
        if (inventory == null) return false;
        InventoryHolder holder = inventory.getHolder(false);
        MenuLock.Locked locked = holder instanceof MenuLock.Locked menu ? menu : null;
        boolean changed = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (locked != null && !locked.depositSlot(slot)) continue;
            ItemStack current = inventory.getItem(slot);
            ItemStack clean = sanitizeStack(current, player, context + "_slot_" + slot, 0, survivalRestrictions);
            if (clean != current) {
                inventory.setItem(slot, clean);
                changed = true;
            }
        }
        return changed;
    }

    private ItemStack sanitizeStack(ItemStack stack, Player player, String context, int depth) {
        return sanitizeStack(stack, player, context, depth,
            player == null ? true : survivalRestrictionsFor(player));
    }

    private ItemStack sanitizeStack(ItemStack stack, Player player, String context, int depth, boolean survivalRestrictions) {
        if (stack == null || stack.getType().isAir()) return stack;
        // Exempt players keep everything in their own inventory and in containers they open.
        if (player != null && player.hasPermission(EXEMPT_PERMISSION)) return stack;
        if (depth > MAX_NESTING) {
            report(stack, player, "container_depth", context);
            return null;
        }
        String reason = violation(stack, survivalRestrictions);
        if (reason != null) {
            report(stack, player, reason, context);
            return null;
        }

        if (!stack.hasItemMeta()) return stack;
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
                List<ItemStack> cleaned = new ArrayList<>();
                boolean changed = false;
                for (int i = 0; i < bundle.getItems().size(); i++) {
                    ItemStack child = bundle.getItems().get(i);
                    ItemStack clean = sanitizeStack(child, player, context + "_bundle_" + i, depth + 1, survivalRestrictions);
                    if (clean == null) changed = true;
                    else {
                        cleaned.add(clean);
                        changed |= clean != child;
                    }
                }
                if (changed) {
                    ItemStack copy = stack.clone();
                    ItemMeta copyMeta = copy.getItemMeta();
                    ((BundleMeta) copyMeta).setItems(cleaned);
                    copy.setItemMeta(copyMeta);
                    return copy;
                }
            }
            if (meta instanceof BlockStateMeta blockMeta && blockMeta.hasBlockState()) {
                BlockState state = blockMeta.getBlockState();
                if (state instanceof InventoryHolder holder
                        && sanitizeInventory(holder.getInventory(), player, context + "_container", survivalRestrictions)) {
                    ItemStack copy = stack.clone();
                    BlockStateMeta copyMeta = (BlockStateMeta) copy.getItemMeta();
                    copyMeta.setBlockState(state);
                    copy.setItemMeta(copyMeta);
                    return copy;
                }
            }
        } catch (RuntimeException unreadable) {
            report(stack, player, "unreadable_container", context);
            return null;
        }
        return stack;
    }

    private String violation(ItemStack stack) {
        return violation(stack, true);
    }

    private String violation(ItemStack stack, boolean survivalRestrictions) {
        Material material = stack.getType();
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        boolean pluginData = false;
        boolean customModel = false;
        boolean unbreakable = false;
        boolean customPotionEffects = false;
        boolean validAttributes = true;
        List<VanillaItemRules.EnchantmentFact> enchantments = new ArrayList<>();

        if (meta != null) {
            for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                // The old sell GUI briefly tagged real items; its own join/container handler heals this marker.
                if (key.equals(legacySellWorthMarker)) continue;
                pluginData = true;
            }
            customModel = meta.hasCustomModelData() || meta.hasCustomModelDataComponent();
            if (meta.hasItemModel() && !"minecraft".equals(meta.getItemModel().getNamespace())) customModel = true;
            unbreakable = meta.isUnbreakable();
            customPotionEffects = meta instanceof PotionMeta potion && potion.hasCustomEffects();
            if (meta.hasAttributeModifiers()) {
                var modifiers = meta.getAttributeModifiers();
                if (modifiers == null) validAttributes = false;
                else for (Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
                    Attribute attribute = entry.getKey();
                    AttributeModifier modifier = entry.getValue();
                    if (attribute == null || modifier == null || !"minecraft".equals(attribute.getKey().getNamespace())
                            || !"minecraft".equals(modifier.getKey().getNamespace())
                            || !Double.isFinite(modifier.getAmount())
                            || Math.abs(modifier.getAmount()) > MAX_ATTRIBUTE_MODIFIER) {
                        validAttributes = false;
                        break;
                    }
                }
            }

            Map<Enchantment, Integer> values = new java.util.LinkedHashMap<>(meta.getEnchants());
            if (meta instanceof EnchantmentStorageMeta storage) values.putAll(storage.getStoredEnchants());
            boolean enchantedBook = material == Material.ENCHANTED_BOOK;
            for (Map.Entry<Enchantment, Integer> entry : values.entrySet()) {
                Enchantment enchantment = entry.getKey();
                String key = enchantmentKey(enchantment);
                Set<String> conflicts = new HashSet<>();
                for (Enchantment other : values.keySet()) {
                    String otherKey = enchantmentKey(other);
                    if (otherKey != null && !enchantment.equals(other) && enchantment.conflictsWith(other)) conflicts.add(otherKey);
                }
                enchantments.add(new VanillaItemRules.EnchantmentFact(key, entry.getValue(),
                    enchantment.getMaxLevel(), enchantedBook || enchantment.canEnchantItem(stack), conflicts));
            }
        }

        boolean registeredVanillaItem = material.isItem() && "minecraft".equals(material.getKey().getNamespace());
        VanillaItemRules.Facts facts = new VanillaItemRules.Facts(registeredVanillaItem, stack.getAmount(),
            material.getMaxStackSize(), pluginData, customModel, unbreakable, customPotionEffects, validAttributes, enchantments);
        return VanillaItemRules.violation(facts, material.getKey().toString(), survivalRestrictions);
    }

    /** Read-only check used before SMP-Core itself persists an item-operation payload. */
    String violationReason(ItemStack stack) {
        return this.violation(stack);
    }

    private static String enchantmentKey(Enchantment enchantment) {
        NamespacedKey key = enchantment.getKey();
        return key == null ? null : key.toString();
    }

    /** Returns true if the stack remains valid (a nested container may be rewritten). */
    private boolean sanitizeWorldItem(Item item, String context, boolean cancelSpawn) {
        return sanitizeWorldItem(item, context, cancelSpawn, null);
    }

    private boolean sanitizeWorldItem(Item item, String context, boolean cancelSpawn, Player player) {
        return sanitizeWorldItem(item, context, cancelSpawn, player,
            player == null ? survivalRestrictionsAt(item.getWorld().getName()) : survivalRestrictionsFor(player));
    }

    private boolean sanitizeWorldItem(Item item, String context, boolean cancelSpawn, Player player, boolean survivalRestrictions) {
        ItemStack original = item.getItemStack();
        ItemStack clean = sanitizeStack(original, player, context, 0, survivalRestrictions);
        if (clean == null) {
            if (cancelSpawn) {
                // ItemSpawnEvent owns cancellation; the caller will mark the event after this return.
                return false;
            }
            item.remove();
            return false;
        }
        if (clean != original) item.setItemStack(clean);
        return true;
    }

    private void report(ItemStack stack, Player player, String reason, String context) {
        Destruction entry = new Destruction(player == null ? null : player.getUniqueId().toString(),
            player == null ? null : player.getName(), stack.getType().name(), Math.max(1, stack.getAmount()), reason, context);
        if (audit != null) audit.accept(entry, player);
        else plugin.getLogger().warning("[item-sanitizer] removed " + entry.material() + " reason=" + reason + " context=" + context);
    }

    private boolean survivalRestrictionsFor(Player player) {
        GameMode mode = player.getGameMode();
        return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE)
            && survivalRestrictionsAt(player.getWorld().getName());
    }

    private boolean survivalRestrictionsAt(String worldName) {
        return worldName == null || !creativeWorlds.contains(worldName);
    }

    private boolean survivalRestrictionsForInventory(Inventory inventory, Player player) {
        if (player != null) return survivalRestrictionsFor(player);
        var location = inventory == null ? null : inventory.getLocation();
        var world = location == null ? null : location.getWorld();
        return survivalRestrictionsAt(world == null ? null : world.getName());
    }

    private boolean custodyBlocks(Player player) {
        if (player == null) return false;
        try {
            var registration = Bukkit.getServicesManager().getRegistration(InventoryCustody.class);
            if (registration == null) return false;
            InventoryCustody custody = registration.getProvider();
            return custody == null || custody.mutationBlocked(player);
        } catch (RuntimeException unavailable) {
            if (custodyQueryFailureLogged.compareAndSet(false, true)) {
                plugin.getLogger().warning("[item-sanitizer] deferred player inventory cleanup because Canopy custody state could not be read ("
                    + unavailable.getClass().getSimpleName() + ")");
            }
            return true;
        }
    }

}
