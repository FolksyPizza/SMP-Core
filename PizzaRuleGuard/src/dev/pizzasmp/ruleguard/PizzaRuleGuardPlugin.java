package dev.pizzasmp.ruleguard;

/*
 * PizzaRuleGuardPlugin — part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Licensed under the MIT License (see LICENSE). No feature is gated or paid.
 */

import dev.pizzasmp.common.scheduler.PlatformScheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;

public final class PizzaRuleGuardPlugin extends JavaPlugin implements Listener {
   private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
   private static final int MAX_PENDING_OFFENSE_LINES = 8192;
   private static final int MAX_OFFENSE_LINES_PER_DRAIN = 256;
   private static final System.Logger OFFENSE_LOGGER = System.getLogger(PizzaRuleGuardPlugin.class.getName());
   private final Map<UUID, PizzaRuleGuardPlugin.MovementSnapshot> recentMovement = new ConcurrentHashMap<>();
   private final Map<UUID, PizzaRuleGuardPlugin.CounterWindow> inventoryMoveWindows = new ConcurrentHashMap<>();
   private final Map<UUID, Deque<Long>> scaffoldSamples = new ConcurrentHashMap<>();
   private final Map<UUID, Deque<Long>> combatClickSamples = new ConcurrentHashMap<>();
   private final Map<UUID, Deque<Long>> hiddenOreSamples = new ConcurrentHashMap<>();
   private final Map<UUID, PizzaRuleGuardPlugin.CounterWindow> dupeWindows = new ConcurrentHashMap<>();
   private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();
   private final Map<String, Long> punishmentCooldowns = new ConcurrentHashMap<>();
   private final Object offenseQueueLock = new Object();
   private final Object offenseFileLock = new Object();
   private final Deque<String> pendingOffenseLines = new ArrayDeque<>();
   private PlatformScheduler.TaskHandle offenseLogTask;
   private boolean offenseLogDrainScheduled;
   private boolean offenseLogClosing;
   private long droppedOffenseLogLines;
   private Path offenseLogPath;
   private PlatformScheduler.TaskHandle dupeSweepTask;

   public void onEnable() {
      this.saveDefaultConfig();
      this.offenseLogPath = this.getDataFolder().toPath().resolve("offenses.log");
      Bukkit.getPluginManager().registerEvents(this, this);
      this.startDupeSweepTask();
      this.warnIfAccountLimitHookIsStubbed();
      this.getLogger().info("PizzaRuleGuard enabled.");
   }

