package dev.pizzasmp.plugins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Replaces the vanilla 9x3 (27-slot) ender chest with a 9x6 (54-slot) virtual inventory that is
 * persisted per player to a gzipped .dat file. Any way of opening an ender chest — the block, the
 * inventory-open event, or /ec | /enderchest | /endersee &lt;player&gt; — is redirected to the
 * expanded inventory.
 *
 * <p>Source note: the original source for this small plugin was lost; this is a cleaned,
 * parity-preserving reconstruction from the compiled 1.0.0 jar.
 */
public final class EnderchestExpander extends JavaPlugin implements Listener {

    private final Map<UUID, Inventory> expandedEnderchests = new HashMap<>();
    private final Map<UUID, Long> lastSaveTime = new HashMap<>();
    private File enderchestFolder;
    private static final long SAVE_INTERVAL_MS = 5000L;

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);
        this.enderchestFolder = new File(this.getDataFolder(), "enderchests");
        if (!this.enderchestFolder.exists()) {
            this.enderchestFolder.mkdirs();
        }
        this.getLogger().info("EnderchestExpander enabled - enderchests now 9x6!");
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::autoSaveAll, 6000L, 6000L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase();
        boolean self = msg.equals("/ec") || msg.equals("/enderchest")
                || msg.startsWith("/ec ") || msg.startsWith("/enderchest ");
        boolean endersee = msg.equals("/endersee") || msg.startsWith("/endersee ");
        if (!self && !endersee) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (endersee) {
            String[] parts = msg.split(" ");
            if (parts.length > 1) {
                Player target = Bukkit.getPlayer(parts[1]);
                if (target != null) {
                    player.openInventory(this.getOrCreateExpandedEnderchest(target));
                    return;
                }
            }
            player.sendActionBar(Component.text("§cPlayer not found or offline."));
            return;
        }
        player.openInventory(this.getOrCreateExpandedEnderchest(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Inventory inv = event.getInventory();
            if (!(inv.getHolder() instanceof DoubleChestInventory) && inv.getType() == InventoryType.ENDER_CHEST) {
                event.setCancelled(true);
                player.openInventory(this.getOrCreateExpandedEnderchest(player));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.ENDER_CHEST) {
            Player player = event.getPlayer();
            event.setCancelled(true);
            player.openInventory(this.getOrCreateExpandedEnderchest(player));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Inventory expanded = this.expandedEnderchests.get(player.getUniqueId());
            if (expanded != null && expanded.equals(event.getInventory())) {
                this.saveEnderchestData(player, expanded);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Inventory expanded = this.expandedEnderchests.get(uuid);
        if (expanded != null) {
            this.saveEnderchestData(player, expanded);
            this.expandedEnderchests.remove(uuid);
            this.lastSaveTime.remove(uuid);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.getOrCreateExpandedEnderchest(event.getPlayer());
    }

    private Inventory getOrCreateExpandedEnderchest(Player player) {
        UUID uuid = player.getUniqueId();
        Inventory existing = this.expandedEnderchests.get(uuid);
        if (existing != null) {
            return existing;
        }
        Inventory inv = Bukkit.createInventory(null, 54, "§7Enderchest");
        this.loadEnderchestData(player, inv);
        this.expandedEnderchests.put(uuid, inv);
        this.lastSaveTime.put(uuid, System.currentTimeMillis());
        return inv;
    }

    private void loadEnderchestData(Player player, Inventory inv) {
        File file = new File(this.enderchestFolder, player.getUniqueId() + ".dat");
        if (!file.exists()) {
            this.loadDefaultEnderchest(player, inv);
            return;
        }
        try (FileInputStream fis = new FileInputStream(file);
             GZIPInputStream gis = new GZIPInputStream(fis);
             BukkitObjectInputStream in = new BukkitObjectInputStream(gis)) {
            ItemStack[] items = (ItemStack[]) in.readObject();
            for (int i = 0; i < items.length && i < 54; i++) {
                if (items[i] != null) {
                    inv.setItem(i, items[i]);
                }
            }
        } catch (ClassNotFoundException | IOException ex) {
            this.getLogger().warning("Failed to load enderchest data for " + player.getName() + ": " + ex.getMessage());
            this.loadDefaultEnderchest(player, inv);
        }
    }

    // First run: seed the expanded chest from the player's real 27-slot vanilla ender chest.
    private void loadDefaultEnderchest(Player player, Inventory inv) {
        ItemStack[] vanilla = player.getEnderChest().getContents();
        for (int i = 0; i < vanilla.length && i < 54; i++) {
            if (vanilla[i] != null && vanilla[i].getType() != Material.AIR) {
                inv.setItem(i, vanilla[i].clone());
            }
        }
    }

    private void saveEnderchestData(Player player, Inventory inv) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now - this.lastSaveTime.getOrDefault(uuid, 0L) < SAVE_INTERVAL_MS) {
            return; // throttle writes
        }
        File file = new File(this.enderchestFolder, uuid + ".dat");
        try (FileOutputStream fos = new FileOutputStream(file);
             GZIPOutputStream gos = new GZIPOutputStream(fos);
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(gos)) {
            out.writeObject(inv.getContents());
            this.lastSaveTime.put(uuid, now);
        } catch (IOException ex) {
            this.getLogger().warning("Failed to save enderchest data for " + player.getName() + ": " + ex.getMessage());
        }
    }

    private void autoSaveAll() {
        for (Map.Entry<UUID, Inventory> entry : this.expandedEnderchests.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                this.saveEnderchestData(player, entry.getValue());
            }
        }
    }
}
