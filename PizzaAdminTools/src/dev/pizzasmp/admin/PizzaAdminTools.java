package dev.pizzasmp.admin;

/*
 * PizzaAdminTools is part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Released under the MIT License (see LICENSE). Provided AS IS, without warranty.
 */

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.ActionButton.Builder;
import io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback.Options;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Bed.Part;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

public final class PizzaAdminTools extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
   private static final String PERM_GTP = "pizzasmp.gtp";
   private static final String PERM_HOME_ADMIN = "sethome.admin";
   private static final String PERM_HOME_USE = "sethome.use";
   private static final String PERM_HOMES_CMD = "pizzasmp.homes";
   private static final String PERM_MENU_CMD = "pizzasmp.menu";
   private static final String PERM_GUIDE_CMD = "pizzasmp.guide";
   private static final String PERM_FREEZE_CMD = "pizzasmp.freeze";
   private static final String PERM_UNFREEZE_CMD = "pizzasmp.unfreeze";
   private static final String PERM_TRANSFER_CMD = "pizzasmp.transfer";
   private static final String PERM_TRANSFER_OTHERS_CMD = "pizzasmp.transfer.others";
   private static final String PERM_TRANSFER_MAINTENANCE_CMD = "pizzasmp.transfer.maintenance";
   private static final String PERM_MAINTENANCE_STAY = "pizzasmp.maintenance.stay";
   private static final String PERM_MAINT_ADMIN = "pizzasmp.maintenance.admin";
   private static final String PERM_COMBAT_BYPASS = "pizzasmp.combat.bypass";
   private static final String PERM_PLUGIN_ADMIN = "pizzasmp.pluginadmin";
   private static final String PERM_SUS_CMD = "pizzasmp.sus";
   private static final String PERM_NV_CMD = "pizzasmp.nv";
   private static final String PERM_NV_OTHERS_CMD = "pizzasmp.nv.others";
   private static final String PERM_STASH_CMD = "pizzasmp.admin.stash";
   private static final String PERM_ATRACK_CMD = "pizzasmp.admin.track";
   private static final String PERM_PIZZAPLUS_ADMIN = "pizzasmp.admin.pizzaplus";
   private static final String PERM_PERKS_CMD = "pizzasmp.perks";
   private static final String LP_GROUP_PIZZAPLUS = "pizza+";
   private static final String PERM_NODE_PIZZAPLUS = "group.pizza+";
   private static final String LP_GROUP_PIZZAPLUSPLUS = "pizza++";
   private static final String PERM_NODE_PIZZAPLUSPLUS = "group.pizza++";
   private static final long PIZZAPLUS_STIPEND_PER_MONTH = 500L;
   private static final long PIZZAPLUSPLUS_STIPEND_PER_MONTH = 1000L;
   private static final String PIZZAPLUS_LIST_TITLE = "&6Pizza+ Subscribers";
   private static final String PERM_RTP_USE = "rtp.use";
   private static final String RTP_PLUGIN_NAME = "RTPGUI";
   private static final String RTP_LISTENER_CLASS = "com.jolly.rtp.RTPListener";
   private static final String BRAND_NAME = "PizzaPaper";
   private static final String DISCORD_INVITE = "discord.gg/example";
   private static String BRAND_DISPLAY = "ExampleSMP";
   private static String BRAND_DISCORD = "discord.gg/example";
   private static String BRAND_SECTION = "&x&0&0&B&F&F&F";
   private static net.kyori.adventure.text.format.TextColor BRAND_COLOR = net.kyori.adventure.text.format.TextColor.color(0x00BFFF);
   private static void loadBranding() {
      try {
         org.bukkit.configuration.file.YamlConfiguration b = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            new java.io.File("plugins/PizzaNetworkCore/branding.yml"));
         String a = b.getString("active", "example");
         BRAND_DISPLAY = b.getString("profiles." + a + ".display", "ExampleSMP");
         BRAND_DISCORD = b.getString("profiles." + a + ".discord", "discord.gg/example");
         String hex = b.getString("profiles." + a + ".colors.primary", "00BFFF").toUpperCase(java.util.Locale.ROOT);
         BRAND_COLOR = net.kyori.adventure.text.format.TextColor.color(Integer.parseInt(hex, 16));
         StringBuilder sb = new StringBuilder("&x");
         for (char c : hex.toCharArray()) sb.append('&').append(c);
         BRAND_SECTION = sb.toString();
         SUS_MENU_TITLE = "&8" + BRAND_DISPLAY + " Sus";
         HOME_MENU_TITLE = "&8" + BRAND_DISPLAY + " Homes";
         HOME_PRIMARY = BRAND_SECTION;
      } catch (Exception ignored) {}
   }
   private static String SUS_MENU_TITLE = "&8ExampleSMP Sus";
   private static final long COMBAT_TAG_MILLIS = 15000L;
   private static final long SUS_LOOKBACK_MILLIS = 1800000L;
   private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
   private static final int SUS_PAGE_SIZE = 45;
   private static final Set<String> NON_TELEPORT_HOME_SUBCOMMANDS = Set.of(
      "create", "delete", "admin", "reload", "import", "open", "help", "set", "remove", "team"
   );
   private static final Set<String> FROZEN_ALLOWED_COMMANDS = Set.of("freeze", "unfreeze", "msg", "tell", "w", "whisper", "r", "reply");
   private static final Set<String> ONLINE_TARGET_FIRST_ARG_COMMANDS = Set.of(
      "tpa", "tpahere", "tpaccept", "tpdeny", "msg", "tell", "w", "whisper", "pay", "trade", "duel"
   );
   private static final Set<String> RESTRICTED_FOR_NON_STAFF = Set.of(
      "attribute",
      "ban",
      "ban-ip",
      "banip",
      "banlist",
      "bossbar",
      "clone",
      "damage",
      "data",
      "datapack",
      "debug",
      "defaultgamemode",
      "deop",
      "dialog",
      "difficulty",
      "effect",
      "execute",
      "experience",
      "fill",
      "fillbiome",
      "forceload",
      "function",
      "gamemode",
      "gamerule",
      "give",
      "gm",
      "gma",
      "gmc",
      "gmt",
      "gms",
      "gmsp",
      "jfr",
      "kick",
      "kill",
      "locate",
      "locatebiome",
      "loot",
      "op",
      "pardon",
      "pardon-ip",
      "particle",
      "perf",
      "place",
      "playsound",
      "reload",
      "reset",
      "restart",
      "ride",
      "rl",
      "rotate",
      "save-all",
      "save-off",
      "save-on",
      "say",
      "schedule",
      "scoreboard",
      "seed",
      "setblock",
      "setidletimeout",
      "setworldspawn",
      "spawnpoint",
      "spectate",
      "spreadplayers",
      "stop",
      "stopsound",
      "summon",
      "tab",
      "tag",
      "tell",
      "tellraw",
      "teleport",
      "tempban",
      "tempbanip",
      "test",
      "tick",
      "title",
      "tp",
      "tpall",
      "tphere",
      "tpo",
      "tpohere",
      "tppos",
      "transfer",
      "unban",
      "unbanip",
      "viewdistance",
      "waypoint",
      "whitelist",
      "worldborder",
      "xp",
      "broadcast",
      "bcast",
      "butcher",
      "ci",
      "clear",
      "clearinventory",
      "clearwarnings",
      "eco",
      "economy",
      "ess",
      "essentials",
      "feed",
      "fix",
      "fly",
      "flyspeed",
      "freeze",
      "getpos",
      "god",
      "heal",
      "i",
      "invsee",
      "item",
      "jail",
      "jails",
      "kit",
      "kits",
      "killall",
      "maintenance",
      "more",
      "near",
      "nick",
      "nickname",
      "offend",
      "pizzaplus",
      "powertool",
      "ptime",
      "pweather",
      "remove",
      "repair",
      "seen",
      "setspawn",
      "setwarp",
      "delwarp",
      "socialspy",
      "spawnmob",
      "spawnstash",
      "speed",
      "stash",
      "sudo",
      "time",
      "togglejail",
      "top",
      "unfreeze",
      "unjail",
      "unmute",
      "unoffend",
      "vanish",
      "walkspeed",
      "weather",
      "geyser",
      "grim",
      "grimac",
      "mspt",
      "paper",
      "plan",
      "spigot",
      "timings",
      "viabackwards",
      "viaversion",
      "admin",
      "admindelhome",
      "bancheck",
      "bans",
      "clearbans",
      "clearmutes",
      "call",
      "deluxmenu",
      "maintenancemotd",
      "setmaintenancemotd",
      "maintmotd",
      "limbomaint",
      "deluxemenus",
      "diagnostics",
      "dm",
      "dmenu",
      "gtp",
      "history",
      "listbans",
      "listmutes",
      "lp",
      "luckperms",
      "moderation",
      "mute",
      "papi",
      "perm",
      "permban",
      "permission",
      "permissions",
      "perms",
      "pizzaadmin",
      "pizzaadmintools",
      "pizzabans",
      "pizzadebug",
      "pizzahome",
      "pizzamenus",
      "pizzasusflag",
      "pizzateams",
      "placeholderapi",
      "pm",
      "pong",
      "punish",
      "reply",
      "rtpreload",
      "searchid",
      "sethomegui",
      "sfmode",
      "stopwatch",
      "suicide",
      "sus",
      "suspicious",
      "tools",
      "warp",
      "warps",
      "whisper",
      "eafk",
      "eantioch",
      "eattack",
      "eban",
      "ebanip",
      "ebreak",
      "ebroadcast",
      "eburn",
      "eclear",
      "eclearinventory",
      "edelhome",
      "edelwarp",
      "edeop",
      "edisposal",
      "eeco",
      "eecogive",
      "eecotake",
      "efeed",
      "efireball",
      "efirework",
      "efly",
      "egamemode",
      "egetpos",
      "egive",
      "egod",
      "eheal",
      "ehelpop",
      "einvsee",
      "eitem",
      "ejails",
      "ejump",
      "ekick",
      "ekickall",
      "ekill",
      "ekit",
      "ekittycannon",
      "elist",
      "emore",
      "emute",
      "enchant",
      "enear",
      "enick",
      "eopme",
      "eplayerlist",
      "epowertool",
      "eptime",
      "epweather",
      "equit",
      "eremove",
      "erepair",
      "eseen",
      "esell",
      "esetspawn",
      "esetwarp",
      "eshowkit",
      "esocialspy",
      "espawnmob",
      "esudo",
      "etempban",
      "etop",
      "etreasure",
      "evanish",
      "ewarp",
      "eweather",
      "eworkbench",
      "eworld"
   );
   private static final Set<String> PLUGIN_ADMIN_COMMANDS = Set.of("lp", "luckperms", "version", "ver", "about", "paper", "timings");
   private static final Map<String, String> RTP_WORLD_ALIASES = Map.ofEntries(
      Map.entry("world", "world"),
      Map.entry("overworld", "world"),
      Map.entry("ow", "world"),
      Map.entry("nether", "world_nether"),
      Map.entry("the_nether", "world_nether"),
      Map.entry("world_nether", "world_nether"),
      Map.entry("n", "world_nether"),
      Map.entry("end", "world_the_end"),
      Map.entry("the_end", "world_the_end"),
      Map.entry("world_the_end", "world_the_end"),
      Map.entry("e", "world_the_end")
   );
   private static String HOME_MENU_TITLE = "&8ExampleSMP Homes";
   private static final String HOME_DELETE_TITLE = "&8Confirm Home Deletion";
   private static final int MAX_HOME_SLOTS = 27;
   private static final String PERM_HOMES_9 = "pizzasmp.homes.9";
   private static final String PERM_HOMES_27 = "pizzasmp.homes.27";
   private static final int HOMES_DEFAULT_LIMIT = 3;
   private static final Pattern HOME_NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_,'!?()#+~@=\\-]{1,24}$");
   private static final int[] BED_SLOTS = new int[]{10, 11, 12, 13, 14};
   private static final int[] DYE_SLOTS = new int[]{19, 20, 21, 22, 23};
   private static final String OWNER_BLUE = "&b";
   private static String HOME_PRIMARY = "&b";
   private static final List<String> BRANDED_PLUGIN_LIST = List.of(
      "PizzaTeamsGUI",
      "PizzaTeams",
      "PizzaMenus",
      "Essentials",
      "Essentials Chat",
      "floodgate",
      "Geyser-Spigot",
      "PizzaAC",
      "PizzaBans",
      "LuckPerms",
      "Maintenance",
      "MyCommand",
      "PizzaAdminTools",
      "PizzaChatGuard",
      "PlaceholderAPI",
      "Plan",
      "PizzaPunishment",
      "PizzaHome",
      "TAB",
      "Vault",
      "ViaBackwards",
      "ViaVersion"
   );
   private final Map<UUID, Long> combatTaggedUntil = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> adminTargetIndex = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> homesDialogExpanded = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> pendingDeleteSlot = new ConcurrentHashMap<>();
   private final Map<UUID, String> pendingDeleteHome = new ConcurrentHashMap<>();
   private final Map<UUID, Long> noFallDamageUntil = new ConcurrentHashMap<>();
   private final Set<UUID> staffMode = ConcurrentHashMap.newKeySet();
   private File staffModeFile;
   private final Set<UUID> creativeBypass = ConcurrentHashMap.newKeySet();
   private final Map<UUID, Integer> pendingTeleportTasks = new ConcurrentHashMap<>();
   private final Map<UUID, Location> pendingTeleportOrigins = new ConcurrentHashMap<>();
   private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
   private final Map<UUID, Location> frozenAnchors = new ConcurrentHashMap<>();
   private final Map<UUID, Long> frozenNoticeCooldown = new ConcurrentHashMap<>();
   private volatile boolean maintenanceActive = false;
   private String maintenanceReason = "Scheduled maintenance";
   private final Set<UUID> maintenanceFrozen = ConcurrentHashMap.newKeySet();
   private File maintenanceModeFile;
   private final Map<UUID, Integer> susMenuPages = new ConcurrentHashMap<>();
   private final Set<UUID> nvEnabled = ConcurrentHashMap.newKeySet();
   private final Map<UUID, UUID> atrackTargets = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> atrackTasks = new ConcurrentHashMap<>();
   private final Map<UUID, GameMode> atrackPriorMode = new ConcurrentHashMap<>();
   private CommandMap commandMap;
   private File transferDestinationsFile;
   private FileConfiguration transferDestinationsConfig;
   private File maintenanceTransferStateFile;
   private FileConfiguration maintenanceTransferStateConfig;
   private static final Material[] STASH_TINY_POOL = new Material[]{
      Material.ENDER_CHEST,
      Material.CHEST,
      Material.BARREL,
      Material.CANDLE,
      Material.WHITE_CANDLE,
      Material.ORANGE_CANDLE,
      Material.YELLOW_CANDLE,
      Material.LANTERN,
      Material.SOUL_LANTERN,
      Material.TORCH,
      Material.SOUL_TORCH,
      Material.JUKEBOX,
      Material.LECTERN,
      Material.COMPOSTER,
      Material.BREWING_STAND,
      Material.FLOWER_POT,
      Material.CAULDRON,
      Material.GRINDSTONE,
      Material.CRAFTING_TABLE,
      Material.FURNACE,
      Material.SMOKER,
      Material.BLAST_FURNACE,
      Material.CARTOGRAPHY_TABLE,
      Material.SMITHING_TABLE,
      Material.LOOM,
      Material.BOOKSHELF,
      Material.BEEHIVE
   };
   private static final long PIZZAPLUS_DEFAULT_DAYS = 30L;
   private File pizzaPlusSubsFile;
   private FileConfiguration pizzaPlusSubsConfig;
   private static final TextColor DIALOG_BRAND = TextColor.color(49151);
   private static final List<String> HOME_ICONS = List.of("\ud83d\udecf", "⛏", "⚔", "★", "♥", "☀", "☾", "⚓", "☠", "✿", "♦", "⚑");
   private String[] cachedDbCreds;
   private static final Set<String> ALWAYS_VALID_ROOTS = Set.of(
      "plugins", "pl", "help", "?", "ver", "version", "about", "icanhasbukkit", "me", "msg", "tell", "w", "whisper", "r", "reply", "trigger", "teammsg", "tm"
   );

   public void onEnable() {
      loadBranding();
      this.registerCommand("gtp", true);
      this.registerCommand("homes", false);
      this.registerCommand("menu", false);
      this.registerCommand("guide", false);
      this.registerCommand("freeze", true);
      this.registerCommand("unfreeze", true);
      this.registerCommand("transfer", true);
      this.registerCommand("transfermaintenance", true);
      this.registerCommand("pizzaadmintools", false);
      this.registerCommand("pizzateams", false);
      this.registerCommand("pizzamenus", false);
      this.registerCommand("pizzahome", false);
      this.registerCommand("pizzabans", false);
      this.registerCommand("pizzaplugins", false);
      this.registerCommand("stash", false);
      this.registerCommand("spawnstash", false);
      this.registerCommand("pizzaplus", true);
      this.registerCommand("perks", false);
      this.registerCommand("atrack", true);
      this.registerCommand("servermaint", true);
      this.registerCommand("sfmode", false);
      this.registerCommand("gmcbypass", false);
      this.commandMap = this.resolveCommandMap();
      this.loadStaffMode();
      this.initTransferDestinations();
      this.initMaintenanceTransferState();
      this.loadMaintenanceMode();
      Bukkit.getScheduler().runTaskTimer(this, () -> {
         if (this.maintenanceActive) {
            for (UUID var2 : this.maintenanceFrozen) {
               Player var3 = Bukkit.getPlayer(var2);
               if (var3 != null && var3.isOnline()) {
                  this.showMaintenanceTitle(var3);
               }
            }
         }
      }, 40L, 40L);
      this.initPizzaPlusSubscriptions();
      this.getServer().getPluginManager().registerEvents(this, this);
      Bukkit.getScheduler().runTaskTimer(this, this::runPizzaPlusExpirySweep, 1200L, 72000L);
      // NV retired here: /nv + /nightvision now live in PizzaNetworkCore, unified with the
      // /settings "night_vision" toggle (one shared state). loadNvState + the re-apply timer are
      // intentionally NOT started so PAT can never fight PNC over the potion effect (nvEnabled
      // stays empty, which also neutralizes the respawn/consume re-apply hooks).
      this.getLogger().info("PizzaAdminTools enabled.");
   }

   public void onDisable() {
      for (int var2 : this.pendingTeleportTasks.values()) {
         Bukkit.getScheduler().cancelTask(var2);
      }

      this.pendingTeleportTasks.clear();
      this.combatTaggedUntil.clear();
      this.adminTargetIndex.clear();
      this.pendingDeleteSlot.clear();
      this.frozenPlayers.clear();
      this.frozenAnchors.clear();
      this.frozenNoticeCooldown.clear();
      this.susMenuPages.clear();
      this.commandMap = null;
      this.saveMaintenanceTransferState();
      this.savePizzaPlusSubscriptions();
   }

   private void registerCommand(String var1, boolean var2) {
      if (this.getCommand(var1) == null) {
         this.getLogger().warning("Command missing in plugin.yml: " + var1);
      } else {
         Objects.requireNonNull(this.getCommand(var1), "command missing: " + var1).setExecutor(this);
         if (var2) {
            Objects.requireNonNull(this.getCommand(var1), "command missing: " + var1).setTabCompleter(this);
         }
      }
   }

   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      String var5 = var2.getName().toLowerCase(Locale.ROOT);
      switch (var5) {
         case "gtp":
            return this.handleGtpCommand(var1, var4);
         case "homes":
            return this.handleHomesCommand(var1);
         case "menu":
            return this.handleMenuCommand(var1);
         case "guide":
            return this.handleGuideCommand(var1);
         case "freeze":
            return this.handleFreezeCommand(var1, var4);
         case "unfreeze":
            return this.handleUnfreezeCommand(var1, var4);
         case "servermaint":
            return this.handleServerMaintCommand(var1, var4);
         case "transfer":
            return this.handleTransferCommand(var1, var4);
         case "transfermaintenance":
            return this.handleTransferMaintenanceCommand(var1, var4);
         case "pizzaadmintools":
            return this.handlePizzaAdminToolsHelp(var1);
         case "pizzateams":
            return this.handlePizzaTeamsHelp(var1);
         case "pizzamenus":
            return this.handlePizzaMenusHelp(var1);
         case "pizzahome":
            return this.handlePizzaHomeHelp(var1);
         case "pizzabans":
            return this.handlePizzaBansHelp(var1);
         case "pizzaplugins":
            return this.handlePizzaPlugins(var1);
         case "sus":
            return this.handleSusCommand(var1, var4);
         case "stash":
         case "spawnstash":
            return this.handleStashCommand(var1);
         case "pizzaplus":
            return this.handlePizzaPlusCommand(var1, var4);
         case "perks":
            return this.handlePerksCommand(var1);
         case "atrack":
            return this.handleAtrackCommand(var1, var4);
         case "sfmode":
            return this.handleSfModeCommand(var1);
         case "gmcbypass":
            return this.handleGmcBypassCommand(var1, var4);
         case "nv":
         case "nightvision":
            return this.handleNightVisionCommand(var1, var4);
         default:
            return false;
      }
   }

   public List<String> onTabComplete(CommandSender var1, Command var2, String var3, String[] var4) {
      if (var2.getName().equalsIgnoreCase("freeze")) {
         return var4.length == 1 ? this.completePlayerNames(var4[0], false) : Collections.emptyList();
      } else if (var2.getName().equalsIgnoreCase("unfreeze")) {
         return var4.length == 1 ? this.completePlayerNames(var4[0], true) : Collections.emptyList();
      } else if (var2.getName().equalsIgnoreCase("transfer")) {
         return this.completeTransferCommand(var1, var4);
      } else if (var2.getName().equalsIgnoreCase("transfermaintenance")) {
         return var4.length == 1 ? filterByPrefix(List.of("on", "off", "status"), var4[0]) : Collections.emptyList();
      } else if (var2.getName().equalsIgnoreCase("sus")) {
         return var4.length == 1 ? filterByPrefix(List.of("1", "2", "3", "4", "5"), var4[0]) : Collections.emptyList();
      } else if (var2.getName().equalsIgnoreCase("atrack")) {
         return var4.length == 1 ? this.completePlayerNames(var4[0], false) : Collections.emptyList();
      } else if (!var2.getName().equalsIgnoreCase("pizzaplus")) {
         if (!var2.getName().equalsIgnoreCase("gtp")) {
            return Collections.emptyList();
         } else if (var4.length == 1) {
            String var13 = var4[0].toLowerCase(Locale.ROOT);
            ArrayList var14 = new ArrayList();

            for (OfflinePlayer var16 : this.getKnownPlayersWithHomes()) {
               String var17 = var16.getName();
               if (var17 != null && var17.toLowerCase(Locale.ROOT).startsWith(var13)) {
                  var14.add(var17);
               }
            }

            return var14;
         } else if (var4.length == 2) {
            OfflinePlayer var5 = Bukkit.getOfflinePlayer(var4[0]);
            File var6 = this.getSetHomeDataFile(var5);
            if (var6 != null && var6.exists()) {
               YamlConfiguration var7 = YamlConfiguration.loadConfiguration(var6);
               List var8 = var7.getStringList("homes");
               ArrayList var9 = new ArrayList();
               String var10 = var4[1].toLowerCase(Locale.ROOT);

               for (int var11 = 0; var11 < var8.size(); var11++) {
                  String var12 = String.valueOf(var11 + 1);
                  if (var12.startsWith(var10)) {
                     var9.add(var12);
                  }
               }

               for (String var19 : (Iterable<String>) var8) {
                  if (var19.toLowerCase(Locale.ROOT).startsWith(var10)) {
                     var9.add(var19);
                  }
               }

               return var9;
            } else {
               return Collections.emptyList();
            }
         } else {
            return Collections.emptyList();
         }
      } else if (var4.length == 1) {
         return filterByPrefix(List.of("give", "revoke", "check", "extend", "list"), var4[0]);
      } else if (var4.length == 2) {
         return var4[0].equalsIgnoreCase("list") ? filterByPrefix(List.of("plus", "plusplus"), var4[1]) : this.completePlayerNames(var4[1], true);
      } else if (var4.length != 3 || !var4[0].equalsIgnoreCase("give") && !var4[0].equalsIgnoreCase("extend")) {
         return var4.length != 4 || !var4[0].equalsIgnoreCase("give") && !var4[0].equalsIgnoreCase("extend")
            ? Collections.emptyList()
            : filterByPrefix(List.of("30", "90", "1mo", "3mo", "6mo", "12mo"), var4[3]);
      } else {
         return filterByPrefix(List.of("plus", "plusplus", "30", "90", "1mo", "3mo", "6mo", "12mo"), var4[2]);
      }
   }

   private boolean handleGtpCommand(CommandSender var1, String[] var2) {
      if (!(var1 instanceof Player var3)) {
         var1.sendMessage(color("&cOnly players can use /gtp."));
         return true;
      } else if (!var3.hasPermission("pizzasmp.gtp")) {
         var3.sendMessage(color("&cYou do not have permission to use /gtp."));
         return true;
      } else if (!var3.hasPermission("pizzasmp.combat.bypass") && this.isCombatTagged(var3)) {
         var3.sendMessage(color("&cYou are in combat. Wait &e" + this.remainingCombatSeconds(var3) + "s &cbefore using /gtp."));
         return true;
      } else if (var2.length >= 1 && var2.length <= 2) {
         OfflinePlayer var4 = Bukkit.getOfflinePlayer(var2[0]);
         if (var4 != null && (var4.hasPlayedBefore() || var4.isOnline())) {
            File var5 = this.getSetHomeDataFile(var4);
            if (var5 != null && var5.exists()) {
               YamlConfiguration var6 = YamlConfiguration.loadConfiguration(var5);
               ArrayList var7 = new ArrayList(var6.getStringList("homes"));
               if (var7.isEmpty()) {
                  var3.sendMessage(color("&c" + safeName(var4) + " has no homes."));
                  return true;
               } else {
                  String var8;
                  if (var2.length == 1) {
                     var8 = (String)var7.get(0);
                  } else {
                     var8 = this.resolveHomeArg(var2[1], var7, var6);
                     if (var8 == null) {
                        var3.sendMessage(color("&cHome not found for &e" + safeName(var4) + "&c. Use a valid home name or 1-" + var7.size() + "."));
                        return true;
                     }
                  }

                  Location var9 = this.readHomeLocation(var6, var8);
                  if (var9 == null) {
                     var3.sendMessage(color("&cHome data is invalid for &e" + var8 + "&c."));
                     return true;
                  } else {
                     var3.teleport(var9, TeleportCause.COMMAND);
                     var3.sendMessage(color("&fTeleported To &b" + safeName(var4) + "&f home."));
                     return true;
                  }
               }
            } else {
               var3.sendMessage(color("&cNo SetHome data found for &e" + safeName(var4) + "&c."));
               return true;
            }
         } else {
            var3.sendMessage(color("&cPlayer not found: &e" + var2[0]));
            return true;
         }
      } else {
         var3.sendMessage(color("&cUsage: &e/gtp <player> [home-name|home-index]"));
         return true;
      }
   }

   private boolean handleStashCommand(CommandSender var1) {
      if (var1 instanceof Player var2) {
         if (!var2.hasPermission("pizzasmp.admin.stash")) {
            var2.sendMessage(color("&cYou do not have permission to use /stash."));
            return true;
         } else {
            Block var3 = var2.getTargetBlockExact(64);
            if (var3 != null && var3.getType() != Material.AIR) {
               try {
                  World var4 = var3.getWorld();
                  ThreadLocalRandom var5 = ThreadLocalRandom.current();
                  int var6 = var3.getX();
                  int var7 = var3.getY();
                  int var8 = var3.getZ();
                  int var9 = var5.nextInt(100);
                  String var10;
                  if (var9 < 45) {
                     var10 = "tiny";
                     this.buildStashTiny(var4, var6, var7, var8, var5);
                  } else if (var9 < 75) {
                     var10 = "small";
                     this.buildStashSmall(var4, var6, var7, var8, var5);
                  } else if (var9 < 92) {
                     var10 = "medium";
                     this.buildStashMedium(var4, var6, var7, var8, var5);
                  } else {
                     var10 = "full";
                     this.buildStashFull(var4, var6, var7, var8, var5);
                  }

                  this.getLogger().info(var2.getName() + " spawned a " + var10 + " stash at " + var6 + "," + var7 + "," + var8 + " in " + var4.getName());
               } catch (Exception var11) {
                  var2.sendActionBar(color("&cStash build failed."));
                  this.getLogger().warning("Stash build failed for " + var2.getName() + ": " + var11);
               }

               return true;
            } else {
               var2.sendActionBar(color("&cLook at a solid block within 64 blocks."));
               return true;
            }
         }
      } else {
         var1.sendMessage(color("&cOnly players can use /stash."));
         return true;
      }
   }

   private void buildStashTiny(World var1, int var2, int var3, int var4, ThreadLocalRandom var5) {
      Material var6 = STASH_TINY_POOL[var5.nextInt(STASH_TINY_POOL.length)];
      Block var7 = var1.getBlockAt(var2, var3 + 1, var4);
      if (var7.getType() != Material.AIR) {
         var7.setType(Material.AIR, false);
      }

      Block var8 = var1.getBlockAt(var2, var3, var4);
      var8.setType(var6, false);
      if (var6 == Material.CHEST || var6 == Material.BARREL) {
         this.fillStashChest(var8, var5);
      }
   }

   private void buildStashSmall(World var1, int var2, int var3, int var4, ThreadLocalRandom var5) {
      int[][] var6 = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      int[] var7 = var6[var5.nextInt(var6.length)];
      int[][] var8 = new int[][]{{0, 0}, {var7[0], var7[1]}};

      for (int[] var12 : var8) {
         Block var13 = var1.getBlockAt(var2 + var12[0], var3 + 1, var4 + var12[1]);
         if (var13.getType() != Material.AIR) {
            var13.setType(Material.AIR, false);
         }
      }

      for (int[] var18 : var8) {
         Material var19 = STASH_TINY_POOL[var5.nextInt(STASH_TINY_POOL.length)];
         Block var14 = var1.getBlockAt(var2 + var18[0], var3, var4 + var18[1]);
         var14.setType(var19, false);
         if (var19 == Material.CHEST || var19 == Material.BARREL) {
            this.fillStashChest(var14, var5);
         }
      }
   }

   private void buildStashMedium(World var1, int var2, int var3, int var4, ThreadLocalRandom var5) {
      int var6 = var5.nextBoolean() ? 1 : -1;
      int var7 = var5.nextBoolean() ? 1 : -1;
      int[][] var8 = new int[][]{{0, 0}, {var6, 0}, {0, var7}, {var6, var7}};

      for (int[] var12 : var8) {
         for (int var13 = 1; var13 <= 2; var13++) {
            Block var14 = var1.getBlockAt(var2 + var12[0], var3 + var13, var4 + var12[1]);
            if (var14.getType() != Material.AIR) {
               var14.setType(Material.AIR, false);
            }
         }
      }

      ArrayList var15 = new ArrayList<>(List.of(var8));
      Collections.shuffle(var15, new Random(var5.nextLong()));
      byte var16 = 3;

      for (int var17 = 0; var17 < var16; var17++) {
         int[] var18 = (int[])var15.get(var17);
         Material var19 = STASH_TINY_POOL[var5.nextInt(STASH_TINY_POOL.length)];
         Block var20 = var1.getBlockAt(var2 + var18[0], var3, var4 + var18[1]);
         var20.setType(var19, false);
         if (var19 == Material.CHEST || var19 == Material.BARREL) {
            this.fillStashChest(var20, var5);
         }
      }
   }

   private void buildStashFull(World var1, int var2, int var3, int var4, ThreadLocalRandom var5) {
      for (int var6 = -1; var6 <= 1; var6++) {
         for (int var7 = -1; var7 <= 1; var7++) {
            var1.getBlockAt(var2 + var6, var3, var4 + var7).setType(Material.OAK_PLANKS, false);

            for (int var8 = 1; var8 <= 3; var8++) {
               Block var9 = var1.getBlockAt(var2 + var6, var3 + var8, var4 + var7);
               if (var9.getType() != Material.AIR) {
                  var9.setType(Material.AIR, false);
               }
            }
         }
      }

      int var12 = var3 + 1;
      Block var13 = var1.getBlockAt(var2 - 1, var12, var4 - 1);
      var13.setType(Material.CHEST, false);
      this.fillStashChest(var13, var5);
      var1.getBlockAt(var2 + 1, var12, var4 - 1).setType(Material.FURNACE, false);
      var1.getBlockAt(var2, var12, var4 - 1).setType(Material.CRAFTING_TABLE, false);
      var1.getBlockAt(var2 + 1, var12, var4 + 1).setType(Material.TORCH, false);
      Block var14 = var1.getBlockAt(var2 - 1, var12, var4 + 1);
      Block var15 = var1.getBlockAt(var2, var12, var4 + 1);
      var14.setType(Material.RED_BED, false);
      var15.setType(Material.RED_BED, false);
      Bed var10 = (Bed)var14.getBlockData();
      var10.setPart(Part.FOOT);
      var10.setFacing(BlockFace.EAST);
      var14.setBlockData(var10, false);
      Bed var11 = (Bed)var15.getBlockData();
      var11.setPart(Part.HEAD);
      var11.setFacing(BlockFace.EAST);
      var15.setBlockData(var11, false);
   }

   private void initPizzaPlusSubscriptions() {
      this.pizzaPlusSubsFile = new File(this.getDataFolder(), "pizzaplus-subs.yml");
      if (this.pizzaPlusSubsFile.getParentFile() != null && !this.pizzaPlusSubsFile.getParentFile().exists()) {
         this.pizzaPlusSubsFile.getParentFile().mkdirs();
      }

      if (!this.pizzaPlusSubsFile.exists()) {
         try {
            this.pizzaPlusSubsFile.createNewFile();
         } catch (Exception var2) {
         }
      }

      this.pizzaPlusSubsConfig = YamlConfiguration.loadConfiguration(this.pizzaPlusSubsFile);
   }

   private void savePizzaPlusSubscriptions() {
      if (this.pizzaPlusSubsConfig != null && this.pizzaPlusSubsFile != null) {
         try {
            this.pizzaPlusSubsConfig.save(this.pizzaPlusSubsFile);
         } catch (Exception var2) {
            this.getLogger().warning("Failed saving pizzaplus-subs.yml: " + var2.getMessage());
         }
      }
   }

   private long getPizzaPlusExpiry(UUID var1) {
      return this.pizzaPlusSubsConfig == null ? 0L : this.pizzaPlusSubsConfig.getLong("expiry." + var1.toString(), 0L);
   }

   private void setPizzaPlusExpiry(UUID var1, String var2, long var3) {
      if (this.pizzaPlusSubsConfig != null) {
         if (var3 <= 0L) {
            this.pizzaPlusSubsConfig.set("expiry." + var1.toString(), null);
            this.pizzaPlusSubsConfig.set("names." + var1.toString(), null);
            this.pizzaPlusSubsConfig.set("tier." + var1.toString(), null);
         } else {
            this.pizzaPlusSubsConfig.set("expiry." + var1.toString(), var3);
            if (var2 != null) {
               this.pizzaPlusSubsConfig.set("names." + var1.toString(), var2);
            }
         }
      }
   }

   private String getPizzaPlusTier(UUID var1) {
      return this.pizzaPlusSubsConfig == null ? "plus" : this.pizzaPlusSubsConfig.getString("tier." + var1.toString(), "plus");
   }

   private void setPizzaPlusTier(UUID var1, String var2) {
      if (this.pizzaPlusSubsConfig != null) {
         this.pizzaPlusSubsConfig.set("tier." + var1.toString(), var2);
      }
   }

   private static String tierDisplay(String var0) {
      return "plusplus".equals(var0) ? "Pizza++" : "Pizza+";
   }

   private static String tierGroup(String var0) {
      return "plusplus".equals(var0) ? "pizza++" : "pizza+";
   }

   private static String normalizeTierArg(String var0) {
      if (var0 == null) {
         return null;
      } else {
         String var1 = var0.toLowerCase(Locale.ROOT);
         switch (var1) {
            case "plus":
            case "+":
            case "pizza+":
            case "pizzaplus":
               return "plus";
            case "plusplus":
            case "++":
            case "pizza++":
            case "pizzaplusplus":
               return "plusplus";
            default:
               return null;
         }
      }
   }

   private static long parseDaysArg(String var0) {
      if (var0 == null) {
         return -1L;
      } else {
         String var1 = var0.toLowerCase(Locale.ROOT).trim();

         try {
            if (var1.endsWith("mo")) {
               return Math.max(1L, Long.parseLong(var1.substring(0, var1.length() - 2))) * 30L;
            } else {
               return var1.endsWith("d") ? Math.max(1L, Long.parseLong(var1.substring(0, var1.length() - 1))) : Math.max(1L, Long.parseLong(var1));
            }
         } catch (NumberFormatException var3) {
            return -1L;
         }
      }
   }

   private void runPizzaPlusExpirySweep() {
      if (this.pizzaPlusSubsConfig != null) {
         ConfigurationSection var1 = this.pizzaPlusSubsConfig.getConfigurationSection("expiry");
         if (var1 != null) {
            long var2 = System.currentTimeMillis();
            ArrayList var4 = new ArrayList();

            for (String var6 : var1.getKeys(false)) {
               if (var1.getLong(var6, 0L) <= var2) {
                  var4.add(var6);
               }
            }

            if (!var4.isEmpty()) {
               for (String var15 : (Iterable<String>) var4) {
                  String var7 = this.pizzaPlusSubsConfig.getString("names." + var15, null);

                  UUID var8;
                  try {
                     var8 = UUID.fromString(var15);
                  } catch (Exception var13) {
                     continue;
                  }

                  OfflinePlayer var9 = Bukkit.getOfflinePlayer(var8);
                  String var10 = var9.getName() != null ? var9.getName() : var7;
                  if (var10 == null) {
                     this.setPizzaPlusExpiry(var8, null, 0L);
                  } else {
                     String var11 = this.getPizzaPlusTier(var8);
                     Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + var10 + " parent remove pizza+");
                     Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + var10 + " parent remove pizza++");
                     this.setPizzaPlusExpiry(var8, null, 0L);
                     this.getLogger().info(tierDisplay(var11) + " expired and revoked for " + var10);
                     Player var12 = Bukkit.getPlayer(var8);
                     if (var12 != null && var12.isOnline()) {
                        var12.sendMessage(
                           Component.text("Your " + tierDisplay(var11) + " subscription has expired. Use /perks to learn how to renew.", NamedTextColor.GRAY)
                        );
                     }
                  }
               }

               this.savePizzaPlusSubscriptions();
            }
         }
      }
   }

   private static String formatRemainingMs(long var0) {
      if (var0 <= 0L) {
         return "expired";
      } else {
         long var2 = var0 / 86400000L;
         long var4 = var0 % 86400000L / 3600000L;
         if (var2 > 0L) {
            return var2 + "d " + var4 + "h";
         } else {
            long var6 = var0 % 3600000L / 60000L;
            return var4 + "h " + var6 + "m";
         }
      }
   }

   private boolean handlePizzaPlusCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3 && !var3.hasPermission("pizzasmp.admin.pizzaplus")) {
         var3.sendMessage(color("&cYou do not have permission to manage Pizza+."));
         return true;
      }

      String var28 = "&cUsage: &e/pizzaplus <give|revoke|check|extend> <player> [plus|plusplus] [days|Nmo] &7| &e/pizzaplus list [plus|plusplus]";
      if (var2.length < 1) {
         this.handlePizzaPlusList(var1, null);
         return true;
      } else {
         String var4 = var2[0].toLowerCase(Locale.ROOT);
         if ("list".equals(var4)) {
            String var29 = var2.length >= 2 ? normalizeTierArg(var2[1]) : null;
            this.handlePizzaPlusList(var1, var29);
            return true;
         } else if (Set.of("give", "revoke", "check", "extend").contains(var4) && var2.length >= 2) {
            String var5 = var2[1];
            OfflinePlayer var6 = Bukkit.getOfflinePlayer(var5);
            if (var6 != null && (var6.hasPlayedBefore() || Bukkit.getPlayerExact(var5) != null)) {
               UUID var7 = var6.getUniqueId();
               String var8 = var6.getName() != null ? var6.getName() : var5;
               if ("check".equals(var4)) {
                  long var31 = this.getPizzaPlusExpiry(var7);
                  long var33 = var31 - System.currentTimeMillis();
                  String var34 = this.getPizzaPlusTier(var7);
                  Player var35 = Bukkit.getPlayerExact(var8);
                  boolean var15 = var35 != null && (var35.hasPermission("group.pizza+") || var35.hasPermission("group.pizza++"));
                  if (var31 > 0L && var33 > 0L) {
                     var1.sendMessage(color("&f" + var8 + " &7" + tierDisplay(var34) + ": &aACTIVE &7(expires in &f" + formatRemainingMs(var33) + "&7)"));
                  } else if (var15) {
                     var1.sendMessage(color("&f" + var8 + " &7Pizza+: &aACTIVE &7(no expiry tracked — legacy grant)"));
                  } else {
                     var1.sendMessage(color("&f" + var8 + " &7Pizza+/Pizza++: &cnone"));
                  }

                  return true;
               } else if ("revoke".equals(var4)) {
                  String var30 = this.getPizzaPlusTier(var7);
                  Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + var8 + " parent remove pizza+");
                  Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + var8 + " parent remove pizza++");
                  this.setPizzaPlusExpiry(var7, null, 0L);
                  this.savePizzaPlusSubscriptions();
                  var1.sendMessage(color("&7Revoked " + tierDisplay(var30) + " from &f" + var8 + "&7."));
                  Player var32 = Bukkit.getPlayerExact(var8);
                  if (var32 != null) {
                     var32.sendMessage(Component.text("Your " + tierDisplay(var30) + " status has been removed.", NamedTextColor.GRAY));
                  }

                  return true;
               } else {
                  String var9 = null;
                  byte var10 = 2;
                  if (var2.length >= 3) {
                     var9 = normalizeTierArg(var2[2]);
                     if (var9 != null) {
                        var10 = 3;
                     }
                  }

                  long var11 = 30L;
                  if (var2.length > var10) {
                     var11 = parseDaysArg(var2[var10]);
                     if (var11 <= 0L) {
                        var1.sendMessage(color("&cInvalid duration: &e" + var2[var10] + " &7(use days, e.g. 90, or months, e.g. 3mo)"));
                        return true;
                     }
                  }

                  String var13 = this.getPizzaPlusExpiry(var7) > System.currentTimeMillis() ? this.getPizzaPlusTier(var7) : null;
                  if (var9 == null) {
                     var9 = var13 != null ? var13 : "plus";
                  }

                  long var14 = System.currentTimeMillis();
                  long var16 = this.getPizzaPlusExpiry(var7);
                  long var18 = "extend".equals(var4) && var16 > var14 ? var16 : var14;
                  long var20 = var18 + var11 * 86400000L;
                  if (var13 != null && !var13.equals(var9)) {
                     Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + var8 + " parent remove " + tierGroup(var13));
                  }

                  boolean var22 = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + var8 + " parent add " + tierGroup(var9));
                  if (!var22) {
                     var1.sendMessage(color("&cLuckPerms command failed."));
                     return true;
                  } else {
                     this.setPizzaPlusExpiry(var7, var8, var20);
                     this.setPizzaPlusTier(var7, var9);
                     this.savePizzaPlusSubscriptions();
                     long var23 = Math.max(1L, var11 / 30L);
                     long var25 = var23 * ("plusplus".equals(var9) ? 1000L : 500L);
                     Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "shards give " + var8 + " " + var25);
                     var1.sendMessage(
                        color(
                           "&a"
                              + ("give".equals(var4) ? "Granted" : "Extended")
                              + " "
                              + tierDisplay(var9)
                              + " for &f"
                              + var8
                              + " &7("
                              + var11
                              + "d, expires in "
                              + formatRemainingMs(var20 - var14)
                              + ", stipend "
                              + var25
                              + " shards)"
                        )
                     );
                     Player var27 = Bukkit.getPlayerExact(var8);
                     if (var27 != null && "give".equals(var4)) {
                        this.sendPizzaPlusWelcome(var27, var9);
                     } else if (var27 != null) {
                        var27.sendMessage(
                           Component.text("Your " + tierDisplay(var9) + " subscription was extended (" + var11 + " more days).", TextColor.color(49151))
                        );
                     }

                     return true;
                  }
               }
            } else {
               var1.sendMessage(color("&cPlayer not found: &e" + var5));
               return true;
            }
         } else {
            var1.sendMessage(color(var28));
            return true;
         }
      }
   }

   private void handlePizzaPlusList(CommandSender var1, String var2) {
      long var3 = System.currentTimeMillis();
      ArrayList var5 = new ArrayList();
      ConfigurationSection var6 = this.pizzaPlusSubsConfig != null ? this.pizzaPlusSubsConfig.getConfigurationSection("expiry") : null;
      if (var6 != null) {
         Iterator var7 = var6.getKeys(false).iterator();

         label105:
         while (true) {
            UUID var9;
            while (true) {
               if (!var7.hasNext()) {
                  break label105;
               }

               String var8 = (String)var7.next();
               if (var6.getLong(var8, 0L) > var3) {
                  try {
                     var9 = UUID.fromString(var8);
                     break;
                  } catch (Exception var17) {
                  }
               }
            }

            if (var2 == null || var2.equals(this.getPizzaPlusTier(var9))) {
               var5.add(var9);
            }
         }
      }

      var5.sort(Comparator.comparingLong(this::getPizzaPlusExpiry));
      if (var1 instanceof Player var18) {
         if (this.useDialogUi(var18)) {
            this.openPizzaPlusListDialog(var18, var2, var5);
         } else {
            Inventory var20 = Bukkit.createInventory(null, 54, color("&6Pizza+ Subscribers" + (var2 != null ? " &7(" + tierDisplay(var2) + ")" : "")));
            int var22 = 0;

            for (UUID var26 : (Iterable<UUID>) var5) {
               if (var22 >= 53) {
                  break;
               }

               String var12 = this.pizzaPlusSubsConfig.getString("names." + var26, var26.toString());
               String var13 = this.getPizzaPlusTier(var26);
               boolean var14 = "plusplus".equals(var13);
               ItemStack var15 = new ItemStack(Material.PLAYER_HEAD);
               SkullMeta var16 = (SkullMeta)var15.getItemMeta();
               if (var16 != null) {
                  var16.setOwningPlayer(Bukkit.getOfflinePlayer(var26));
                  var16.setDisplayName(color((var14 ? "&6" : "&f") + var12 + " " + (var14 ? "&6&l++" : "&e&l+")));
                  var16.setLore(
                     List.of(
                        color("&7Tier: " + (var14 ? "&6Pizza++" : "&ePizza+")),
                        color("&7Expires in: &f" + formatRemainingMs(this.getPizzaPlusExpiry(var26) - var3)),
                        color("&8" + var26)
                     )
                  );
                  var15.setItemMeta(var16);
               }

               var20.setItem(var22++, var15);
            }

            if (var5.isEmpty()) {
               ItemStack var24 = new ItemStack(Material.GRAY_DYE);
               ItemMeta var27 = var24.getItemMeta();
               if (var27 != null) {
                  var27.setDisplayName(color("&7No active subscribers"));
                  var24.setItemMeta(var27);
               }

               var20.setItem(22, var24);
            }

            ItemStack var25 = new ItemStack(Material.BARRIER);
            ItemMeta var28 = var25.getItemMeta();
            if (var28 != null) {
               var28.setDisplayName(color("&cClose"));
               var25.setItemMeta(var28);
            }

            var20.setItem(53, var25);
            var18.openInventory(var20);
         }
      } else {
         var1.sendMessage(color("&6Pizza+ subscribers (" + var5.size() + "):"));

         for (UUID var21 : (Iterable<UUID>) var5) {
            String var10 = this.pizzaPlusSubsConfig.getString("names." + var21, var21.toString());
            String var11 = this.getPizzaPlusTier(var21);
            var1.sendMessage(color(" &f" + var10 + " &7— " + tierDisplay(var11) + ", expires in &f" + formatRemainingMs(this.getPizzaPlusExpiry(var21) - var3)));
         }
      }
   }

   private void revokePizzaPlusUuid(CommandSender var1, UUID var2, String var3) {
      Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + var3 + " parent remove pizza+");
      Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + var3 + " parent remove pizza++");
      if (this.pizzaPlusSubsConfig != null) {
         this.pizzaPlusSubsConfig.set("expiry." + var2, null);
         this.pizzaPlusSubsConfig.set("tier." + var2, null);
         this.pizzaPlusSubsConfig.set("names." + var2, null);
      }

      this.savePizzaPlusSubscriptions();
      var1.sendMessage(color("&7Revoked Pizza+/++ from &f" + var3 + "&7."));
   }

   private void openPizzaPlusListDialog(Player var1, String var2, List<UUID> var3) {
      long var4 = System.currentTimeMillis();
      List var6 = List.of(
         DialogBody.plainMessage(
            Component.text(
               var3.size()
                  + " active subscriber"
                  + (var3.size() == 1 ? "" : "s")
                  + (var2 != null ? " (" + tierDisplay(var2).replaceAll("&[0-9a-fk-or]", "") + ")" : ""),
               NamedTextColor.GRAY
            )
         )
      );
      ArrayList var7 = new ArrayList();
      if (var2 == null) {
         String var10000 = "ALL";
      } else {
         String var16 = var2.equals("plus") ? "PLUS" : "PLUSPLUS";
      }

      var7.add(
         this.dialogButton(
            Component.text("Filter: " + (var2 == null ? "All" : tierDisplay(var2).replaceAll("&[0-9a-fk-or]", "")), DIALOG_BRAND),
            "Click to change",
            150,
            var2x -> {
               String var3x = var2 == null ? "plus" : (var2.equals("plus") ? "plusplus" : null);
               this.handlePizzaPlusList(var2x, var3x);
            }
         )
      );
      var7.add(this.dialogButton(Component.text("Give Subscription", NamedTextColor.GREEN), "Grant Pizza+ / Pizza++", 150, this::openPizzaPlusGiveDialog));

      for (UUID var10 : var3) {
         String var11 = this.pizzaPlusSubsConfig.getString("names." + var10, var10.toString());
         String var12 = this.getPizzaPlusTier(var10);
         boolean var13 = "plusplus".equals(var12);
         String var14 = formatRemainingMs(this.getPizzaPlusExpiry(var10) - var4);
         var7.add(
            this.dialogButton(
               Component.text(var11 + (var13 ? " ++" : " +"), var13 ? NamedTextColor.GOLD : NamedTextColor.YELLOW),
               "Expires in " + var14,
               200,
               var3x -> this.openPizzaPlusDetailDialog(var3x, var10, var11)
            )
         );
      }

      if (var3.isEmpty()) {
         var7.add(this.dialogButton(Component.text("(no subscribers)", NamedTextColor.DARK_GRAY), null, 200, null));
      }

      Dialog var15 = this.buildDialog(
         Component.text("Pizza+ Management", DIALOG_BRAND),
         var6,
         List.of(),
         DialogType.multiAction(var7).columns(1).exitAction(this.dialogButton(Component.text("Close"), null, 150, null)).build()
      );
      var1.showDialog(var15);
   }

   private void openPizzaPlusDetailDialog(Player var1, UUID var2, String var3) {
      long var4 = System.currentTimeMillis();
      String var6 = this.getPizzaPlusTier(var2);
      List var7 = List.of(
         DialogBody.plainMessage(Component.text("Tier: " + tierDisplay(var6).replaceAll("&[0-9a-fk-or]", ""), NamedTextColor.GRAY)),
         DialogBody.plainMessage(Component.text("Expires in: " + formatRemainingMs(this.getPizzaPlusExpiry(var2) - var4), NamedTextColor.GRAY))
      );
      ArrayList var8 = new ArrayList();
      var8.add(this.dialogButton(Component.text("Extend 30 days", NamedTextColor.GREEN), null, 200, var3x -> {
         var3x.performCommand("pizzaplus extend " + var3 + " 30");
         this.openPizzaPlusDetailDialog(var3x, var2, var3);
      }));
      var8.add(this.dialogButton(Component.text("Revoke", NamedTextColor.RED), null, 200, var3x -> {
         this.revokePizzaPlusUuid(var3x, var2, var3);
         this.handlePizzaPlusList(var3x, null);
      }));
      Dialog var9 = this.buildDialog(
         Component.text(var3, DIALOG_BRAND),
         var7,
         List.of(),
         DialogType.multiAction(var8)
            .columns(1)
            .exitAction(this.dialogButton(Component.text("Back"), null, 200, var1x -> this.handlePizzaPlusList(var1x, null)))
            .build()
      );
      var1.showDialog(var9);
   }

   private void openPizzaPlusGiveDialog(Player var1) {
      List var2 = List.of(DialogInput.text("name", Component.text("Player name", NamedTextColor.GRAY)).width(220).maxLength(16).build());
      ActionButton var3 = this.pizzaPlusGiveButton("Give Pizza+", NamedTextColor.YELLOW, "plus");
      ActionButton var4 = this.pizzaPlusGiveButton("Give Pizza++", NamedTextColor.GOLD, "plusplus");
      ActionButton var5 = this.dialogButton(Component.text("Back"), null, 150, var1x -> this.handlePizzaPlusList(var1x, null));
      Dialog var6 = this.buildDialog(
         Component.text("Give Subscription", DIALOG_BRAND),
         List.of(DialogBody.plainMessage(Component.text("Grants a 30-day subscription.", NamedTextColor.GRAY))),
         var2,
         DialogType.multiAction(List.of(var3, var4)).columns(2).exitAction(var5).build()
      );
      var1.showDialog(var6);
   }

   private ActionButton pizzaPlusGiveButton(String var1, NamedTextColor var2, String var3) {
      return ActionButton.builder(Component.text(var1, var2)).width(150).action(DialogAction.customClick((var2x, var3x) -> {
         if (var3x instanceof Player var4) {
            String var5 = var2x.getText("name");
            Bukkit.getScheduler().runTask(this, () -> {
               if (var5 != null && !var5.isBlank()) {
                  var4.performCommand("pizzaplus give " + var5.trim() + " " + var3);
                  this.handlePizzaPlusList(var4, null);
               } else {
                  var4.sendActionBar(Component.text("§cEnter a player name."));
               }
            });
         }
      }, (Options)Options.builder().build())).build();
   }

   @EventHandler
   public void onPizzaPlusListClick(InventoryClickEvent var1) {
      if (var1.getWhoClicked() instanceof Player var2) {
         String var4 = var1.getView().getTitle();
         if (var4.startsWith(color("&6Pizza+ Subscribers"))) {
            var1.setCancelled(true);
            if (var1.getRawSlot() == 53) {
               var2.closeInventory();
            }
         }
      }
   }

   private void sendPizzaPlusWelcome(Player var1, String var2) {
      TextColor var3 = TextColor.color(49151);
      NamedTextColor var4 = NamedTextColor.GOLD;
      NamedTextColor var5 = NamedTextColor.YELLOW;
      NamedTextColor var6 = NamedTextColor.GRAY;
      String var7 = tierDisplay(var2);
      var1.sendMessage(Component.empty());
      var1.sendMessage(
         ((TextComponent)Component.text("✨ Welcome to ", var5).append(Component.text(var7, var4).decoration(TextDecoration.BOLD, true)))
            .append(Component.text("! ✨", var5))
      );
      boolean var8 = var2 != null && var2.toLowerCase(Locale.ROOT).contains("plusplus");
      String var9 = var8 ? "27 homes, 45 market slots, 2x shards & 1000/mo" : "9 homes, 27 market slots, faster cooldowns & 500/mo";
      var1.sendMessage(Component.text(var9, NamedTextColor.WHITE));
      var1.sendMessage(Component.text("To see everything, run ", var6).append(Component.text("/perks", var3).decoration(TextDecoration.BOLD, true)));
      var1.sendMessage(Component.empty());

      try {
         var1.playSound(var1.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6F, 1.2F);
      } catch (Throwable var11) {
      }
   }

   private boolean handleAtrackCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3) {
         if (!var3.hasPermission("pizzasmp.admin.track")) {
            var3.sendMessage(color("&cNo permission."));
            return true;
         } else if (var2.length == 0) {
            this.stopTracking(var3);
            return true;
         } else {
            Player var4 = this.getServer().getPlayer(var2[0]);
            if (var4 != null && var4.isOnline()) {
               if (var4.getUniqueId().equals(var3.getUniqueId())) {
                  var3.sendMessage(color("&cYou can't track yourself."));
                  return true;
               } else {
                  if (this.atrackTargets.containsKey(var3.getUniqueId())) {
                     this.stopTrackingTask(var3.getUniqueId());
                  }

                  if (var3.getGameMode() != GameMode.SPECTATOR) {
                     this.atrackPriorMode.put(var3.getUniqueId(), var3.getGameMode());
                  }

                  var3.setGameMode(GameMode.SPECTATOR);
                  var3.teleport(var4.getLocation());
                  this.atrackTargets.put(var3.getUniqueId(), var4.getUniqueId());
                  int var5 = this.getServer().getScheduler().runTaskTimer(this, () -> {
                     if (!var3.isOnline()) {
                        this.stopTrackingTask(var3.getUniqueId());
                     } else {
                        UUID var2x = this.atrackTargets.get(var3.getUniqueId());
                        if (var2x == null) {
                           this.stopTrackingTask(var3.getUniqueId());
                        } else {
                           Player var3x = this.getServer().getPlayer(var2x);
                           if (var3x != null && var3x.isOnline()) {
                              var3.setSpectatorTarget(var3x);
                           } else {
                              var3.sendActionBar(Component.text("§cTarget disconnected — tracking stopped"));
                              this.stopTracking(var3);
                           }
                        }
                     }
                  }, 1L, 1L).getTaskId();
                  this.atrackTasks.put(var3.getUniqueId(), var5);
                  var3.sendActionBar(Component.text("§dNow tracking §f" + var4.getName() + " §d— /atrack to stop"));
                  return true;
               }
            } else {
               var3.sendMessage(color("&cPlayer not found or offline."));
               return true;
            }
         }
      } else {
         var1.sendMessage(color("&cOnly players can use /atrack."));
         return true;
      }
   }

   private void stopTracking(Player var1) {
      UUID var2 = var1.getUniqueId();
      if (!this.atrackTargets.containsKey(var2)) {
         var1.sendMessage(color("&7Not tracking anyone."));
      } else {
         this.stopTrackingTask(var2);
         var1.setSpectatorTarget(null);
         GameMode var3 = this.atrackPriorMode.remove(var2);
         if (var3 != null && var3 != GameMode.SPECTATOR) {
            var1.setGameMode(var3);
         }

         var1.sendActionBar(Component.text("§7Tracking stopped"));
      }
   }

   private void stopTrackingTask(UUID var1) {
      this.atrackTargets.remove(var1);
      Integer var2 = this.atrackTasks.remove(var1);
      if (var2 != null) {
         this.getServer().getScheduler().cancelTask(var2);
      }
   }

   private boolean handlePerksCommand(CommandSender var1) {
      if (var1 instanceof Player var2) {
         if (!var2.hasPermission("pizzasmp.perks")) {
            var2.sendMessage(color("&cYou do not have permission to use /perks."));
            return true;
         } else {
            TextColor var3 = TextColor.color(49151);
            NamedTextColor var4 = NamedTextColor.GOLD;
            NamedTextColor var5 = NamedTextColor.GRAY;
            NamedTextColor var6 = NamedTextColor.WHITE;
            boolean var7 = var2.hasPermission("group.pizza++");
            boolean var8 = var7 || var2.hasPermission("group.pizza+");
            var2.sendMessage(Component.empty());
            var2.sendMessage(
               ((TextComponent)Component.text("✨ ", var4).append(Component.text("Pizza+ & Pizza++ Perks", var4).decoration(TextDecoration.BOLD, true)))
                  .append(Component.text(" ✨", var4))
            );
            var2.sendMessage(
               ((TextComponent)Component.text("Pizza+", var4).decoration(TextDecoration.BOLD, true)).append(Component.text("  (500 shards/mo)", var5))
            );
            String[] var9 = new String[]{
               "9 homes (default 3)",
               "27 auction & order slots (default 18)",
               "Faster RTP & TPA cooldowns (15s)",
               "Priority queue during maintenance",
               "Pizza+ tag in chat & tab",
               "500 bonus shards every month"
            };

            for (String var13 : var9) {
               var2.sendMessage(Component.text(" • ", var3).append(Component.text(var13, var6)));
            }

            var2.sendMessage(
               ((TextComponent)Component.text("Pizza++", var4).decoration(TextDecoration.BOLD, true))
                  .append(Component.text("  (everything above, plus...)", var5))
            );
            String[] var15 = new String[]{
               "27 homes",
               "45 auction & order slots (the max)",
               "Fastest RTP & TPA cooldowns (8s)",
               "2x shards (passive earn & PvP kills)",
               "1000 bonus shards every month",
               "Gold ++ tag in chat & tab",
               "Front of the priority queue"
            };

            for (String var14 : var15) {
               var2.sendMessage(Component.text(" • ", var4).append(Component.text(var14, var6)));
            }

            var2.sendMessage(Component.empty());
            if (var7) {
               var2.sendMessage(
                  ((TextComponent)Component.text("You currently have ", var5).append(Component.text("Pizza++", var4).decoration(TextDecoration.BOLD, true)))
                     .append(Component.text(" — enjoy!", var5))
               );
            } else if (var8) {
               var2.sendMessage(
                  ((TextComponent)((TextComponent)((TextComponent)Component.text("You currently have ", var5)
                              .append(Component.text("Pizza+", var4).decoration(TextDecoration.BOLD, true)))
                           .append(Component.text(" — upgrade to ", var5)))
                        .append(Component.text("Pizza++", var4).decoration(TextDecoration.BOLD, true)))
                     .append(Component.text(" for even more!", var5))
               );
            } else {
               var2.sendMessage(
                  ((TextComponent)((TextComponent)((TextComponent)Component.text("Upgrade to ", var5)
                              .append(Component.text("Pizza+", var4).decoration(TextDecoration.BOLD, true)))
                           .append(Component.text(" or ", var5)))
                        .append(Component.text("Pizza++", var4).decoration(TextDecoration.BOLD, true)))
                     .append(Component.text(" to unlock these perks.", var5))
               );
            }

            var2.sendMessage(Component.empty());
            return true;
         }
      } else {
         var1.sendMessage(color("&cOnly players can use /perks."));
         return true;
      }
   }

   private void fillStashChest(Block var1, ThreadLocalRandom var2) {
      if (var1.getState() instanceof Chest var3) {
         Material[] var11 = new Material[]{
            Material.BREAD,
            Material.COOKED_BEEF,
            Material.COOKED_CHICKEN,
            Material.GOLDEN_APPLE,
            Material.TORCH,
            Material.OAK_PLANKS,
            Material.OAK_LOG,
            Material.COBBLESTONE,
            Material.IRON_INGOT,
            Material.GOLD_INGOT,
            Material.COAL,
            Material.IRON_PICKAXE,
            Material.IRON_SWORD,
            Material.IRON_AXE,
            Material.ARROW,
            Material.ENDER_PEARL,
            Material.WHEAT,
            Material.CARROT,
            Material.POTATO,
            Material.STRING,
            Material.LEATHER_CHESTPLATE,
            Material.IRON_HELMET,
            Material.WATER_BUCKET,
            Material.OAK_SAPLING,
            Material.APPLE
         };
         int var5 = 4 + var2.nextInt(6);
         Inventory var6 = var3.getBlockInventory();

         for (int var7 = 0; var7 < var5; var7++) {
            Material var8 = var11[var2.nextInt(var11.length)];
            int var9 = var8.getMaxStackSize();
            int var10 = var9 <= 1 ? 1 : 1 + var2.nextInt(Math.min(var9, 16));
            var6.setItem(var2.nextInt(var6.getSize()), new ItemStack(var8, var10));
         }
      }
   }

   private boolean handleHomesCommand(CommandSender var1) {
      if (var1 instanceof Player var2) {
         if (var2.hasPermission("pizzasmp.homes") && var2.hasPermission("sethome.use")) {
            this.setTargetToSelf(var2);
            this.openHomesMenu(var2, var2);
            return true;
         } else {
            var2.sendMessage(color("&cYou do not have permission to use /homes."));
            return true;
         }
      } else {
         var1.sendMessage(color("&cOnly players can use /homes."));
         return true;
      }
   }

   private boolean handleMenuCommand(CommandSender var1) {
      if (var1 instanceof Player var2) {
         if (!var2.hasPermission("pizzasmp.menu")) {
            var2.sendMessage(color("&cYou do not have permission to use /menu."));
            return true;
         } else {
            if (this.useDialogUi(var2)) {
               Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialog show " + var2.getName() + " pizzasmp:menu");
            } else {
               Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dm open pizzasmp_menu " + var2.getName());
            }

            return true;
         }
      } else {
         var1.sendMessage(color("&cOnly players can use /menu."));
         return true;
      }
   }

   private boolean handleGuideCommand(CommandSender var1) {
      if (var1 instanceof Player var2) {
         if (!var2.hasPermission("pizzasmp.guide")) {
            var2.sendMessage(color("&cYou do not have permission to use /guide."));
            return true;
         } else {
            if (this.useDialogUi(var2)) {
               this.openGuideDialog(var2);
            } else {
               Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dm open pizzasmp_guide_book " + var2.getName());
            }

            return true;
         }
      } else {
         var1.sendMessage(color("&cOnly players can use /guide."));
         return true;
      }
   }

   private Component guideLine(String var1, String var2) {
      return Component.text(var1, NamedTextColor.WHITE).append(Component.text("  " + var2, NamedTextColor.GRAY));
   }

   private void openGuideDialog(Player var1) {
      List var2 = List.of(DialogBody.plainMessage(Component.text("Welcome to " + BRAND_DISPLAY + ". Pick a section below.", NamedTextColor.GRAY)));
      List var3 = List.of(
         this.dialogButton(Component.text("Commands", DIALOG_BRAND), null, 150, var1x -> this.openGuideSection(var1x, "commands")),
         this.dialogButton(Component.text("Economy & Shop", DIALOG_BRAND), null, 150, var1x -> this.openGuideSection(var1x, "economy")),
         this.dialogButton(Component.text("Social", DIALOG_BRAND), null, 150, var1x -> this.openGuideSection(var1x, "social")),
         this.dialogButton(Component.text("Rules", DIALOG_BRAND), null, 150, var1x -> this.openGuideSection(var1x, "rules")),
         this.dialogButton(Component.text("Discord", NamedTextColor.BLUE), null, 150, var0 -> {
            var0.closeDialog();
            var0.performCommand("discord");
         }),
         this.dialogButton(Component.text("Open Menu", NamedTextColor.WHITE), null, 150, var0 -> {
            var0.closeDialog();
            var0.performCommand("menu");
         })
      );
      Dialog var4 = this.buildDialog(
         Component.text(BRAND_DISPLAY + " Guide", DIALOG_BRAND),
         var2,
         List.of(),
         DialogType.multiAction(var3).columns(2).exitAction(this.dialogButton(Component.text("Close"), null, 150, var0 -> var0.closeDialog())).build()
      );
      var1.showDialog(var4);
   }

   private void openGuideSection(Player var1, String var2) {
      ArrayList var4 = new ArrayList();
      String var3;
      switch (var2) {
         case "commands":
            var3 = "Commands";
            var4.add(DialogBody.plainMessage(this.guideLine("/menu", "open the quick menu")));
            var4.add(DialogBody.plainMessage(this.guideLine("/home, /homes", "your homes")));
            var4.add(DialogBody.plainMessage(this.guideLine("/sethome [name]", "set a home")));
            var4.add(DialogBody.plainMessage(this.guideLine("/rtp", "random teleport")));
            var4.add(DialogBody.plainMessage(this.guideLine("/tpa <player>", "request to teleport")));
            var4.add(DialogBody.plainMessage(this.guideLine("/ping", "show your latency")));
            var4.add(DialogBody.plainMessage(this.guideLine("/linkaccount", "link Java/Bedrock")));
            break;
         case "economy":
            var3 = "Economy & Shop";
            var4.add(DialogBody.plainMessage(this.guideLine("/shop", "buy server items")));
            var4.add(DialogBody.plainMessage(this.guideLine("/ah", "auction house")));
            var4.add(DialogBody.plainMessage(this.guideLine("/orders", "buy orders from players")));
            var4.add(DialogBody.plainMessage(this.guideLine("/sell", "sell held or inventory items")));
            var4.add(DialogBody.plainMessage(this.guideLine("/worth", "check item sell value")));
            var4.add(DialogBody.plainMessage(this.guideLine("/pay <player> <amt>", "send money")));
            var4.add(DialogBody.plainMessage(this.guideLine("/shards", "shard shop & crates")));
            break;
         case "social":
            var3 = "Social";
            var4.add(DialogBody.plainMessage(this.guideLine("/friend", "open the friends menu")));
            var4.add(DialogBody.plainMessage(this.guideLine("/follow <player>", "follow a player")));
            var4.add(DialogBody.plainMessage(this.guideLine("/unfollow <player>", "stop following")));
            var4.add(DialogBody.plainMessage(Component.text("A mutual follow makes you friends.", NamedTextColor.GRAY)));
            var4.add(DialogBody.plainMessage(this.guideLine("/leaderboard", "top players")));
            break;
         default:
            var3 = "Rules";
            var4.add(DialogBody.plainMessage(Component.text("1) No cheating", NamedTextColor.GRAY)));
            var4.add(DialogBody.plainMessage(Component.text("2) No slurs / racism", NamedTextColor.GRAY)));
            var4.add(DialogBody.plainMessage(Component.text("3) No doxxing / threats", NamedTextColor.GRAY)));
            var4.add(DialogBody.plainMessage(Component.text("Appeals: Discord #appeals", NamedTextColor.GRAY)));
      }

      Dialog var5 = this.buildDialog(
         Component.text(var3, DIALOG_BRAND),
         var4,
         List.of(),
         DialogType.multiAction(List.of(this.dialogButton(Component.text("Back to Guide"), null, 150, this::openGuideDialog)))
            .columns(1)
            .exitAction(this.dialogButton(Component.text("Close"), null, 150, var0 -> var0.closeDialog()))
            .build()
      );
      var1.showDialog(var5);
   }

   private boolean handleFreezeCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3 && !var3.hasPermission("pizzasmp.freeze")) {
         var3.sendMessage(color("&cYou do not have permission to use /freeze."));
         return true;
      }

      if (var2.length != 1) {
         var1.sendMessage(color("&cUsage: &e/freeze <player>"));
         return true;
      } else {
         Player var5 = Bukkit.getPlayerExact(var2[0]);
         if (var5 == null) {
            this.sendOfflinePlayerMessage(var1, var2[0]);
            return true;
         } else {
            UUID var4 = var5.getUniqueId();
            if (!this.frozenPlayers.add(var4)) {
               var1.sendMessage(color("&e" + var5.getName() + " is already frozen."));
               return true;
            } else {
               this.frozenAnchors.put(var4, var5.getLocation().clone());
               this.frozenNoticeCooldown.remove(var4);
               var5.setVelocity(new Vector(0, 0, 0));
               if (var5.isFlying()) {
                  var5.setFlying(false);
               }

               var5.sendMessage(color("&cYou have been frozen by staff. Do not log out."));
               var5.sendActionBar(color("&cYou are frozen."));
               var1.sendMessage(color("&aFrozen &e" + var5.getName() + "&a."));
               return true;
            }
         }
      }
   }

   private boolean handleUnfreezeCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3 && !var3.hasPermission("pizzasmp.unfreeze")) {
         var3.sendMessage(color("&cYou do not have permission to use /unfreeze."));
         return true;
      }

      if (var2.length != 1) {
         var1.sendMessage(color("&cUsage: &e/unfreeze <player>"));
         return true;
      } else {
         Player var5 = Bukkit.getPlayerExact(var2[0]);
         if (var5 == null) {
            this.sendOfflinePlayerMessage(var1, var2[0]);
            return true;
         } else {
            UUID var4 = var5.getUniqueId();
            if (!this.frozenPlayers.remove(var4)) {
               var1.sendMessage(color("&e" + var5.getName() + " is not frozen."));
               return true;
            } else {
               this.frozenAnchors.remove(var4);
               this.frozenNoticeCooldown.remove(var4);
               var5.sendMessage(color("&aYou have been unfrozen."));
               var1.sendMessage(color("&aUnfroze &e" + var5.getName() + "&a."));
               return true;
            }
         }
      }
   }

   private boolean isMaintenanceStaff(Player var1) {
      return var1.hasPermission("pizzasmp.maintenance.admin") || var1.hasPermission("pizzasmp.maintenance.stay") || var1.hasPermission("pizzasmp.staff");
   }

   private void showMaintenanceTitle(Player var1) {
      var1.sendActionBar(color("&aYour region is under maintenance"));
   }

   private void enterMaintenanceFreeze(Player var1) {
      this.enterMaintenanceFreeze(var1, false);
   }

   private void enterMaintenanceFreeze(Player var1, boolean var2) {
      if (var1 != null && var1.isOnline()) {
         if (var2 || !this.isMaintenanceStaff(var1)) {
            UUID var3 = var1.getUniqueId();
            this.frozenAnchors.put(var3, var1.getLocation().clone());
            this.frozenPlayers.add(var3);
            this.maintenanceFrozen.add(var3);

            try {
               var1.setInvulnerable(true);
            } catch (Throwable var5) {
            }

            this.showMaintenanceTitle(var1);
         }
      }
   }

   private void exitMaintenanceFreeze(Player var1) {
      if (var1 != null) {
         UUID var2 = var1.getUniqueId();
         this.maintenanceFrozen.remove(var2);
         this.frozenPlayers.remove(var2);
         this.frozenAnchors.remove(var2);
         this.frozenNoticeCooldown.remove(var2);

         try {
            var1.setInvulnerable(false);
         } catch (Throwable var4) {
         }

         if (var1.isOnline()) {
            var1.resetTitle();
            var1.sendActionBar(Component.text(color("&aMaintenance complete — welcome back!")));
         }
      }
   }

   private boolean handleServerMaintCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3 && !var3.hasPermission("pizzasmp.maintenance.admin")) {
         var3.sendMessage(color("&cYou do not have permission to use /servermaint."));
         return true;
      }

      String var10 = var2.length >= 1 ? var2[0].toLowerCase(Locale.ROOT) : "status";
      switch (var10) {
         case "start":
         case "on":
            if (var2.length >= 2) {
               this.maintenanceReason = String.join(" ", Arrays.copyOfRange(var2, 1, var2.length));
            }

            this.maintenanceActive = true;
            this.saveMaintenanceMode();
            int var11 = 0;

            for (Player var13 : Bukkit.getOnlinePlayers()) {
               this.enterMaintenanceFreeze(var13, true);
               var11++;
            }

            var1.sendMessage(color("&aFrozen-maintenance &lON&a. Froze &e" + var11 + " &aplayer(s). Reason: &f" + this.maintenanceReason));
            this.getLogger().info("[Maintenance] Frozen-maintenance ON by " + var1.getName() + " (" + this.maintenanceReason + ") — froze " + var11 + ".");
            break;
         case "end":
         case "off":
            this.maintenanceActive = false;
            this.saveMaintenanceMode();
            int var6 = 0;

            for (UUID var8 : List.copyOf(this.maintenanceFrozen)) {
               Player var9 = Bukkit.getPlayer(var8);
               if (var9 != null) {
                  this.exitMaintenanceFreeze(var9);
                  var6++;
               } else {
                  this.maintenanceFrozen.remove(var8);
                  this.frozenPlayers.remove(var8);
                  this.frozenAnchors.remove(var8);
               }
            }

            var1.sendMessage(color("&aFrozen-maintenance &lOFF&a. Released &e" + var6 + " &aplayer(s)."));
            this.getLogger().info("[Maintenance] Frozen-maintenance OFF by " + var1.getName() + " — released " + var6 + ".");
            break;
         case "status":
            var1.sendMessage(
               color(
                  "&fFrozen-maintenance: "
                     + (this.maintenanceActive ? "&aACTIVE" : "&cinactive")
                     + " &7| frozen players: &e"
                     + this.maintenanceFrozen.size()
                     + " &7| reason: &f"
                     + this.maintenanceReason
               )
            );
            break;
         default:
            var1.sendMessage(color("&cUsage: &e/servermaint <start [reason]|end|status>"));
      }

      return true;
   }

   private void loadMaintenanceMode() {
      this.maintenanceModeFile = new File(this.getDataFolder(), "maintenance-mode.yml");
      if (this.maintenanceModeFile.exists()) {
         YamlConfiguration var1 = YamlConfiguration.loadConfiguration(this.maintenanceModeFile);
         this.maintenanceActive = var1.getBoolean("active", false);
         this.maintenanceReason = var1.getString("reason", "Scheduled maintenance");
      }
   }

   private void saveMaintenanceMode() {
      if (this.maintenanceModeFile == null) {
         this.maintenanceModeFile = new File(this.getDataFolder(), "maintenance-mode.yml");
      }

      YamlConfiguration var1 = new YamlConfiguration();
      var1.set("active", this.maintenanceActive);
      var1.set("reason", this.maintenanceReason);

      try {
         var1.save(this.maintenanceModeFile);
      } catch (Exception var3) {
         this.getLogger().warning("Failed saving maintenance mode: " + var3.getMessage());
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onMaintenanceJoin(PlayerJoinEvent var1) {
      if (this.maintenanceActive) {
         Player var2 = var1.getPlayer();
         Bukkit.getScheduler().runTaskLater(this, () -> this.enterMaintenanceFreeze(var2, true), 2L);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onMaintenanceInteract(BlockBreakEvent var1) {
      if (this.maintenanceFrozen.contains(var1.getPlayer().getUniqueId())) {
         var1.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onMaintenancePlace(BlockPlaceEvent var1) {
      if (this.maintenanceFrozen.contains(var1.getPlayer().getUniqueId())) {
         var1.setCancelled(true);
      }
   }

   private boolean handleTransferCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3 && !var3.hasPermission("pizzasmp.transfer")) {
         var3.sendMessage(color("&cYou do not have permission to use /transfer."));
         return true;
      }

      if (var2.length < 1) {
         var1.sendMessage(color("&cUsage: &e/transfer <destination|host:port|host port>"));
         var1.sendMessage(color("&cUsage: &e/transfer <player> <destination|host:port|host port>"));
         return true;
      } else {
         Player var4;
         PizzaAdminTools.TransferDestination var6;
         if (var1 instanceof Player var5) {
            if (var2.length == 1) {
               var6 = this.parseTransferDestination(var2[0], null);
               if (var6 == null) {
                  var5.sendMessage(color("&cUnknown destination. Use a configured destination name or host:port."));
                  return true;
               }

               var4 = var5;
            } else if (var2.length == 2 && isLikelyPort(var2[1])) {
               var6 = this.parseTransferDestination(var2[0], var2[1]);
               if (var6 == null) {
                  var5.sendMessage(color("&cInvalid host/port."));
                  return true;
               }

               var4 = var5;
            } else {
               if (!var5.hasPermission("pizzasmp.transfer.others")) {
                  var5.sendMessage(color("&cYou do not have permission to transfer other players."));
                  return true;
               }

               var4 = Bukkit.getPlayerExact(var2[0]);
               if (var4 == null) {
                  this.sendOfflinePlayerMessage(var5, var2[0]);
                  return true;
               }

               if (var2.length == 2) {
                  var6 = this.parseTransferDestination(var2[1], null);
               } else {
                  if (var2.length != 3) {
                     var5.sendMessage(color("&cUsage: &e/transfer <player> <destination|host:port|host port>"));
                     return true;
                  }

                  var6 = this.parseTransferDestination(var2[1], var2[2]);
               }

               if (var6 == null) {
                  var5.sendMessage(color("&cUnknown destination. Use a configured destination name or host:port."));
                  return true;
               }
            }
         } else {
            if (var2.length < 2) {
               var1.sendMessage(color("&cConsole usage: &e/transfer <player> <destination|host:port|host port>"));
               return true;
            }

            var4 = Bukkit.getPlayerExact(var2[0]);
            if (var4 == null) {
               this.sendOfflinePlayerMessage(var1, var2[0]);
               return true;
            }

            if (var2.length == 2) {
               var6 = this.parseTransferDestination(var2[1], null);
            } else {
               if (var2.length != 3) {
                  var1.sendMessage(color("&cConsole usage: &e/transfer <player> <destination|host:port|host port>"));
                  return true;
               }

               var6 = this.parseTransferDestination(var2[1], var2[2]);
            }

            if (var6 == null) {
               var1.sendMessage(color("&cUnknown destination. Use a configured destination name or host:port."));
               return true;
            }
         }

         var4.transfer(var6.host, var6.port);
         if (var1 instanceof Player var7 && var7.getUniqueId().equals(var4.getUniqueId())) {
            var1.sendMessage(color("&aTransferring you to &e" + var6.host + ":" + var6.port + "&a..."));
            return true;
         }

         var1.sendMessage(color("&aTransferring &e" + var4.getName() + " &ato &e" + var6.host + ":" + var6.port + "&a..."));
         return true;
      }
   }

   private boolean handleTransferMaintenanceCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3 && !var3.hasPermission("pizzasmp.transfer.maintenance")) {
         var3.sendMessage(color("&cYou do not have permission to use /transfermaintenance."));
         return true;
      }

      if (var2.length < 1) {
         var1.sendMessage(color("&cUsage: &e/transfermaintenance <on|off|status>"));
         return true;
      } else {
         String var6 = var2[0].toLowerCase(Locale.ROOT);
         switch (var6) {
            case "on":
            case "enable":
            case "start":
               return this.handleMaintenanceTransferEnable(var1);
            case "off":
            case "disable":
            case "stop":
            case "end":
               return this.handleMaintenanceTransferDisable(var1);
            case "status":
               var1.sendMessage(color("&fMaintenance transfer active: " + (this.isMaintenanceTransferActive() ? "&aON" : "&cOFF")));
               var1.sendMessage(color("&fDestination: &b" + this.getMaintenanceTransferDestinationName()));
               var1.sendMessage(color("&fTracked transferred users: &b" + this.getMaintenanceTransferred().size()));
               return true;
            default:
               var1.sendMessage(color("&cUsage: &e/transfermaintenance <on|off|status>"));
               return true;
         }
      }
   }

   private boolean handleMaintenanceTransferEnable(CommandSender var1) {
      PizzaAdminTools.TransferDestination var2 = this.parseTransferDestination(this.getMaintenanceTransferDestinationName(), null);
      if (var2 == null) {
         var1.sendMessage(color("&cInvalid maintenance destination. Check &eplugins/PizzaAdminTools/transfer-destinations.yml"));
         return true;
      } else {
         this.setMaintenanceTransferActive(true);
         int var3 = 0;
         int var4 = 0;
         Set var5 = this.getMaintenanceTransferred();

         for (Player var7 : Bukkit.getOnlinePlayers()) {
            if (this.shouldStayDuringMaintenance(var7)) {
               var4++;
            } else {
               for (String var9 : this.getMaintenanceStartMessages(var2.host, var2.port)) {
                  var7.sendMessage(color(var9));
               }

               var7.transfer(var2.host, var2.port);
               var3++;
               var5.add(var7.getUniqueId().toString());
            }
         }

         this.saveMaintenanceTransferred(var5);
         this.saveMaintenanceTransferState();
         var1.sendMessage(color("&aMaintenance transfer started. Moved: &e" + var3 + "&a, skipped: &e" + var4 + "&a."));
         return true;
      }
   }

   private boolean handleMaintenanceTransferDisable(CommandSender var1) {
      this.setMaintenanceTransferActive(false);
      this.saveMaintenanceTransferState();
      var1.sendMessage(color("&aMaintenance transfer disabled."));
      var1.sendMessage(color("&7Note: players already on maintenance server cannot be force-returned from here."));
      return true;
   }

   private boolean handlePizzaAdminToolsHelp(CommandSender var1) {
      var1.sendMessage(color("&bPizzaAdminTools Commands"));
      var1.sendMessage(color("&f/pizzaadmintools help &7- Show this list"));
      var1.sendMessage(color("&f/pizzateams help &7- Team command hub"));
      var1.sendMessage(color("&f/pizzamenus help &7- Menu command hub"));
      var1.sendMessage(color("&f/pizzahome help &7- Home command hub"));
      var1.sendMessage(color("&f/pizzabans help &7- Moderation command hub"));
      var1.sendMessage(color("&f/pizzaplugins &7- Branded plugin list (staff)"));
      var1.sendMessage(color("&f/gtp, /homes, /menu, /guide, /freeze, /unfreeze, /transfer, /sus"));
      return true;
   }

   private boolean handlePizzaTeamsHelp(CommandSender var1) {
      var1.sendMessage(color("&bPizzaTeams Commands"));
      var1.sendMessage(color("&f/team help &7- Full BetterTeams command help"));
      var1.sendMessage(color("&f/team create <name>"));
      var1.sendMessage(color("&f/team invite <player>"));
      var1.sendMessage(color("&f/team home &7- Teleport to team home"));
      var1.sendMessage(color("&f/team sethome &7- Set team home (leaders/admin)"));
      return true;
   }

   private boolean handlePizzaMenusHelp(CommandSender var1) {
      var1.sendMessage(color("&bPizzaMenus Commands"));
      var1.sendMessage(color("&f/menu &7- Open " + BRAND_DISPLAY + " main menu"));
      var1.sendMessage(color("&f/guide &7- Open " + BRAND_DISPLAY + " guide"));
      return true;
   }

   private boolean handlePizzaHomeHelp(CommandSender var1) {
      var1.sendMessage(color("&bPizzaHome Commands"));
      var1.sendMessage(color("&f/home &7- Open homes menu"));
      var1.sendMessage(color("&f/homes &7- Open homes menu"));
      var1.sendMessage(color("&f/home <name|slot> &7- Teleport to home"));
      var1.sendMessage(color("&f/gtp <player> [home] &7- Staff/admin home teleport"));
      return true;
   }

   private boolean handlePizzaBansHelp(CommandSender var1) {
      var1.sendMessage(color("&bPizzaBans Commands"));
      var1.sendMessage(color("&f/punish <player> <category|duration|reason...> [duration]"));
      var1.sendMessage(color("&f/ban, /permban, /ipban, /ippermban, /mute, /kick"));
      var1.sendMessage(color("&f/unban, /unpunish, /forgive, /idunban, /unmute"));
      var1.sendMessage(color("&f/bans or /moderation &7- Open the moderation GUI"));
      var1.sendMessage(color("&f/history <player|ip|id>, /searchid <id>"));
      var1.sendMessage(color("&f/listbans, /listmutes, /clearbans, /clearmutes"));
      var1.sendMessage(color("&f/sus &7- Grim suspects from the last 30 minutes"));
      var1.sendMessage(color("&f/clearwarnings <player>"));
      var1.sendMessage(color("&f/clearallwarnings [player]"));
      return true;
   }

   private boolean handleSusCommand(CommandSender var1, String[] var2) {
      int var3 = 0;
      if (var2.length > 0 && !var2[0].isBlank()) {
         try {
            var3 = Math.max(0, Integer.parseInt(var2[0]) - 1);
         } catch (NumberFormatException var5) {
            var1.sendMessage(color("&cUsage: &e/sus [page]"));
            return true;
         }
      }

      if (var1 instanceof Player var4) {
         if (!var4.hasPermission("pizzasmp.sus") && !var4.hasPermission("pizzasmp.pluginadmin")) {
            var4.sendMessage(color("&cYou do not have permission to use /sus."));
            return true;
         } else {
            this.openSusMenu(var4, var3);
            return true;
         }
      } else {
         this.sendConsoleSusPage(var1, var3);
         return true;
      }
   }

   private boolean handlePizzaPlugins(CommandSender var1) {
      if (var1 instanceof Player var2 && !var2.hasPermission("pizzasmp.pluginadmin")) {
         var2.sendMessage(color("&cYou do not have permission to view plugin/admin commands."));
         return true;
      }

      this.sendBrandedPluginList(var1);
      return true;
   }

   private boolean handleNightVisionCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3) {
         if (!var3.hasPermission("pizzasmp.nv")) {
            var3.sendMessage(color("&cYou do not have permission to use /nv."));
            return true;
         } else {
            Player var4 = var3;
            if (var2.length > 0) {
               if (!var3.hasPermission("pizzasmp.nv.others")) {
                  var3.sendMessage(color("&cYou do not have permission to toggle night vision on others."));
                  return true;
               }

               var4 = Bukkit.getPlayer(var2[0]);
               if (var4 == null) {
                  var3.sendMessage(color("&cPlayer not found: &e" + var2[0]));
                  return true;
               }
            }

            if (this.nvEnabled.contains(var4.getUniqueId())) {
               this.nvEnabled.remove(var4.getUniqueId());
               var4.removePotionEffect(PotionEffectType.NIGHT_VISION);
               var4.sendActionBar(Component.text("§7night vision disabled"));
               if (var4 != var3) {
                  var3.sendActionBar(Component.text("§7night vision disabled for " + var4.getName()));
               }
            } else {
               this.nvEnabled.add(var4.getUniqueId());
               var4.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
               var4.sendActionBar(Component.text("§7night vision enabled"));
               if (var4 != var3) {
                  var3.sendActionBar(Component.text("§7night vision enabled for " + var4.getName()));
               }
            }

            this.saveNvState();
            return true;
         }
      } else {
         var1.sendMessage(color("&cThis command can only be used by players."));
         return true;
      }
   }

   private File nvStateFile() {
      return new File(this.getDataFolder(), "nv-players.yml");
   }

   private void saveNvState() {
      try {
         File var1 = this.nvStateFile();
         YamlConfiguration var2 = new YamlConfiguration();
         ArrayList var3 = new ArrayList();

         for (UUID var5 : this.nvEnabled) {
            var3.add(var5.toString());
         }

         var2.set("players", var3);
         var2.save(var1);
      } catch (Exception var6) {
         this.getLogger().warning("Failed saving NV state: " + var6.getMessage());
      }
   }

   private void loadNvState() {
      try {
         File var1 = this.nvStateFile();
         if (!var1.exists()) {
            return;
         }

         YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);

         for (String var4 : var2.getStringList("players")) {
            try {
               this.nvEnabled.add(UUID.fromString(var4));
            } catch (Exception var6) {
            }
         }
      } catch (Exception var7) {
         this.getLogger().warning("Failed loading NV state: " + var7.getMessage());
      }
   }

   private void sendBrandedPluginList(CommandSender var1) {
      var1.sendMessage(color(BRAND_SECTION + BRAND_DISPLAY + " Plugin Stack &7(" + BRANDED_PLUGIN_LIST.size() + ")"));
      var1.sendMessage(color("&f" + String.join("&7, &f", BRANDED_PLUGIN_LIST)));
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onMaintenanceMotdCommand(PlayerCommandPreprocessEvent var1) {
      Player var2 = var1.getPlayer();
      String var3 = var1.getMessage();
      String var4 = var3.toLowerCase(Locale.ROOT);
      if (var4.startsWith("/maintenance motd ") || var4.startsWith("/maintenance setmotd ")) {
         if (!var2.hasPermission("pizzasmp.maintenance.motd") && !var2.hasPermission("pizzasmp.pluginadmin")) {
            var1.setCancelled(true);
            var2.sendMessage(color("&cNo permission."));
         } else {
            var1.setCancelled(true);
            int var5 = var3.indexOf(32, var3.indexOf("motd"));
            String var6 = var3.substring(var3.toLowerCase(Locale.ROOT).indexOf("motd ") + 5).trim();
            if (var6.startsWith("\"") && var6.endsWith("\"") && var6.length() >= 2) {
               var6 = var6.substring(1, var6.length() - 1);
            }

            if (var6.isBlank()) {
               var2.sendMessage(color("&cUsage: /maintenance motd \"<text>\""));
            } else {
               if (this.setMaintenanceMotd(var6)) {
                  var2.sendMessage(color("&aMaintenance MOTD set to: &f" + var6));
                  Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "maintenance reloadconfig");
               } else {
                  var2.sendMessage(color("&cFailed to update Maintenance config."));
               }
            }
         }
      }
   }

   private boolean setMaintenanceMotd(String var1) {
      try {
         File var2 = new File(this.getDataFolder().getParentFile(), "Maintenance/config.yml");
         if (!var2.exists()) {
            return false;
         } else {
            YamlConfiguration var3 = YamlConfiguration.loadConfiguration(var2);
            var3.set("ping-message.messages", List.of(var1));
            var3.save(var2);
            return true;
         }
      } catch (Exception var4) {
         this.getLogger().warning("Failed to set Maintenance MOTD: " + var4.getMessage());
         return false;
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOWEST
   )
   public void onPluginAdminCommandGate(PlayerCommandPreprocessEvent var1) {
      Player var2 = var1.getPlayer();
      String[] var3 = parseCommandParts(var1.getMessage());
      if (var3.length != 0) {
         String var4 = var3[0];
         if (PLUGIN_ADMIN_COMMANDS.contains(var4)) {
            boolean var5 = var2.hasPermission("pizzasmp.pluginadmin");
            if (!var5) {
               var1.setCancelled(true);
               var2.sendMessage(color("&cThis command is staff-only."));
            } else {
               if (var4.equals("version") || var4.equals("ver") || var4.equals("about")) {
                  var1.setCancelled(true);
                  this.sendBrandedPluginList(var2);
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOWEST
   )
   public void onRestrictedNonStaffCommand(PlayerCommandPreprocessEvent var1) {
      Player var2 = var1.getPlayer();
      if (!this.isEffectivelyStaff(var2)) {
         String[] var3 = parseCommandParts(var1.getMessage());
         if (var3.length <= 0 || !var3[0].equalsIgnoreCase("sfmode") || !var2.hasPermission("pizzasmp.pluginadmin")) {
            String[] var4 = parseCommandParts(var1.getMessage());
            if (var4.length != 0) {
               String var5 = var4[0].toLowerCase(Locale.ROOT);
               int var6 = var5.indexOf(58);
               if (var6 > 0) {
                  var5 = var5.substring(var6 + 1);
               }

               if (RESTRICTED_FOR_NON_STAFF.contains(var5)) {
                  var1.setCancelled(true);
                  this.sendUnknownCommandMessage(var2);
               }
            }
         }
      }
   }

   @EventHandler
   public void onCommandSendFilter(PlayerCommandSendEvent var1) {
      Player var2 = var1.getPlayer();
      var1.getCommands().removeIf(var0 -> {
         String var1x = var0.toLowerCase(Locale.ROOT);
         int var2x = var1x.indexOf(58);
         if (var2x > 0) {
            var1x = var1x.substring(var2x + 1);
         }

         return var1x.equals("gmcbypass");
      });
      if (!this.isEffectivelyStaff(var2)) {
         boolean var3 = var2.hasPermission("pizzasmp.pluginadmin");
         var1.getCommands().removeIf(var1x -> {
            String var2x = var1x.toLowerCase(Locale.ROOT);
            if (var2x.indexOf(58) >= 0) {
               return true;
            } else {
               return var2x.equals("sfmode") && var3 ? false : RESTRICTED_FOR_NON_STAFF.contains(var2x);
            }
         });
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onCreativeAdventureBlock(PlayerGameModeChangeEvent var1) {
      GameMode var2 = var1.getNewGameMode();
      if (var2 == GameMode.CREATIVE || var2 == GameMode.ADVENTURE) {
         // Dev/full-access holders bypass the hard creative/adventure block entirely.
         if (var1.getPlayer().hasPermission("pizzasmp.gamemode.bypass")) {
            return;
         }

         if (this.creativeBypass.remove(var1.getPlayer().getUniqueId())) {
            return;
         }

         var1.setCancelled(true);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOWEST
   )
   public void onGamemodeCommandBlock(PlayerCommandPreprocessEvent var1) {
      // Dev/full-access holders bypass the /gmc /gma /gm creative block entirely.
      if (var1.getPlayer().hasPermission("pizzasmp.gamemode.bypass")) {
         return;
      }

      String[] var2 = parseCommandParts(var1.getMessage());
      if (var2.length != 0) {
         String var3 = var2[0].toLowerCase(Locale.ROOT);
         int var4 = var3.indexOf(58);
         if (var4 > 0) {
            var3 = var3.substring(var4 + 1);
         }

         boolean var5 = var3.equals("gmc") || var3.equals("gma") || var3.equals("gmt") || var3.equals("egamemode") && var2.length >= 2;
         if (!var5 && (var3.equals("gamemode") || var3.equals("gm")) && var2.length >= 2) {
            String var6 = var2[1].toLowerCase(Locale.ROOT);
            var5 = var6.equals("creative") || var6.equals("c") || var6.equals("1") || var6.equals("adventure") || var6.equals("a") || var6.equals("2");
         }

         if (var5) {
            var1.setCancelled(true);
            var1.getPlayer().sendActionBar(color("&cCreative and Adventure mode are disabled."));
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onUnknownCommandFallback(PlayerCommandPreprocessEvent var1) {
      Player var2 = var1.getPlayer();
      if (!var2.hasPermission("pizzasmp.pluginadmin")) {
         String[] var3 = parseCommandParts(var1.getMessage());
         if (var3.length != 0) {
            if (!this.isRegisteredCommand(var3[0])) {
               var1.setCancelled(true);
               this.sendUnknownCommandMessage(var2);
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onOnlineTargetCommandGate(PlayerCommandPreprocessEvent var1) {
      String[] var2 = parseCommandParts(var1.getMessage());
      if (var2.length >= 2) {
         if (ONLINE_TARGET_FIRST_ARG_COMMANDS.contains(var2[0])) {
            if (this.resolveOnlinePlayer(var2[1]) == null) {
               var1.setCancelled(true);
               this.sendOfflinePlayerMessage(var1.getPlayer(), var2[1]);
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onHomeMenuCommand(PlayerCommandPreprocessEvent var1) {
      Player var2 = var1.getPlayer();
      String[] var3 = parseCommandParts(var1.getMessage());
      if (var3.length != 0) {
         String var4 = var3[0];
         if (var4.equals("homes")) {
            if (var2.hasPermission("pizzasmp.homes") && var2.hasPermission("sethome.use")) {
               var1.setCancelled(true);
               this.openHomesDialogFresh(var2);
            } else {
               var1.setCancelled(true);
               var2.sendMessage(color("&cYou do not have permission to use /homes."));
            }
         } else if (var4.equals("home") && var3.length == 1) {
            if (!var2.hasPermission("sethome.use")) {
               var1.setCancelled(true);
               var2.sendMessage(color("&cYou do not have permission to use /home."));
            } else {
               var1.setCancelled(true);
               this.openHomesDialogFresh(var2);
            }
         } else if (var4.equals("home") && var3.length >= 2) {
            if (!var2.hasPermission("sethome.use")) {
               var1.setCancelled(true);
               var2.sendMessage(color("&cYou do not have permission to use /home."));
            } else {
               String var5 = var3[1].toLowerCase(Locale.ROOT);
               if ("team".equals(var5)) {
                  var1.setCancelled(true);
                  var2.sendMessage(color("&cTeam homes have been retired — your team's home was saved as a personal home (&e/home teamhome&c)."));
               } else if (NON_TELEPORT_HOME_SUBCOMMANDS.contains(var5)) {
                  var1.setCancelled(true);
                  this.openHomesDialogFresh(var2);
               } else {
                  var1.setCancelled(true);
                  this.handleDirectHomeTeleport(var2, var3[1]);
               }
            }
         } else if (var4.equals("sethome")) {
            var1.setCancelled(true);
            if (!var2.hasPermission("sethome.use")) {
               var2.sendMessage(color("&cYou do not have permission to use /sethome."));
            } else {
               this.handleSetHomeCommand(var2, var3.length >= 2 ? var3[1] : null);
            }
         } else {
            if (var4.equals("delhome")) {
               var1.setCancelled(true);
               if (!var2.hasPermission("sethome.use")) {
                  var2.sendMessage(color("&cYou do not have permission to use /delhome."));
                  return;
               }

               if (var3.length < 2) {
                  var2.sendMessage(color("&cUsage: /delhome <name|number>"));
                  return;
               }

               this.handleDelHomeCommand(var2, var3[1]);
            }
         }
      }
   }

   public void onRtpWorldShortcut_DISABLED(PlayerCommandPreprocessEvent var1) {
   }

   private boolean openRtpGui(Player var1) {
      Listener var2 = findRtpListener();
      if (var2 == null) {
         return false;
      } else {
         try {
            Method var3 = var2.getClass().getMethod("openGUI", Player.class);
            var3.invoke(null, var1);
            return true;
         } catch (ReflectiveOperationException var4) {
            this.getLogger().warning("Failed to open /rtp GUI for " + var1.getName() + ": " + var4.getMessage());
            return false;
         }
      }
   }

   private boolean startRtpCountdown(Player var1, World var2, String var3) {
      Listener var4 = findRtpListener();
      if (var4 == null) {
         return false;
      } else {
         try {
            Class var5 = var4.getClass();
            Method var6 = var5.getMethod("canUseRTP", Player.class);
            boolean var7 = (Boolean)var6.invoke(null, var1);
            if (!var7) {
               Method var12 = var5.getMethod("getCooldownRemaining", Player.class);
               long var9 = (Long)var12.invoke(null, var1);
               var1.sendMessage(color("&cYou can't RTP for another &e" + var9 + "s&c."));
               return true;
            } else {
               Method var8 = var5.getDeclaredMethod("startTeleportCountdown", Player.class, World.class);
               var8.setAccessible(true);
               var1.closeInventory();
               var8.invoke(var4, var1, var2);
               return true;
            }
         } catch (ReflectiveOperationException var11) {
            this.getLogger().warning("Failed to route /rtp " + var3 + " for " + var1.getName() + ": " + var11.getMessage());
            return false;
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onCustomCommandTabComplete(TabCompleteEvent var1) {
      if (var1.isCommand()) {
         PizzaAdminTools.TabRequest var2 = parseTabRequest(var1.getBuffer());
         if (var2 != null && var2.argPosition >= 0) {
            if (var1.getSender() instanceof Player var3 && !this.isEffectivelyStaff(var3) && RESTRICTED_FOR_NON_STAFF.contains(var2.root)) {
               var1.setCompletions(Collections.emptyList());
               return;
            }

            List var7 = null;
            String var8 = var2.root;
            switch (var8) {
               case "rtp":
                  if (var2.argPosition == 0) {
                     var7 = filterByPrefix(List.of("east", "nether", "end"), var2.prefix);
                  }
                  break;
               case "servermaint":
                  if (var2.argPosition == 0) {
                     var7 = filterByPrefix(List.of("start", "end", "status"), var2.prefix);
                  }
                  break;
               case "punish":
                  if (hasAnyPermission(var1.getSender(), "pizzasmp.punish", "mycommand.punish")) {
                     if (var2.argPosition == 0) {
                        var7 = this.completeOnlineNames(var2.prefix);
                     } else if (var2.argPosition == 1) {
                        var7 = filterByPrefix(List.of("7d", "14d", "30d", "60d", "perm"), var2.prefix);
                     } else if (var2.argPosition == 2) {
                        var7 = filterByPrefix(List.of("Hacking", "Cheating", "Griefing", "Scamming", "Abuse", "Spam"), var2.prefix);
                     }
                  }
                  break;
               case "unpunish":
               case "forgive":
                  if (hasAnyPermission(var1.getSender(), "pizzasmp.unpunish", "pizzasmp.forgive", "mycommand.unpunish", "mycommand.forgive")
                     && var2.argPosition == 0) {
                     var7 = this.completeBannedPlayers(var2.prefix);
                  }
                  break;
               case "bancheck":
                  if (hasAnyPermission(var1.getSender(), "pizzasmp.bancheck", "mycommand.bancheck") && var2.argPosition == 0) {
                     var7 = this.completePunishmentLookups(var2.prefix, false);
                  }
                  break;
               case "bans":
                  if (hasAnyPermission(var1.getSender(), "pizzasmp.bans", "pizzasmp.bancheck", "mycommand.bancheck") && var2.argPosition == 0) {
                     var7 = filterByPrefix(List.of("1", "2", "3", "4", "5"), var2.prefix);
                  }
                  break;
               case "sus":
               case "suspicious":
                  if (hasAnyPermission(var1.getSender(), "pizzasmp.admin.commands") && var2.argPosition == 0) {
                     var7 = filterByPrefix(List.of("1", "2", "3", "4", "5"), var2.prefix);
                  }
                  break;
               case "freeze":
                  if (hasAnyPermission(var1.getSender(), "pizzasmp.freeze") && var2.argPosition == 0) {
                     var7 = this.completeOnlineNames(var2.prefix);
                  }
                  break;
               case "unfreeze":
                  if (hasAnyPermission(var1.getSender(), "pizzasmp.unfreeze") && var2.argPosition == 0) {
                     var7 = this.completePlayerNames(var2.prefix, true);
                  }
                  break;
               case "clearwarnings":
               case "clearwarn":
               case "clearwarns":
               case "clearallwarnings":
               case "clearallwarns":
               case "clearwarningsall":
                  if (var2.argPosition == 0) {
                     var7 = this.completeOnlineNames(var2.prefix);
                  }
                  break;
               case "gtp":
                  if (hasAnyPermission(var1.getSender(), "pizzasmp.gtp")) {
                     if (var2.argPosition == 0) {
                        var7 = this.completeKnownHomeTargets(var2.prefix);
                     } else if (var2.argPosition == 1 && !var2.args.isEmpty()) {
                        var7 = this.completeTargetHomeSlots(var2.args.get(0), var2.prefix);
                     }
                  }
                  break;
               case "home":
               case "delhome":
                  if (var2.argPosition == 0) {
                     var7 = this.completeOwnHomes(var1.getSender(), var2.prefix);
                  }
                  break;
            }

            if (var7 != null && !var7.isEmpty()) {
               var1.setCompletions(var7);
            }
         }
      }
   }

   private List<String> completePunishmentLookups(String var1, boolean var2) {
      HashSet var3 = new HashSet<>(this.completeOnlineNames(""));
      File var4 = new File(this.getDataFolder().getParentFile(), "PizzaPunishment/ban-records.yml");
      if (!var4.isFile()) {
         return filterByPrefix(new ArrayList<>(var3), var1);
      } else {
         YamlConfiguration var5 = YamlConfiguration.loadConfiguration(var4);
         ConfigurationSection var6 = var5.getConfigurationSection("records");
         if (var6 != null) {
            for (String var8 : var6.getKeys(false)) {
               ConfigurationSection var9 = var6.getConfigurationSection(var8);
               if (var9 != null && (!var2 || var9.getBoolean("active", true))) {
                  String var10 = var9.getString("target-name");
                  if (var10 != null && !var10.isBlank()) {
                     var3.add(var10);
                  }

                  var3.add(var8.toUpperCase(Locale.ROOT));
               }
            }
         }

         ArrayList var11 = new ArrayList(var3);
         var11.sort(String.CASE_INSENSITIVE_ORDER);
         return filterByPrefix(var11, var1);
      }
   }

   private List<String> completeBannedPlayers(String var1) {
      HashSet var2 = new HashSet();
      File var3 = new File(this.getDataFolder().getParentFile(), "PizzaPunishment/ban-records.yml");
      if (!var3.isFile()) {
         return new ArrayList<>();
      } else {
         YamlConfiguration var4 = YamlConfiguration.loadConfiguration(var3);
         ConfigurationSection var5 = var4.getConfigurationSection("records");
         if (var5 != null) {
            for (String var7 : var5.getKeys(false)) {
               ConfigurationSection var8 = var5.getConfigurationSection(var7);
               if (var8 != null && var8.getBoolean("active", true)) {
                  String var9 = var8.getString("target-name");
                  if (var9 != null && !var9.isBlank()) {
                     var2.add(var9);
                  }

                  var2.add(var7.toUpperCase(Locale.ROOT));
               }
            }
         }

         ArrayList var10 = new ArrayList(var2);
         var10.sort(String.CASE_INSENSITIVE_ORDER);
         return filterByPrefix(var10, var1);
      }
   }

   private List<String> completeOwnHomes(CommandSender var1, String var2) {
      if (var1 instanceof Player var3) {
         File var4 = this.getSetHomeDataFile(var3);
         if (var4 != null && var4.exists()) {
            YamlConfiguration var5 = YamlConfiguration.loadConfiguration(var4);
            ArrayList var6 = new ArrayList();
            ArrayList var7 = new ArrayList();

            for (String var9 : var5.getStringList("homes")) {
               if (var9.matches("home\\d+")) {
                  var6.add(var9.substring(4));
               } else {
                  var7.add(var9);
               }
            }

            ((java.util.List<String>) var6).sort(Comparator.comparingInt(Integer::parseInt));
            var7.sort(String.CASE_INSENSITIVE_ORDER);
            ArrayList var10 = new ArrayList(var6);
            var10.addAll(var7);
            return filterByPrefix(var10, var2);
         } else {
            return new ArrayList<>();
         }
      } else {
         return new ArrayList<>();
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.LOWEST
   )
   public void onFrozenCommandBlock(PlayerCommandPreprocessEvent var1) {
      Player var2 = var1.getPlayer();
      if (this.isFrozen(var2)) {
         String[] var3 = parseCommandParts(var1.getMessage());
         if (var3.length != 0) {
            String var4 = var3[0];
            if (!FROZEN_ALLOWED_COMMANDS.contains(var4)) {
               if (!this.maintenanceFrozen.contains(var2.getUniqueId()) || !var2.hasPermission("pizzasmp.maintenance.admin")) {
                  var1.setCancelled(true);
                  this.notifyFrozen(var2);
                  var2.sendMessage(color("&cYou are frozen and cannot use commands right now."));
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onFrozenMove(PlayerMoveEvent var1) {
      Player var2 = var1.getPlayer();
      if (this.isFrozen(var2)) {
         Location var3 = var1.getTo();
         if (var3 != null) {
            Location var4 = var1.getFrom();
            if (var4.getX() != var3.getX() || var4.getY() != var3.getY() || var4.getZ() != var3.getZ()) {
               Location var5 = this.frozenAnchors.computeIfAbsent(var2.getUniqueId(), var1x -> var4.clone());
               Location var6 = var5.clone();
               var6.setYaw(var3.getYaw());
               var6.setPitch(var3.getPitch());
               var1.setTo(var6);
               this.notifyFrozen(var2);
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onFrozenTeleport(PlayerTeleportEvent var1) {
      Player var2 = var1.getPlayer();
      if (this.isFrozen(var2)) {
         var1.setCancelled(true);
         this.notifyFrozen(var2);
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.MONITOR
   )
   public void onCombatHit(EntityDamageByEntityEvent var1) {
      if (var1.getEntity() instanceof Player var2) {
         Player var4 = resolveAttacker(var1.getDamager());
         if (var4 != null) {
            this.tagCombat(var2);
            this.tagCombat(var4);
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGH
   )
   public void onTeleportCommand(PlayerCommandPreprocessEvent var1) {
      Player var2 = var1.getPlayer();
      if (!var2.hasPermission("pizzasmp.combat.bypass") && this.isCombatTagged(var2)) {
         String[] var3 = parseCommandParts(var1.getMessage());
         if (var3.length != 0) {
            String var4 = var3[0];
            if (var4.equals("rtp")) {
               var1.setCancelled(true);
               var2.sendMessage(color("&cYou are in combat. Wait &e" + this.remainingCombatSeconds(var2) + "s &cbefore using /rtp."));
            } else {
               if ((var4.equals("home") || var4.equals("homes") || var4.equals("gtp")) && isTeleportHomeCommand(var3)) {
                  var1.setCancelled(true);
                  var2.sendMessage(color("&cYou are in combat. Wait &e" + this.remainingCombatSeconds(var2) + "s &cbefore teleporting home."));
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onHomeInventoryClick(InventoryClickEvent var1) {
      if (var1.getWhoClicked() instanceof Player var2) {
         String var5 = var1.getView().getTitle();
         if (this.isHomesMenu(var5) || this.isHomeDeleteMenu(var5)) {
            var1.setCancelled(true);
            int var4 = var1.getRawSlot();
            if (var4 >= 0 && var4 < var1.getView().getTopInventory().getSize()) {
               if (this.isHomeDeleteMenu(var5)) {
                  this.handleDeleteConfirmClick(var2, var4);
               } else if (var4 >= 0 && var4 < 27) {
                  this.handleHomeGridClick(var2, var4, var1.isRightClick());
               } else {
                  if (var4 == 31) {
                     var2.closeInventory();
                  }
               }
            }
         }
      }
   }

   @EventHandler(
      ignoreCancelled = true,
      priority = EventPriority.HIGHEST
   )
   public void onSusInventoryClick(InventoryClickEvent var1) {
      if (var1.getWhoClicked() instanceof Player var2) {
         String var5 = var1.getView().getTitle();
         if (this.isSusMenu(var5)) {
            var1.setCancelled(true);
            int var4 = var1.getRawSlot();
            if (var4 >= 0 && var4 < var1.getView().getTopInventory().getSize()) {
               this.handleSusClick(var2, var4);
            }
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      UUID var2 = var1.getPlayer().getUniqueId();
      Integer var3 = this.pendingTeleportTasks.remove(var2);
      if (var3 != null) {
         Bukkit.getScheduler().cancelTask(var3);
      }

      // Opt-in staff mode is per-session: clear on logout so the next login starts hidden/blocked.
      this.staffMode.remove(var2);
      this.combatTaggedUntil.remove(var2);
      this.adminTargetIndex.remove(var2);
      this.pendingDeleteSlot.remove(var2);
      this.pendingDeleteHome.remove(var2);
      this.pendingTeleportOrigins.remove(var2);
      this.frozenPlayers.remove(var2);
      this.frozenAnchors.remove(var2);
      this.frozenNoticeCooldown.remove(var2);
      this.susMenuPages.remove(var2);
      this.stopTrackingTask(var2);
      this.atrackPriorMode.remove(var2);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onPreLoginBanMessageSanitize(AsyncPlayerPreLoginEvent var1) {
      if (var1.getLoginResult() == Result.KICK_BANNED) {
         String var2 = var1.getKickMessage();
         if (var2 != null && !var2.isBlank()) {
            String var3 = normalizeBanKickMessage(sanitizeBanMessage(var2));
            if (!var3.equals(var2)) {
               var1.setKickMessage(var3);
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onMaintenanceJoinGate(PlayerLoginEvent var1) {
      if (this.isMaintenanceTransferActive()) {
         Player var2 = var1.getPlayer();
         if (!this.shouldStayDuringMaintenance(var2)) {
            var1.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER, this.buildMaintenanceKickMessage());
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onPlayerLoginBanMessageSanitize(PlayerLoginEvent var1) {
      if (var1.getResult() == org.bukkit.event.player.PlayerLoginEvent.Result.KICK_BANNED) {
         String var2 = var1.getKickMessage();
         if (var2 != null && !var2.isBlank()) {
            String var3 = normalizeBanKickMessage(sanitizeBanMessage(var2));
            if (!var3.equals(var2)) {
               var1.setKickMessage(var3);
            }
         }
      }
   }

   private void openHomesMenu(Player var1, OfflinePlayer var2) {
      OfflinePlayer var3 = this.normalizeTarget(var1, var2);
      int var4 = this.allowedHomes(var1);
      boolean var5 = var3.getUniqueId().equals(var1.getUniqueId());
      File var6 = this.getSetHomeDataFile(var3);
      YamlConfiguration var7 = var6 != null && var6.exists() ? YamlConfiguration.loadConfiguration(var6) : new YamlConfiguration();
      ArrayList var8 = new ArrayList(var7.getStringList("homes"));
      String[] var9 = this.buildHomeSlots(var8, 27);
      Inventory var10 = Bukkit.createInventory(null, 36, color(HOME_MENU_TITLE));

      for (int var11 = 0; var11 < 27; var11++) {
         if (var11 >= var4) {
            var10.setItem(var11, item(Material.RED_BED, "&cHome " + (var11 + 1) + " &8(Locked)", List.of("&7Unlock with &6Pizza+ &7/ &6Pizza++.")));
         } else {
            String var12 = var11 < var9.length ? var9[var11] : null;
            if (var12 == null) {
               var10.setItem(var11, item(Material.GRAY_BED, "&7New Home", List.of(var5 ? "&fClick to set a home here." : "&8Empty")));
            } else {
               String var13 = this.homeDisplayName(var12);
               ArrayList var14 = new ArrayList();
               var14.add("&fLeft-click to Teleport.");
               if (var5) {
                  var14.add("&cRight-click to Delete.");
               }

               var10.setItem(var11, item(Material.BLUE_BED, "&b" + var13, var14));
            }
         }
      }

      var10.setItem(31, item(Material.BARRIER, "&cClose", List.of("&7Close this menu.")));
      var1.openInventory(var10);
   }

   private void handleHomeGridClick(Player var1, int var2, boolean var3) {
      OfflinePlayer var4 = this.getCurrentTarget(var1);
      boolean var5 = var4.getUniqueId().equals(var1.getUniqueId());
      int var6 = this.allowedHomes(var1);
      if (var2 >= var6) {
         var1.sendMessage(color("&cThis home slot is locked for your rank."));
      } else {
         File var7 = this.getSetHomeDataFile(var4);
         YamlConfiguration var8 = var7 != null && var7.exists() ? YamlConfiguration.loadConfiguration(var7) : new YamlConfiguration();
         ArrayList var9 = new ArrayList(var8.getStringList("homes"));
         String[] var10 = this.buildHomeSlots(var9, 27);
         String var11 = var2 < var10.length ? var10[var2] : null;
         if (var11 == null) {
            if (var5) {
               if (this.setHomeInSlot(var1, var2)) {
                  var1.sendMessage(color("&7Home &bhome" + (var2 + 1) + "&7 set."));
               }

               this.openHomesMenu(var1, var4);
            }
         } else if (var3) {
            if (var5) {
               this.openHomeDeleteConfirmByName(var1, var11);
            }
         } else if (!var1.hasPermission("pizzasmp.combat.bypass") && this.isCombatTagged(var1)) {
            var1.sendMessage(color("&cYou are in combat. Wait &e" + this.remainingCombatSeconds(var1) + "s &cbefore teleporting home."));
         } else {
            Location var12 = this.readHomeLocation(var4, var11);
            if (var12 == null) {
               var1.sendMessage(color("&cThat home is invalid or its world is missing."));
            } else {
               var1.closeInventory();
               String var13 = var5 ? "&fTeleported To Your Home." : "&fTeleported To " + var4.getName() + "'s Home.";
               this.startCountdownTeleport(var1, var12, var13);
            }
         }
      }
   }

   private void openHomeDeleteConfirmByName(Player var1, String var2) {
      this.pendingDeleteHome.put(var1.getUniqueId(), var2);
      Inventory var3 = Bukkit.createInventory(null, 27, color("&8Confirm Home Deletion"));
      Location var4 = this.readHomeLocation(var1, var2);
      ArrayList var5 = new ArrayList();
      var5.add("&7You are about to delete &f" + this.homeDisplayName(var2) + "&7.");
      if (var4 != null && var4.getWorld() != null) {
         var5.add("&8" + var4.getWorld().getName() + " " + (int)var4.getX() + ", " + (int)var4.getY() + ", " + (int)var4.getZ());
      }

      var5.add("&cThis cannot be undone (a backup is kept).");
      var3.setItem(13, item(Material.BLUE_BED, "&cDelete " + this.homeDisplayName(var2), var5));
      var3.setItem(11, item(Material.RED_STAINED_GLASS_PANE, "&cCancel", List.of("&fClick To Cancel")));
      var3.setItem(15, item(Material.LIME_STAINED_GLASS_PANE, "&aConfirm", List.of("&fClick To Delete")));
      var1.openInventory(var3);
   }

   private void handleDirectHomeTeleport(Player var1, String var2) {
      if (!var1.hasPermission("pizzasmp.combat.bypass") && this.isCombatTagged(var1)) {
         var1.sendMessage(color("&cYou are in combat. Wait &e" + this.remainingCombatSeconds(var1) + "s &cbefore teleporting home."));
      } else {
         File var3 = this.getSetHomeDataFile(var1);
         if (var3 != null && var3.exists()) {
            YamlConfiguration var4 = YamlConfiguration.loadConfiguration(var3);
            ArrayList var5 = new ArrayList(var4.getStringList("homes"));
            if (var5.isEmpty()) {
               var1.sendMessage(color("&cNo homes found."));
            } else {
               String var6 = this.resolveHomeArg(var2, var5, var4);
               if (var6 == null) {
                  var1.sendMessage(color("&cHome not found. Use a valid home number or name."));
               } else {
                  Location var7 = this.readHomeLocation(var4, var6);
                  if (var7 == null) {
                     var1.sendMessage(color("&cThat home is invalid or its world is missing."));
                  } else {
                     var1.closeInventory();
                     this.startCountdownTeleport(var1, var7, "&fTeleported To Your Home.");
                  }
               }
            }
         } else {
            var1.sendMessage(color("&cNo homes found."));
         }
      }
   }

   private boolean useDialogUi(Player var1) {
      if (this.isBedrockPlayer(var1)) {
         return false;
      } else {
         try {
            Class var2 = Class.forName("com.viaversion.viaversion.api.Via");
            Object var3 = var2.getMethod("getAPI").invoke(null);
            int var4 = (Integer)var3.getClass().getMethod("getPlayerVersion", UUID.class).invoke(var3, var1.getUniqueId());
            return var4 >= 771;
         } catch (Throwable var5) {
            return true;
         }
      }
   }

   private boolean isBedrockPlayer(Player var1) {
      try {
         if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return false;
         } else {
            Class var2 = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object var3 = var2.getMethod("getInstance").invoke(null);
            return (Boolean)var2.getMethod("isFloodgatePlayer", UUID.class).invoke(var3, var1.getUniqueId());
         }
      } catch (Throwable var4) {
         return false;
      }
   }

   private DialogAction dialogClick(Consumer<Player> var1) {
      return DialogAction.customClick((var2, var3) -> {
         if (var3 instanceof Player var4) {
            Bukkit.getScheduler().runTask(this, () -> var1.accept(var4));
         }
      }, (Options)Options.builder().build());
   }

   private ActionButton dialogButton(Component var1, String var2, int var3, Consumer<Player> var4) {
      Builder var5 = ActionButton.builder(var1).width(var3);
      if (var2 != null) {
         var5.tooltip(Component.text(var2, NamedTextColor.GRAY));
      }

      if (var4 != null) {
         var5.action(this.dialogClick(var4));
      }

      return var5.build();
   }

   private Dialog buildDialog(Component var1, List<DialogBody> var2, List<DialogInput> var3, DialogType var4) {
      return Dialog.create(
         var4x -> ((io.papermc.paper.registry.data.dialog.DialogRegistryEntry.Builder)var4x.empty())
               .base(DialogBase.builder(var1).canCloseWithEscape(true).pause(false).afterAction(DialogAfterAction.NONE).body(var2).inputs(var3).build())
               .type(var4)
      );
   }

   private String homeDisplayName(String var1) {
      return var1.matches("home\\d+") ? "Home " + var1.substring(4) : Character.toUpperCase(var1.charAt(0)) + var1.substring(1);
   }

   private String homeWhere(FileConfiguration var1, String var2) {
      Location var3 = this.readHomeLocation(var1, var2);
      return var3 != null && var3.getWorld() != null
         ? var3.getWorld().getName() + "  " + (int)var3.getX() + ", " + (int)var3.getY() + ", " + (int)var3.getZ()
         : "invalid";
   }

   private String homeIcon(FileConfiguration var1, String var2) {
      String var3 = var1.getString(var2 + ".icon");
      return var3 != null && !var3.isBlank() ? var3 : "\ud83d\udecf";
   }

   private String[] buildHomeSlots(List<String> var1, int var2) {
      int var3 = Math.max(var2, var1.size());
      String[] var4 = new String[var3];
      ArrayList var5 = new ArrayList();

      for (String var7 : var1) {
         int var8 = -1;
         if (var7.matches("home\\d+")) {
            try {
               var8 = Integer.parseInt(var7.substring(4)) - 1;
            } catch (NumberFormatException var10) {
            }
         }

         if (var8 >= 0 && var8 < var3 && var4[var8] == null) {
            var4[var8] = var7;
         } else {
            var5.add(var7);
         }
      }

      int var11 = 0;

      for (String var13 : (Iterable<String>) var5) {
         while (var11 < var3 && var4[var11] != null) {
            var11++;
         }

         if (var11 < var3) {
            var4[var11++] = var13;
         }
      }

      return var4;
   }

   private ActionButton homeSlotButton(FileConfiguration var1, String var2, int var3, int var4) {
      return var2 != null
         ? this.dialogButton(
            Component.text(this.homeIcon(var1, var2) + " " + this.homeDisplayName(var2), NamedTextColor.WHITE),
            null,
            var4,
            var2x -> this.openHomeDetailDialog(var2x, var2)
         )
         : this.dialogButton(Component.text("New Home", NamedTextColor.GRAY), "Set a home at your current location", var4, var2x -> {
            if (this.setHomeInSlot(var2x, var3)) {
               var2x.sendMessage(color("&7Home &bhome" + (var3 + 1) + "&7 set."));
            } else {
               var2x.sendMessage(color("&cFailed to set that home."));
            }

            this.openHomesDialog(var2x);
         });
   }

   private void openHomesDialogFresh(Player var1) {
      this.homesDialogExpanded.remove(var1.getUniqueId());
      this.openHomesDialog(var1);
   }

   private void openHomesDialog(Player var1) {
      if (!this.useDialogUi(var1)) {
         this.setTargetToSelf(var1);
         this.openHomesMenu(var1, var1);
      } else {
         Integer var2 = this.homesDialogExpanded.get(var1.getUniqueId());
         if (var2 != null && var2 > 0) {
            this.openAllHomesDialog(var1, var2);
         } else {
            File var3 = this.getSetHomeDataFile(var1);
            YamlConfiguration var4 = var3 != null && var3.exists() ? YamlConfiguration.loadConfiguration(var3) : new YamlConfiguration();
            ArrayList var5 = new ArrayList(var4.getStringList("homes"));
            int var6 = this.allowedHomes(var1);
            String[] var7 = this.buildHomeSlots(var5, var6);
            ArrayList var8 = new ArrayList();
            boolean var9 = var7.length > 4;
            int var10 = var9 ? 150 : 200;

            for (int var11 = 0; var11 < var7.length; var11++) {
               var8.add(this.homeSlotButton(var4, var7[var11], var11, var10));
            }

            if (var7.length < 27) {
               int var12 = var6 < 9 ? 9 : 27;
               var8.add(
                  this.dialogButton(Component.text("Show More", DIALOG_BRAND), "View locked home slots", var10, var2x -> this.openAllHomesDialog(var2x, var12))
               );
            }

            Dialog var13 = this.buildDialog(
               Component.text("Homes", DIALOG_BRAND),
               List.of(DialogBody.plainMessage(Component.text(var5.size() + "/" + var6 + " homes used", NamedTextColor.GRAY))),
               List.of(),
               DialogType.multiAction(var8).columns(var9 ? 3 : 1).exitAction(this.dialogButton(Component.text("Close"), null, var10, null)).build()
            );
            var1.showDialog(var13);
         }
      }
   }

   private ActionButton lockedSlotButton(int var1, int var2, int var3) {
      String var4 = var1 < 9 ? "Pizza+" : "Pizza++";
      return this.dialogButton(Component.text("Locked", NamedTextColor.RED), "Buy " + var4 + " for more home slots", var2, var3x -> {
         var3x.sendMessage(color("&cThis home slot requires &e" + var4 + "&c."));
         this.openAllHomesDialog(var3x, var3);
      });
   }

   private void openAllHomesDialog(Player var1, int var2) {
      this.homesDialogExpanded.put(var1.getUniqueId(), var2);
      File var3 = this.getSetHomeDataFile(var1);
      YamlConfiguration var4 = var3 != null && var3.exists() ? YamlConfiguration.loadConfiguration(var3) : new YamlConfiguration();
      ArrayList var5 = new ArrayList(var4.getStringList("homes"));
      int var6 = this.allowedHomes(var1);
      int var7 = Math.max(var2, Math.max(var6, var5.size()));
      if (var2 < 27) {
         var7 = Math.min(var7, Math.max(var2, var5.size()));
      }

      String[] var8 = this.buildHomeSlots(var5, var7);
      ArrayList var9 = new ArrayList();

      for (int var10 = 0; var10 < var8.length; var10++) {
         if (var8[var10] == null && var10 >= var6) {
            var9.add(this.lockedSlotButton(var10, 150, var2));
         } else {
            var9.add(this.homeSlotButton(var4, var8[var10], var10, 150));
         }
      }

      if (var8.length < 27) {
         var9.add(this.dialogButton(Component.text("Show More", DIALOG_BRAND), "View all 27 home slots", 150, var1x -> this.openAllHomesDialog(var1x, 27)));
      }

      Dialog var11 = this.buildDialog(
         Component.text("All Homes", DIALOG_BRAND),
         List.of(DialogBody.plainMessage(Component.text(var5.size() + "/" + var6 + " homes used", NamedTextColor.GRAY))),
         List.of(),
         DialogType.multiAction(var9).columns(3).exitAction(this.dialogButton(Component.text("Back"), null, 150, this::openHomesDialogFresh)).build()
      );
      var1.showDialog(var11);
   }

   private void openHomeDetailDialog(Player var1, String var2) {
      File var3 = this.getSetHomeDataFile(var1);
      YamlConfiguration var4 = var3 != null && var3.exists() ? YamlConfiguration.loadConfiguration(var3) : new YamlConfiguration();
      if (!var4.getStringList("homes").contains(var2)) {
         this.openHomesDialog(var1);
      } else {
         ArrayList var5 = new ArrayList();
         var5.add(this.dialogButton(Component.text("Teleport", NamedTextColor.GREEN), null, 200, var2x -> {
            var2x.closeDialog();
            this.handleDirectHomeTeleport(var2x, var2);
         }));
         var5.add(
            this.dialogButton(
               Component.text("Change Icon", NamedTextColor.WHITE), "Pick a new icon for this home", 200, var2x -> this.openIconPickerDialog(var2x, var2)
            )
         );
         var5.add(
            this.dialogButton(Component.text("Rename", NamedTextColor.WHITE), "Give this home a new name", 200, var2x -> this.openRenameDialog(var2x, var2))
         );
         var5.add(this.dialogButton(Component.text("Delete", NamedTextColor.RED), "Delete this home", 200, var2x -> this.openDeleteConfirmDialog(var2x, var2)));
         Dialog var6 = this.buildDialog(
            Component.text(this.homeIcon(var4, var2) + " " + this.homeDisplayName(var2), DIALOG_BRAND),
            List.of(),
            List.of(),
            DialogType.multiAction(var5).columns(1).exitAction(this.dialogButton(Component.text("Back"), null, 200, this::openHomesDialog)).build()
         );
         var1.showDialog(var6);
      }
   }

   private void openIconPickerDialog(Player var1, String var2) {
      ArrayList var3 = new ArrayList();

      for (String var5 : HOME_ICONS) {
         var3.add(this.dialogButton(Component.text(var5, NamedTextColor.WHITE), null, 45, var3x -> {
            this.saveHomeIcon(var3x, var2, var5);
            this.openHomeDetailDialog(var3x, var2);
         }));
      }

      Dialog var6 = this.buildDialog(
         Component.text("Change Icon", DIALOG_BRAND),
         List.of(DialogBody.plainMessage(Component.text("Pick an icon for " + this.homeDisplayName(var2) + ".", NamedTextColor.GRAY))),
         List.of(),
         DialogType.multiAction(var3)
            .columns(4)
            .exitAction(this.dialogButton(Component.text("Back"), null, 200, var2x -> this.openHomeDetailDialog(var2x, var2)))
            .build()
      );
      var1.showDialog(var6);
   }

   private void saveHomeIcon(Player var1, String var2, String var3) {
      File var4 = this.getSetHomeDataFile(var1);
      if (var4 != null && var4.exists()) {
         YamlConfiguration var5 = YamlConfiguration.loadConfiguration(var4);
         if (var5.getStringList("homes").contains(var2)) {
            var5.set(var2 + ".icon", var3);

            try {
               var5.save(var4);
            } catch (Exception var7) {
               this.getLogger().warning("Failed to save home icon for " + var1.getName() + ": " + var7.getMessage());
            }
         }
      }
   }

   private void openRenameDialog(Player var1, String var2) {
      this.openRenameDialog(var1, var2, null, var2);
   }

   private void openRenameDialog(Player var1, String var2, String var3, String var4) {
      List var5 = var3 == null ? List.of() : List.of(DialogBody.plainMessage(Component.text(var3, NamedTextColor.RED)));
      List var6 = List.of(
         DialogInput.text("name", Component.text("New Name", NamedTextColor.GRAY))
            .width(200)
            .maxLength(20)
            .initial(var4 != null && !var4.isBlank() ? var4 : var2)
            .build()
      );
      ActionButton var7 = ActionButton.builder(Component.text("Save", NamedTextColor.GREEN)).width(110).action(DialogAction.customClick((var2x, var3x) -> {
         if (var3x instanceof Player var4x) {
            String var5x = var2x.getText("name");
            Bukkit.getScheduler().runTask(this, () -> this.handleHomeRename(var4x, var2, var5x));
         }
      }, (Options)Options.builder().build())).build();
      ActionButton var8 = this.dialogButton(Component.text("Cancel"), null, 110, var2x -> this.openHomeDetailDialog(var2x, var2));
      Dialog var9 = this.buildDialog(Component.text("Rename " + this.homeDisplayName(var2), DIALOG_BRAND), var5, var6, DialogType.confirmation(var7, var8));
      var1.showDialog(var9);
   }

   private void handleHomeRename(Player var1, String var2, String var3) {
      String var4 = var3 == null ? "" : var3.trim().toLowerCase(Locale.ROOT);
      if (var4.equals(var2)) {
         this.openHomeDetailDialog(var1, var2);
      } else if (!HOME_NAME_PATTERN.matcher(var4).matches()) {
         this.openRenameDialog(var1, var2, "Names are 1-20 characters: letters, numbers, - or _", var4);
      } else {
         File var5 = this.getSetHomeDataFile(var1);
         if (var5 != null && var5.exists()) {
            YamlConfiguration var6 = YamlConfiguration.loadConfiguration(var5);
            ArrayList var7 = new ArrayList(var6.getStringList("homes"));
            int var8 = var7.indexOf(var2);
            if (var8 < 0) {
               this.openHomesDialog(var1);
            } else if (var7.contains(var4)) {
               this.openRenameDialog(var1, var2, "You already have a home named " + var4, var4);
            } else {
               ConfigurationSection var9 = var6.getConfigurationSection(var2);
               if (var9 != null) {
                  for (String var11 : var9.getKeys(false)) {
                     var6.set(var4 + "." + var11, var9.get(var11));
                  }
               }

               var6.set(var2, null);
               var7.set(var8, var4);
               var6.set("homes", var7);

               try {
                  var6.save(var5);
               } catch (Exception var12) {
                  this.getLogger().warning("Failed to rename home for " + var1.getName() + ": " + var12.getMessage());
                  var1.sendMessage(color("&cFailed to rename that home."));
                  this.openHomeDetailDialog(var1, var2);
                  return;
               }

               this.openHomeDetailDialog(var1, var4);
            }
         } else {
            this.openHomesDialog(var1);
         }
      }
   }

   private void openDeleteConfirmDialog(Player var1, String var2) {
      ActionButton var3 = this.dialogButton(Component.text("Delete", NamedTextColor.RED), null, 110, var2x -> {
         this.handleDelHomeCommand(var2x, var2);
         this.openHomesDialog(var2x);
      });
      ActionButton var4 = this.dialogButton(Component.text("Cancel"), null, 110, var2x -> this.openHomeDetailDialog(var2x, var2));
      Dialog var5 = this.buildDialog(
         Component.text("Delete " + this.homeDisplayName(var2) + "?", NamedTextColor.RED),
         List.of(DialogBody.plainMessage(Component.text("This cannot be undone.", NamedTextColor.GRAY))),
         List.of(),
         DialogType.confirmation(var3, var4)
      );
      var1.showDialog(var5);
   }

   private int allowedHomes(Player var1) {
      if (var1.hasPermission("pizzasmp.pluginadmin") || var1.hasPermission("pizzasmp.homes.27")) {
         return 27;
      } else if (var1.hasPermission("pizzasmp.homes.9")) {
         return 9;
      } else {
         return var1.hasPermission("sethome.maxhomes.5") ? 5 : 3;
      }
   }

   private boolean canViewOtherHomes(Player var1) {
      return var1.hasPermission("pizzasmp.gtp") || var1.hasPermission("sethome.admin");
   }

   private void shiftTarget(Player var1, int var2) {
      List var3 = this.getKnownPlayersWithHomes();
      if (var3.isEmpty()) {
         this.setTargetToSelf(var1);
         this.openHomesMenu(var1, var1);
      } else {
         int var4 = this.adminTargetIndex.getOrDefault(var1.getUniqueId(), this.indexOfTarget(var3, var1.getUniqueId()));
         if (var4 < 0 || var4 >= var3.size()) {
            var4 = this.indexOfTarget(var3, var1.getUniqueId());
            if (var4 < 0) {
               var4 = 0;
            }
         }

         int var5 = (var4 + var2) % var3.size();
         if (var5 < 0) {
            var5 += var3.size();
         }

         this.adminTargetIndex.put(var1.getUniqueId(), var5);
         this.openHomesMenu(var1, (OfflinePlayer)var3.get(var5));
      }
   }

   private void setTargetToSelf(Player var1) {
      List var2 = this.getKnownPlayersWithHomes();
      int var3 = this.indexOfTarget(var2, var1.getUniqueId());
      if (var3 < 0) {
         var3 = 0;
      }

      this.adminTargetIndex.put(var1.getUniqueId(), var3);
   }

   private OfflinePlayer getCurrentTarget(Player var1) {
      if (!this.canViewOtherHomes(var1)) {
         return var1;
      } else {
         List var2 = this.getKnownPlayersWithHomes();
         if (var2.isEmpty()) {
            return var1;
         } else {
            int var3 = this.adminTargetIndex.getOrDefault(var1.getUniqueId(), this.indexOfTarget(var2, var1.getUniqueId()));
            if (var3 < 0 || var3 >= var2.size()) {
               var3 = this.indexOfTarget(var2, var1.getUniqueId());
               if (var3 < 0) {
                  var3 = 0;
               }

               this.adminTargetIndex.put(var1.getUniqueId(), var3);
            }

            return (OfflinePlayer)var2.get(var3);
         }
      }
   }

   private OfflinePlayer normalizeTarget(Player var1, OfflinePlayer var2) {
      if (!this.canViewOtherHomes(var1)) {
         return var1;
      } else {
         List var3 = this.getKnownPlayersWithHomes();
         if (var3.isEmpty()) {
            return var1;
         } else {
            UUID var4 = var2 == null ? var1.getUniqueId() : var2.getUniqueId();
            int var5 = this.indexOfTarget(var3, var4);
            if (var5 < 0) {
               var5 = this.indexOfTarget(var3, var1.getUniqueId());
            }

            if (var5 < 0) {
               var5 = 0;
            }

            this.adminTargetIndex.put(var1.getUniqueId(), var5);
            return (OfflinePlayer)var3.get(var5);
         }
      }
   }

   private List<OfflinePlayer> getKnownPlayersWithHomes() {
      ArrayList var1 = new ArrayList();

      for (Player var3 : Bukkit.getOnlinePlayers()) {
         if (this.isLikelyRealPlayerName(var3.getName())) {
            File var4 = this.getSetHomeDataFile(var3);
            if (var4 != null && var4.exists()) {
               var1.add(var3);
            }
         }
      }

      var1.sort(Comparator.comparing(PizzaAdminTools::safeName, String.CASE_INSENSITIVE_ORDER));
      return var1;
   }

   private boolean isLikelyRealPlayerName(String var1) {
      return var1 == null ? false : PLAYER_NAME_PATTERN.matcher(var1).matches();
   }

   private int indexOfTarget(List<OfflinePlayer> var1, UUID var2) {
      for (int var3 = 0; var3 < var1.size(); var3++) {
         if (((OfflinePlayer)var1.get(var3)).getUniqueId().equals(var2)) {
            return var3;
         }
      }

      return -1;
   }

   private List<String> getHomes(OfflinePlayer var1) {
      File var2 = this.getSetHomeDataFile(var1);
      if (var2 != null && var2.exists()) {
         YamlConfiguration var3 = YamlConfiguration.loadConfiguration(var2);
         return new ArrayList<>(var3.getStringList("homes"));
      } else {
         return new ArrayList<>();
      }
   }

   private File getSetHomeDataDir() {
      File var1 = this.getDataFolder().getParentFile();
      if (var1 == null) {
         return new File("plugins/SetHome/data");
      } else {
         File var2 = new File(var1, "SetHome/data");
         if (!var2.exists()) {
            var2 = new File(var1, "sethome/data");
         }

         return var2;
      }
   }

   private void initTransferDestinations() {
      this.transferDestinationsFile = new File(this.getDataFolder(), "transfer-destinations.yml");
      if (!this.transferDestinationsFile.getParentFile().exists() && !this.transferDestinationsFile.getParentFile().mkdirs()) {
         this.getLogger().warning("Failed to create data folder for transfer destinations.");
      }

      if (!this.transferDestinationsFile.exists()) {
         YamlConfiguration var1 = new YamlConfiguration();
         var1.set("destinations.lobby.host", "127.0.0.1");
         var1.set("destinations.lobby.port", 25566);
         var1.set("destinations.main.host", "127.0.0.1");
         var1.set("destinations.main.port", 25569);

         try {
            var1.save(this.transferDestinationsFile);
         } catch (IOException var3) {
            this.getLogger().warning("Failed to write transfer-destinations.yml: " + var3.getMessage());
         }
      }

      this.transferDestinationsConfig = YamlConfiguration.loadConfiguration(this.transferDestinationsFile);
   }

   private void initMaintenanceTransferState() {
      this.maintenanceTransferStateFile = new File(this.getDataFolder(), "maintenance-transfer.yml");
      if (!this.maintenanceTransferStateFile.exists()) {
         YamlConfiguration var1 = new YamlConfiguration();
         var1.set("active", false);
         var1.set("destination", "maintenance");
         var1.set("transferred", new ArrayList());
         var1.set(
            "start-message",
            List.of("&cMaintenance is active.", "&7You are being moved to the maintenance server.", "&7When maintenance ends, reconnect here.")
         );
         var1.set(
            "kick-message",
            List.of(
               "&cServer maintenance is active.",
               "&7Please join the maintenance server: &e%destination%",
               "&7You cannot join this server until maintenance is complete."
            )
         );

         try {
            var1.save(this.maintenanceTransferStateFile);
         } catch (IOException var3) {
            this.getLogger().warning("Failed to write maintenance-transfer.yml: " + var3.getMessage());
         }
      }

      this.maintenanceTransferStateConfig = YamlConfiguration.loadConfiguration(this.maintenanceTransferStateFile);
   }

   private List<String> getTransferDestinationNames() {
      if (this.transferDestinationsConfig == null) {
         return Collections.emptyList();
      } else if (this.transferDestinationsConfig.getConfigurationSection("destinations") == null) {
         return Collections.emptyList();
      } else {
         ArrayList var1 = new ArrayList(this.transferDestinationsConfig.getConfigurationSection("destinations").getKeys(false));
         var1.sort(String.CASE_INSENSITIVE_ORDER);
         return var1;
      }
   }

   private String getMaintenanceTransferDestinationName() {
      return this.maintenanceTransferStateConfig == null ? "maintenance" : this.maintenanceTransferStateConfig.getString("destination", "maintenance");
   }

   private boolean isMaintenanceTransferActive() {
      return this.maintenanceTransferStateConfig != null && this.maintenanceTransferStateConfig.getBoolean("active", false);
   }

   private void setMaintenanceTransferActive(boolean var1) {
      if (this.maintenanceTransferStateConfig != null) {
         this.maintenanceTransferStateConfig.set("active", var1);
      }
   }

   private Set<String> getMaintenanceTransferred() {
      return this.maintenanceTransferStateConfig == null ? new HashSet<>() : new HashSet<>(this.maintenanceTransferStateConfig.getStringList("transferred"));
   }

   private void saveMaintenanceTransferred(Set<String> var1) {
      if (this.maintenanceTransferStateConfig != null) {
         this.maintenanceTransferStateConfig.set("transferred", new ArrayList(var1));
      }
   }

   private void saveMaintenanceTransferState() {
      if (this.maintenanceTransferStateConfig != null && this.maintenanceTransferStateFile != null) {
         try {
            this.maintenanceTransferStateConfig.save(this.maintenanceTransferStateFile);
         } catch (IOException var2) {
            this.getLogger().warning("Failed to save maintenance-transfer.yml: " + var2.getMessage());
         }
      }
   }

   private boolean shouldStayDuringMaintenance(Player var1) {
      return var1.hasPermission("pizzasmp.maintenance.stay") || var1.hasPermission("maintenance.bypass") || var1.isOp();
   }

   private List<String> getMaintenanceStartMessages(String var1, int var2) {
      if (this.maintenanceTransferStateConfig == null) {
         return List.of("&cMaintenance is active.", "&7You are being moved to " + var1 + ":" + var2 + ".");
      } else {
         List var3 = this.maintenanceTransferStateConfig.getStringList("start-message");
         if (var3.isEmpty()) {
            var3 = List.of("&cMaintenance is active.", "&7You are being moved to &e" + var1 + ":" + var2 + "&7.");
         }

         String var4 = var1 + ":" + var2;
         ArrayList var5 = new ArrayList(var3.size());

         for (String var7 : (Iterable<String>) var3) {
            var5.add(var7.replace("%destination%", var4));
         }

         return var5;
      }
   }

   private String buildMaintenanceKickMessage() {
      if (this.maintenanceTransferStateConfig == null) {
         return color("&cServer maintenance is active.");
      } else {
         PizzaAdminTools.TransferDestination var1 = this.parseTransferDestination(this.getMaintenanceTransferDestinationName(), null);
         String var2 = var1 == null ? this.getMaintenanceTransferDestinationName() : var1.host + ":" + var1.port;
         List var3 = this.maintenanceTransferStateConfig.getStringList("kick-message");
         if (var3.isEmpty()) {
            var3 = List.of("&cServer maintenance is active.", "&7Join: &e%destination%");
         }

         ArrayList var4 = new ArrayList(var3.size());

         for (String var6 : (Iterable<String>) var3) {
            var4.add(color(var6.replace("%destination%", var2)));
         }

         return String.join("\n", var4);
      }
   }

   private PizzaAdminTools.TransferDestination parseTransferDestination(String var1, String var2) {
      if (var1 == null || var1.isBlank()) {
         return null;
      } else if (var2 == null || var2.isBlank()) {
         int var3 = var1.lastIndexOf(58);
         if (var3 > 0 && var3 < var1.length() - 1) {
            String var7 = var1.substring(0, var3);
            String var8 = var1.substring(var3 + 1);
            return !isLikelyPort(var8) ? null : new PizzaAdminTools.TransferDestination(var7, Integer.parseInt(var8));
         } else if (this.transferDestinationsConfig == null) {
            return null;
         } else {
            String var4 = "destinations." + var1.toLowerCase(Locale.ROOT) + ".";
            String var5 = this.transferDestinationsConfig.getString(var4 + "host");
            int var6 = this.transferDestinationsConfig.getInt(var4 + "port", -1);
            return var5 != null && !var5.isBlank() && var6 >= 1 && var6 <= 65535 ? new PizzaAdminTools.TransferDestination(var5, var6) : null;
         }
      } else {
         return !isLikelyPort(var2) ? null : new PizzaAdminTools.TransferDestination(var1, Integer.parseInt(var2));
      }
   }

   private static boolean isLikelyPort(String var0) {
      if (var0 != null && !var0.isBlank()) {
         try {
            int var1 = Integer.parseInt(var0);
            return var1 >= 1 && var1 <= 65535;
         } catch (NumberFormatException var2) {
            return false;
         }
      } else {
         return false;
      }
   }

   private File getSetHomeDataFile(OfflinePlayer var1) {
      return var1 != null && var1.getUniqueId() != null ? new File(this.getSetHomeDataDir(), var1.getUniqueId() + ".yml") : null;
   }

   private String[] loadDbCreds() {
      if (this.cachedDbCreds != null) {
         return this.cachedDbCreds;
      } else {
         File var1 = new File(this.getDataFolder().getParentFile(), "PizzaNetworkCore/config.yml");
         YamlConfiguration var2 = YamlConfiguration.loadConfiguration(var1);
         String var3 = var2.getString("database.host", "127.0.0.1");
         int var4 = var2.getInt("database.port", 3306);
         String var5 = var2.getString("database.name", "smpcore");
         String var6 = var2.getString("database.user", "smpcore");
         String var7 = var2.getString("database.password", "");
         this.cachedDbCreds = new String[]{var3 + ":" + var4, var5, var6, var7};
         return this.cachedDbCreds;
      }
   }

   private String resolveHomeArg(String var1, List<String> var2, FileConfiguration var3) {
      try {
         int var4 = Integer.parseInt(var1);
         String var5 = "home" + var4;
         return var4 < 1 || var4 > 27 || !var2.contains(var5) && !var3.contains(var5 + ".x") ? null : var5;
      } catch (NumberFormatException var6) {
         if (var2.contains(var1)) {
            return var1;
         } else if (var3.contains(var1 + ".x") && var3.contains(var1 + ".y") && var3.contains(var1 + ".z")) {
            return var1;
         } else {
            // Typo-tolerant fallback ("/home loodrop" -> lootdrop): unique case-insensitive
            // prefix first, then closest home name within edit distance 2. Covers /home AND
            // /delhome since both resolve through here.
            String q = var1.toLowerCase();
            String prefixHit = null;
            int prefixCount = 0;
            for (String h : var2) {
               if (h.toLowerCase().startsWith(q)) { prefixHit = h; prefixCount++; }
            }
            if (prefixCount == 1) return prefixHit;
            String best = null;
            int bestD = 3;
            for (String h : var2) {
               int d = homeNameDistance(q, h.toLowerCase(), 2);
               if (d >= 0 && d < bestD) { bestD = d; best = h; }
            }
            return best;
         }
      }
   }

   // Small bounded Levenshtein for home-name typo matching (-1 when over max).
   private static int homeNameDistance(String a, String b, int max) {
      int n = a.length(), m = b.length();
      if (Math.abs(n - m) > max) return -1;
      int[] prev = new int[m + 1];
      int[] cur = new int[m + 1];
      for (int j = 0; j <= m; j++) prev[j] = j;
      for (int i = 1; i <= n; i++) {
         cur[0] = i;
         int rowMin = cur[0];
         for (int j = 1; j <= m; j++) {
            int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
            cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            rowMin = Math.min(rowMin, cur[j]);
         }
         if (rowMin > max) return -1;
         int[] t = prev; prev = cur; cur = t;
      }
      return prev[m] <= max ? prev[m] : -1;
   }

   private Location readHomeLocation(OfflinePlayer var1, String var2) {
      File var3 = this.getSetHomeDataFile(var1);
      return var3 != null && var3.exists() ? this.readHomeLocation(YamlConfiguration.loadConfiguration(var3), var2) : null;
   }

   private Location readHomeLocation(FileConfiguration var1, String var2) {
      String var3 = var1.getString(var2 + ".world");
      World var4 = var3 == null ? null : Bukkit.getWorld(var3);
      if (var4 == null) {
         return null;
      } else if (var1.contains(var2 + ".x") && var1.contains(var2 + ".y") && var1.contains(var2 + ".z")) {
         double var5 = var1.getDouble(var2 + ".x");
         double var7 = var1.getDouble(var2 + ".y");
         double var9 = var1.getDouble(var2 + ".z");
         float var11 = (float)var1.getDouble(var2 + ".yaw", 0.0);
         float var12 = (float)var1.getDouble(var2 + ".pitch", 0.0);
         return new Location(var4, var5, var7, var9, var11, var12);
      } else {
         return null;
      }
   }

   private boolean isHomesMenu(String var1) {
      String var2 = ChatColor.stripColor(var1);
      String var3 = ChatColor.stripColor(color(HOME_MENU_TITLE));
      return var2 != null && var3 != null && var2.equalsIgnoreCase(var3);
   }

   private boolean isHomeDeleteMenu(String var1) {
      String var2 = ChatColor.stripColor(var1);
      String var3 = ChatColor.stripColor(color("&8Confirm Home Deletion"));
      return var2 != null && var3 != null && var2.equalsIgnoreCase(var3);
   }

   private boolean isSusMenu(String var1) {
      String var2 = ChatColor.stripColor(var1);
      String var3 = ChatColor.stripColor(color(SUS_MENU_TITLE));
      return var2 != null && var3 != null && var2.startsWith(var3);
   }

   private static int indexOfSlot(int[] var0, int var1) {
      for (int var2 = 0; var2 < var0.length; var2++) {
         if (var0[var2] == var1) {
            return var2;
         }
      }

      return -1;
   }

   private static ItemStack item(Material var0, String var1, List<String> var2) {
      ItemStack var3 = new ItemStack(var0);
      ItemMeta var4 = var3.getItemMeta();
      if (var4 != null) {
         var4.setDisplayName(color(var1));
         if (var2 != null && !var2.isEmpty()) {
            ArrayList var5 = new ArrayList(var2.size());

            for (String var7 : var2) {
               var5.add(color(var7));
            }

            var4.setLore(var5);
         }

         var3.setItemMeta(var4);
      }

      return var3;
   }

   private static ItemStack playerHead(OfflinePlayer var0, String var1, List<String> var2) {
      ItemStack var3 = new ItemStack(Material.PLAYER_HEAD);
      if (var3.getItemMeta() instanceof SkullMeta var5) {
         var5.setDisplayName(color(var1));
         if (var0 != null) {
            var5.setOwningPlayer(var0);
         }

         if (var2 != null && !var2.isEmpty()) {
            ArrayList var6 = new ArrayList(var2.size());

            for (String var8 : var2) {
               var6.add(color(var8));
            }

            var5.setLore(var6);
         }

         var3.setItemMeta(var5);
         return var3;
      } else {
         return item(Material.PLAYER_HEAD, var1, var2);
      }
   }

   private void openSusMenu(Player var1, int var2) {
      List var3 = this.loadRecentSusEntries();
      int var4 = Math.max(0, (var3.size() - 1) / 45);
      int var5 = Math.max(0, Math.min(var2, var4));
      this.susMenuPages.put(var1.getUniqueId(), var5);
      Inventory var6 = Bukkit.createInventory(null, 54, color(SUS_MENU_TITLE + " &7(Page " + (var5 + 1) + "/" + (var4 + 1) + ")"));
      ItemStack var7 = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());

      for (int var8 = 0; var8 < var6.getSize(); var8++) {
         var6.setItem(var8, var7);
      }

      int var11 = var5 * 45;

      for (int var9 = 0; var9 < 45; var9++) {
         int var10 = var11 + var9;
         if (var10 >= var3.size()) {
            break;
         }

         var6.setItem(var9, this.buildSusItem((PizzaAdminTools.SusEntry)var3.get(var10)));
      }

      if (var3.isEmpty()) {
         var6.setItem(22, item(Material.LIME_DYE, "&aNo Recent Grim Flags", List.of("&7No players have been flagged in the last 30 minutes.")));
      }

      var6.setItem(
         45,
         var5 > 0
            ? item(Material.ARROW, "&ePrevious Page", List.of("&7Go back one page."))
            : item(Material.GRAY_STAINED_GLASS_PANE, "&8Previous Page", List.of())
      );
      var6.setItem(49, item(Material.NETHER_STAR, "&bRefresh", List.of("&7Reload Grim suspects from the last 30 minutes.")));
      var6.setItem(
         53,
         var5 < var4
            ? item(Material.ARROW, "&eNext Page", List.of("&7Go forward one page."))
            : item(Material.BARRIER, "&cClose", List.of("&7Close this menu."))
      );
      var1.openInventory(var6);
   }

   private ItemStack buildSusItem(PizzaAdminTools.SusEntry var1) {
      OfflinePlayer var2 = Bukkit.getOfflinePlayer(var1.uuid);
      Player var3 = Bukkit.getPlayer(var1.uuid);
      ArrayList var4 = new ArrayList();
      var4.add("&7Last Flag: &f" + formatAge(var1.latestAt) + " ago");
      var4.add("&7Flags: &f" + var1.flagCount);
      var4.add("&7Latest Check: &f" + var1.latestCheck);
      var4.add("&7Highest VL: &f" + var1.highestVl);
      var4.add("&7Status: " + (var3 != null ? "&aOnline" : "&cOffline"));
      if (var1.latestVerbose != null && !var1.latestVerbose.isBlank()) {
         var4.add("&7Detail:");

         for (String var6 : wrapText(var1.latestVerbose, 30)) {
            var4.add("&f" + var6);
         }
      }

      var4.add("&7UUID: &f" + var1.uuid);
      var4.add(var3 != null ? "&eClick to teleport." : "&cPlayer is offline.");
      return playerHead(var2, (var3 != null ? "&a" : "&c") + var1.playerName, var4);
   }

   private void handleSusClick(Player var1, int var2) {
      int var3 = this.susMenuPages.getOrDefault(var1.getUniqueId(), 0);
      List var4 = this.loadRecentSusEntries();
      int var5 = Math.max(0, (var4.size() - 1) / 45);
      if (var2 < 45) {
         int var6 = var3 * 45 + var2;
         if (var6 >= 0 && var6 < var4.size()) {
            PizzaAdminTools.SusEntry var7 = (PizzaAdminTools.SusEntry)var4.get(var6);
            Player var8 = Bukkit.getPlayer(var7.uuid);
            if (var8 != null && var8.isOnline()) {
               var1.closeInventory();
               var1.teleport(var8, TeleportCause.COMMAND);
               var1.sendMessage(color("&aTeleported to &e" + var8.getName() + "&a."));
            } else {
               this.sendOfflinePlayerMessage(var1, var7.playerName);
               this.openSusMenu(var1, var3);
            }
         }
      } else if (var2 == 45 && var3 > 0) {
         this.openSusMenu(var1, var3 - 1);
      } else if (var2 == 49) {
         this.openSusMenu(var1, var3);
      } else {
         if (var2 == 53) {
            if (var3 < var5) {
               this.openSusMenu(var1, var3 + 1);
            } else {
               var1.closeInventory();
            }
         }
      }
   }

   private void sendConsoleSusPage(CommandSender var1, int var2) {
      List var3 = this.loadRecentSusEntries();
      if (var3.isEmpty()) {
         var1.sendMessage(color("&7No Grim suspects in the last 30 minutes."));
      } else {
         byte var4 = 10;
         int var5 = Math.max(0, (var3.size() - 1) / var4);
         int var6 = Math.max(0, Math.min(var2, var5));
         int var7 = var6 * var4;
         int var8 = Math.min(var3.size(), var7 + var4);
         var1.sendMessage(color(BRAND_SECTION + BRAND_DISPLAY + " Suspects &7(page " + (var6 + 1) + "/" + (var5 + 1) + ")"));

         for (int var9 = var7; var9 < var8; var9++) {
            PizzaAdminTools.SusEntry var10 = (PizzaAdminTools.SusEntry)var3.get(var9);
            var1.sendMessage(
               color(
                  "&f"
                     + var10.playerName
                     + " &7| &f"
                     + var10.latestCheck
                     + " &7| &fVL "
                     + var10.highestVl
                     + " &7| &f"
                     + var10.flagCount
                     + " flags &7| &f"
                     + formatAge(var10.latestAt)
                     + " ago"
               )
            );
         }
      }
   }

   private List<PizzaAdminTools.SusEntry> loadRecentSusEntries() {
      File var1 = this.findGrimViolationsDb();
      if (var1 != null && var1.exists()) {
         long var2 = System.currentTimeMillis() - 1800000L;
         HashMap var4 = new HashMap();
         String var5 = "SELECT hex(v.uuid) AS uuid_hex, c.check_name_string, v.verbose, v.vl, v.created_at FROM grim_history_violations v JOIN grim_history_check_names c ON c.id = v.check_name_id JOIN grim_history_servers s ON s.id = v.server_id WHERE s.server_name = ? AND v.created_at >= ? ORDER BY v.created_at DESC";

         try (
            Connection var6 = DriverManager.getConnection("jdbc:sqlite:" + var1.getAbsolutePath());
            PreparedStatement var7 = var6.prepareStatement(var5);
         ) {
            var7.setString(1, "FolksyPizza");
            var7.setLong(2, var2);

            try (ResultSet var8 = var7.executeQuery()) {
               while (var8.next()) {
                  UUID var9 = uuidFromHex(var8.getString("uuid_hex"));
                  if (var9 != null) {
                     String var10 = var8.getString("check_name_string");
                     String var11 = var8.getString("verbose");
                     int var12 = var8.getInt("vl");
                     long var13 = var8.getLong("created_at");
                     PizzaAdminTools.SusEntry var15 = (PizzaAdminTools.SusEntry)var4.get(var9);
                     if (var15 == null) {
                        OfflinePlayer var16 = Bukkit.getOfflinePlayer(var9);
                        var15 = new PizzaAdminTools.SusEntry(var9, safeName(var16), var13, var10, var11, var12, 0);
                        var4.put(var9, var15);
                     }

                     var15.flagCount++;
                     var15.highestVl = Math.max(var15.highestVl, var12);
                     if (var13 >= var15.latestAt) {
                        var15.latestAt = var13;
                        var15.latestCheck = var10 == null ? "Unknown" : var10;
                        var15.latestVerbose = var11 == null ? "" : var11;
                     }
                  }
               }
            }
         } catch (Exception var23) {
            this.getLogger().warning("Failed to load Grim suspects: " + var23.getMessage());
            return Collections.emptyList();
         }

         ArrayList var24 = new ArrayList(var4.values());
         var24.sort(Comparator.<PizzaAdminTools.SusEntry>comparingLong(var0 -> var0.latestAt).reversed().thenComparingInt(var0 -> -var0.flagCount));
         return var24;
      } else {
         return Collections.emptyList();
      }
   }

   private File findGrimViolationsDb() {
      File var1 = new File(this.getServer().getWorldContainer(), "plugins/GrimAC/violations.sqlite");
      if (var1.exists()) {
         return var1;
      } else {
         File var2 = new File(this.getServer().getWorldContainer(), "plugins/AC/GrimAC/violations.sqlite");
         return var2.exists() ? var2 : null;
      }
   }

   private static UUID uuidFromHex(String var0) {
      if (var0 != null && !var0.isBlank()) {
         String var1 = var0.replace("-", "").trim();
         if (var1.length() != 32) {
            return null;
         } else {
            try {
               return UUID.fromString(
                  var1.substring(0, 8)
                     + "-"
                     + var1.substring(8, 12)
                     + "-"
                     + var1.substring(12, 16)
                     + "-"
                     + var1.substring(16, 20)
                     + "-"
                     + var1.substring(20, 32)
               );
            } catch (IllegalArgumentException var3) {
               return null;
            }
         }
      } else {
         return null;
      }
   }

   private static String formatAge(long var0) {
      long var2 = Math.max(0L, (System.currentTimeMillis() - var0) / 1000L);
      if (var2 < 60L) {
         return var2 + "s";
      } else {
         long var4 = var2 / 60L;
         if (var4 < 60L) {
            return var4 + "m";
         } else {
            long var6 = var4 / 60L;
            return var6 < 24L ? var6 + "h" : var6 / 24L + "d";
         }
      }
   }

   private static List<String> wrapText(String var0, int var1) {
      if (var0 != null && !var0.isBlank()) {
         ArrayList var2 = new ArrayList();
         StringBuilder var3 = new StringBuilder();

         for (String var7 : var0.split("\\s+")) {
            if (var3.length() == 0) {
               var3.append(var7);
            } else if (var3.length() + 1 + var7.length() > var1) {
               var2.add(var3.toString());
               var3.setLength(0);
               var3.append(var7);
            } else {
               var3.append(' ').append(var7);
            }
         }

         if (var3.length() > 0) {
            var2.add(var3.toString());
         }

         return var2;
      } else {
         return Collections.emptyList();
      }
   }

   private void openHomeDeleteConfirmMenu(Player var1, int var2) {
      this.pendingDeleteSlot.put(var1.getUniqueId(), var2);
      Inventory var3 = Bukkit.createInventory(null, 27, color("&8Confirm Home Deletion"));
      Location var4 = this.readHomeLocation(var1, "home" + (var2 + 1));
      ArrayList var5 = new ArrayList();
      var5.add("&7You are about to delete &fHome " + (var2 + 1) + "&7.");
      if (var4 != null && var4.getWorld() != null) {
         var5.add("&8" + var4.getWorld().getName() + " " + (int)var4.getX() + ", " + (int)var4.getY() + ", " + (int)var4.getZ());
      }

      var5.add("&cThis cannot be undone (a backup is kept).");
      var3.setItem(13, item(Material.BLUE_BED, "&cDelete Home " + (var2 + 1), var5));
      var3.setItem(11, item(Material.RED_STAINED_GLASS_PANE, "&cCancel", List.of("&fClick To Cancel")));
      var3.setItem(15, item(Material.LIME_STAINED_GLASS_PANE, "&aConfirm", List.of("&fClick To Delete Home " + (var2 + 1))));
      var1.openInventory(var3);
   }

   private void handleDeleteConfirmClick(Player var1, int var2) {
      if (var2 == 11) {
         this.pendingDeleteHome.remove(var1.getUniqueId());
         this.pendingDeleteSlot.remove(var1.getUniqueId());
         this.openHomesMenu(var1, this.getCurrentTarget(var1));
      } else if (var2 == 15) {
         String var3 = this.pendingDeleteHome.remove(var1.getUniqueId());
         if (var3 != null) {
            this.handleDelHomeCommand(var1, var3);
            this.openHomesMenu(var1, var1);
         } else {
            Integer var4 = this.pendingDeleteSlot.remove(var1.getUniqueId());
            if (var4 == null) {
               this.openHomesMenu(var1, this.getCurrentTarget(var1));
            } else {
               if (this.removeHomeAtSlot(var1, var4)) {
                  var1.sendActionBar(color("&7Deleted Home " + (var4 + 1) + "&7."));
               }

               this.openHomesMenu(var1, var1);
            }
         }
      }
   }

   private void handleSetHomeCommand(Player var1, String var2) {
      File var3 = this.getSetHomeDataFile(var1);
      if (var3 == null) {
         var1.sendMessage(color("&cHomes are unavailable right now."));
      } else {
         File var4 = var3.getParentFile();
         if (var4 != null && !var4.exists() && !var4.mkdirs()) {
            var1.sendMessage(color("&cHomes are unavailable right now."));
            this.getLogger().warning("Could not create SetHome data dir: " + var4);
         } else {
            YamlConfiguration var5 = var3.exists() ? YamlConfiguration.loadConfiguration(var3) : new YamlConfiguration();
            ArrayList var6 = new ArrayList(var5.getStringList("homes"));
            String var7;
            if (var2 != null && !var2.isEmpty()) {
               var7 = var2.toLowerCase(Locale.ROOT);
               if (!HOME_NAME_PATTERN.matcher(var7).matches()) {
                  var1.sendMessage(color("&cHome names must be 1-20 characters: letters, numbers, &e-&c or &e_&c."));
                  return;
               }
            } else {
               var7 = null;

               for (int var8 = 1; var8 <= 27; var8++) {
                  String var9 = "home" + var8;
                  if (!var6.contains(var9)) {
                     var7 = var9;
                     break;
                  }
               }

               if (var7 == null) {
                  var1.sendMessage(color("&cAll home slots are in use. Delete one with &e/delhome&c."));
                  return;
               }
            }

            boolean var13 = !var6.contains(var7);
            int var14 = this.allowedHomes(var1);
            if (var13 && var6.size() >= var14) {
               var1.sendMessage(
                  color("&cHome limit reached (&e" + var6.size() + "&7/&e" + var14 + "&c). Delete a home or upgrade to Pizza+ / Pizza++ for more.")
               );
            } else {
               if (var13) {
                  var6.add(var7);
                  var5.set("homes", var6);
               }

               Location var10 = var1.getLocation();
               var5.set(var7 + ".world", var10.getWorld() == null ? "world" : var10.getWorld().getName());
               var5.set(var7 + ".x", var10.getX());
               var5.set(var7 + ".y", var10.getY());
               var5.set(var7 + ".z", var10.getZ());
               var5.set(var7 + ".yaw", var10.getYaw());
               var5.set(var7 + ".pitch", var10.getPitch());

               try {
                  var5.save(var3);
               } catch (Exception var12) {
                  this.getLogger().warning("Failed to save home for " + var1.getName() + ": " + var12.getMessage());
                  var1.sendMessage(color("&cFailed to save your home. Staff have been notified."));
                  return;
               }

               if (var13) {
                  var1.sendMessage(color("&7Home &b" + var7 + "&7 set. &8(" + var6.size() + "/" + var14 + " homes)"));
               } else {
                  var1.sendMessage(color("&7Home &b" + var7 + "&7 moved here."));
               }
            }
         }
      }
   }

   private void handleDelHomeCommand(Player var1, String var2) {
      File var3 = this.getSetHomeDataFile(var1);
      if (var3 != null && var3.exists()) {
         YamlConfiguration var4 = YamlConfiguration.loadConfiguration(var3);
         ArrayList var5 = new ArrayList(var4.getStringList("homes"));
         if (var5.isEmpty()) {
            var1.sendMessage(color("&cNo homes found."));
         } else {
            String var6 = this.resolveHomeArg(var2.toLowerCase(Locale.ROOT), var5, var4);
            if (var6 == null) {
               var1.sendMessage(color("&cHome not found. See &e/homes&c."));
            } else {
               this.backupHome(var1, var6, var4);
               var5.remove(var6);
               var4.set(var6, null);
               var4.set("homes", var5);

               try {
                  var4.save(var3);
                  this.getLogger().info("[Homes] " + var1.getName() + " deleted " + var6 + " via /delhome (backed up).");
               } catch (Exception var8) {
                  this.getLogger().warning("Failed to delete home for " + var1.getName() + ": " + var8.getMessage());
                  var1.sendMessage(color("&cFailed to delete that home."));
                  return;
               }

               if (var6.matches("home\\d+")) {
                  var1.sendMessage(color("&7Deleted home &b" + var6.substring(4) + "&7."));
               } else {
                  var1.sendMessage(color("&7Deleted &b" + var6 + "&7."));
               }
            }
         }
      } else {
         var1.sendMessage(color("&cNo homes found."));
      }
   }

   private boolean setHomeInSlot(Player var1, int var2) {
      File var3 = this.getSetHomeDataFile(var1);
      if (var3 == null) {
         return false;
      } else {
         YamlConfiguration var4 = var3.exists() ? YamlConfiguration.loadConfiguration(var3) : new YamlConfiguration();
         ArrayList var5 = new ArrayList(var4.getStringList("homes"));
         String var6 = "home" + (var2 + 1);
         if (!var5.contains(var6)) {
            var5.add(var6);
            var4.set("homes", var5);
         }

         Location var7 = var1.getLocation();
         var4.set(var6 + ".world", var7.getWorld() == null ? "world" : var7.getWorld().getName());
         var4.set(var6 + ".x", var7.getX());
         var4.set(var6 + ".y", var7.getY());
         var4.set(var6 + ".z", var7.getZ());
         var4.set(var6 + ".yaw", var7.getYaw());
         var4.set(var6 + ".pitch", var7.getPitch());

         try {
            var4.save(var3);
            return true;
         } catch (Exception var9) {
            this.getLogger().warning("Failed to save home for " + var1.getName() + ": " + var9.getMessage());
            return false;
         }
      }
   }

   private boolean removeHomeAtSlot(Player var1, int var2) {
      File var3 = this.getSetHomeDataFile(var1);
      if (var3 != null && var3.exists()) {
         String var4 = "home" + (var2 + 1);
         YamlConfiguration var5 = YamlConfiguration.loadConfiguration(var3);
         ArrayList var6 = new ArrayList(var5.getStringList("homes"));
         if (!var6.contains(var4) && !var5.contains(var4 + ".world")) {
            return false;
         } else {
            this.backupHome(var1, var4, var5);
            var6.remove(var4);
            var5.set(var4, null);
            var5.set("homes", var6);

            try {
               var5.save(var3);
               this.getLogger().info("[Homes] " + var1.getName() + " deleted " + var4 + " (backed up).");
               return true;
            } catch (Exception var8) {
               this.getLogger().warning("Failed to delete home for " + var1.getName() + ": " + var8.getMessage());
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private void backupHome(OfflinePlayer var1, String var2, FileConfiguration var3) {
      try {
         File var4 = new File(this.getDataFolder(), "home-backups");
         if (!var4.exists() && !var4.mkdirs()) {
            this.getLogger().warning("Could not create home-backups dir.");
            return;
         }

         File var5 = new File(var4, var1.getUniqueId() + ".yml");
         YamlConfiguration var6 = var5.exists() ? YamlConfiguration.loadConfiguration(var5) : new YamlConfiguration();
         String var7 = "deleted." + System.currentTimeMillis();
         var6.set(var7 + ".home-name", var2);
         var6.set(var7 + ".world", var3.get(var2 + ".world"));
         var6.set(var7 + ".x", var3.get(var2 + ".x"));
         var6.set(var7 + ".y", var3.get(var2 + ".y"));
         var6.set(var7 + ".z", var3.get(var2 + ".z"));
         var6.set(var7 + ".yaw", var3.get(var2 + ".yaw"));
         var6.set(var7 + ".pitch", var3.get(var2 + ".pitch"));
         var6.save(var5);
      } catch (Exception var8) {
         this.getLogger().warning("Failed to back up home " + var2 + " for " + var1.getUniqueId() + ": " + var8.getMessage());
      }
   }

   private void tagCombat(Player var1) {
      this.combatTaggedUntil.put(var1.getUniqueId(), System.currentTimeMillis() + 15000L);
   }

   private boolean isCombatTagged(Player var1) {
      Long var2 = this.combatTaggedUntil.get(var1.getUniqueId());
      if (var2 == null) {
         return false;
      } else if (var2 <= System.currentTimeMillis()) {
         this.combatTaggedUntil.remove(var1.getUniqueId());
         return false;
      } else {
         return true;
      }
   }

   private long remainingCombatSeconds(Player var1) {
      Long var2 = this.combatTaggedUntil.get(var1.getUniqueId());
      if (var2 == null) {
         return 0L;
      } else {
         long var3 = var2 - System.currentTimeMillis();
         if (var3 <= 0L) {
            this.combatTaggedUntil.remove(var1.getUniqueId());
            return 0L;
         } else {
            return Math.max(1L, (var3 + 999L) / 1000L);
         }
      }
   }

   private void startCountdownTeleport(Player var1, Location var2, String var3) {
      this.startCountdownCommand(var1, () -> var1.teleportAsync(var2).thenAccept(var3xx -> {
            if (var3xx) {
               var1.setFallDistance(0.0F);
               var1.setVelocity(new Vector(0, 0, 0));
               this.noFallDamageUntil.put(var1.getUniqueId(), System.currentTimeMillis() + 4000L);
               var1.sendActionBar(color(var3));

               try {
                  var1.playSound(var1.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 1.2F);
               } catch (Throwable var5) {
               }
            }
         }), var3);
   }

   @EventHandler(
      ignoreCancelled = true
   )
   public void onTeleportFallDamage(EntityDamageEvent var1) {
      if (var1.getCause() == DamageCause.FALL) {
         if (var1.getEntity() instanceof Player var2) {
            Long var4 = this.noFallDamageUntil.get(var2.getUniqueId());
            if (var4 != null && var4 > System.currentTimeMillis()) {
               var1.setCancelled(true);
               var2.setFallDistance(0.0F);
            }
         }
      }
   }

   private void startCountdownCommand(Player var1, Runnable var2, String var3) {
      this.cancelPendingTeleport(var1);
      if (var1.isOnline()) {
         if (!var1.hasPermission("pizzasmp.combat.bypass") && this.isCombatTagged(var1)) {
            var1.sendMessage(color("&cYou are in combat. Wait &e" + this.remainingCombatSeconds(var1) + "s &cbefore teleporting."));
         } else {
            var2.run();
         }
      }
   }

   private void cancelPendingTeleport(Player var1) {
      UUID var2 = var1.getUniqueId();
      Integer var3 = this.pendingTeleportTasks.remove(var2);
      if (var3 != null) {
         Bukkit.getScheduler().cancelTask(var3);
      }

      this.pendingTeleportOrigins.remove(var2);
   }

   private boolean hasPlayerMoved(Location var1, Location var2) {
      if (var1 == null || var2 == null) {
         return false;
      } else if (var1.getWorld() != null && var2.getWorld() != null) {
         return !var1.getWorld().getUID().equals(var2.getWorld().getUID()) ? true : var1.distanceSquared(var2) > 1.0E-4;
      } else {
         return true;
      }
   }

   private boolean isFrozen(Player var1) {
      return this.frozenPlayers.contains(var1.getUniqueId());
   }

   private void notifyFrozen(Player var1) {
      UUID var2 = var1.getUniqueId();
      long var3 = System.currentTimeMillis();
      long var5 = this.frozenNoticeCooldown.getOrDefault(var2, 0L);
      if (var5 <= var3) {
         this.frozenNoticeCooldown.put(var2, var3 + 1500L);
         if (this.maintenanceFrozen.contains(var2)) {
            var1.sendActionBar(color("&aYour region is under maintenance"));
         } else {
            var1.sendActionBar(color("&cYou are frozen."));
         }
      }
   }

   private List<String> completePlayerNames(String var1, boolean var2) {
      String var3 = var1.toLowerCase(Locale.ROOT);
      ArrayList var4 = new ArrayList();

      for (Player var6 : Bukkit.getOnlinePlayers()) {
         if (!var2 || this.frozenPlayers.contains(var6.getUniqueId())) {
            String var7 = var6.getName();
            if (var7.toLowerCase(Locale.ROOT).startsWith(var3)) {
               var4.add(var7);
            }
         }
      }

      var4.sort(String.CASE_INSENSITIVE_ORDER);
      return var4;
   }

   private List<String> completeOnlineNames(String var1) {
      String var2 = var1.toLowerCase(Locale.ROOT);
      ArrayList var3 = new ArrayList();

      for (Player var5 : Bukkit.getOnlinePlayers()) {
         String var6 = var5.getName();
         if (var6.toLowerCase(Locale.ROOT).startsWith(var2)) {
            var3.add(var6);
         }
      }

      var3.sort(String.CASE_INSENSITIVE_ORDER);
      return var3;
   }

   private List<String> completeKnownHomeTargets(String var1) {
      String var2 = var1.toLowerCase(Locale.ROOT);
      ArrayList var3 = new ArrayList();

      for (OfflinePlayer var5 : this.getKnownPlayersWithHomes()) {
         String var6 = var5.getName();
         if (var6 != null && var6.toLowerCase(Locale.ROOT).startsWith(var2)) {
            var3.add(var6);
         }
      }

      var3.sort(String.CASE_INSENSITIVE_ORDER);
      return var3;
   }

   private List<String> completeTargetHomeSlots(String var1, String var2) {
      OfflinePlayer var3 = Bukkit.getOfflinePlayer(var1);
      File var4 = this.getSetHomeDataFile(var3);
      if (var4 != null && var4.exists()) {
         YamlConfiguration var5 = YamlConfiguration.loadConfiguration(var4);
         List var6 = var5.getStringList("homes");
         ArrayList var7 = new ArrayList();
         String var8 = var2.toLowerCase(Locale.ROOT);

         for (int var9 = 0; var9 < var6.size(); var9++) {
            String var10 = String.valueOf(var9 + 1);
            if (var10.startsWith(var8)) {
               var7.add(var10);
            }
         }

         for (String var12 : (Iterable<String>) var6) {
            if (var12.toLowerCase(Locale.ROOT).startsWith(var8)) {
               var7.add(var12);
            }
         }

         return var7;
      } else {
         return Collections.emptyList();
      }
   }

   private List<String> completeTransferCommand(CommandSender var1, String[] var2) {
      boolean var3 = hasAnyPermission(var1, "pizzasmp.transfer.others");
      List var4 = this.getTransferDestinationNames();
      if (var2.length == 1) {
         String var9 = var2[0].toLowerCase(Locale.ROOT);
         ArrayList var6 = new ArrayList();

         for (String var8 : (Iterable<String>) var4) {
            if (var8.toLowerCase(Locale.ROOT).startsWith(var9)) {
               var6.add(var8);
            }
         }

         if (var3) {
            var6.addAll(this.completeOnlineNames(var2[0]));
         }

         var6.sort(String.CASE_INSENSITIVE_ORDER);
         return this.dedupeCaseInsensitive(var6);
      } else if (var2.length == 2) {
         if (var3 && Bukkit.getPlayerExact(var2[0]) != null) {
            return filterByPrefix(var4, var2[1]);
         } else if (isLikelyPort(var2[1])) {
            return Collections.emptyList();
         } else {
            ArrayList var5 = new ArrayList<>(filterByPrefix(var4, var2[1]));
            var5.addAll(filterByPrefix(List.of("25569", "25570", "25571"), var2[1]));
            return this.dedupeCaseInsensitive(var5);
         }
      } else {
         return var2.length == 3 && var3 && Bukkit.getPlayerExact(var2[0]) != null
            ? filterByPrefix(List.of("25569", "25570", "25571"), var2[2])
            : Collections.emptyList();
      }
   }

   private List<String> dedupeCaseInsensitive(List<String> var1) {
      if (var1.isEmpty()) {
         return var1;
      } else {
         HashSet var2 = new HashSet();
         ArrayList var3 = new ArrayList();

         for (String var5 : var1) {
            String var6 = var5.toLowerCase(Locale.ROOT);
            if (var2.add(var6)) {
               var3.add(var5);
            }
         }

         return var3;
      }
   }

   private static Player resolveAttacker(Entity var0) {
      if (var0 instanceof Player) {
         return (Player)var0;
      } else {
         if (var0 instanceof Projectile var1) {
            ProjectileSource var3 = var1.getShooter();
            if (var3 instanceof Player) {
               return (Player)var3;
            }
         }

         return null;
      }
   }

   private static PizzaAdminTools.TabRequest parseTabRequest(String var0) {
      if (var0 != null && !var0.isBlank()) {
         String var1 = var0.startsWith("/") ? var0.substring(1) : var0;
         if (var1.isBlank()) {
            return null;
         } else {
            boolean var2 = var1.endsWith(" ");
            String var3 = var1.trim();
            if (var3.isEmpty()) {
               return null;
            } else {
               String[] var4 = var3.split("\\s+");
               if (var4.length == 0) {
                  return null;
               } else {
                  String var5 = var4[0].toLowerCase(Locale.ROOT);
                  int var6 = var5.indexOf(58);
                  if (var6 >= 0 && var6 + 1 < var5.length()) {
                     var5 = var5.substring(var6 + 1);
                  }

                  ArrayList var7 = new ArrayList();

                  for (int var8 = 1; var8 < var4.length; var8++) {
                     var7.add(var4[var8]);
                  }

                  String var9;
                  int var10;
                  if (var2) {
                     var10 = var7.size();
                     var9 = "";
                  } else if (var7.isEmpty()) {
                     var10 = -1;
                     var9 = "";
                  } else {
                     var10 = var7.size() - 1;
                     var9 = ((String)var7.get(var7.size() - 1)).toLowerCase(Locale.ROOT);
                  }

                  return new PizzaAdminTools.TabRequest(var5, var7, var10, var9);
               }
            }
         }
      } else {
         return null;
      }
   }

   private static List<String> filterByPrefix(List<String> var0, String var1) {
      if (var0 != null && !var0.isEmpty()) {
         String var2 = var1 == null ? "" : var1.toLowerCase(Locale.ROOT);
         ArrayList var3 = new ArrayList();

         for (String var5 : var0) {
            if (var2.isEmpty() || var5.toLowerCase(Locale.ROOT).startsWith(var2)) {
               var3.add(var5);
            }
         }

         return var3;
      } else {
         return Collections.emptyList();
      }
   }

   private static boolean hasAnyPermission(CommandSender var0, String... var1) {
      if (var0 instanceof Player var2) {
         for (String var6 : var1) {
            if (var2.hasPermission(var6)) {
               return true;
            }
         }

         return false;
      } else {
         return true;
      }
   }

   private static String resolveRtpWorldName(String var0) {
      if (var0 != null && !var0.isBlank()) {
         String var1 = var0.toLowerCase(Locale.ROOT);
         String var2 = RTP_WORLD_ALIASES.get(var1);
         if (var2 != null) {
            return var2;
         } else {
            for (World var4 : Bukkit.getWorlds()) {
               if (var4.getName().equalsIgnoreCase(var0)) {
                  return var4.getName();
               }
            }

            return null;
         }
      } else {
         return null;
      }
   }

   private static Listener findRtpListener() {
      Plugin var0 = Bukkit.getPluginManager().getPlugin("RTPGUI");
      if (var0 != null && var0.isEnabled()) {
         for (RegisteredListener var2 : HandlerList.getRegisteredListeners(var0)) {
            Listener var3 = var2.getListener();
            if ("com.jolly.rtp.RTPListener".equals(var3.getClass().getName())) {
               return var3;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private CommandMap resolveCommandMap() {
      try {
         Method var1 = this.getServer().getClass().getMethod("getCommandMap");
         Object var2 = var1.invoke(this.getServer());
         if (var2 instanceof CommandMap) {
            return (CommandMap)var2;
         }
      } catch (ReflectiveOperationException var4) {
         this.getLogger().warning("Failed to resolve command map: " + var4.getMessage());
      }

      return null;
   }

   private boolean isRegisteredCommand(String var1) {
      if (var1 != null && !var1.isBlank()) {
         return ALWAYS_VALID_ROOTS.contains(var1.toLowerCase(Locale.ROOT)) ? true : this.commandMap != null && this.commandMap.getCommand(var1) != null;
      } else {
         return false;
      }
   }

   // OPT-IN staff mode: a staff member can only USE/SEE staff functions after running /sfmode, which
   // adds them to staffMode for this session (reset on logout). Dev can permanently opt OUT of the
   // hider via the bypass permission (toggleable in /settings). Default on login = hidden + blocked.
   private boolean isEffectivelyStaff(Player var1) {
      if (!var1.hasPermission("pizzasmp.pluginadmin")) {
         return false;
      }
      return this.staffMode.contains(var1.getUniqueId()) || var1.hasPermission("pizzasmp.sfmode.bypass");
   }

   // Opt-in staff mode is PER-SESSION now: it must NOT persist across restarts/logins (default =
   // hidden every login), so loadStaffMode intentionally restores nothing.
   private void loadStaffMode() {
      this.staffModeFile = new File(this.getDataFolder(), "staffmode.yml");
      // no restore: staff must re-run /sfmode each session (fair-play by default).
   }

   // Audit trail: every /sfmode toggle is logged to console + staffmode-audit.log for accountability.
   private void auditStaffMode(Player var1, boolean var2) {
      String var3 = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
         .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.now());
      String var4 = var3 + " " + var1.getName() + " (" + var1.getUniqueId() + ") staff-mode "
         + (var2 ? "ENABLED" : "DISABLED");
      this.getLogger().info("[sfmode] " + var4);
      try {
         this.getDataFolder().mkdirs();
         java.nio.file.Files.writeString(new File(this.getDataFolder(), "staffmode-audit.log").toPath(),
            var4 + System.lineSeparator(),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (Exception var6) {
      }
   }

   private boolean handleGmcBypassCommand(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var4) {
         var4.sendActionBar(color("&cThis command does not exist"));
         return true;
      } else if (var2.length < 1) {
         var1.sendMessage(color("&cUsage: /gmcbypass <player>"));
         return true;
      } else {
         Player var3 = Bukkit.getPlayerExact(var2[0]);
         if (var3 != null && var3.isOnline()) {
            this.creativeBypass.add(var3.getUniqueId());
            var3.setGameMode(GameMode.CREATIVE);
            var1.sendMessage(color("&aSet &e" + var3.getName() + " &ato creative (rule bypassed)."));
            return true;
         } else {
            var1.sendMessage(color("&cPlayer not found or offline: &e" + var2[0]));
            return true;
         }
      }
   }

   private boolean handleSfModeCommand(CommandSender var1) {
      if (var1 instanceof Player var2) {
         if (!var2.hasPermission("pizzasmp.pluginadmin")) {
            var2.sendActionBar(color("&cThis command does not exist"));
            return true;
         } else {
            UUID var3 = var2.getUniqueId();
            if (this.staffMode.contains(var3)) {
               // Toggling OFF while on: hide/disable staff functions immediately (no logout needed).
               this.staffMode.remove(var3);
               var2.sendActionBar(color("&7Staff mode is now disabled. Your next login will be normal."));
               this.auditStaffMode(var2, false);
            } else {
               // Toggling ON: grant staff functions for THIS SESSION (reset on next login).
               this.staffMode.add(var3);
               var2.sendActionBar(color("&7Staff mode is now on, all staff functions will hide on next log in and you won't be able to use any."));
               this.auditStaffMode(var2, true);
            }

            try {
               var2.updateCommands();
            } catch (Throwable var5) {
            }

            return true;
         }
      } else {
         var1.sendMessage(color("&cOnly players can use /sfmode."));
         return true;
      }
   }

   private Player resolveOnlinePlayer(String var1) {
      if (var1 != null && !var1.isBlank()) {
         Player var2 = Bukkit.getPlayerExact(var1);
         return var2 != null ? var2 : Bukkit.getPlayer(var1);
      } else {
         return null;
      }
   }

   private void sendUnknownCommandMessage(CommandSender var1) {
      if (var1 instanceof Player var2) {
         var2.sendActionBar(color("&cThis command does not exist"));
      } else {
         var1.sendMessage(color("&cThis command does not exist"));
      }
   }

   private void sendOfflinePlayerMessage(CommandSender var1, String var2) {
      if (var1 instanceof Player var3) {
         var3.sendActionBar(color("&cThe user is not online"));
      } else {
         var1.sendMessage(color("&cThe user is not online"));
      }
   }

   private static String[] parseCommandParts(String var0) {
      if (var0 != null && !var0.isBlank()) {
         String var1 = var0.startsWith("/") ? var0.substring(1) : var0;
         String[] var2 = var1.trim().split("\\s+");
         if (var2.length == 0) {
            return new String[0];
         } else {
            String var3 = var2[0].toLowerCase(Locale.ROOT);
            int var4 = var3.indexOf(58);
            if (var4 >= 0 && var4 + 1 < var3.length()) {
               var3 = var3.substring(var4 + 1);
            }

            var2[0] = var3;
            return var2;
         }
      } else {
         return new String[0];
      }
   }

   private static boolean isTeleportHomeCommand(String[] var0) {
      if (var0.length <= 1) {
         return false;
      } else {
         String var1 = var0[1].toLowerCase(Locale.ROOT);
         return !NON_TELEPORT_HOME_SUBCOMMANDS.contains(var1);
      }
   }

   private static String color(String var0) {
      return ChatColor.translateAlternateColorCodes('&', var0);
   }

   private static String sanitizeBanMessage(String var0) {
      String[] var1 = var0.replace("\r", "").split("\n");
      ArrayList var2 = new ArrayList();

      for (String var6 : var1) {
         String var7 = ChatColor.stripColor(var6 == null ? "" : var6).trim().toLowerCase(Locale.ROOT);
         if (!var7.startsWith("appeal your punishment") && !var7.startsWith("website:") && !var7.startsWith("discord:")) {
            var2.add(var6);
         }
      }

      while (!var2.isEmpty() && ((String)var2.get(var2.size() - 1)).trim().isEmpty()) {
         var2.remove(var2.size() - 1);
      }

      return String.join("\n", var2);
   }

   private static String normalizeBanKickMessage(String var0) {
      if (var0 != null && !var0.isBlank()) {
         String var1 = ChatColor.stripColor(var0).replace("\r", "");
         String var2 = var1.toLowerCase(Locale.ROOT);
         if (var2.contains("you are banned from pizzasmp") && var2.contains("ban id:") && var2.contains("duration:")) {
            return var0;
         } else {
            String var3 = "No reason stated.";
            String var4 = "Permanent";
            String var5 = null;
            String var6 = null;
            int var7 = var2.indexOf("\nreason\n");
            if (var7 >= 0) {
               int var8 = var7 + "\nreason\n".length();
               String var9 = var1.substring(var8).trim();
               if (!var9.isEmpty()) {
                  var3 = var9;
               }
            }

            int var19 = var2.indexOf("duration:");
            if (var19 >= 0) {
               int var20 = var1.indexOf(10, var19);
               String var10 = (var20 >= 0 ? var1.substring(var19 + "duration:".length(), var20) : var1.substring(var19 + "duration:".length())).trim();
               if (!var10.isEmpty()) {
                  var4 = var10;
               }
            }

            int var21 = var2.indexOf("ban id:");
            if (var21 >= 0) {
               int var22 = var1.indexOf(10, var21);
               String var11 = (var22 >= 0 ? var1.substring(var21 + "ban id:".length(), var22) : var1.substring(var21 + "ban id:".length())).trim();
               if (!var11.isEmpty()) {
                  var5 = var11;
               }
            }

            int var23 = var2.indexOf("date:");
            if (var23 >= 0) {
               int var24 = var1.indexOf(10, var23);
               String var12 = (var24 >= 0 ? var1.substring(var23 + "date:".length(), var24) : var1.substring(var23 + "date:".length())).trim();
               if (!var12.isEmpty()) {
                  var6 = var12;
               }
            }

            String var25 = "You are banned from this server for ";
            int var26 = var2.indexOf(var25);
            if (var26 >= 0) {
               int var13 = var2.indexOf(". reason:", var26);
               if (var13 > var26) {
                  String var14 = var1.substring(var26 + var25.length(), var13).trim();
                  if (!var14.isEmpty()) {
                     var4 = var14;
                  }

                  String var15 = var1.substring(var13 + ". reason:".length()).trim();
                  int var16 = var15.toLowerCase(Locale.ROOT).indexOf("you have been banned from the pizzasmp");
                  if (var16 >= 0) {
                     var15 = var15.substring(var16);
                  }

                  int var17 = var15.toLowerCase(Locale.ROOT).indexOf("\nreason\n");
                  if (var17 >= 0) {
                     String var18 = var15.substring(var17 + "\nreason\n".length()).trim();
                     if (!var18.isEmpty()) {
                        var3 = var18;
                     }
                  } else if (!var15.isEmpty() && !var15.toLowerCase(Locale.ROOT).startsWith("you have been banned from the pizzasmp")) {
                     var3 = var15;
                  }
               }
            }

            StringBuilder var27 = new StringBuilder()
               .append(ChatColor.RED)
               .append("You are banned from " + BRAND_DISPLAY + ". If you believe this was a mistake please make a ticket in the " + BRAND_DISPLAY + " Discord")
               .append('\n')
               .append(ChatColor.YELLOW)
               .append("discord.gg/example")
               .append("\n\n");
            if (var6 != null) {
               var27.append(ChatColor.GRAY).append("Date: ").append(ChatColor.WHITE).append(var6).append('\n');
            }

            var27.append(ChatColor.GRAY).append("Duration: ").append(ChatColor.WHITE).append(var4).append('\n');
            if (var5 != null) {
               var27.append(ChatColor.GRAY).append("Ban ID: ").append(ChatColor.WHITE).append(var5).append('\n');
            }

            var27.append(ChatColor.GRAY).append("Reason: ").append(ChatColor.WHITE).append(var3).append('\n');
            var27.append(ChatColor.GRAY)
               .append("You may be able to appeal this ban on ")
               .append(ChatColor.WHITE)
               .append("discord.gg/example")
               .append(ChatColor.GRAY)
               .append('.');
            return var27.toString();
         }
      } else {
         return var0;
      }
   }

   @EventHandler
   public void onNvPlayerRespawn(PlayerRespawnEvent var1) {
      if (this.nvEnabled.contains(var1.getPlayer().getUniqueId())) {
         Bukkit.getScheduler().runTask(this, () -> {
            Player var1x = var1.getPlayer();
            if (var1x.isOnline()) {
               var1x.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }
         });
      }
   }

   @EventHandler
   public void onNvItemConsume(PlayerItemConsumeEvent var1) {
      if (this.nvEnabled.contains(var1.getPlayer().getUniqueId())) {
         Bukkit.getScheduler().runTaskLater(this, () -> {
            Player var1x = var1.getPlayer();
            if (var1x.isOnline()) {
               var1x.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }
         }, 2L);
      }
   }

   @EventHandler
   public void onNvResurrect(EntityResurrectEvent var1) {
      if (var1.getEntity() instanceof Player var2 && this.nvEnabled.contains(var2.getUniqueId())) {
         Bukkit.getScheduler().runTaskLater(this, () -> {
            if (var2.isOnline()) {
               var2.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }
         }, 2L);
      }
   }

   @EventHandler
   public void onNvJoin(PlayerJoinEvent var1) {
      if (this.nvEnabled.contains(var1.getPlayer().getUniqueId())) {
         Bukkit.getScheduler().runTaskLater(this, () -> {
            Player var1x = var1.getPlayer();
            if (var1x.isOnline()) {
               var1x.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }
         }, 10L);
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onPlayerJoinBrand(PlayerJoinEvent var1) {
      Player var2 = var1.getPlayer();
      Bukkit.getScheduler().runTaskLater(this, () -> {
         if (var2.isOnline()) {
            try {
               Method var2x = var2.getClass().getMethod("getHandle");
               Object var3 = var2x.invoke(var2);
               Class var4 = Class.forName("net.minecraft.network.protocol.common.custom.BrandPayload");
               Object var5 = var4.getConstructor(String.class).newInstance("PizzaPaper");
               Class var6 = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
               Class var7 = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
               Object var8 = var7.getConstructor(var6).newInstance(var5);
               Field var9 = var3.getClass().getField("connection");
               Object var10 = var9.get(var3);
               Class var11 = Class.forName("net.minecraft.network.protocol.Packet");
               Method var12 = var10.getClass().getMethod("send", var11);
               var12.invoke(var10, var8);
            } catch (Exception var13) {
               this.getLogger().warning("Failed to send brand packet: " + var13.getMessage());
            }
         }
      }, 20L);
   }

   private static String safeName(OfflinePlayer var0) {
      return var0.getName() == null ? var0.getUniqueId().toString() : var0.getName();
   }

   private static final class SusEntry {
      private final UUID uuid;
      private final String playerName;
      private long latestAt;
      private String latestCheck;
      private String latestVerbose;
      private int highestVl;
      private int flagCount;

      private SusEntry(UUID var1, String var2, long var3, String var5, String var6, int var7, int var8) {
         this.uuid = var1;
         this.playerName = var2;
         this.latestAt = var3;
         this.latestCheck = var5 == null ? "Unknown" : var5;
         this.latestVerbose = var6 == null ? "" : var6;
         this.highestVl = var7;
         this.flagCount = var8;
      }
   }

   private static final class TabRequest {
      private final String root;
      private final List<String> args;
      private final int argPosition;
      private final String prefix;

      private TabRequest(String var1, List<String> var2, int var3, String var4) {
         this.root = var1;
         this.args = var2;
         this.argPosition = var3;
         this.prefix = var4;
      }
   }

   private static final class TransferDestination {
      private final String host;
      private final int port;

      private TransferDestination(String var1, int var2) {
         this.host = var1;
         this.port = var2;
      }
   }
}