   public void onDisable() {
      if (this.dupeSweepTask != null) {
         this.dupeSweepTask.cancel();
         this.dupeSweepTask = null;
      }

      PlatformScheduler.TaskHandle offenseTask;
      synchronized (this.offenseQueueLock) {
         this.offenseLogClosing = true;
         offenseTask = this.offenseLogTask;
         this.offenseLogTask = null;
         this.offenseLogDrainScheduled = false;
      }
      if (offenseTask != null) {
         offenseTask.cancel();
      }
      this.drainOffenseLogOnDisable();

      this.recentMovement.clear();
      this.inventoryMoveWindows.clear();
      this.scaffoldSamples.clear();
      this.combatClickSamples.clear();
      this.hiddenOreSamples.clear();
      this.dupeWindows.clear();
      this.alertCooldowns.clear();
      this.punishmentCooldowns.clear();
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onPlayerMove(PlayerMoveEvent var1) {
      Location var2 = var1.getFrom();
      Location var3 = var1.getTo();
      if (var3 != null && !sameBlockAndRotation(var2, var3)) {
         double var4 = horizontalDistance(var2, var3);
         this.recentMovement.put(var1.getPlayer().getUniqueId(), new PizzaRuleGuardPlugin.MovementSnapshot(System.currentTimeMillis(), var4));
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onTeleport(PlayerTeleportEvent var1) {
      this.recentMovement.remove(var1.getPlayer().getUniqueId());
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onJoin(PlayerJoinEvent var1) {
      if (this.isDupeSweepEnabled()) {
         this.scanInventoryForIllegalStacks(var1.getPlayer(), "join");
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent var1) {
      UUID var2 = var1.getPlayer().getUniqueId();
      this.recentMovement.remove(var2);
      this.inventoryMoveWindows.remove(var2);
      this.scaffoldSamples.remove(var2);
      this.combatClickSamples.remove(var2);
      this.hiddenOreSamples.remove(var2);
      this.dupeWindows.remove(var2);
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onInventoryOpen(InventoryOpenEvent var1) {
      if (var1.getPlayer() instanceof Player var2 && this.isDupeSweepEnabled()) {
         this.scanInventoryForIllegalStacks(var2, "inventory-open");
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onInventoryClick(InventoryClickEvent var1) {
      if (var1.getWhoClicked() instanceof Player var2) {
         this.handleInventoryMoveCheck(var2, var1);
         if (this.isDupeSweepEnabled()) {
            PlatformScheduler.entityLater(this, var2,
               () -> this.scanInventoryForIllegalStacks(var2, "inventory-click"), null, 1L);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onBlockPlace(BlockPlaceEvent var1) {
      Player var2 = var1.getPlayer();
      if (this.isSurvivalLike(var2)) {
         if (this.getConfig().getBoolean("checks.scaffold.enabled", true)) {
            Location var3 = var2.getLocation();
            Location var4 = var1.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5);
            boolean var5 = (double)var1.getBlockPlaced().getY() <= Math.floor(var3.getY()) - 1.0;
            boolean var6 = !var2.isOnGround() || var2.getFallDistance() > 0.0F;
            boolean var7 = horizontalDistance(var3, var4) >= this.getConfig().getDouble("checks.scaffold.horizontal-reach-threshold", 1.15);
            boolean var8 = (double)Math.abs(var2.getLocation().getPitch()) >= this.getConfig().getDouble("checks.scaffold.min-pitch-degrees", 55.0);
            if (var5 || var6 && var7 || var5 && var8) {
               Deque var9 = this.scaffoldSamples.computeIfAbsent(var2.getUniqueId(), var0 -> new ArrayDeque<>());
               long var10 = System.currentTimeMillis();
               trimOlderThan(var9, var10 - this.getConfig().getLong("checks.scaffold.window-ms", 1400L));
               var9.addLast(var10);
               int var12 = this.getConfig().getInt("checks.scaffold.alert-threshold", 7);
               int var13 = this.getConfig().getInt("checks.scaffold.punish-threshold", 11);
               int var14 = var9.size();
               String var15 = "placements="
                  + var14
                  + ", below-feet="
                  + var5
                  + ", airborne="
                  + var6
                  + ", reach="
                  + String.format(Locale.US, "%.2f", horizontalDistance(var3, var4));
               if (var14 >= var13 && this.getConfig().getBoolean("checks.scaffold.cancel-at-high-confidence", false)) {
                  var1.setCancelled(true);
                  this.alert(var2, "scaffold", "Cancelled scaffold-like placement (" + var15 + ")", true);
                  this.maybePunish(var2, "scaffold", var14, var15);
               } else {
                  if (var14 >= var12) {
                     this.alert(var2, "scaffold", "Suspicious placement pattern (" + var15 + ")", true);
                     this.maybePunish(var2, "scaffold", var14, var15);
                  }
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBlockBreak(BlockBreakEvent var1) {
      Player var2 = var1.getPlayer();
      if (this.isSurvivalLike(var2) && this.getConfig().getBoolean("checks.mining.enabled", true)) {
         Block var3 = var1.getBlock();
         if (this.watchedMiningBlocks().contains(var3.getType()) && !this.isExposed(var3)) {
            long var4 = System.currentTimeMillis();
            Deque var6 = this.hiddenOreSamples.computeIfAbsent(var2.getUniqueId(), var0 -> new ArrayDeque<>());
            trimOlderThan(var6, var4 - this.getConfig().getLong("checks.mining.window-ms", 600000L));
            var6.addLast(var4);
            int var7 = var6.size();
            int var8 = this.getConfig().getInt("checks.mining.alert-threshold", 6);
            if (var7 >= var8) {
               String var9 = "hidden-valuables=" + var7 + ", block=" + var3.getType().name().toLowerCase(Locale.ROOT);
               this.alert(var2, "mining", "Suspicious hidden-ore mining pattern (" + var9 + ")", true);
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onCombatClick(EntityDamageByEntityEvent var1) {
      if (!(var1.getDamager() instanceof Player attacker) || !(var1.getEntity() instanceof Entity)) {
         return;
      }

      long eventTimestamp = System.currentTimeMillis();
      try {
         if (attacker.getScheduler().run(this,
               task -> this.recordCombatClick(attacker, eventTimestamp), () -> { }) == null) {
            this.getLogger().warning("Could not queue a RuleGuard combat sample because the attacker scheduler rejected it.");
         }
      } catch (RuntimeException ex) {
         this.getLogger().warning("Could not queue a RuleGuard combat sample ("
               + ex.getClass().getSimpleName() + ").");
      }
   }

   private void recordCombatClick(Player attacker, long eventTimestamp) {
      if (!this.isSurvivalLike(attacker) || !this.getConfig().getBoolean("checks.autoclicker.enabled", true)) {
         return;
      }

      Deque<Long> samples = this.combatClickSamples.computeIfAbsent(attacker.getUniqueId(), ignored -> new ArrayDeque<>());
      long latestTimestamp = samples.isEmpty() ? eventTimestamp : Math.max(eventTimestamp, samples.peekLast());
      addCombatClickSampleInTimeOrder(samples, eventTimestamp);
      trimOlderThan(samples, latestTimestamp - this.getConfig().getLong("checks.autoclicker.sample-window-ms", 3000L));

      int minimumSamples = this.getConfig().getInt("checks.autoclicker.minimum-samples", 12);
      if (samples.size() >= minimumSamples) {
         ClickStats stats = summarize(samples);
         double alertCps = this.getConfig().getDouble("checks.autoclicker.alert-cps", 15.0);
         double alertMaxStddevMs = this.getConfig().getDouble("checks.autoclicker.alert-max-stddev-ms", 8.0);
         if (!(stats.cps < alertCps) && !(stats.stddevMs > alertMaxStddevMs)) {
            String detail = "cps=" + formatDouble(stats.cps) + ", stddevMs=" + formatDouble(stats.stddevMs)
                  + ", samples=" + samples.size();
            this.alert(attacker, "autoclicker", "Low-variance combat clicks detected (" + detail + ")", true);
            if (stats.cps >= this.getConfig().getDouble("checks.autoclicker.punish-cps", 18.5)
                  && stats.stddevMs <= this.getConfig().getDouble("checks.autoclicker.punish-max-stddev-ms", 4.5)) {
               this.maybePunish(attacker, "autoclicker", (int)Math.round(stats.cps), detail);
            }
         }
      }
   }

   private static void addCombatClickSampleInTimeOrder(Deque<Long> samples, long timestamp) {
      if (samples.isEmpty() || timestamp >= samples.peekLast()) {
         samples.addLast(timestamp);
         return;
      }

      ArrayList<Long> ordered = new ArrayList<>(samples);
      int insertionPoint = java.util.Collections.binarySearch(ordered, timestamp);
      if (insertionPoint < 0) {
         insertionPoint = -insertionPoint - 1;
      } else {
         while (insertionPoint < ordered.size() && ordered.get(insertionPoint) <= timestamp) {
            insertionPoint++;
         }
      }
      ordered.add(insertionPoint, timestamp);
      samples.clear();
      samples.addAll(ordered);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onCraft(CraftItemEvent var1) {
      if (var1.getWhoClicked() instanceof Player var2) {
         CraftingInventory var7 = var1.getInventory();
         if (var7 instanceof CraftingInventory) {
            if (!this.getConfig().getBoolean("checks.crafting.enabled", true)) {
               return;
            }

            Recipe var8 = var7.getRecipe();
            ItemStack var5 = var8 != null ? var8.getResult() : null;
            ItemStack var6 = var1.getCurrentItem();
            if (this.containsImpossibleIngredients(var7.getMatrix())) {
               var1.setCancelled(true);
               this.alert(var2, "crafting", "Blocked crafting with impossible ingredient stack sizes", true);
               return;
            }

            if (!this.resultMatchesRecipe(var5, var6)) {
               var1.setCancelled(true);
               this.alert(var2, "crafting", "Blocked crafting result that does not match the recipe", true);
            }

            return;
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onAsyncPreLogin(AsyncPlayerPreLoginEvent var1) {
      if (this.getConfig().getBoolean("account-limit-hook.enabled", false)) {
         String var2 = this.getConfig().getString("account-limit-hook.backend", "none");
         if (var2 != null && !var2.equalsIgnoreCase("none")) {
            this.getLogger().info("Account-limit hook requested for " + var1.getName() + " using backend '" + var2 + "', but no backend adapter is installed.");
         }
      }
   }

   private void handleInventoryMoveCheck(Player var1, InventoryClickEvent var2) {
      if (this.getConfig().getBoolean("checks.inventory-move.enabled", true) && this.isSurvivalLike(var1)) {
         if (var2.getView().getTopInventory() != null && var2.getView().getTopInventory().getSize() > 0) {
            PizzaRuleGuardPlugin.MovementSnapshot var3 = this.recentMovement.get(var1.getUniqueId());
            if (var3 != null) {
               long var4 = System.currentTimeMillis();
               long var6 = this.getConfig().getLong("checks.inventory-move.move-window-ms", 160L);
               double var8 = this.getConfig().getDouble("checks.inventory-move.minimum-horizontal-distance", 0.28);
               if (var4 - var3.timestampMillis <= var6 && !(var3.horizontalDistance < var8)) {
                  PizzaRuleGuardPlugin.CounterWindow var10 = this.inventoryMoveWindows
                     .computeIfAbsent(
                        var1.getUniqueId(),
                        var1x -> new PizzaRuleGuardPlugin.CounterWindow(this.getConfig().getLong("checks.inventory-move.counter-reset-ms", 12000L))
                     );
                  int var11 = var10.increment(var4);
                  int var12 = this.getConfig().getInt("checks.inventory-move.alert-threshold", 4);
                  if (var11 >= var12) {
                     String var13 = "count="
                        + var11
                        + ", lastMoveMs="
                        + (var4 - var3.timestampMillis)
                        + ", horizontalDistance="
                        + formatDouble(var3.horizontalDistance);
                     this.alert(var1, "inventory-move", "Inventory interaction while moving (" + var13 + ")", false);
                  }
               }
            }
         }
      }
   }

   private boolean containsImpossibleIngredients(ItemStack[] var1) {
      if (var1 == null) {
         return false;
      } else {
         for (ItemStack var5 : var1) {
            if (this.isIllegalStack(var5)) {
               return true;
            }
         }

         return false;
      }
   }

   private boolean resultMatchesRecipe(ItemStack var1, ItemStack var2) {
      if (var1 != null) {
         if (var2 == null || var2.getType() == Material.AIR) {
            return false;
         } else if (var1.getType() != var2.getType()) {
            return false;
         } else {
            return var2.getAmount() > Math.max(1, var1.getAmount()) ? false : var2.getAmount() <= var2.getMaxStackSize();
         }
      } else {
         return var2 == null || var2.getType() == Material.AIR;
      }
   }

   private void scanInventoryForIllegalStacks(Player var1, String var2) {
      ArrayList var3 = new ArrayList();

      for (ItemStack var7 : var1.getInventory().getContents()) {
         if (this.isIllegalStack(var7)) {
            var3.add(var7.clone());
         }
      }

      ItemStack var10 = var1.getInventory().getItemInOffHand();
      if (this.isIllegalStack(var10)) {
         var3.add(var10.clone());
      }

      if (!var3.isEmpty()) {
         long var11 = System.currentTimeMillis();
         PizzaRuleGuardPlugin.CounterWindow var12 = this.dupeWindows
            .computeIfAbsent(
               var1.getUniqueId(), var1x -> new PizzaRuleGuardPlugin.CounterWindow(this.getConfig().getLong("checks.dupe.counter-reset-ms", 300000L))
            );
         int var8 = var12.increment(var11);
         String var9 = "source=" + var2 + ", offendingStacks=" + var3.size() + ", repeatCount=" + var8;
         this.alert(var1, "dupe", "Illegal inventory stack state detected (" + var9 + ")", true);
         if (this.getConfig().getBoolean("checks.dupe.remove-illegal-stacks", true)) {
            this.sanitizeInventory(var1);
         }
      }
   }

   private boolean isIllegalStack(ItemStack var1) {
      if (var1 != null && var1.getType() != Material.AIR) {
         int var2 = var1.getAmount();
         return var2 <= 0 || var2 > var1.getMaxStackSize();
      } else {
         return false;
      }
   }

   private void sanitizeInventory(Player var1) {
      boolean var2 = false;
      ItemStack[] var3 = var1.getInventory().getContents();

      for (int var4 = 0; var4 < var3.length; var4++) {
         ItemStack var5 = var3[var4];
         if (this.isIllegalStack(var5)) {
            var3[var4] = null;
            var2 = true;
         }
      }

      ItemStack var6 = var1.getInventory().getItemInOffHand();
      if (this.isIllegalStack(var6)) {
         var1.getInventory().setItemInOffHand(null);
         var2 = true;
      }

      if (var2) {
         var1.getInventory().setContents(var3);
         var1.updateInventory();
      }
   }

   private void maybePunish(Player var1, String var2, int var3, String var4) {
      String var5 = "punishments." + var2 + ".";
      if (this.getConfig().getBoolean(var5 + "enabled", false)) {
         String var6 = this.getConfig().getString(var5 + "command", "");
         if (var6 != null && !var6.isBlank()) {
            long var7 = System.currentTimeMillis();
            long var9 = this.getConfig().getLong(var5 + "cooldown-ms", 300000L);
            String var11 = var2 + ":" + var1.getUniqueId();
             long var12 = this.punishmentCooldowns.getOrDefault(var11, 0L);
             if (var7 - var12 >= var9) {
                this.punishmentCooldowns.put(var11, var7);
               String playerName = var1.getName();
               String detail = var4.replace('\n', ' ').replace('\r', ' ');
               String var14 = var6.replace("%player%", playerName)
                  .replace("%score%", Integer.toString(var3))
                  .replace("%detail%", detail);
               PlatformScheduler.TaskHandle dispatch;
               try {
                  dispatch = PlatformScheduler.globalNow(this, () -> {
                     boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), var14);
                     if (dispatched) {
                        this.logOffense("PUNISH " + var2 + " player=" + playerName + " score=" + var3 + " detail=" + detail);
                     } else {
                        this.getLogger().warning("Configured punishment command was not dispatched for check " + var2 + ".");
                     }
                  });
               } catch (RuntimeException failure) {
                  this.punishmentCooldowns.remove(var11, var7);
                  this.getLogger().warning("Could not schedule punishment command for check " + var2
                     + "; cooldown released (" + failure.getClass().getSimpleName() + ").");
                  return;
               }
               if (!dispatch.wasAccepted()) {
                  this.punishmentCooldowns.remove(var11, var7);
                  this.getLogger().warning("Could not schedule punishment command for check " + var2 + "; cooldown released.");
               }
            }
         }
      }
   }

   private void alert(Player var1, String var2, String var3, boolean var4) {
      long var5 = this.getConfig().getLong("alerts.cooldowns." + var2, this.getConfig().getLong("alerts.default-cooldown-ms", 8000L));
      String var7 = var2 + ":" + var1.getUniqueId();
      long var8 = System.currentTimeMillis();
      long var10 = this.alertCooldowns.getOrDefault(var7, 0L);
      if (var8 - var10 >= var5) {
         this.alertCooldowns.put(var7, var8);
         String var12 = color("&c[RuleGuard]&7 " + var1.getName() + " &f" + var3);
         String var13 = this.getConfig().getString("alerts.permission", "pizzaruleguard.alerts");
         boolean alertConsole = this.getConfig().getBoolean("alerts.console", true);
         String alertMessage = var12;
         String alertPermission = var13;
         try {
            PlatformScheduler.TaskHandle alertTask = PlatformScheduler.globalNow(this, () -> {
               for (Player recipient : Bukkit.getOnlinePlayers()) {
                  PlatformScheduler.entityNow(this, recipient, () -> {
                     if (recipient.isOp() || recipient.hasPermission(alertPermission)) {
                        recipient.sendMessage(alertMessage);
                     }
                  }, null);
               }

               if (alertConsole) {
                  Bukkit.getConsoleSender().sendMessage(alertMessage);
               }
            });
            if (!alertTask.wasAccepted()) {
               this.alertCooldowns.remove(var7, var8);
               this.getLogger().warning("Could not schedule alert for check " + var2 + "; alert cooldown released.");
            }
         } catch (RuntimeException failure) {
            this.alertCooldowns.remove(var7, var8);
            this.getLogger().warning("Could not schedule alert for check " + var2
               + "; alert cooldown released (" + failure.getClass().getSimpleName() + ").");
         }

         if (var4 && this.getConfig().getBoolean("logging.offense-log", true)) {
            this.logOffense(var2.toUpperCase(Locale.ROOT) + " player=" + var1.getName() + " " + var3);
         }
      }
   }

   private void logOffense(String var1) {
      synchronized (this.offenseQueueLock) {
         if (this.offenseLogClosing) return;
         String line = LOG_TIME.format(Instant.now()) + " " + ChatColor.stripColor(var1) + System.lineSeparator();
         if (this.pendingOffenseLines.size() < MAX_PENDING_OFFENSE_LINES) {
            this.pendingOffenseLines.addLast(line);
         } else {
            this.droppedOffenseLogLines++;
         }

         if (!this.offenseLogDrainScheduled && !this.pendingOffenseLines.isEmpty()) {
            this.scheduleOffenseLogDrain();
         }
      }
   }

   private void scheduleOffenseLogDrain() {
      this.offenseLogDrainScheduled = true;
      try {
         PlatformScheduler.TaskHandle task = PlatformScheduler.asyncNow(this, this::drainOffenseLogBatch);
         if (!task.wasAccepted()) {
            this.offenseLogDrainScheduled = false;
            this.offenseLogTask = null;
            OFFENSE_LOGGER.log(System.Logger.Level.WARNING,
               "Could not schedule the RuleGuard offense log writer; entries remain queued.");
            return;
         }
         this.offenseLogTask = task;
      } catch (RuntimeException ex) {
         this.offenseLogDrainScheduled = false;
         this.offenseLogTask = null;
         OFFENSE_LOGGER.log(System.Logger.Level.WARNING,
            "Could not schedule the RuleGuard offense log writer; entries remain queued.", ex);
      }
   }

   private void drainOffenseLogBatch() {
      String failure = null;
      synchronized (this.offenseFileLock) {
         for (int written = 0; written < MAX_OFFENSE_LINES_PER_DRAIN; written++) {
            String line;
            synchronized (this.offenseQueueLock) {
               line = this.pendingOffenseLines.peekFirst();
            }
            if (line == null) {
               break;
            }

            try {
               this.appendOffenseLine(line);
            } catch (IOException | RuntimeException ex) {
               failure = ex.getClass().getSimpleName();
               break;
            }

            synchronized (this.offenseQueueLock) {
               this.pendingOffenseLines.removeFirst();
            }
         }
      }

      long dropped;
      synchronized (this.offenseQueueLock) {
         dropped = this.droppedOffenseLogLines;
         this.droppedOffenseLogLines = 0L;
         if (failure != null || this.offenseLogClosing || this.pendingOffenseLines.isEmpty()) {
            this.offenseLogDrainScheduled = false;
            this.offenseLogTask = null;
         } else {
            this.scheduleOffenseLogDrain();
         }
      }

      if (failure != null) {
         OFFENSE_LOGGER.log(System.Logger.Level.WARNING,
            "Could not append a RuleGuard offense log entry (" + failure + "); entries remain queued.");
      }
      if (dropped > 0L) {
         OFFENSE_LOGGER.log(System.Logger.Level.WARNING,
            "Dropped " + dropped + " RuleGuard offense log entr" + (dropped == 1L ? "y" : "ies")
               + " because the pending log queue reached its limit.");
      }
   }

   private void drainOffenseLogOnDisable() {
      String failure = null;
      synchronized (this.offenseFileLock) {
         while (true) {
            String line;
            synchronized (this.offenseQueueLock) {
               line = this.pendingOffenseLines.peekFirst();
            }
            if (line == null) {
               break;
            }

            try {
               this.appendOffenseLine(line);
            } catch (IOException | RuntimeException ex) {
               failure = ex.getClass().getSimpleName();
               break;
            }

            synchronized (this.offenseQueueLock) {
               this.pendingOffenseLines.removeFirst();
            }
         }
      }

      long dropped;
      int remaining;
      synchronized (this.offenseQueueLock) {
         dropped = this.droppedOffenseLogLines;
         this.droppedOffenseLogLines = 0L;
         remaining = this.pendingOffenseLines.size();
      }
      if (failure != null) {
         OFFENSE_LOGGER.log(System.Logger.Level.WARNING,
            "Could not drain " + remaining + " queued RuleGuard offense log entr"
               + (remaining == 1 ? "y" : "ies") + " during disable (" + failure + ").");
      }
      if (dropped > 0L) {
         OFFENSE_LOGGER.log(System.Logger.Level.WARNING,
            "Dropped " + dropped + " RuleGuard offense log entr" + (dropped == 1L ? "y" : "ies")
               + " because the pending log queue reached its limit.");
      }
   }

   private void appendOffenseLine(String line) throws IOException {
      Files.createDirectories(this.offenseLogPath.getParent());
      Files.writeString(
         this.offenseLogPath,
         line,
         StandardCharsets.UTF_8,
         StandardOpenOption.CREATE,
         StandardOpenOption.WRITE,
         StandardOpenOption.APPEND
      );
   }

   private void startDupeSweepTask() {
      if (this.isDupeSweepEnabled()) {
         long var1 = Math.max(20L, this.getConfig().getLong("checks.dupe.periodic-scan-ticks", 1200L));
         PlatformScheduler.TaskHandle sweep;
         try {
            sweep = PlatformScheduler.globalRepeating(this, () -> {
               for (Player player : Bukkit.getOnlinePlayers()) {
                  PlatformScheduler.entityNow(this, player, () -> {
                     if (this.isSurvivalLike(player)) {
                        this.scanInventoryForIllegalStacks(player, "periodic-scan");
                     }
                  }, null);
               }
            }, var1, var1);
         } catch (RuntimeException failure) {
            this.getLogger().warning("Periodic dupe scan could not be scheduled; the configured scan is inactive ("
               + failure.getClass().getSimpleName() + ").");
            return;
         }
         if (sweep.wasAccepted()) {
            this.dupeSweepTask = sweep;
         } else {
            this.getLogger().warning("Periodic dupe scan could not be scheduled; the configured scan is inactive.");
         }
      }
   }

   private boolean isDupeSweepEnabled() {
      return this.getConfig().getBoolean("checks.dupe.enabled", true);
   }

   private void warnIfAccountLimitHookIsStubbed() {
      FileConfiguration var1 = this.getConfig();
      if (var1.getBoolean("account-limit-hook.enabled", false)) {
         String var2 = var1.getString("account-limit-hook.backend", "none");
         if (var2 == null || var2.equalsIgnoreCase("none")) {
            this.getLogger().warning("Account-limit hook is enabled but no account-linking backend is configured. This remains an extension point only.");
         }
      }
   }

   private Set<Material> watchedMiningBlocks() {
      HashSet var1 = new HashSet();

      for (String var3 : this.getConfig().getStringList("checks.mining.watched-blocks")) {
         Material var4 = Material.matchMaterial(var3);
         if (var4 != null) {
            var1.add(var4);
         }
      }

      return var1;
   }

   private boolean isExposed(Block var1) {
      int var2 = 0;
      int var3 = Math.max(0, this.getConfig().getInt("checks.mining.max-exposed-faces", 1));

      for (BlockFace var5 : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
         int x = var1.getX() + var5.getModX();
         int y = var1.getY() + var5.getModY();
         int z = var1.getZ() + var5.getModZ();
         if (!Bukkit.isOwnedByCurrentRegion(new Location(var1.getWorld(), x, y, z))) {
            // Mining events cannot wait for neighboring regions; skip this sample rather than read across owners.
            return true;
         }
         Material var6 = var1.getWorld().getBlockAt(x, y, z).getType();
         if (var6 == Material.AIR || var6 == Material.CAVE_AIR || var6 == Material.VOID_AIR || var6 == Material.WATER || var6 == Material.LAVA) {
            if (++var2 > var3) {
               return true;
            }
         }
      }

      return false;
   }

   private boolean isSurvivalLike(Player var1) {
      GameMode var2 = var1.getGameMode();
      return var2 == GameMode.SURVIVAL || var2 == GameMode.ADVENTURE;
   }

   private static void trimOlderThan(Deque<Long> var0, long var1) {
      while (!var0.isEmpty() && var0.peekFirst() < var1) {
         var0.removeFirst();
      }
   }

   private static PizzaRuleGuardPlugin.ClickStats summarize(Deque<Long> var0) {
      if (var0.size() < 2) {
         return new PizzaRuleGuardPlugin.ClickStats(0.0, 999.0);
      } else {
         ArrayList var1 = new ArrayList(var0);
         long var2 = (Long)var1.get(0);
         long var4 = (Long)var1.get(var1.size() - 1);
         double var6 = Math.max(0.05, (double)(var4 - var2) / 1000.0);
         double var8 = (double)var1.size() / var6;
         ArrayList var10 = new ArrayList();

         for (int var11 = 1; var11 < var1.size(); var11++) {
            var10.add((Long)var1.get(var11) - (Long)var1.get(var11 - 1));
         }

         // decompiler raw-generics fix: var10 is a raw ArrayList, so cast the elements explicitly
         double var15 = var10.stream().mapToLong(o -> ((Long) o).longValue()).average().orElse(0.0);
         double var13 = var10.stream().mapToDouble(var2x -> Math.pow((double)((Long) var2x).longValue() - var15, 2.0)).average().orElse(0.0);
         return new PizzaRuleGuardPlugin.ClickStats(var8, Math.sqrt(var13));
      }
   }

   private static boolean sameBlockAndRotation(Location var0, Location var1) {
      return var0.getWorld() == var1.getWorld()
         && var0.getX() == var1.getX()
         && var0.getY() == var1.getY()
         && var0.getZ() == var1.getZ()
         && var0.getYaw() == var1.getYaw()
         && var0.getPitch() == var1.getPitch();
   }

   private static double horizontalDistance(Location var0, Location var1) {
      if (var0.getWorld() != var1.getWorld()) {
         return 0.0;
      } else {
         double var2 = var0.getX() - var1.getX();
         double var4 = var0.getZ() - var1.getZ();
         return Math.sqrt(var2 * var2 + var4 * var4);
      }
   }

   private static String formatDouble(double var0) {
      return String.format(Locale.US, "%.2f", var0);
   }

   private static String color(String var0) {
      return ChatColor.translateAlternateColorCodes('&', var0);
   }

   private static final class ClickStats {
      private final double cps;
      private final double stddevMs;

      private ClickStats(double var1, double var3) {
         this.cps = var1;
         this.stddevMs = var3;
      }
   }

   private static final class CounterWindow {
      private final long resetAfterMillis;
      private int count;
      private long lastSeenMillis;

      private CounterWindow(long var1) {
         this.resetAfterMillis = var1;
      }

      private int increment(long var1) {
         if (var1 - this.lastSeenMillis > this.resetAfterMillis) {
            this.count = 0;
         }

         this.lastSeenMillis = var1;
         this.count++;
         return this.count;
      }
   }

   private static final class MovementSnapshot {
      private final long timestampMillis;
      private final double horizontalDistance;

      private MovementSnapshot(long var1, double var3) {
         this.timestampMillis = var1;
         this.horizontalDistance = var3;
      }
   }
}
