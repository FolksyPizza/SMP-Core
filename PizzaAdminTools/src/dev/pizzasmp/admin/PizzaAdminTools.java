package dev.pizzasmp.admin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
    private static final long PIZZAPLUS_STIPEND_PER_MONTH = 500L;      // shards per 30d block (Pizza+)
    private static final long PIZZAPLUSPLUS_STIPEND_PER_MONTH = 1000L; // shards per 30d block (Pizza++)
    private static final String PIZZAPLUS_LIST_TITLE = "&6Pizza+ Subscribers";
    private static final String PERM_RTP_USE = "rtp.use";
    private static final String RTP_PLUGIN_NAME = "RTPGUI";
    private static final String RTP_LISTENER_CLASS = "com.jolly.rtp.RTPListener";

    private static final String BRAND_NAME = "PizzaPaper";
    private static final String DISCORD_INVITE = "discord.gg/example";
    private static final String SUS_MENU_TITLE = "&8Server Sus";

    private static final long COMBAT_TAG_MILLIS = 15_000L;
    private static final long SUS_LOOKBACK_MILLIS = 30L * 60L * 1000L;
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final int SUS_PAGE_SIZE = 45;

    private static final Set<String> NON_TELEPORT_HOME_SUBCOMMANDS = Set.of(
        "create", "delete", "admin", "reload", "import", "open", "help", "set", "remove", "team"
    );
    private static final Set<String> FROZEN_ALLOWED_COMMANDS = Set.of(
        "freeze", "unfreeze", "msg", "tell", "w", "whisper", "r", "reply"
    );
    private static final Set<String> ONLINE_TARGET_FIRST_ARG_COMMANDS = Set.of(
        "tpa", "tpahere", "tpaccept", "tpdeny", "msg", "tell", "w", "whisper", "pay", "trade", "duel"
    );
    // Commands non-staff (anyone lacking pizzasmp.pluginadmin — i.e. default + pizza+)
    // must NEITHER see in tab NOR be able to run. The preprocess interceptor below
    // fires the existing "this command does not exist" hotbar message for these,
    // matching the UX of typing a truly unknown command. LuckPerms negations on the
    // permission nodes hide them from tab automatically.
    private static final Set<String> RESTRICTED_FOR_NON_STAFF = Set.of(
        // Vanilla minecraft / server admin
        "attribute","ban","ban-ip","banip","banlist","bossbar","clone","damage","data","datapack",
        "debug","defaultgamemode","deop","dialog","difficulty","effect","execute",
        "experience","fill","fillbiome","forceload","function","gamemode","gamerule",
        "give","gm","gma","gmc","gmt","gms","gmsp","jfr",
        "kick","kill","locate","locatebiome","loot","op","pardon","pardon-ip","particle",
        "perf","place","playsound",
        "reload","reset","restart","ride","rl","rotate","save-all","save-off","save-on","say",
        "schedule","scoreboard","seed","setblock","setidletimeout","setworldspawn",
        "spawnpoint","spectate","spreadplayers","stop","stopsound","summon","tab",
        "tag","tell","tellraw","teleport","tempban","tempbanip","test","tick","title",
        "tp","tpall","tphere","tpo","tpohere","tppos","transfer","unban","unbanip",
        "viewdistance","waypoint","whitelist","worldborder","xp",
        // Essentials / staff tools (non-prefixed variants)
        "broadcast","bcast","butcher","ci","clear","clearinventory","clearwarnings",
        "eco","economy","ess","essentials","feed","fix","fly","flyspeed","freeze",
        "getpos","god","heal","i","invsee","item","jail","jails","kit","kits","killall",
        "maintenance","more","near","nick","nickname","offend","pizzaplus","powertool",
        "ptime","pweather","remove","repair","seen","setspawn","setwarp","delwarp",
        "socialspy","spawnmob","spawnstash","speed","stash","sudo","time","togglejail",
        "top","unfreeze","unjail","unmute","unoffend","vanish","walkspeed","weather",
        // Plugin platform / diagnostics
        "geyser","grim","grimac","mspt","paper","plan","spigot","timings","viabackwards",
        "viaversion",
        // Plugin admin / staff tools
        "admin","admindelhome","bancheck","bans","clearbans","clearmutes","call","deluxmenu",
        "maintenancemotd","setmaintenancemotd","maintmotd","limbomaint",
        "deluxemenus","diagnostics","dm","dmenu","gtp","history","listbans","listmutes",
        "lp","luckperms","moderation","mute","papi","perm","permban","permission",
        "permissions","perms","pizzaadmin","pizzaadmintools","pizzabans","pizzadebug",
        "pizzahome","pizzamenus","pizzasusflag","pizzateams","placeholderapi","pm",
        "pong","punish","reply","rtpreload","searchid","sethomegui","sfmode","stopwatch",
        "suicide","sus","suspicious","tools","warp","warps","whisper",
        // Essentials e-prefixed admin (NOT gameplay — gameplay e-aliases like emsg,
        // er, ereply, epay, ehome, etpa, etpaccept, etpadeny, etpacancel are kept).
        "eafk","eantioch","eattack","eban","ebanip","ebreak","ebroadcast","eburn",
        "eclear","eclearinventory","edelhome","edelwarp","edeop","edisposal","eeco",
        "eecogive","eecotake","efeed","efireball","efirework","efly","egamemode",
        "egetpos","egive","egod","eheal","ehelpop","einvsee","eitem","ejails","ejump",
        "ekick","ekickall","ekill","ekit","ekittycannon","elist","emore","emute",
        "enchant","enear","enick","eopme","eplayerlist","epowertool","eptime",
        "epweather","equit","eremove","erepair","eseen","esell","esetspawn","esetwarp",
        "eshowkit","esocialspy","espawnmob","esudo","etempban","etop","etreasure",
        "evanish","ewarp","eweather","eworkbench","eworld"
    );
    // NOTE: "plugins"/"pl" intentionally NOT gated — /plugins must show the real
    // Bukkit plugin list. Branded list stays available via /pizzaplugins.
    private static final Set<String> PLUGIN_ADMIN_COMMANDS = Set.of(
        "lp", "luckperms", "version", "ver", "about", "paper", "timings"
    );
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

    private static final String HOME_MENU_TITLE = "&8Server Homes";
    private static final String HOME_DELETE_TITLE = "&8Confirm Home Deletion";
    // Absolute slot ceiling (pizza++ tier). Per-player limits come from allowedHomes().
    // NOTE: the current GUI still renders only the 5 BED_SLOTS — the GUI rework lands later;
    // the data layer + /sethome|/delhome|/home <name> already honor the full tier limits.
    private static final int MAX_HOME_SLOTS = 27;
    // Tier permission nodes: default 3, pizza+ 9, pizza++ 27 (granted via LuckPerms on deploy).
    private static final String PERM_HOMES_9 = "pizzasmp.homes.9";
    private static final String PERM_HOMES_27 = "pizzasmp.homes.27";
    private static final int HOMES_DEFAULT_LIMIT = 3;
    // Home names allow letters (any case), digits, and a broad set of safe punctuation.
    // EXCLUDED on purpose: '.' (Bukkit config path separator -> would corrupt SetHome storage),
    // ' ' space (breaks `/home <name>` arg parsing), ':' (UI action-id delimiter), and the color
    // chars '&'/'§'. Everything else listed (, _ - ' ! ? ( ) # + ~ @ = and more) is fine.
    private static final Pattern HOME_NAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_,'!?()#+~@=\\-]{1,24}$");
    private static final int[] BED_SLOTS = {10, 11, 12, 13, 14};
    private static final int[] DYE_SLOTS = {19, 20, 21, 22, 23};
    private static final String OWNER_BLUE = "&b";
    private static final String HOME_PRIMARY = OWNER_BLUE;
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
        "PunishDrop",
        "PizzaHome",
        "TAB",
        "Vault",
        "ViaBackwards",
        "ViaVersion"
    );

    private final Map<UUID, Long> combatTaggedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> adminTargetIndex = new ConcurrentHashMap<>();
    // Homes dialog: remembered expansion (9/27) so actions return to the same view; cleared on fresh /homes.
    private final Map<UUID, Integer> homesDialogExpanded = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingDeleteSlot = new ConcurrentHashMap<>();
    // Legacy homes grid: delete confirmation keyed by the actual home NAME (so custom-named homes
    // are never mis-deleted by slot index).
    private final Map<UUID, String> pendingDeleteHome = new ConcurrentHashMap<>();
    // Short window after a teleport during which fall damage is cancelled (kills carried momentum).
    private final Map<UUID, Long> noFallDamageUntil = new ConcurrentHashMap<>();
    // Staff mode (/sfmode): staff who toggle this are treated as normal players for the command
    // lockdown (restricted commands blocked + hidden). Persisted across restarts.
    private final Set<UUID> staffMode = ConcurrentHashMap.newKeySet();
    private File staffModeFile;
    // One-time creative passes granted by the console-only /gmcbypass (bypasses the creative ban).
    private final Set<UUID> creativeBypass = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> pendingTeleportTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pendingTeleportOrigins = new ConcurrentHashMap<>();
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> frozenAnchors = new ConcurrentHashMap<>();
    private final Map<UUID, Long> frozenNoticeCooldown = new ConcurrentHashMap<>();
    // Frozen-in-place maintenance ("virtual maintenance"): non-staff stay connected, see their
    // last location, but can't move/interact while the server is being updated.
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

    @Override
    public void onEnable() {
        registerCommand("gtp", true);
        registerCommand("homes", false);
        registerCommand("menu", false);
        registerCommand("guide", false);
        registerCommand("freeze", true);
        registerCommand("unfreeze", true);
        registerCommand("transfer", true);
        registerCommand("transfermaintenance", true);
        registerCommand("pizzaadmintools", false);
        registerCommand("pizzateams", false);
        registerCommand("pizzamenus", false);
        registerCommand("pizzahome", false);
        registerCommand("pizzabans", false);
        registerCommand("pizzaplugins", false);
        registerCommand("stash", false);
        registerCommand("spawnstash", false);
        registerCommand("pizzaplus", true);
        registerCommand("perks", false);
        registerCommand("atrack", true);
        registerCommand("servermaint", true);
        registerCommand("sfmode", false);
        registerCommand("gmcbypass", false);
        // /sus is registered by PizzaNetworkCore (with /suspicious alias) — removed here to avoid conflict
        commandMap = resolveCommandMap();
        loadStaffMode();
        initTransferDestinations();
        initMaintenanceTransferState();
        loadMaintenanceMode();
        // Keep the green maintenance hotbar persistent (action bars fade after ~3s) — refresh every 2s.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!maintenanceActive) return;
            for (UUID id : maintenanceFrozen) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) showMaintenanceTitle(p);
            }
        }, 40L, 40L);
        initPizzaPlusSubscriptions();
        getServer().getPluginManager().registerEvents(this, this);
        // Hourly Pizza+ expiry sweep — auto-revokes subscriptions whose 30-day clock ran out.
        Bukkit.getScheduler().runTaskTimer(this, this::runPizzaPlusExpirySweep, 20L * 60L, 20L * 60L * 60L);
        // Persisted NV state survives relog/restart.
        loadNvState();
        // Re-apply night vision every 2s for players in nvEnabled (handles totem/milk/respawn/any removal)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID id : nvEnabled) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline() && !p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
                }
            }
        }, 40L, 40L);
        getLogger().info("PizzaAdminTools enabled.");
    }

    @Override
    public void onDisable() {
        for (int taskId : pendingTeleportTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        pendingTeleportTasks.clear();
        combatTaggedUntil.clear();
        adminTargetIndex.clear();
        pendingDeleteSlot.clear();
        frozenPlayers.clear();
        frozenAnchors.clear();
        frozenNoticeCooldown.clear();
        susMenuPages.clear();
        commandMap = null;
        saveMaintenanceTransferState();
        savePizzaPlusSubscriptions();
    }

    private void registerCommand(String name, boolean tabComplete) {
        if (getCommand(name) == null) {
            getLogger().warning("Command missing in plugin.yml: " + name);
            return;
        }
        Objects.requireNonNull(getCommand(name), "command missing: " + name).setExecutor(this);
        if (tabComplete) {
            Objects.requireNonNull(getCommand(name), "command missing: " + name).setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        switch (cmd) {
            case "gtp":
                return handleGtpCommand(sender, args);
            case "homes":
                return handleHomesCommand(sender);
            case "menu":
                return handleMenuCommand(sender);
            case "guide":
                return handleGuideCommand(sender);
            case "freeze":
                return handleFreezeCommand(sender, args);
            case "unfreeze":
                return handleUnfreezeCommand(sender, args);
            case "servermaint":
                return handleServerMaintCommand(sender, args);
            case "transfer":
                return handleTransferCommand(sender, args);
            case "transfermaintenance":
                return handleTransferMaintenanceCommand(sender, args);
            case "pizzaadmintools":
                return handlePizzaAdminToolsHelp(sender);
            case "pizzateams":
                return handlePizzaTeamsHelp(sender);
            case "pizzamenus":
                return handlePizzaMenusHelp(sender);
            case "pizzahome":
                return handlePizzaHomeHelp(sender);
            case "pizzabans":
                return handlePizzaBansHelp(sender);
            case "pizzaplugins":
                return handlePizzaPlugins(sender);
            case "sus":
                return handleSusCommand(sender, args);
            case "stash":
            case "spawnstash":
                return handleStashCommand(sender);
            case "pizzaplus":
                return handlePizzaPlusCommand(sender, args);
            case "perks":
                return handlePerksCommand(sender);
            case "atrack":
                return handleAtrackCommand(sender, args);
            case "sfmode":
                return handleSfModeCommand(sender);
            case "gmcbypass":
                return handleGmcBypassCommand(sender, args);
            case "nv":
            case "nightvision":
                return handleNightVisionCommand(sender, args);
            default:
                return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("freeze")) {
            if (args.length == 1) {
                return completePlayerNames(args[0], false);
            }
            return Collections.emptyList();
        }

        if (command.getName().equalsIgnoreCase("unfreeze")) {
            if (args.length == 1) {
                return completePlayerNames(args[0], true);
            }
            return Collections.emptyList();
        }

        if (command.getName().equalsIgnoreCase("transfer")) {
            return completeTransferCommand(sender, args);
        }

        if (command.getName().equalsIgnoreCase("transfermaintenance")) {
            if (args.length == 1) {
                return filterByPrefix(List.of("on", "off", "status"), args[0]);
            }
            return Collections.emptyList();
        }

        if (command.getName().equalsIgnoreCase("sus")) {
            if (args.length == 1) {
                return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[0]);
            }
            return Collections.emptyList();
        }

        if (command.getName().equalsIgnoreCase("atrack")) {
            if (args.length == 1) {
                return completePlayerNames(args[0], false);
            }
            return Collections.emptyList();
        }

        if (command.getName().equalsIgnoreCase("pizzaplus")) {
            if (args.length == 1) {
                return filterByPrefix(List.of("give", "revoke", "check", "extend", "list"), args[0]);
            }
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("list")) {
                    return filterByPrefix(List.of("plus", "plusplus"), args[1]);
                }
                return completePlayerNames(args[1], true);
            }
            if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("extend"))) {
                return filterByPrefix(List.of("plus", "plusplus", "30", "90", "1mo", "3mo", "6mo", "12mo"), args[2]);
            }
            if (args.length == 4 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("extend"))) {
                return filterByPrefix(List.of("30", "90", "1mo", "3mo", "6mo", "12mo"), args[3]);
            }
            return Collections.emptyList();
        }

        if (!command.getName().equalsIgnoreCase("gtp")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (OfflinePlayer p : getKnownPlayersWithHomes()) {
                String name = p.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(name);
                }
            }
            return out;
        }

        if (args.length == 2) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            File targetFile = getSetHomeDataFile(target);
            if (targetFile == null || !targetFile.exists()) {
                return Collections.emptyList();
            }
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(targetFile);
            List<String> homes = cfg.getStringList("homes");
            List<String> out = new ArrayList<>();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (int i = 0; i < homes.size(); i++) {
                String idx = String.valueOf(i + 1);
                if (idx.startsWith(prefix)) {
                    out.add(idx);
                }
            }
            for (String home : homes) {
                if (home.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(home);
                }
            }
            return out;
        }

        return Collections.emptyList();
    }

    private boolean handleGtpCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /gtp."));
            return true;
        }

        if (!player.hasPermission(PERM_GTP)) {
            player.sendMessage(color("&cYou do not have permission to use /gtp."));
            return true;
        }

        if (!player.hasPermission(PERM_COMBAT_BYPASS) && isCombatTagged(player)) {
            player.sendMessage(color("&cYou are in combat. Wait &e" + remainingCombatSeconds(player) + "s &cbefore using /gtp."));
            return true;
        }

        if (args.length < 1 || args.length > 2) {
            player.sendMessage(color("&cUsage: &e/gtp <player> [home-name|home-index]"));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            player.sendMessage(color("&cPlayer not found: &e" + args[0]));
            return true;
        }

        File targetFile = getSetHomeDataFile(target);
        if (targetFile == null || !targetFile.exists()) {
            player.sendMessage(color("&cNo SetHome data found for &e" + safeName(target) + "&c."));
            return true;
        }

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(targetFile);
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        if (homes.isEmpty()) {
            player.sendMessage(color("&c" + safeName(target) + " has no homes."));
            return true;
        }

        String selectedHome;
        if (args.length == 1) {
            selectedHome = homes.get(0);
        } else {
            selectedHome = resolveHomeArg(args[1], homes, cfg);
            if (selectedHome == null) {
                player.sendMessage(color("&cHome not found for &e" + safeName(target) + "&c. Use a valid home name or 1-" + homes.size() + "."));
                return true;
            }
        }

        Location targetLocation = readHomeLocation(cfg, selectedHome);
        if (targetLocation == null) {
            player.sendMessage(color("&cHome data is invalid for &e" + selectedHome + "&c."));
            return true;
        }

        player.teleport(targetLocation, PlayerTeleportEvent.TeleportCause.COMMAND);
        player.sendMessage(color("&fTeleported To &b" + safeName(target) + "&f home."));
        return true;
    }

    // /stash | /spawnstash — drop a random small stash centered on the block the
    // admin is looking at. Variants are weighted toward SMALL: most are 1-cell
    // (ender chest / candle / lantern / etc) up to a rare 3x3 camp with a bed.
    // Footprint never exceeds 3x3. Fully silent — failures report via action bar.
    // Staff-only (PERM_STASH_CMD).
    private static final Material[] STASH_TINY_POOL = {
        Material.ENDER_CHEST, Material.CHEST, Material.BARREL, Material.CANDLE,
        Material.WHITE_CANDLE, Material.ORANGE_CANDLE, Material.YELLOW_CANDLE,
        Material.LANTERN, Material.SOUL_LANTERN, Material.TORCH, Material.SOUL_TORCH,
        Material.JUKEBOX, Material.LECTERN, Material.COMPOSTER, Material.BREWING_STAND,
        Material.FLOWER_POT, Material.CAULDRON, Material.GRINDSTONE,
        Material.CRAFTING_TABLE, Material.FURNACE, Material.SMOKER, Material.BLAST_FURNACE,
        Material.CARTOGRAPHY_TABLE, Material.SMITHING_TABLE, Material.LOOM,
        Material.BOOKSHELF, Material.BEEHIVE
    };

    private boolean handleStashCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /stash."));
            return true;
        }
        if (!player.hasPermission(PERM_STASH_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /stash."));
            return true;
        }
        org.bukkit.block.Block center = player.getTargetBlockExact(64);
        if (center == null || center.getType() == Material.AIR) {
            player.sendActionBar(color("&cLook at a solid block within 64 blocks."));
            return true;
        }
        try {
            World world = center.getWorld();
            java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
            int cx = center.getX(), cy = center.getY(), cz = center.getZ();

            // Weighted variant pick — usually small, rarely a full camp.
            int roll = rng.nextInt(100);
            String variant;
            if (roll < 45) {
                variant = "tiny";
                buildStashTiny(world, cx, cy, cz, rng);
            } else if (roll < 75) {
                variant = "small";
                buildStashSmall(world, cx, cy, cz, rng);
            } else if (roll < 92) {
                variant = "medium";
                buildStashMedium(world, cx, cy, cz, rng);
            } else {
                variant = "full";
                buildStashFull(world, cx, cy, cz, rng);
            }

            // Console-only audit; nothing sent to the player.
            getLogger().info(player.getName() + " spawned a " + variant + " stash at "
                + cx + "," + cy + "," + cz + " in " + world.getName());
        } catch (Exception ex) {
            player.sendActionBar(color("&cStash build failed."));
            getLogger().warning("Stash build failed for " + player.getName() + ": " + ex);
        }
        return true;
    }

    // 1 cell: replace the cursor block with one randomly-picked small item.
    private void buildStashTiny(World world, int cx, int cy, int cz, java.util.concurrent.ThreadLocalRandom rng) {
        Material m = STASH_TINY_POOL[rng.nextInt(STASH_TINY_POOL.length)];
        org.bukkit.block.Block above = world.getBlockAt(cx, cy + 1, cz);
        if (above.getType() != Material.AIR) above.setType(Material.AIR, false);
        org.bukkit.block.Block b = world.getBlockAt(cx, cy, cz);
        b.setType(m, false);
        if (m == Material.CHEST || m == Material.BARREL) fillStashChest(b, rng);
    }

    // 1x2: cursor + one neighbor cell, two items.
    private void buildStashSmall(World world, int cx, int cy, int cz, java.util.concurrent.ThreadLocalRandom rng) {
        int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        int[] dir = dirs[rng.nextInt(dirs.length)];
        int[][] cells = { {0, 0}, {dir[0], dir[1]} };
        for (int[] cell : cells) {
            org.bukkit.block.Block up = world.getBlockAt(cx + cell[0], cy + 1, cz + cell[1]);
            if (up.getType() != Material.AIR) up.setType(Material.AIR, false);
        }
        for (int[] cell : cells) {
            Material m = STASH_TINY_POOL[rng.nextInt(STASH_TINY_POOL.length)];
            org.bukkit.block.Block b = world.getBlockAt(cx + cell[0], cy, cz + cell[1]);
            b.setType(m, false);
            if (m == Material.CHEST || m == Material.BARREL) fillStashChest(b, rng);
        }
    }

    // 2x2: cursor as one corner, three of four cells filled with random items.
    private void buildStashMedium(World world, int cx, int cy, int cz, java.util.concurrent.ThreadLocalRandom rng) {
        int signX = rng.nextBoolean() ? 1 : -1;
        int signZ = rng.nextBoolean() ? 1 : -1;
        int[][] cells = { {0, 0}, {signX, 0}, {0, signZ}, {signX, signZ} };
        for (int[] cell : cells) {
            for (int dy = 1; dy <= 2; dy++) {
                org.bukkit.block.Block up = world.getBlockAt(cx + cell[0], cy + dy, cz + cell[1]);
                if (up.getType() != Material.AIR) up.setType(Material.AIR, false);
            }
        }
        java.util.List<int[]> shuffled = new java.util.ArrayList<>(java.util.List.of(cells));
        java.util.Collections.shuffle(shuffled, new java.util.Random(rng.nextLong()));
        int fillCount = 3;
        for (int i = 0; i < fillCount; i++) {
            int[] cell = shuffled.get(i);
            Material m = STASH_TINY_POOL[rng.nextInt(STASH_TINY_POOL.length)];
            org.bukkit.block.Block b = world.getBlockAt(cx + cell[0], cy, cz + cell[1]);
            b.setType(m, false);
            if (m == Material.CHEST || m == Material.BARREL) fillStashChest(b, rng);
        }
    }

    // 3x3 full camp with bed, furnace, crafting table, chest+loot, torch.
    private void buildStashFull(World world, int cx, int cy, int cz, java.util.concurrent.ThreadLocalRandom rng) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(cx + dx, cy, cz + dz).setType(Material.OAK_PLANKS, false);
                for (int dy = 1; dy <= 3; dy++) {
                    org.bukkit.block.Block clr = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (clr.getType() != Material.AIR) clr.setType(Material.AIR, false);
                }
            }
        }
        int wy = cy + 1;
        org.bukkit.block.Block chestB = world.getBlockAt(cx - 1, wy, cz - 1);
        chestB.setType(Material.CHEST, false);
        fillStashChest(chestB, rng);
        world.getBlockAt(cx + 1, wy, cz - 1).setType(Material.FURNACE, false);
        world.getBlockAt(cx, wy, cz - 1).setType(Material.CRAFTING_TABLE, false);
        world.getBlockAt(cx + 1, wy, cz + 1).setType(Material.TORCH, false);
        org.bukkit.block.Block bedFoot = world.getBlockAt(cx - 1, wy, cz + 1);
        org.bukkit.block.Block bedHead = world.getBlockAt(cx, wy, cz + 1);
        bedFoot.setType(Material.RED_BED, false);
        bedHead.setType(Material.RED_BED, false);
        org.bukkit.block.data.type.Bed footData = (org.bukkit.block.data.type.Bed) bedFoot.getBlockData();
        footData.setPart(org.bukkit.block.data.type.Bed.Part.FOOT);
        footData.setFacing(org.bukkit.block.BlockFace.EAST);
        bedFoot.setBlockData(footData, false);
        org.bukkit.block.data.type.Bed headData = (org.bukkit.block.data.type.Bed) bedHead.getBlockData();
        headData.setPart(org.bukkit.block.data.type.Bed.Part.HEAD);
        headData.setFacing(org.bukkit.block.BlockFace.EAST);
        bedHead.setBlockData(headData, false);
    }

    // ============================================================================
    // /pizzaplus give|revoke|check  +  /perks
    // ============================================================================
    // Admins use /pizzaplus to grant or revoke the LuckPerms "pizza+" group.
    // Granting fires a themed welcome message to the target if online.
    // /perks shows everyone what Pizza+ unlocks (encourages upgrades).

    // ---------- Pizza+ monthly subscription state ----------
    private static final long PIZZAPLUS_DEFAULT_DAYS = 30L;
    private File pizzaPlusSubsFile;
    private FileConfiguration pizzaPlusSubsConfig;

    private void initPizzaPlusSubscriptions() {
        pizzaPlusSubsFile = new File(getDataFolder(), "pizzaplus-subs.yml");
        if (pizzaPlusSubsFile.getParentFile() != null && !pizzaPlusSubsFile.getParentFile().exists()) {
            pizzaPlusSubsFile.getParentFile().mkdirs();
        }
        if (!pizzaPlusSubsFile.exists()) {
            try { pizzaPlusSubsFile.createNewFile(); } catch (Exception ignored) {}
        }
        pizzaPlusSubsConfig = YamlConfiguration.loadConfiguration(pizzaPlusSubsFile);
    }

    private void savePizzaPlusSubscriptions() {
        if (pizzaPlusSubsConfig == null || pizzaPlusSubsFile == null) return;
        try { pizzaPlusSubsConfig.save(pizzaPlusSubsFile); } catch (Exception ex) {
            getLogger().warning("Failed saving pizzaplus-subs.yml: " + ex.getMessage());
        }
    }

    private long getPizzaPlusExpiry(UUID uuid) {
        return pizzaPlusSubsConfig == null ? 0L : pizzaPlusSubsConfig.getLong("expiry." + uuid.toString(), 0L);
    }

    private void setPizzaPlusExpiry(UUID uuid, String name, long expiryMs) {
        if (pizzaPlusSubsConfig == null) return;
        if (expiryMs <= 0L) {
            pizzaPlusSubsConfig.set("expiry." + uuid.toString(), null);
            pizzaPlusSubsConfig.set("names." + uuid.toString(), null);
            pizzaPlusSubsConfig.set("tier." + uuid.toString(), null);
        } else {
            pizzaPlusSubsConfig.set("expiry." + uuid.toString(), expiryMs);
            if (name != null) pizzaPlusSubsConfig.set("names." + uuid.toString(), name);
        }
    }

    /** Subscription tier: "plus" (Pizza+) or "plusplus" (Pizza++). Defaults to plus for legacy entries. */
    private String getPizzaPlusTier(UUID uuid) {
        return pizzaPlusSubsConfig == null ? "plus"
            : pizzaPlusSubsConfig.getString("tier." + uuid.toString(), "plus");
    }

    private void setPizzaPlusTier(UUID uuid, String tier) {
        if (pizzaPlusSubsConfig != null) pizzaPlusSubsConfig.set("tier." + uuid.toString(), tier);
    }

    private static String tierDisplay(String tier) {
        return "plusplus".equals(tier) ? "Pizza++" : "Pizza+";
    }

    private static String tierGroup(String tier) {
        return "plusplus".equals(tier) ? LP_GROUP_PIZZAPLUSPLUS : LP_GROUP_PIZZAPLUS;
    }

    /** Accepts plus/+/pizzaplus or plusplus/++/pizzaplusplus; null if not a tier token. */
    private static String normalizeTierArg(String s) {
        if (s == null) return null;
        switch (s.toLowerCase(Locale.ROOT)) {
            case "plus": case "+": case "pizza+": case "pizzaplus": return "plus";
            case "plusplus": case "++": case "pizza++": case "pizzaplusplus": return "plusplus";
            default: return null;
        }
    }

    /** Parses "90", "90d", "3mo" → days; -1 if invalid. */
    private static long parseDaysArg(String s) {
        if (s == null) return -1L;
        String t = s.toLowerCase(Locale.ROOT).trim();
        try {
            if (t.endsWith("mo")) return Math.max(1L, Long.parseLong(t.substring(0, t.length() - 2))) * 30L;
            if (t.endsWith("d")) return Math.max(1L, Long.parseLong(t.substring(0, t.length() - 1)));
            return Math.max(1L, Long.parseLong(t));
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    // Hourly sweep: revoke pizza+ from any UUID whose stamped expiry has passed.
    private void runPizzaPlusExpirySweep() {
        if (pizzaPlusSubsConfig == null) return;
        org.bukkit.configuration.ConfigurationSection sec = pizzaPlusSubsConfig.getConfigurationSection("expiry");
        if (sec == null) return;
        long now = System.currentTimeMillis();
        java.util.List<String> expired = new ArrayList<>();
        for (String key : sec.getKeys(false)) {
            if (sec.getLong(key, 0L) <= now) expired.add(key);
        }
        if (expired.isEmpty()) return;
        for (String uuidStr : expired) {
            String lpName = pizzaPlusSubsConfig.getString("names." + uuidStr, null);
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); } catch (Exception ex) { continue; }
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String name = (op.getName() != null) ? op.getName() : lpName;
            if (name == null) { setPizzaPlusExpiry(uuid, null, 0L); continue; }
            String tier = getPizzaPlusTier(uuid);
            // Remove both groups so a tier mismatch can never leave a stale grant behind.
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + name + " parent remove " + LP_GROUP_PIZZAPLUS);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + name + " parent remove " + LP_GROUP_PIZZAPLUSPLUS);
            setPizzaPlusExpiry(uuid, null, 0L);
            getLogger().info(tierDisplay(tier) + " expired and revoked for " + name);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                online.sendMessage(net.kyori.adventure.text.Component.text(
                    "Your " + tierDisplay(tier) + " subscription has expired. Use /perks to learn how to renew.",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
        }
        savePizzaPlusSubscriptions();
    }

    private static String formatRemainingMs(long ms) {
        if (ms <= 0L) return "expired";
        long days = ms / 86_400_000L;
        long hours = (ms % 86_400_000L) / 3_600_000L;
        if (days > 0L) return days + "d " + hours + "h";
        long minutes = (ms % 3_600_000L) / 60_000L;
        return hours + "h " + minutes + "m";
    }
    // -------------------------------------------------------

    private boolean handlePizzaPlusCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.hasPermission(PERM_PIZZAPLUS_ADMIN)) {
            p.sendMessage(color("&cYou do not have permission to manage Pizza+."));
            return true;
        }
        String usage = "&cUsage: &e/pizzaplus <give|revoke|check|extend> <player> [plus|plusplus] [days|Nmo] &7| &e/pizzaplus list [plus|plusplus]";
        if (args.length < 1) {
            // Bare /pizzaplus opens the management GUI; the subcommands still work.
            handlePizzaPlusList(sender, null);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("list".equals(sub)) {
            String filter = args.length >= 2 ? normalizeTierArg(args[1]) : null;
            handlePizzaPlusList(sender, filter);
            return true;
        }
        if (!java.util.Set.of("give","revoke","check","extend").contains(sub) || args.length < 2) {
            sender.sendMessage(color(usage));
            return true;
        }
        String targetName = args[1];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target == null || (!target.hasPlayedBefore() && Bukkit.getPlayerExact(targetName) == null)) {
            sender.sendMessage(color("&cPlayer not found: &e" + targetName));
            return true;
        }
        UUID uuid = target.getUniqueId();
        String lpName = target.getName() != null ? target.getName() : targetName;

        if ("check".equals(sub)) {
            long expiry = getPizzaPlusExpiry(uuid);
            long remaining = expiry - System.currentTimeMillis();
            String tier = getPizzaPlusTier(uuid);
            Player online = Bukkit.getPlayerExact(lpName);
            boolean hasNode = online != null
                && (online.hasPermission(PERM_NODE_PIZZAPLUS) || online.hasPermission(PERM_NODE_PIZZAPLUSPLUS));
            if (expiry > 0L && remaining > 0L) {
                sender.sendMessage(color("&f" + lpName + " &7" + tierDisplay(tier) + ": &aACTIVE &7(expires in &f"
                    + formatRemainingMs(remaining) + "&7)"));
            } else if (hasNode) {
                sender.sendMessage(color("&f" + lpName + " &7Pizza+: &aACTIVE &7(no expiry tracked — legacy grant)"));
            } else {
                sender.sendMessage(color("&f" + lpName + " &7Pizza+/Pizza++: &cnone"));
            }
            return true;
        }

        if ("revoke".equals(sub)) {
            String tier = getPizzaPlusTier(uuid);
            // Remove both groups so a tier mismatch can never leave a stale grant behind.
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + lpName + " parent remove " + LP_GROUP_PIZZAPLUS);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + lpName + " parent remove " + LP_GROUP_PIZZAPLUSPLUS);
            setPizzaPlusExpiry(uuid, null, 0L);
            savePizzaPlusSubscriptions();
            sender.sendMessage(color("&7Revoked " + tierDisplay(tier) + " from &f" + lpName + "&7."));
            Player online = Bukkit.getPlayerExact(lpName);
            if (online != null) {
                online.sendMessage(net.kyori.adventure.text.Component.text(
                    "Your " + tierDisplay(tier) + " status has been removed.",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
            return true;
        }

        // give / extend — optional tier token then optional days ("90", "90d", "3mo")
        String tier = null;
        int dayArgIdx = 2;
        if (args.length >= 3) {
            tier = normalizeTierArg(args[2]);
            if (tier != null) dayArgIdx = 3;
        }
        long days = PIZZAPLUS_DEFAULT_DAYS;
        if (args.length > dayArgIdx) {
            days = parseDaysArg(args[dayArgIdx]);
            if (days <= 0L) {
                sender.sendMessage(color("&cInvalid duration: &e" + args[dayArgIdx] + " &7(use days, e.g. 90, or months, e.g. 3mo)"));
                return true;
            }
        }
        String existingTier = getPizzaPlusExpiry(uuid) > System.currentTimeMillis() ? getPizzaPlusTier(uuid) : null;
        if (tier == null) tier = existingTier != null ? existingTier : "plus";

        long now = System.currentTimeMillis();
        long currentExpiry = getPizzaPlusExpiry(uuid);
        long base = "extend".equals(sub) && currentExpiry > now ? currentExpiry : now;
        long newExpiry = base + days * 86_400_000L;

        // Grant the right group; on tier change, drop the old group first.
        if (existingTier != null && !existingTier.equals(tier)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + lpName + " parent remove " + tierGroup(existingTier));
        }
        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "lp user " + lpName + " parent add " + tierGroup(tier));
        if (!ok) {
            sender.sendMessage(color("&cLuckPerms command failed."));
            return true;
        }
        setPizzaPlusExpiry(uuid, lpName, newExpiry);
        setPizzaPlusTier(uuid, tier);
        savePizzaPlusSubscriptions();

        // Monthly shard stipend: Pizza+ 500 / Pizza++ 1000 per started 30-day block.
        long months = Math.max(1L, days / 30L);
        long stipend = months * ("plusplus".equals(tier) ? PIZZAPLUSPLUS_STIPEND_PER_MONTH : PIZZAPLUS_STIPEND_PER_MONTH);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "shards give " + lpName + " " + stipend);

        sender.sendMessage(color("&a" + ("give".equals(sub) ? "Granted" : "Extended") + " " + tierDisplay(tier) + " for &f"
            + lpName + " &7(" + days + "d, expires in " + formatRemainingMs(newExpiry - now)
            + ", stipend " + stipend + " shards)"));
        Player online = Bukkit.getPlayerExact(lpName);
        if (online != null && "give".equals(sub)) {
            sendPizzaPlusWelcome(online, tier);
        } else if (online != null) {
            online.sendMessage(net.kyori.adventure.text.Component.text(
                "Your " + tierDisplay(tier) + " subscription was extended (" + days + " more days).",
                net.kyori.adventure.text.format.TextColor.color(0x00BFFF)));
        }
        return true;
    }

    // ---- /pizzaplus list — GUI for players, text for console ----

    private void handlePizzaPlusList(CommandSender sender, String tierFilter) {
        long now = System.currentTimeMillis();
        List<UUID> subs = new ArrayList<>();
        org.bukkit.configuration.ConfigurationSection sec =
            pizzaPlusSubsConfig != null ? pizzaPlusSubsConfig.getConfigurationSection("expiry") : null;
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                if (sec.getLong(key, 0L) <= now) continue;
                UUID uuid;
                try { uuid = UUID.fromString(key); } catch (Exception ex) { continue; }
                if (tierFilter != null && !tierFilter.equals(getPizzaPlusTier(uuid))) continue;
                subs.add(uuid);
            }
        }
        subs.sort(java.util.Comparator.comparingLong(this::getPizzaPlusExpiry));

        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(color("&6Pizza+ subscribers (" + subs.size() + "):"));
            for (UUID uuid : subs) {
                String name = pizzaPlusSubsConfig.getString("names." + uuid, uuid.toString());
                String tier = getPizzaPlusTier(uuid);
                sender.sendMessage(color(" &f" + name + " &7— " + tierDisplay(tier)
                    + ", expires in &f" + formatRemainingMs(getPizzaPlusExpiry(uuid) - now)));
            }
            return;
        }

        if (useDialogUi(viewer)) {
            openPizzaPlusListDialog(viewer, tierFilter, subs);
            return;
        }
        Inventory inv = Bukkit.createInventory(null, 54, color(PIZZAPLUS_LIST_TITLE
            + (tierFilter != null ? " &7(" + tierDisplay(tierFilter) + ")" : "")));
        int slot = 0;
        for (UUID uuid : subs) {
            if (slot >= 53) break; // last row slot 53 reserved for close
            String name = pizzaPlusSubsConfig.getString("names." + uuid, uuid.toString());
            String tier = getPizzaPlusTier(uuid);
            boolean pp = "plusplus".equals(tier);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
                meta.setDisplayName(color((pp ? "&6" : "&f") + name + " " + (pp ? "&6&l++" : "&e&l+")));
                meta.setLore(List.of(
                    color("&7Tier: " + (pp ? "&6Pizza++" : "&ePizza+")),
                    color("&7Expires in: &f" + formatRemainingMs(getPizzaPlusExpiry(uuid) - now)),
                    color("&8" + uuid)));
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }
        if (subs.isEmpty()) {
            ItemStack none = new ItemStack(Material.GRAY_DYE);
            ItemMeta nm = none.getItemMeta();
            if (nm != null) {
                nm.setDisplayName(color("&7No active subscribers"));
                none.setItemMeta(nm);
            }
            inv.setItem(22, none);
        }
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        if (cm != null) {
            cm.setDisplayName(color("&cClose"));
            close.setItemMeta(cm);
        }
        inv.setItem(53, close);
        viewer.openInventory(inv);
    }

    /** Revokes a subscription by EXACT uuid (clears its subs entry), so duplicate-name entries clear cleanly. */
    private void revokePizzaPlusUuid(CommandSender actor, UUID uuid, String name) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + name + " parent remove " + LP_GROUP_PIZZAPLUS);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + name + " parent remove " + LP_GROUP_PIZZAPLUSPLUS);
        if (pizzaPlusSubsConfig != null) {
            pizzaPlusSubsConfig.set("expiry." + uuid, null);
            pizzaPlusSubsConfig.set("tier." + uuid, null);
            pizzaPlusSubsConfig.set("names." + uuid, null);
        }
        savePizzaPlusSubscriptions();
        actor.sendMessage(color("&7Revoked Pizza+/++ from &f" + name + "&7."));
    }

    /** Pizza+ management dialog: filter tabs, Give, and a button per subscriber (-> detail). */
    private void openPizzaPlusListDialog(Player viewer, String tierFilter, List<UUID> subs) {
        long now = System.currentTimeMillis();
        List<DialogBody> body = List.of(DialogBody.plainMessage(
            Component.text(subs.size() + " active subscriber" + (subs.size() == 1 ? "" : "s")
                + (tierFilter != null ? " (" + tierDisplay(tierFilter).replaceAll("&[0-9a-fk-or]", "") + ")" : ""), NamedTextColor.GRAY)));
        List<ActionButton> buttons = new ArrayList<>();
        String nf = tierFilter == null ? "ALL" : (tierFilter.equals("plus") ? "PLUS" : "PLUSPLUS");
        buttons.add(dialogButton(Component.text("Filter: " + (tierFilter == null ? "All" : tierDisplay(tierFilter).replaceAll("&[0-9a-fk-or]", "")), DIALOG_BRAND),
            "Click to change", 150, p -> {
                String next = tierFilter == null ? "plus" : (tierFilter.equals("plus") ? "plusplus" : null);
                handlePizzaPlusList(p, next);
            }));
        buttons.add(dialogButton(Component.text("Give Subscription", NamedTextColor.GREEN), "Grant Pizza+ / Pizza++", 150,
            this::openPizzaPlusGiveDialog));
        for (UUID uuid : subs) {
            String name = pizzaPlusSubsConfig.getString("names." + uuid, uuid.toString());
            String tier = getPizzaPlusTier(uuid);
            boolean pp = "plusplus".equals(tier);
            String exp = formatRemainingMs(getPizzaPlusExpiry(uuid) - now);
            buttons.add(dialogButton(Component.text(name + (pp ? " ++" : " +"), pp ? NamedTextColor.GOLD : NamedTextColor.YELLOW),
                "Expires in " + exp, 200, p -> openPizzaPlusDetailDialog(p, uuid, name)));
        }
        if (subs.isEmpty()) {
            buttons.add(dialogButton(Component.text("(no subscribers)", NamedTextColor.DARK_GRAY), null, 200, null));
        }
        Dialog dialog = buildDialog(Component.text("Pizza+ Management", DIALOG_BRAND), body, List.of(),
            DialogType.multiAction(buttons).columns(1).exitAction(dialogButton(Component.text("Close"), null, 150, null)).build());
        viewer.showDialog(dialog);
    }

    private void openPizzaPlusDetailDialog(Player viewer, UUID uuid, String name) {
        long now = System.currentTimeMillis();
        String tier = getPizzaPlusTier(uuid);
        List<DialogBody> body = List.of(
            DialogBody.plainMessage(Component.text("Tier: " + tierDisplay(tier).replaceAll("&[0-9a-fk-or]", ""), NamedTextColor.GRAY)),
            DialogBody.plainMessage(Component.text("Expires in: " + formatRemainingMs(getPizzaPlusExpiry(uuid) - now), NamedTextColor.GRAY)));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogButton(Component.text("Extend 30 days", NamedTextColor.GREEN), null, 200,
            p -> { p.performCommand("pizzaplus extend " + name + " 30"); openPizzaPlusDetailDialog(p, uuid, name); }));
        buttons.add(dialogButton(Component.text("Revoke", NamedTextColor.RED), null, 200,
            p -> { revokePizzaPlusUuid(p, uuid, name); handlePizzaPlusList(p, null); }));
        Dialog dialog = buildDialog(Component.text(name, DIALOG_BRAND), body, List.of(),
            DialogType.multiAction(buttons).columns(1).exitAction(dialogButton(Component.text("Back"), null, 200, p -> handlePizzaPlusList(p, null))).build());
        viewer.showDialog(dialog);
    }

    private void openPizzaPlusGiveDialog(Player viewer) {
        List<DialogInput> inputs = List.of(
            DialogInput.text("name", Component.text("Player name", NamedTextColor.GRAY)).width(220).maxLength(16).build());
        ActionButton plus = pizzaPlusGiveButton("Give Pizza+", NamedTextColor.YELLOW, "plus");
        ActionButton plusplus = pizzaPlusGiveButton("Give Pizza++", NamedTextColor.GOLD, "plusplus");
        ActionButton back = dialogButton(Component.text("Back"), null, 150, p -> handlePizzaPlusList(p, null));
        Dialog dialog = buildDialog(Component.text("Give Subscription", DIALOG_BRAND),
            List.of(DialogBody.plainMessage(Component.text("Grants a 30-day subscription.", NamedTextColor.GRAY))),
            inputs, DialogType.multiAction(List.of(plus, plusplus)).columns(2).exitAction(back).build());
        viewer.showDialog(dialog);
    }

    /** A "Give Pizza+/++" button that reads the name input and runs /pizzaplus give. */
    private ActionButton pizzaPlusGiveButton(String label, NamedTextColor color, String tier) {
        return ActionButton.builder(Component.text(label, color)).width(150)
            .action(DialogAction.customClick((view, audience) -> {
                if (audience instanceof Player p) {
                    String raw = view.getText("name");
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (raw == null || raw.isBlank()) {
                            p.sendActionBar(net.kyori.adventure.text.Component.text("§cEnter a player name."));
                            return;
                        }
                        p.performCommand("pizzaplus give " + raw.trim() + " " + tier);
                        handlePizzaPlusList(p, null);
                    });
                }
            }, net.kyori.adventure.text.event.ClickCallback.Options.builder().build()))
            .build();
    }

    @org.bukkit.event.EventHandler
    public void onPizzaPlusListClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        String title = event.getView().getTitle();
        if (!title.startsWith(color(PIZZAPLUS_LIST_TITLE))) return;
        event.setCancelled(true);
        if (event.getRawSlot() == 53) viewer.closeInventory();
    }

    private void sendPizzaPlusWelcome(Player p, String tier) {
        net.kyori.adventure.text.format.TextColor brand = net.kyori.adventure.text.format.TextColor.color(0x00BFFF);
        net.kyori.adventure.text.format.TextColor gold = net.kyori.adventure.text.format.NamedTextColor.GOLD;
        net.kyori.adventure.text.format.TextColor yellow = net.kyori.adventure.text.format.NamedTextColor.YELLOW;
        net.kyori.adventure.text.format.TextColor gray = net.kyori.adventure.text.format.NamedTextColor.GRAY;
        String tierName = tierDisplay(tier);
        p.sendMessage(net.kyori.adventure.text.Component.empty());
        p.sendMessage(net.kyori.adventure.text.Component.text("✨ Welcome to ", yellow)
            .append(net.kyori.adventure.text.Component.text(tierName, gold)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
            .append(net.kyori.adventure.text.Component.text("! ✨", yellow)));
        // Headline perks for the tier they just received.
        boolean plusplus = tier != null && tier.toLowerCase(Locale.ROOT).contains("plusplus");
        String headline = plusplus
            ? "27 homes, 45 market slots, 2x shards & 1000/mo"
            : "9 homes, 27 market slots, faster cooldowns & 500/mo";
        p.sendMessage(net.kyori.adventure.text.Component.text(headline, net.kyori.adventure.text.format.NamedTextColor.WHITE));
        p.sendMessage(net.kyori.adventure.text.Component.text("To see everything, run ", gray)
            .append(net.kyori.adventure.text.Component.text("/perks", brand)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)));
        p.sendMessage(net.kyori.adventure.text.Component.empty());
        try { p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f); } catch (Throwable ignored) {}
    }

    // ---- /atrack <player> — admin spectator tracking in 3rd person ----

    private boolean handleAtrackCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(color("&cOnly players can use /atrack."));
            return true;
        }
        if (!admin.hasPermission(PERM_ATRACK_CMD)) {
            admin.sendMessage(color("&cNo permission."));
            return true;
        }
        // No args = stop tracking
        if (args.length == 0) {
            stopTracking(admin);
            return true;
        }
        Player target = getServer().getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            admin.sendMessage(color("&cPlayer not found or offline."));
            return true;
        }
        if (target.getUniqueId().equals(admin.getUniqueId())) {
            admin.sendMessage(color("&cYou can't track yourself."));
            return true;
        }
        // If already tracking someone, stop previous tracking first
        if (atrackTargets.containsKey(admin.getUniqueId())) {
            stopTrackingTask(admin.getUniqueId());
        }
        // Save prior gamemode if not already spectator
        if (admin.getGameMode() != GameMode.SPECTATOR) {
            atrackPriorMode.put(admin.getUniqueId(), admin.getGameMode());
        }
        admin.setGameMode(GameMode.SPECTATOR);
        admin.teleport(target.getLocation());
        atrackTargets.put(admin.getUniqueId(), target.getUniqueId());
        // Schedule repeating task: re-lock spectator target every tick
        int taskId = getServer().getScheduler().runTaskTimer(this, () -> {
            if (!admin.isOnline()) { stopTrackingTask(admin.getUniqueId()); return; }
            UUID targetUuid = atrackTargets.get(admin.getUniqueId());
            if (targetUuid == null) { stopTrackingTask(admin.getUniqueId()); return; }
            Player t = getServer().getPlayer(targetUuid);
            if (t == null || !t.isOnline()) {
                admin.sendActionBar(net.kyori.adventure.text.Component.text("§cTarget disconnected — tracking stopped"));
                stopTracking(admin);
                return;
            }
            admin.setSpectatorTarget(t);
        }, 1L, 1L).getTaskId();
        atrackTasks.put(admin.getUniqueId(), taskId);
        admin.sendActionBar(net.kyori.adventure.text.Component.text("§dNow tracking §f" + target.getName() + " §d— /atrack to stop"));
        return true;
    }

    private void stopTracking(Player admin) {
        UUID uuid = admin.getUniqueId();
        if (!atrackTargets.containsKey(uuid)) {
            admin.sendMessage(color("&7Not tracking anyone."));
            return;
        }
        stopTrackingTask(uuid);
        // Remove spectator target before mode change
        admin.setSpectatorTarget(null);
        GameMode prior = atrackPriorMode.remove(uuid);
        if (prior != null && prior != GameMode.SPECTATOR) {
            admin.setGameMode(prior);
        }
        admin.sendActionBar(net.kyori.adventure.text.Component.text("§7Tracking stopped"));
    }

    private void stopTrackingTask(UUID uuid) {
        atrackTargets.remove(uuid);
        Integer taskId = atrackTasks.remove(uuid);
        if (taskId != null) {
            getServer().getScheduler().cancelTask(taskId);
        }
    }

    private boolean handlePerksCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /perks."));
            return true;
        }
        if (!player.hasPermission(PERM_PERKS_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /perks."));
            return true;
        }
        net.kyori.adventure.text.format.TextColor brand = net.kyori.adventure.text.format.TextColor.color(0x00BFFF);
        net.kyori.adventure.text.format.TextColor gold = net.kyori.adventure.text.format.NamedTextColor.GOLD;
        net.kyori.adventure.text.format.TextColor gray = net.kyori.adventure.text.format.NamedTextColor.GRAY;
        net.kyori.adventure.text.format.TextColor white = net.kyori.adventure.text.format.NamedTextColor.WHITE;
        boolean hasPlusPlus = player.hasPermission(PERM_NODE_PIZZAPLUSPLUS);
        boolean hasPlus = hasPlusPlus || player.hasPermission(PERM_NODE_PIZZAPLUS);
        player.sendMessage(net.kyori.adventure.text.Component.empty());
        player.sendMessage(net.kyori.adventure.text.Component.text("✨ ", gold)
            .append(net.kyori.adventure.text.Component.text("Pizza+ & Pizza++ Perks", gold)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
            .append(net.kyori.adventure.text.Component.text(" ✨", gold)));
        player.sendMessage(net.kyori.adventure.text.Component.text("Pizza+", gold)
            .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)
            .append(net.kyori.adventure.text.Component.text("  (500 shards/mo)", gray)));
        String[] plusPerks = {
            "9 homes (default 3)",
            "27 auction & order slots (default 18)",
            "Faster RTP & TPA cooldowns (15s)",
            "Priority queue during maintenance",
            "Pizza+ tag in chat & tab",
            "500 bonus shards every month"
        };
        for (String perk : plusPerks) {
            player.sendMessage(net.kyori.adventure.text.Component.text(" • ", brand)
                .append(net.kyori.adventure.text.Component.text(perk, white)));
        }
        player.sendMessage(net.kyori.adventure.text.Component.text("Pizza++", gold)
            .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)
            .append(net.kyori.adventure.text.Component.text("  (everything above, plus...)", gray)));
        String[] plusPlusPerks = {
            "27 homes",
            "45 auction & order slots (the max)",
            "Fastest RTP & TPA cooldowns (8s)",
            "2x shards (passive earn & PvP kills)",
            "1000 bonus shards every month",
            "Gold ++ tag in chat & tab",
            "Front of the priority queue"
        };
        for (String perk : plusPlusPerks) {
            player.sendMessage(net.kyori.adventure.text.Component.text(" • ", gold)
                .append(net.kyori.adventure.text.Component.text(perk, white)));
        }
        player.sendMessage(net.kyori.adventure.text.Component.empty());
        if (hasPlusPlus) {
            player.sendMessage(net.kyori.adventure.text.Component.text("You currently have ", gray)
                .append(net.kyori.adventure.text.Component.text("Pizza++", gold)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
                .append(net.kyori.adventure.text.Component.text(" — enjoy!", gray)));
        } else if (hasPlus) {
            player.sendMessage(net.kyori.adventure.text.Component.text("You currently have ", gray)
                .append(net.kyori.adventure.text.Component.text("Pizza+", gold)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
                .append(net.kyori.adventure.text.Component.text(" — upgrade to ", gray))
                .append(net.kyori.adventure.text.Component.text("Pizza++", gold)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
                .append(net.kyori.adventure.text.Component.text(" for even more!", gray)));
        } else {
            player.sendMessage(net.kyori.adventure.text.Component.text("Upgrade to ", gray)
                .append(net.kyori.adventure.text.Component.text("Pizza+", gold)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
                .append(net.kyori.adventure.text.Component.text(" or ", gray))
                .append(net.kyori.adventure.text.Component.text("Pizza++", gold)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true))
                .append(net.kyori.adventure.text.Component.text(" to unlock these perks.", gray)));
        }
        player.sendMessage(net.kyori.adventure.text.Component.empty());
        return true;
    }

    private void fillStashChest(org.bukkit.block.Block chestBlock, java.util.concurrent.ThreadLocalRandom rng) {
        if (!(chestBlock.getState() instanceof org.bukkit.block.Chest chest)) return;
        Material[] loot = {
            Material.BREAD, Material.COOKED_BEEF, Material.COOKED_CHICKEN, Material.GOLDEN_APPLE,
            Material.TORCH, Material.OAK_PLANKS, Material.OAK_LOG, Material.COBBLESTONE,
            Material.IRON_INGOT, Material.GOLD_INGOT, Material.COAL, Material.IRON_PICKAXE,
            Material.IRON_SWORD, Material.IRON_AXE, Material.ARROW, Material.ENDER_PEARL,
            Material.WHEAT, Material.CARROT, Material.POTATO, Material.STRING,
            Material.LEATHER_CHESTPLATE, Material.IRON_HELMET, Material.WATER_BUCKET,
            Material.OAK_SAPLING, Material.APPLE };
        int slots = 4 + rng.nextInt(6); // 4-9 filled slots
        org.bukkit.inventory.Inventory inv = chest.getBlockInventory();
        for (int i = 0; i < slots; i++) {
            Material m = loot[rng.nextInt(loot.length)];
            int max = m.getMaxStackSize();
            int amt = max <= 1 ? 1 : 1 + rng.nextInt(Math.min(max, 16));
            inv.setItem(rng.nextInt(inv.getSize()), new ItemStack(m, amt));
        }
    }

    private boolean handleHomesCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /homes."));
            return true;
        }
        if (!player.hasPermission(PERM_HOMES_CMD) || !player.hasPermission(PERM_HOME_USE)) {
            player.sendMessage(color("&cYou do not have permission to use /homes."));
            return true;
        }
        setTargetToSelf(player);
        openHomesMenu(player, player);
        return true;
    }

    private boolean handleMenuCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /menu."));
            return true;
        }
        if (!player.hasPermission(PERM_MENU_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /menu."));
            return true;
        }
        if (useDialogUi(player)) {
            // Java/dialog clients open the Example SMP quick menu (the same datapack dialog the
            // pause screen uses, so there is a single source of truth for the quick menu).
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialog show " + player.getName() + " pizzasmp:menu");
        } else {
            // Bedrock / legacy clients keep the DeluxeMenus inventory fallback.
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dm open pizzasmp_menu " + player.getName());
        }
        return true;
    }

    private boolean handleGuideCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /guide."));
            return true;
        }
        if (!player.hasPermission(PERM_GUIDE_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /guide."));
            return true;
        }
        if (useDialogUi(player)) {
            openGuideDialog(player);
        } else {
            // Bedrock / legacy clients keep the DeluxeMenus guide book fallback.
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dm open pizzasmp_guide_book " + player.getName());
        }
        return true;
    }

    // ===================== Guide dialog (Java / dialog clients) =====================

    private Component guideLine(String label, String desc) {
        return Component.text(label, NamedTextColor.WHITE)
            .append(Component.text("  " + desc, NamedTextColor.GRAY));
    }

    private void openGuideDialog(Player player) {
        java.util.List<DialogBody> body = java.util.List.of(
            DialogBody.plainMessage(Component.text("Welcome to PizzaSMP. Pick a section below.", NamedTextColor.GRAY)));
        java.util.List<ActionButton> buttons = java.util.List.of(
            dialogButton(Component.text("Commands", DIALOG_BRAND), null, 150, p -> openGuideSection(p, "commands")),
            dialogButton(Component.text("Economy & Shop", DIALOG_BRAND), null, 150, p -> openGuideSection(p, "economy")),
            dialogButton(Component.text("Social", DIALOG_BRAND), null, 150, p -> openGuideSection(p, "social")),
            dialogButton(Component.text("Rules", DIALOG_BRAND), null, 150, p -> openGuideSection(p, "rules")),
            dialogButton(Component.text("Discord", NamedTextColor.BLUE), null, 150, p -> { p.closeDialog(); p.performCommand("discord"); }),
            dialogButton(Component.text("Open Menu", NamedTextColor.WHITE), null, 150, p -> { p.closeDialog(); p.performCommand("menu"); }));
        Dialog dialog = buildDialog(Component.text("Server Guide", DIALOG_BRAND), body, java.util.List.of(),
            DialogType.multiAction(buttons).columns(2)
                .exitAction(dialogButton(Component.text("Close"), null, 150, p -> p.closeDialog())).build());
        player.showDialog(dialog);
    }

    private void openGuideSection(Player player, String section) {
        String title;
        java.util.List<DialogBody> body = new java.util.ArrayList<>();
        switch (section) {
            case "commands" -> {
                title = "Commands";
                body.add(DialogBody.plainMessage(guideLine("/menu", "open the quick menu")));
                body.add(DialogBody.plainMessage(guideLine("/home, /homes", "your homes")));
                body.add(DialogBody.plainMessage(guideLine("/sethome [name]", "set a home")));
                body.add(DialogBody.plainMessage(guideLine("/rtp", "random teleport")));
                body.add(DialogBody.plainMessage(guideLine("/tpa <player>", "request to teleport")));
                body.add(DialogBody.plainMessage(guideLine("/ping", "show your latency")));
                body.add(DialogBody.plainMessage(guideLine("/linkaccount", "link Java/Bedrock")));
            }
            case "economy" -> {
                title = "Economy & Shop";
                body.add(DialogBody.plainMessage(guideLine("/shop", "buy server items")));
                body.add(DialogBody.plainMessage(guideLine("/ah", "auction house")));
                body.add(DialogBody.plainMessage(guideLine("/orders", "buy orders from players")));
                body.add(DialogBody.plainMessage(guideLine("/sell", "sell held or inventory items")));
                body.add(DialogBody.plainMessage(guideLine("/worth", "check item sell value")));
                body.add(DialogBody.plainMessage(guideLine("/pay <player> <amt>", "send money")));
                body.add(DialogBody.plainMessage(guideLine("/shards", "shard shop & crates")));
            }
            case "social" -> {
                title = "Social";
                body.add(DialogBody.plainMessage(guideLine("/friend", "open the friends menu")));
                body.add(DialogBody.plainMessage(guideLine("/follow <player>", "follow a player")));
                body.add(DialogBody.plainMessage(guideLine("/unfollow <player>", "stop following")));
                body.add(DialogBody.plainMessage(Component.text("A mutual follow makes you friends.", NamedTextColor.GRAY)));
                body.add(DialogBody.plainMessage(guideLine("/leaderboard", "top players")));
            }
            default -> {
                title = "Rules";
                body.add(DialogBody.plainMessage(Component.text("1) No cheating", NamedTextColor.GRAY)));
                body.add(DialogBody.plainMessage(Component.text("2) No slurs / racism", NamedTextColor.GRAY)));
                body.add(DialogBody.plainMessage(Component.text("3) No doxxing / threats", NamedTextColor.GRAY)));
                body.add(DialogBody.plainMessage(Component.text("Appeals: Discord #appeals", NamedTextColor.GRAY)));
            }
        }
        Dialog dialog = buildDialog(Component.text(title, DIALOG_BRAND), body, java.util.List.of(),
            DialogType.multiAction(java.util.List.of(
                dialogButton(Component.text("Back to Guide"), null, 150, this::openGuideDialog)))
                .columns(1)
                .exitAction(dialogButton(Component.text("Close"), null, 150, p -> p.closeDialog())).build());
        player.showDialog(dialog);
    }

    private boolean handleFreezeCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission(PERM_FREEZE_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /freeze."));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(color("&cUsage: &e/freeze <player>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sendOfflinePlayerMessage(sender, args[0]);
            return true;
        }

        UUID uuid = target.getUniqueId();
        if (!frozenPlayers.add(uuid)) {
            sender.sendMessage(color("&e" + target.getName() + " is already frozen."));
            return true;
        }

        frozenAnchors.put(uuid, target.getLocation().clone());
        frozenNoticeCooldown.remove(uuid);
        target.setVelocity(new Vector(0, 0, 0));
        if (target.isFlying()) {
            target.setFlying(false);
        }

        target.sendMessage(color("&cYou have been frozen by staff. Do not log out."));
        target.sendActionBar(color("&cYou are frozen."));
        sender.sendMessage(color("&aFrozen &e" + target.getName() + "&a."));
        return true;
    }

    private boolean handleUnfreezeCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission(PERM_UNFREEZE_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /unfreeze."));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(color("&cUsage: &e/unfreeze <player>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sendOfflinePlayerMessage(sender, args[0]);
            return true;
        }

        UUID uuid = target.getUniqueId();
        if (!frozenPlayers.remove(uuid)) {
            sender.sendMessage(color("&e" + target.getName() + " is not frozen."));
            return true;
        }

        frozenAnchors.remove(uuid);
        frozenNoticeCooldown.remove(uuid);
        target.sendMessage(color("&aYou have been unfrozen."));
        sender.sendMessage(color("&aUnfroze &e" + target.getName() + "&a."));
        return true;
    }

    // ===== Frozen-in-place maintenance ("virtual maintenance") =====

    private boolean isMaintenanceStaff(Player p) {
        return p.hasPermission(PERM_MAINT_ADMIN) || p.hasPermission(PERM_MAINTENANCE_STAY)
            || p.hasPermission("pizzasmp.staff");
    }

    private void showMaintenanceTitle(Player p) {
        // Green, persistent hotbar (action bar) — refreshed by the maintenance task.
        p.sendActionBar(color("&aYour region is under maintenance"));
    }

    private void enterMaintenanceFreeze(Player p) {
        enterMaintenanceFreeze(p, false);
    }

    private void enterMaintenanceFreeze(Player p, boolean force) {
        if (p == null || !p.isOnline()) return;
        if (!force && isMaintenanceStaff(p)) return;
        UUID id = p.getUniqueId();
        frozenAnchors.put(id, p.getLocation().clone());
        frozenPlayers.add(id);
        maintenanceFrozen.add(id);
        try { p.setInvulnerable(true); } catch (Throwable ignored) {}
        showMaintenanceTitle(p);
    }

    private void exitMaintenanceFreeze(Player p) {
        if (p == null) return;
        UUID id = p.getUniqueId();
        maintenanceFrozen.remove(id);
        frozenPlayers.remove(id);
        frozenAnchors.remove(id);
        frozenNoticeCooldown.remove(id);
        try { p.setInvulnerable(false); } catch (Throwable ignored) {}
        if (p.isOnline()) {
            p.resetTitle();
            p.sendActionBar(net.kyori.adventure.text.Component.text(color("&aMaintenance complete — welcome back!")));
        }
    }

    private boolean handleServerMaintCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission(PERM_MAINT_ADMIN)) {
            player.sendMessage(color("&cYou do not have permission to use /servermaint."));
            return true;
        }
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "start", "on" -> {
                if (args.length >= 2) {
                    maintenanceReason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                }
                maintenanceActive = true;
                saveMaintenanceMode();
                int frozen = 0;
                // Freeze EVERYONE, including staff (force=true). Maintenance-admins keep command
                // access (see onFrozenCommandBlock) so they can still run /servermaint end.
                for (Player p : Bukkit.getOnlinePlayers()) {
                    enterMaintenanceFreeze(p, true); frozen++;
                }
                sender.sendMessage(color("&aFrozen-maintenance &lON&a. Froze &e" + frozen + " &aplayer(s). Reason: &f" + maintenanceReason));
                getLogger().info("[Maintenance] Frozen-maintenance ON by " + sender.getName() + " (" + maintenanceReason + ") — froze " + frozen + ".");
            }
            case "end", "off" -> {
                maintenanceActive = false;
                saveMaintenanceMode();
                int unfrozen = 0;
                for (UUID id : java.util.List.copyOf(maintenanceFrozen)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) { exitMaintenanceFreeze(p); unfrozen++; }
                    else { maintenanceFrozen.remove(id); frozenPlayers.remove(id); frozenAnchors.remove(id); }
                }
                sender.sendMessage(color("&aFrozen-maintenance &lOFF&a. Released &e" + unfrozen + " &aplayer(s)."));
                getLogger().info("[Maintenance] Frozen-maintenance OFF by " + sender.getName() + " — released " + unfrozen + ".");
            }
            case "status" -> {
                sender.sendMessage(color("&fFrozen-maintenance: " + (maintenanceActive ? "&aACTIVE" : "&cinactive")
                    + " &7| frozen players: &e" + maintenanceFrozen.size() + " &7| reason: &f" + maintenanceReason));
            }
            default -> sender.sendMessage(color("&cUsage: &e/servermaint <start [reason]|end|status>"));
        }
        return true;
    }

    private void loadMaintenanceMode() {
        maintenanceModeFile = new File(getDataFolder(), "maintenance-mode.yml");
        if (!maintenanceModeFile.exists()) return;
        FileConfiguration c = YamlConfiguration.loadConfiguration(maintenanceModeFile);
        maintenanceActive = c.getBoolean("active", false);
        maintenanceReason = c.getString("reason", "Scheduled maintenance");
        // If we restarted while in maintenance, currently-online players get re-frozen via onJoin.
    }

    private void saveMaintenanceMode() {
        if (maintenanceModeFile == null) maintenanceModeFile = new File(getDataFolder(), "maintenance-mode.yml");
        YamlConfiguration c = new YamlConfiguration();
        c.set("active", maintenanceActive);
        c.set("reason", maintenanceReason);
        try { c.save(maintenanceModeFile); } catch (Exception ex) { getLogger().warning("Failed saving maintenance mode: " + ex.getMessage()); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMaintenanceJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (!maintenanceActive) return;
        Player p = event.getPlayer();
        // Everyone (including staff) is frozen during maintenance.
        Bukkit.getScheduler().runTaskLater(this, () -> enterMaintenanceFreeze(p, true), 2L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMaintenanceInteract(org.bukkit.event.block.BlockBreakEvent event) {
        if (maintenanceFrozen.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMaintenancePlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (maintenanceFrozen.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    private boolean handleTransferCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission(PERM_TRANSFER_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /transfer."));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(color("&cUsage: &e/transfer <destination|host:port|host port>"));
            sender.sendMessage(color("&cUsage: &e/transfer <player> <destination|host:port|host port>"));
            return true;
        }

        TransferDestination destination;
        Player target;

        if (sender instanceof Player playerSender) {
            if (args.length == 1) {
                destination = parseTransferDestination(args[0], null);
                if (destination == null) {
                    playerSender.sendMessage(color("&cUnknown destination. Use a configured destination name or host:port."));
                    return true;
                }
                target = playerSender;
            } else if (args.length == 2 && isLikelyPort(args[1])) {
                destination = parseTransferDestination(args[0], args[1]);
                if (destination == null) {
                    playerSender.sendMessage(color("&cInvalid host/port."));
                    return true;
                }
                target = playerSender;
            } else {
                if (!playerSender.hasPermission(PERM_TRANSFER_OTHERS_CMD)) {
                    playerSender.sendMessage(color("&cYou do not have permission to transfer other players."));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    sendOfflinePlayerMessage(playerSender, args[0]);
                    return true;
                }
                if (args.length == 2) {
                    destination = parseTransferDestination(args[1], null);
                } else if (args.length == 3) {
                    destination = parseTransferDestination(args[1], args[2]);
                } else {
                    playerSender.sendMessage(color("&cUsage: &e/transfer <player> <destination|host:port|host port>"));
                    return true;
                }
                if (destination == null) {
                    playerSender.sendMessage(color("&cUnknown destination. Use a configured destination name or host:port."));
                    return true;
                }
            }
        } else {
            if (args.length < 2) {
                sender.sendMessage(color("&cConsole usage: &e/transfer <player> <destination|host:port|host port>"));
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sendOfflinePlayerMessage(sender, args[0]);
                return true;
            }
            if (args.length == 2) {
                destination = parseTransferDestination(args[1], null);
            } else if (args.length == 3) {
                destination = parseTransferDestination(args[1], args[2]);
            } else {
                sender.sendMessage(color("&cConsole usage: &e/transfer <player> <destination|host:port|host port>"));
                return true;
            }
            if (destination == null) {
                sender.sendMessage(color("&cUnknown destination. Use a configured destination name or host:port."));
                return true;
            }
        }

        target.transfer(destination.host, destination.port);
        if (sender instanceof Player playerSender && playerSender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(color("&aTransferring you to &e" + destination.host + ":" + destination.port + "&a..."));
        } else {
            sender.sendMessage(color("&aTransferring &e" + target.getName() + " &ato &e" + destination.host + ":" + destination.port + "&a..."));
        }
        return true;
    }

    private boolean handleTransferMaintenanceCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission(PERM_TRANSFER_MAINTENANCE_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /transfermaintenance."));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(color("&cUsage: &e/transfermaintenance <on|off|status>"));
            return true;
        }

        String mode = args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "on":
            case "enable":
            case "start":
                return handleMaintenanceTransferEnable(sender);
            case "off":
            case "disable":
            case "stop":
            case "end":
                return handleMaintenanceTransferDisable(sender);
            case "status":
                sender.sendMessage(color("&fMaintenance transfer active: " + (isMaintenanceTransferActive() ? "&aON" : "&cOFF")));
                sender.sendMessage(color("&fDestination: &b" + getMaintenanceTransferDestinationName()));
                sender.sendMessage(color("&fTracked transferred users: &b" + getMaintenanceTransferred().size()));
                return true;
            default:
                sender.sendMessage(color("&cUsage: &e/transfermaintenance <on|off|status>"));
                return true;
        }
    }

    private boolean handleMaintenanceTransferEnable(CommandSender sender) {
        TransferDestination destination = parseTransferDestination(getMaintenanceTransferDestinationName(), null);
        if (destination == null) {
            sender.sendMessage(color("&cInvalid maintenance destination. Check &eplugins/PizzaAdminTools/transfer-destinations.yml"));
            return true;
        }

        setMaintenanceTransferActive(true);

        int moved = 0;
        int skipped = 0;
        Set<String> transferred = getMaintenanceTransferred();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (shouldStayDuringMaintenance(online)) {
                skipped++;
                continue;
            }
            for (String line : getMaintenanceStartMessages(destination.host, destination.port)) {
                online.sendMessage(color(line));
            }
            online.transfer(destination.host, destination.port);
            moved++;
            transferred.add(online.getUniqueId().toString());
        }
        saveMaintenanceTransferred(transferred);
        saveMaintenanceTransferState();

        sender.sendMessage(color("&aMaintenance transfer started. Moved: &e" + moved + "&a, skipped: &e" + skipped + "&a."));
        return true;
    }

    private boolean handleMaintenanceTransferDisable(CommandSender sender) {
        setMaintenanceTransferActive(false);
        saveMaintenanceTransferState();
        sender.sendMessage(color("&aMaintenance transfer disabled."));
        sender.sendMessage(color("&7Note: players already on maintenance server cannot be force-returned from here."));
        return true;
    }

    private boolean handlePizzaAdminToolsHelp(CommandSender sender) {
        sender.sendMessage(color("&bPizzaAdminTools Commands"));
        sender.sendMessage(color("&f/pizzaadmintools help &7- Show this list"));
        sender.sendMessage(color("&f/pizzateams help &7- Team command hub"));
        sender.sendMessage(color("&f/pizzamenus help &7- Menu command hub"));
        sender.sendMessage(color("&f/pizzahome help &7- Home command hub"));
        sender.sendMessage(color("&f/pizzabans help &7- Moderation command hub"));
        sender.sendMessage(color("&f/pizzaplugins &7- Branded plugin list (staff)"));
        sender.sendMessage(color("&f/gtp, /homes, /menu, /guide, /freeze, /unfreeze, /transfer, /sus"));
        return true;
    }

    private boolean handlePizzaTeamsHelp(CommandSender sender) {
        sender.sendMessage(color("&bPizzaTeams Commands"));
        sender.sendMessage(color("&f/team help &7- Full BetterTeams command help"));
        sender.sendMessage(color("&f/team create <name>"));
        sender.sendMessage(color("&f/team invite <player>"));
        sender.sendMessage(color("&f/team home &7- Teleport to team home"));
        sender.sendMessage(color("&f/team sethome &7- Set team home (leaders/admin)"));
        return true;
    }

    private boolean handlePizzaMenusHelp(CommandSender sender) {
        sender.sendMessage(color("&bPizzaMenus Commands"));
        sender.sendMessage(color("&f/menu &7- Open PizzaSMP main menu"));
        sender.sendMessage(color("&f/guide &7- Open PizzaSMP guide"));
        return true;
    }

    private boolean handlePizzaHomeHelp(CommandSender sender) {
        sender.sendMessage(color("&bPizzaHome Commands"));
        sender.sendMessage(color("&f/home &7- Open homes menu"));
        sender.sendMessage(color("&f/homes &7- Open homes menu"));
        sender.sendMessage(color("&f/home <name|slot> &7- Teleport to home"));
        sender.sendMessage(color("&f/gtp <player> [home] &7- Staff/admin home teleport"));
        return true;
    }

    private boolean handlePizzaBansHelp(CommandSender sender) {
        sender.sendMessage(color("&bPizzaBans Commands"));
        sender.sendMessage(color("&f/punish <player> <category|duration|reason...> [duration]"));
        sender.sendMessage(color("&f/ban, /permban, /ipban, /ippermban, /mute, /kick"));
        sender.sendMessage(color("&f/unban, /unpunish, /forgive, /idunban, /unmute"));
        sender.sendMessage(color("&f/bans or /moderation &7- Open the moderation GUI"));
        sender.sendMessage(color("&f/history <player|ip|id>, /searchid <id>"));
        sender.sendMessage(color("&f/listbans, /listmutes, /clearbans, /clearmutes"));
        sender.sendMessage(color("&f/sus &7- Grim suspects from the last 30 minutes"));
        sender.sendMessage(color("&f/clearwarnings <player>"));
        sender.sendMessage(color("&f/clearallwarnings [player]"));
        return true;
    }

    private boolean handleSusCommand(CommandSender sender, String[] args) {
        int requestedPage = 0;
        if (args.length > 0 && !args[0].isBlank()) {
            try {
                requestedPage = Math.max(0, Integer.parseInt(args[0]) - 1);
            } catch (NumberFormatException ex) {
                sender.sendMessage(color("&cUsage: &e/sus [page]"));
                return true;
            }
        }

        if (sender instanceof Player player) {
            if (!player.hasPermission(PERM_SUS_CMD) && !player.hasPermission(PERM_PLUGIN_ADMIN)) {
                player.sendMessage(color("&cYou do not have permission to use /sus."));
                return true;
            }
            openSusMenu(player, requestedPage);
            return true;
        }

        sendConsoleSusPage(sender, requestedPage);
        return true;
    }

    private boolean handlePizzaPlugins(CommandSender sender) {
        if (sender instanceof Player player && !player.hasPermission(PERM_PLUGIN_ADMIN)) {
            player.sendMessage(color("&cYou do not have permission to view plugin/admin commands."));
            return true;
        }
        sendBrandedPluginList(sender);
        return true;
    }

    private boolean handleNightVisionCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cThis command can only be used by players."));
            return true;
        }
        if (!player.hasPermission(PERM_NV_CMD)) {
            player.sendMessage(color("&cYou do not have permission to use /nv."));
            return true;
        }
        Player target = player;
        if (args.length > 0) {
            if (!player.hasPermission(PERM_NV_OTHERS_CMD)) {
                player.sendMessage(color("&cYou do not have permission to toggle night vision on others."));
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage(color("&cPlayer not found: &e" + args[0]));
                return true;
            }
        }
        if (this.nvEnabled.contains(target.getUniqueId())) {
            this.nvEnabled.remove(target.getUniqueId());
            target.removePotionEffect(PotionEffectType.NIGHT_VISION);
            target.sendActionBar(net.kyori.adventure.text.Component.text("§7night vision disabled"));
            if (target != player) player.sendActionBar(net.kyori.adventure.text.Component.text("§7night vision disabled for " + target.getName()));
        } else {
            this.nvEnabled.add(target.getUniqueId());
            target.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            target.sendActionBar(net.kyori.adventure.text.Component.text("§7night vision enabled"));
            if (target != player) player.sendActionBar(net.kyori.adventure.text.Component.text("§7night vision enabled for " + target.getName()));
        }
        this.saveNvState();
        return true;
    }

    private File nvStateFile() {
        return new File(getDataFolder(), "nv-players.yml");
    }

    private void saveNvState() {
        try {
            File f = nvStateFile();
            FileConfiguration cfg = new YamlConfiguration();
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (UUID id : this.nvEnabled) ids.add(id.toString());
            cfg.set("players", ids);
            cfg.save(f);
        } catch (Exception ex) {
            getLogger().warning("Failed saving NV state: " + ex.getMessage());
        }
    }

    private void loadNvState() {
        try {
            File f = nvStateFile();
            if (!f.exists()) return;
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            for (String s : cfg.getStringList("players")) {
                try { this.nvEnabled.add(UUID.fromString(s)); } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            getLogger().warning("Failed loading NV state: " + ex.getMessage());
        }
    }

    private void sendBrandedPluginList(CommandSender sender) {
        sender.sendMessage(color("&bPizzaSMP Plugin Stack &7(" + BRANDED_PLUGIN_LIST.size() + ")"));
        sender.sendMessage(color("&f" + String.join("&7, &f", BRANDED_PLUGIN_LIST)));
    }

    // /maintenance motd "<text>" — sets the kennytv Maintenance ping MOTD live.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMaintenanceMotdCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("/maintenance motd ") || lower.startsWith("/maintenance setmotd "))) {
            return;
        }
        if (!player.hasPermission("pizzasmp.maintenance.motd") && !player.hasPermission(PERM_PLUGIN_ADMIN)) {
            event.setCancelled(true);
            player.sendMessage(color("&cNo permission."));
            return;
        }
        event.setCancelled(true);
        // Extract text after the subcommand, stripping surrounding quotes.
        int idx = msg.indexOf(' ', msg.indexOf("motd") );
        String text = msg.substring(msg.toLowerCase(Locale.ROOT).indexOf("motd ") + 5).trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isBlank()) {
            player.sendMessage(color("&cUsage: /maintenance motd \"<text>\""));
            return;
        }
        if (setMaintenanceMotd(text)) {
            player.sendMessage(color("&aMaintenance MOTD set to: &f" + text));
            // Reload the Maintenance plugin's config so it takes effect immediately.
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "maintenance reloadconfig");
        } else {
            player.sendMessage(color("&cFailed to update Maintenance config."));
        }
    }

    private boolean setMaintenanceMotd(String text) {
        try {
            File cfgFile = new File(getDataFolder().getParentFile(), "Maintenance/config.yml");
            if (!cfgFile.exists()) return false;
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
            // kennytv Maintenance stores ping lines under ping-message.messages (list)
            cfg.set("ping-message.messages", java.util.List.of(text));
            cfg.save(cfgFile);
            return true;
        } catch (Exception ex) {
            getLogger().warning("Failed to set Maintenance MOTD: " + ex.getMessage());
            return false;
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPluginAdminCommandGate(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String[] parts = parseCommandParts(event.getMessage());
        if (parts.length == 0) {
            return;
        }

        String root = parts[0];
        if (!PLUGIN_ADMIN_COMMANDS.contains(root)) {
            return;
        }

        boolean allowed = player.hasPermission(PERM_PLUGIN_ADMIN);
        if (!allowed) {
            event.setCancelled(true);
            player.sendMessage(color("&cThis command is staff-only."));
            return;
        }

        if (root.equals("version") || root.equals("ver") || root.equals("about")) {
            event.setCancelled(true);
            sendBrandedPluginList(player);
        }
    }

    // Non-staff command lockdown: for default / pizza+ players, listed admin /
    // non-gameplay commands behave EXACTLY like an unknown command — same hotbar,
    // no "no permission" hint. Strips the optional minecraft:/bukkit: namespace.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onRestrictedNonStaffCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isEffectivelyStaff(player)) {
            return; // staff and above see/use everything as normal (unless in /sfmode)
        }
        // /sfmode itself must stay usable by real staff even while staff mode is ON (to toggle off).
        String[] sfParts = parseCommandParts(event.getMessage());
        if (sfParts.length > 0 && sfParts[0].equalsIgnoreCase("sfmode") && player.hasPermission(PERM_PLUGIN_ADMIN)) {
            return;
        }
        String[] parts = parseCommandParts(event.getMessage());
        if (parts.length == 0) {
            return;
        }
        String root = parts[0].toLowerCase(Locale.ROOT);
        int colonIdx = root.indexOf(':');
        if (colonIdx > 0) {
            root = root.substring(colonIdx + 1);
        }
        if (RESTRICTED_FOR_NON_STAFF.contains(root)) {
            event.setCancelled(true);
            sendUnknownCommandMessage(player);
        }
    }

    // Hide restricted commands from non-staff clients entirely: they get no root
    // suggestion and no argument tab-completion (the command tree sent on join /
    // permission recalculation simply omits them).
    @EventHandler
    public void onCommandSendFilter(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        // /gmcbypass is CONSOLE-ONLY — strip it from EVERYONE's command tree (staff included),
        // so no player ever gets the arg suggestion / tab completion for it.
        event.getCommands().removeIf(cmd -> {
            String r = cmd.toLowerCase(Locale.ROOT);
            int c = r.indexOf(':');
            if (c > 0) r = r.substring(c + 1);
            return r.equals("gmcbypass");
        });
        if (isEffectivelyStaff(player)) {
            return; // staff see everything (unless in /sfmode)
        }
        // Real staff in /sfmode must keep /sfmode in their command tree so they can toggle it back
        // off (Bedrock/Geyser won't send a command the client doesn't know about).
        boolean realStaff = player.hasPermission(PERM_PLUGIN_ADMIN);
        event.getCommands().removeIf(cmd -> {
            String raw = cmd.toLowerCase(Locale.ROOT);
            // Hide EVERY namespaced command variant (essentials:, deluxemenus:, minecraft:,
            // bukkit:, viaversion:, ...) from non-staff. These are plugin internals / duplicates
            // of a root command and only clutter tab completion. The un-namespaced root (e.g.
            // "pay") is kept if it isn't restricted, so player commands still work.
            if (raw.indexOf(':') >= 0) {
                return true;
            }
            if (raw.equals("sfmode") && realStaff) {
                return false; // keep usable
            }
            return RESTRICTED_FOR_NON_STAFF.contains(raw);
        });
    }

    // Creative & Adventure are ALWAYS denied to everyone (staff and users). Survival + spectator
    // stay available. The GameModeChangeEvent is the catch-all (blocks any path); the command
    // handler below just gives a clean message before the command runs.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCreativeAdventureBlock(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        // Config-gated (2026-08-08). This used to cancel unconditionally for EVERYONE,
        // including dev/owner and regardless of any LuckPerms grant, which made creative
        // impossible to enable on a dev backend. Default is now OFF; set
        // gamemode.block-creative-adventure: true in config.yml to restore the lockdown.
        if (!getConfig().getBoolean("gamemode.block-creative-adventure", false)) {
            return;
        }
        GameMode m = event.getNewGameMode();
        if (m == GameMode.CREATIVE || m == GameMode.ADVENTURE) {
            // Console-issued /gmcbypass adds a one-time pass.
            if (creativeBypass.remove(event.getPlayer().getUniqueId())) {
                return;
            }
            // Anyone explicitly granted the bypass node is exempt.
            if (event.getPlayer().hasPermission("pizzasmp.gamemode.bypass")) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onGamemodeCommandBlock(PlayerCommandPreprocessEvent event) {
        // Same gate as onCreativeAdventureBlock. This SECOND handler was missed when the
        // event handler was gated, so /gmc and /gamemode creative stayed blocked even
        // with the lockdown off - the command was refused before it ever reached Bukkit.
        if (!getConfig().getBoolean("gamemode.block-creative-adventure", false)) {
            return;
        }
        if (event.getPlayer().hasPermission("pizzasmp.gamemode.bypass")) {
            return;
        }
        String[] parts = parseCommandParts(event.getMessage());
        if (parts.length == 0) {
            return;
        }
        String root = parts[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon > 0) {
            root = root.substring(colon + 1);
        }
        boolean deny = root.equals("gmc") || root.equals("gma") || root.equals("gmt") || root.equals("egamemode") && parts.length >= 2;
        if (!deny && (root.equals("gamemode") || root.equals("gm")) && parts.length >= 2) {
            String mode = parts[1].toLowerCase(Locale.ROOT);
            deny = mode.equals("creative") || mode.equals("c") || mode.equals("1")
                || mode.equals("adventure") || mode.equals("a") || mode.equals("2");
        }
        if (deny) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(color("&cCreative and Adventure mode are disabled."));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onUnknownCommandFallback(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        // Staff (real perm, ignoring staff-mode) bypass: never let this fallback swallow a valid
        // command. Some Paper/Brigadier commands aren't in the legacy command map.
        if (player.hasPermission(PERM_PLUGIN_ADMIN)) {
            return;
        }
        String[] parts = parseCommandParts(event.getMessage());
        if (parts.length == 0) {
            return;
        }

        if (isRegisteredCommand(parts[0])) {
            return;
        }

        event.setCancelled(true);
        sendUnknownCommandMessage(player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onOnlineTargetCommandGate(PlayerCommandPreprocessEvent event) {
        String[] parts = parseCommandParts(event.getMessage());
        if (parts.length < 2) {
            return;
        }

        if (!ONLINE_TARGET_FIRST_ARG_COMMANDS.contains(parts[0])) {
            return;
        }

        if (resolveOnlinePlayer(parts[1]) != null) {
            return;
        }

        event.setCancelled(true);
        sendOfflinePlayerMessage(event.getPlayer(), parts[1]);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHomeMenuCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String[] parts = parseCommandParts(event.getMessage());
        if (parts.length == 0) {
            return;
        }

        String root = parts[0];
        if (root.equals("homes")) {
            if (!player.hasPermission(PERM_HOMES_CMD) || !player.hasPermission(PERM_HOME_USE)) {
                event.setCancelled(true);
                player.sendMessage(color("&cYou do not have permission to use /homes."));
                return;
            }
            event.setCancelled(true);
            openHomesDialogFresh(player);
            return;
        }

        if (root.equals("home") && parts.length == 1) {
            if (!player.hasPermission(PERM_HOME_USE)) {
                event.setCancelled(true);
                player.sendMessage(color("&cYou do not have permission to use /home."));
                return;
            }
            event.setCancelled(true);
            openHomesDialogFresh(player);
            return;
        }

        if (root.equals("home") && parts.length >= 2) {
            if (!player.hasPermission(PERM_HOME_USE)) {
                event.setCancelled(true);
                player.sendMessage(color("&cYou do not have permission to use /home."));
                return;
            }
            String sub = parts[1].toLowerCase(Locale.ROOT);
            if ("team".equals(sub)) {
                event.setCancelled(true);
                player.sendMessage(color("&cTeam homes have been retired — your team's home was saved as a personal home (&e/home teamhome&c)."));
                return;
            }
            if (NON_TELEPORT_HOME_SUBCOMMANDS.contains(sub)) {
                // Non-teleport subcommands (help/create/open/...) open the homes GUI rather than
                // falling through to the old legacy homes UI.
                event.setCancelled(true);
                openHomesDialogFresh(player);
                return;
            }
            event.setCancelled(true);
            handleDirectHomeTeleport(player, parts[1]);
            return;
        }

        // Named homes backend (#8): /sethome [name] and /delhome <name|index>.
        // Essentials' versions are in its disabled-commands list, so PAT owns these.
        if (root.equals("sethome")) {
            event.setCancelled(true);
            if (!player.hasPermission(PERM_HOME_USE)) {
                player.sendMessage(color("&cYou do not have permission to use /sethome."));
                return;
            }
            handleSetHomeCommand(player, parts.length >= 2 ? parts[1] : null);
            return;
        }

        if (root.equals("delhome")) {
            event.setCancelled(true);
            if (!player.hasPermission(PERM_HOME_USE)) {
                player.sendMessage(color("&cYou do not have permission to use /delhome."));
                return;
            }
            if (parts.length < 2) {
                player.sendMessage(color("&cUsage: /delhome <name|number>"));
                return;
            }
            handleDelHomeCommand(player, parts[1]);
        }
    }

    // DISABLED: /rtp is now fully owned by PizzaNetworkCore (which absorbed the RTP logic).
    // This old handler routed to the removed RTPGUI plugin; leaving it active would intercept
    // /rtp before PNC and fail with "RTP is unavailable right now". Intentionally a no-op.
    public void onRtpWorldShortcut_DISABLED(PlayerCommandPreprocessEvent event) {
        // no-op
    }

    private boolean openRtpGui(Player player) {
        Object rtpListener = findRtpListener();
        if (rtpListener == null) {
            return false;
        }
        try {
            Method openGui = rtpListener.getClass().getMethod("openGUI", Player.class);
            openGui.invoke(null, player);
            return true;
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Failed to open /rtp GUI for " + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean startRtpCountdown(Player player, World world, String worldArg) {
        Object rtpListener = findRtpListener();
        if (rtpListener == null) {
            return false;
        }
        try {
            Class<?> listenerClass = rtpListener.getClass();
            Method canUseMethod = listenerClass.getMethod("canUseRTP", Player.class);
            boolean canUse = (boolean) canUseMethod.invoke(null, player);
            if (!canUse) {
                Method remainingMethod = listenerClass.getMethod("getCooldownRemaining", Player.class);
                long remaining = (long) remainingMethod.invoke(null, player);
                player.sendMessage(color("&cYou can't RTP for another &e" + remaining + "s&c."));
                return true;
            }

            Method startMethod = listenerClass.getDeclaredMethod("startTeleportCountdown", Player.class, World.class);
            startMethod.setAccessible(true);
            player.closeInventory();
            startMethod.invoke(rtpListener, player, world);
            return true;
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Failed to route /rtp " + worldArg + " for " + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCustomCommandTabComplete(TabCompleteEvent event) {
        if (!event.isCommand()) {
            return;
        }

        TabRequest request = parseTabRequest(event.getBuffer());
        if (request == null || request.argPosition < 0) {
            return;
        }

        // No argument suggestions for restricted (staff) commands to non-staff / staff-mode players.
        if (event.getSender() instanceof Player tabPlayer
                && !isEffectivelyStaff(tabPlayer)
                && RESTRICTED_FOR_NON_STAFF.contains(request.root)) {
            event.setCompletions(Collections.emptyList());
            return;
        }

        List<String> out = null;
        switch (request.root) {
            case "rtp":
                if (request.argPosition == 0) {
                    out = filterByPrefix(List.of("east", "nether", "end"), request.prefix);
                }
                break;
            case "servermaint":
                if (request.argPosition == 0) {
                    out = filterByPrefix(List.of("start", "end", "status"), request.prefix);
                }
                break;
            case "punish":
                if (!hasAnyPermission(event.getSender(), "pizzasmp.punish", "mycommand.punish")) {
                    break;
                }
                if (request.argPosition == 0) {
                    out = completeOnlineNames(request.prefix);
                } else if (request.argPosition == 1) {
                    out = filterByPrefix(List.of("7d", "14d", "30d", "60d", "perm"), request.prefix);
                } else if (request.argPosition == 2) {
                    out = filterByPrefix(List.of("Hacking", "Cheating", "Griefing", "Scamming", "Abuse", "Spam"), request.prefix);
                }
                break;
            case "unpunish":
            case "forgive":
                if (!hasAnyPermission(event.getSender(),
                    "pizzasmp.unpunish", "pizzasmp.forgive", "mycommand.unpunish", "mycommand.forgive")) {
                    break;
                }
                if (request.argPosition == 0) {
                    out = completeBannedPlayers(request.prefix);
                }
                break;
            case "bancheck":
                if (!hasAnyPermission(event.getSender(), "pizzasmp.bancheck", "mycommand.bancheck")) {
                    break;
                }
                if (request.argPosition == 0) {
                    out = completePunishmentLookups(request.prefix, false);
                }
                break;
            case "bans":
                if (!hasAnyPermission(event.getSender(), "pizzasmp.bans", "pizzasmp.bancheck", "mycommand.bancheck")) {
                    break;
                }
                if (request.argPosition == 0) {
                    out = filterByPrefix(List.of("1", "2", "3", "4", "5"), request.prefix);
                }
                break;
            case "sus":
            case "suspicious":
                if (!hasAnyPermission(event.getSender(), "pizzasmp.admin.commands")) {
                    break;
                }
                if (request.argPosition == 0) {
                    out = filterByPrefix(List.of("1", "2", "3", "4", "5"), request.prefix);
                }
                break;
            case "freeze":
                if (!hasAnyPermission(event.getSender(), PERM_FREEZE_CMD)) {
                    break;
                }
                if (request.argPosition == 0) {
                    out = completeOnlineNames(request.prefix);
                }
                break;
            case "unfreeze":
                if (!hasAnyPermission(event.getSender(), PERM_UNFREEZE_CMD)) {
                    break;
                }
                if (request.argPosition == 0) {
                    out = completePlayerNames(request.prefix, true);
                }
                break;
            case "clearwarnings":
            case "clearwarn":
            case "clearwarns":
            case "clearallwarnings":
            case "clearallwarns":
            case "clearwarningsall":
                if (request.argPosition == 0) {
                    out = completeOnlineNames(request.prefix);
                }
                break;
            case "gtp":
                if (!hasAnyPermission(event.getSender(), PERM_GTP)) {
                    break;
                }
                if (request.argPosition == 0) {
                    out = completeKnownHomeTargets(request.prefix);
                } else if (request.argPosition == 1 && !request.args.isEmpty()) {
                    out = completeTargetHomeSlots(request.args.get(0), request.prefix);
                }
                break;
            case "home":
            case "delhome":
                if (request.argPosition == 0) {
                    out = completeOwnHomes(event.getSender(), request.prefix);
                }
                break;
            case "ecobot":
                if (request.argPosition == 0) {
                    out = filterByPrefix(List.of("status", "list", "order", "listpass", "orderpass", "cycle", "buy", "deliver", "clear", "pause", "resume", "reload"), request.prefix);
                } else if (request.argPosition == 1 && !request.args.isEmpty()) {
                    String subCmd = request.args.get(0).toLowerCase();
                    if ("list".equals(subCmd) || "order".equals(subCmd)) {
                        out = filterByPrefix(List.of("DIAMOND", "NETHERITE", "IRON", "GOLD", "EMERALD", "AMETHYST", "STONE", "COBBLESTONE", "OAK_LOG", "BIRCH_LOG"), request.prefix);
                    } else if ("clear".equals(subCmd)) {
                        out = filterByPrefix(List.of("listings", "orders", "all"), request.prefix);
                    }
                }
                break;
            default:
                break;
        }

        if (out != null && !out.isEmpty()) {
            event.setCompletions(out);
        }
    }

    private List<String> completePunishmentLookups(String prefix, boolean activeOnly) {
        Set<String> values = new HashSet<>(completeOnlineNames(""));
        File punishDropRecords = new File(getDataFolder().getParentFile(), "PunishDrop/ban-records.yml");
        if (!punishDropRecords.isFile()) {
            return filterByPrefix(new ArrayList<>(values), prefix);
        }

        FileConfiguration recordsConfig = YamlConfiguration.loadConfiguration(punishDropRecords);
        ConfigurationSection records = recordsConfig.getConfigurationSection("records");
        if (records != null) {
            for (String banId : records.getKeys(false)) {
                ConfigurationSection section = records.getConfigurationSection(banId);
                if (section == null) {
                    continue;
                }
                if (activeOnly && !section.getBoolean("active", true)) {
                    continue;
                }
                String targetName = section.getString("target-name");
                if (targetName != null && !targetName.isBlank()) {
                    values.add(targetName);
                }
                values.add(banId.toUpperCase(Locale.ROOT));
            }
        }

        List<String> out = new ArrayList<>(values);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return filterByPrefix(out, prefix);
    }

    private List<String> completeBannedPlayers(String prefix) {
        Set<String> values = new HashSet<>();
        File punishDropRecords = new File(getDataFolder().getParentFile(), "PunishDrop/ban-records.yml");
        if (!punishDropRecords.isFile()) {
            return new ArrayList<>();
        }

        FileConfiguration recordsConfig = YamlConfiguration.loadConfiguration(punishDropRecords);
        ConfigurationSection records = recordsConfig.getConfigurationSection("records");
        if (records != null) {
            for (String banId : records.getKeys(false)) {
                ConfigurationSection section = records.getConfigurationSection(banId);
                if (section == null || !section.getBoolean("active", true)) {
                    continue;
                }
                String targetName = section.getString("target-name");
                if (targetName != null && !targetName.isBlank()) {
                    values.add(targetName);
                }
                values.add(banId.toUpperCase(Locale.ROOT));
            }
        }

        List<String> out = new ArrayList<>(values);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return filterByPrefix(out, prefix);
    }

    /**
     * Suggests the sender's own homes: slot numbers for default "homeN" slots,
     * the custom name otherwise.
     */
    private List<String> completeOwnHomes(CommandSender sender, String prefix) {
        if (!(sender instanceof Player player)) {
            return new ArrayList<>();
        }
        File file = getSetHomeDataFile(player);
        if (file == null || !file.exists()) {
            return new ArrayList<>();
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        List<String> numbers = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String home : cfg.getStringList("homes")) {
            if (home.matches("home\\d+")) {
                numbers.add(home.substring(4));
            } else {
                names.add(home);
            }
        }
        numbers.sort(Comparator.comparingInt(Integer::parseInt));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> out = new ArrayList<>(numbers);
        out.addAll(names);
        return filterByPrefix(out, prefix);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onFrozenCommandBlock(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isFrozen(player)) {
            return;
        }

        String[] parts = parseCommandParts(event.getMessage());
        if (parts.length == 0) {
            return;
        }

        String root = parts[0];
        if (FROZEN_ALLOWED_COMMANDS.contains(root)) {
            return;
        }
        // Maintenance-frozen admins keep full command access (so they can manage/end maintenance).
        if (maintenanceFrozen.contains(player.getUniqueId()) && player.hasPermission(PERM_MAINT_ADMIN)) {
            return;
        }

        event.setCancelled(true);
        notifyFrozen(player);
        player.sendMessage(color("&cYou are frozen and cannot use commands right now."));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isFrozen(player)) {
            return;
        }
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        Location anchor = frozenAnchors.computeIfAbsent(player.getUniqueId(), ignored -> from.clone());
        Location locked = anchor.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
        notifyFrozen(player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFrozenTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isFrozen(player)) {
            return;
        }
        event.setCancelled(true);
        notifyFrozen(player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCombatHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        tagCombat(victim);
        tagCombat(attacker);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onTeleportCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(PERM_COMBAT_BYPASS) || !isCombatTagged(player)) {
            return;
        }

        String[] parts = parseCommandParts(event.getMessage());
        if (parts.length == 0) {
            return;
        }

        String root = parts[0];
        if (root.equals("rtp")) {
            event.setCancelled(true);
            player.sendMessage(color("&cYou are in combat. Wait &e" + remainingCombatSeconds(player) + "s &cbefore using /rtp."));
            return;
        }

        if ((root.equals("home") || root.equals("homes") || root.equals("gtp")) && isTeleportHomeCommand(parts)) {
            event.setCancelled(true);
            player.sendMessage(color("&cYou are in combat. Wait &e" + remainingCombatSeconds(player) + "s &cbefore teleporting home."));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHomeInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        String title = event.getView().getTitle();
        if (!isHomesMenu(title) && !isHomeDeleteMenu(title)) {
            return;
        }

        event.setCancelled(true);

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        if (isHomeDeleteMenu(title)) {
            handleDeleteConfirmClick(viewer, rawSlot);
            return;
        }

        // 9x4 grid: slots 0-26 are home slots (left = teleport/set, right = delete); 31 = close.
        if (rawSlot >= 0 && rawSlot < 27) {
            handleHomeGridClick(viewer, rawSlot, event.isRightClick());
            return;
        }
        if (rawSlot == 31) {
            viewer.closeInventory();
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSusInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        String title = event.getView().getTitle();
        if (!isSusMenu(title)) {
            return;
        }

        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        handleSusClick(viewer, rawSlot);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Integer taskId = pendingTeleportTasks.remove(uuid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        combatTaggedUntil.remove(uuid);
        adminTargetIndex.remove(uuid);
        pendingDeleteSlot.remove(uuid);
        pendingDeleteHome.remove(uuid);
        pendingTeleportOrigins.remove(uuid);
        frozenPlayers.remove(uuid);
        frozenAnchors.remove(uuid);
        frozenNoticeCooldown.remove(uuid);
        susMenuPages.remove(uuid);
        stopTrackingTask(uuid);
        atrackPriorMode.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLoginBanMessageSanitize(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.KICK_BANNED) {
            return;
        }
        String kick = event.getKickMessage();
        if (kick == null || kick.isBlank()) {
            return;
        }
        String sanitized = normalizeBanKickMessage(sanitizeBanMessage(kick));
        if (!sanitized.equals(kick)) {
            event.setKickMessage(sanitized);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMaintenanceJoinGate(PlayerLoginEvent event) {
        if (!isMaintenanceTransferActive()) {
            return;
        }
        Player player = event.getPlayer();
        if (shouldStayDuringMaintenance(player)) {
            return;
        }
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, buildMaintenanceKickMessage());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLoginBanMessageSanitize(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.KICK_BANNED) {
            return;
        }
        String kick = event.getKickMessage();
        if (kick == null || kick.isBlank()) {
            return;
        }
        String sanitized = normalizeBanKickMessage(sanitizeBanMessage(kick));
        if (!sanitized.equals(kick)) {
            event.setKickMessage(sanitized);
        }
    }

    private void openHomesMenu(Player viewer, OfflinePlayer preferredTarget) {
        OfflinePlayer target = normalizeTarget(viewer, preferredTarget);

        int allowedHomes = allowedHomes(viewer);
        boolean selfTarget = target.getUniqueId().equals(viewer.getUniqueId());
        // Legacy 9x4 grid (non-dialog clients). SAFE slot mapping via buildHomeSlots — it does NOT
        // rename custom homes (unlike normalizeHomes), so named homes survive. Rows 0-2 (slots
        // 0-26) hold up to 27 home slots; left-click teleports/sets, right-click deletes.
        File file = getSetHomeDataFile(target);
        FileConfiguration cfg = (file != null && file.exists()) ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        String[] slots = buildHomeSlots(homes, MAX_HOME_SLOTS);

        Inventory inv = Bukkit.createInventory(null, 36, color(HOME_MENU_TITLE));
        for (int i = 0; i < 27; i++) {
            if (i >= allowedHomes) {
                inv.setItem(i, item(Material.RED_BED, "&cHome " + (i + 1) + " &8(Locked)", List.of(
                    "&7Unlock with &6Pizza+ &7/ &6Pizza++.")));
                continue;
            }
            String homeName = i < slots.length ? slots[i] : null;
            if (homeName == null) {
                inv.setItem(i, item(Material.GRAY_BED, "&7New Home", List.of(
                    selfTarget ? "&fClick to set a home here." : "&8Empty")));
                continue;
            }
            String disp = homeDisplayName(homeName);
            List<String> lore = new ArrayList<>();
            lore.add("&fLeft-click to Teleport.");
            if (selfTarget) lore.add("&cRight-click to Delete.");
            inv.setItem(i, item(Material.BLUE_BED, HOME_PRIMARY + disp, lore));
        }
        inv.setItem(31, item(Material.BARRIER, "&cClose", List.of("&7Close this menu.")));
        viewer.openInventory(inv);
    }

    /** Legacy homes grid click: left = teleport / set, right = delete (self only). */
    private void handleHomeGridClick(Player viewer, int index, boolean rightClick) {
        OfflinePlayer target = getCurrentTarget(viewer);
        boolean selfTarget = target.getUniqueId().equals(viewer.getUniqueId());
        int allowedHomes = allowedHomes(viewer);
        if (index >= allowedHomes) {
            viewer.sendMessage(color("&cThis home slot is locked for your rank."));
            return;
        }
        File file = getSetHomeDataFile(target);
        FileConfiguration cfg = (file != null && file.exists()) ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        String[] slots = buildHomeSlots(homes, MAX_HOME_SLOTS);
        String homeName = index < slots.length ? slots[index] : null;

        if (homeName == null) {
            if (!selfTarget) return;
            if (setHomeInSlot(viewer, index)) {
                viewer.sendMessage(color("&7Home " + HOME_PRIMARY + "home" + (index + 1) + "&7 set."));
            }
            openHomesMenu(viewer, target);
            return;
        }

        if (rightClick) {
            if (!selfTarget) return;
            openHomeDeleteConfirmByName(viewer, homeName);
            return;
        }

        if (!viewer.hasPermission(PERM_COMBAT_BYPASS) && isCombatTagged(viewer)) {
            viewer.sendMessage(color("&cYou are in combat. Wait &e" + remainingCombatSeconds(viewer) + "s &cbefore teleporting home."));
            return;
        }
        Location loc = readHomeLocation(target, homeName);
        if (loc == null) {
            viewer.sendMessage(color("&cThat home is invalid or its world is missing."));
            return;
        }
        viewer.closeInventory();
        String msg = selfTarget ? "&fTeleported To Your Home." : "&fTeleported To " + target.getName() + "'s Home.";
        startCountdownTeleport(viewer, loc, msg);
    }

    private void openHomeDeleteConfirmByName(Player viewer, String homeName) {
        pendingDeleteHome.put(viewer.getUniqueId(), homeName);
        Inventory inv = Bukkit.createInventory(null, 27, color(HOME_DELETE_TITLE));
        Location loc = readHomeLocation(viewer, homeName);
        List<String> previewLore = new ArrayList<>();
        previewLore.add("&7You are about to delete &f" + homeDisplayName(homeName) + "&7.");
        if (loc != null && loc.getWorld() != null) {
            previewLore.add("&8" + loc.getWorld().getName() + " " + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ());
        }
        previewLore.add("&cThis cannot be undone (a backup is kept).");
        inv.setItem(13, item(Material.BLUE_BED, "&cDelete " + homeDisplayName(homeName), previewLore));
        inv.setItem(11, item(Material.RED_STAINED_GLASS_PANE, "&cCancel", List.of("&fClick To Cancel")));
        inv.setItem(15, item(Material.LIME_STAINED_GLASS_PANE, "&aConfirm", List.of("&fClick To Delete")));
        viewer.openInventory(inv);
    }

    private void handleDirectHomeTeleport(Player player, String homeArg) {
        if (!player.hasPermission(PERM_COMBAT_BYPASS) && isCombatTagged(player)) {
            player.sendMessage(color("&cYou are in combat. Wait &e" + remainingCombatSeconds(player) + "s &cbefore teleporting home."));
            return;
        }
        File dataFile = getSetHomeDataFile(player);
        if (dataFile == null || !dataFile.exists()) {
            player.sendMessage(color("&cNo homes found."));
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        if (homes.isEmpty()) {
            player.sendMessage(color("&cNo homes found."));
            return;
        }
        String selectedHome = resolveHomeArg(homeArg, homes, cfg);
        if (selectedHome == null) {
            player.sendMessage(color("&cHome not found. Use a valid home number or name."));
            return;
        }
        Location loc = readHomeLocation(cfg, selectedHome);
        if (loc == null) {
            player.sendMessage(color("&cThat home is invalid or its world is missing."));
            return;
        }
        player.closeInventory();
        startCountdownTeleport(player, loc, "&fTeleported To Your Home.");
    }

    // ===================== Homes dialog frontend (#8) =====================
    // Vanilla 1.21.6+ dialog screens via the Paper Dialog API. Java 1.21.6+
    // clients get dialogs; Bedrock (Floodgate) and pre-1.21.6 clients joining
    // through ViaVersion (both detected via reflection — no compile dep) fall
    // back to the legacy inventory GUI.

    private static final TextColor DIALOG_BRAND = TextColor.color(0x00BFFF);

    /** Dialogs need protocol 771+ (1.21.6). Older Via clients + Bedrock get the legacy GUI. */
    private boolean useDialogUi(Player player) {
        if (isBedrockPlayer(player)) {
            return false;
        }
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            int protocol = (Integer) api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId());
            return protocol >= 771;
        } catch (Throwable t) {
            return true; // no ViaVersion -> only native 1.21.x clients can join
        }
    }

    private boolean isBedrockPlayer(Player player) {
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
                return false;
            }
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(instance, player.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Wraps a player click handler as a single-use dialog action, re-dispatched onto the main thread. */
    private DialogAction dialogClick(java.util.function.Consumer<Player> handler) {
        return DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player p) {
                Bukkit.getScheduler().runTask(this, () -> handler.accept(p));
            }
        }, ClickCallback.Options.builder().build());
    }

    private ActionButton dialogButton(Component label, String tooltip, int width, java.util.function.Consumer<Player> click) {
        ActionButton.Builder builder = ActionButton.builder(label).width(width);
        if (tooltip != null) {
            builder.tooltip(Component.text(tooltip, NamedTextColor.GRAY));
        }
        if (click != null) {
            builder.action(dialogClick(click));
        }
        return builder.build();
    }

    private Dialog buildDialog(Component title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        // afterAction NONE: button clicks don't close the screen, so re-showing a dialog (navigate/
        // toggle) swaps content in place with no flicker. Terminal actions call closeDialog().
        return Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .inputs(inputs)
                .build())
            .type(type));
    }

    /** "home3" -> "Home 3", "base" -> "Base". */
    private String homeDisplayName(String home) {
        if (home.matches("home\\d+")) {
            return "Home " + home.substring(4);
        }
        return Character.toUpperCase(home.charAt(0)) + home.substring(1);
    }

    private String homeWhere(FileConfiguration cfg, String home) {
        Location loc = readHomeLocation(cfg, home);
        return (loc != null && loc.getWorld() != null)
            ? loc.getWorld().getName() + "  " + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ()
            : "invalid";
    }

    private String homeIcon(FileConfiguration cfg, String home) {
        String icon = cfg.getString(home + ".icon");
        return (icon == null || icon.isBlank()) ? "🛏" : icon;
    }

    /**
     * Fixed-slot model: slot i prefers "home"+(i+1); named homes fill the
     * remaining free slots in list order. null = unset slot ("New Home").
     * Sized past the limit if the player has bonus homes (e.g. teamhome).
     */
    private String[] buildHomeSlots(List<String> homes, int limit) {
        int size = Math.max(limit, homes.size());
        String[] slots = new String[size];
        List<String> overflow = new ArrayList<>();
        for (String home : homes) {
            int idx = -1;
            if (home.matches("home\\d+")) {
                try {
                    idx = Integer.parseInt(home.substring(4)) - 1;
                } catch (NumberFormatException ignored) {
                }
            }
            if (idx >= 0 && idx < size && slots[idx] == null) {
                slots[idx] = home;
            } else {
                overflow.add(home);
            }
        }
        int free = 0;
        for (String home : overflow) {
            while (free < size && slots[free] != null) {
                free++;
            }
            if (free < size) {
                slots[free++] = home;
            }
        }
        return slots;
    }

    /** Set slot -> detail screen; unset slot -> grey "New Home" (sets here, stays open). */
    private ActionButton homeSlotButton(FileConfiguration cfg, String home, int slotIndex, int width) {
        if (home != null) {
            return dialogButton(Component.text(homeIcon(cfg, home) + " " + homeDisplayName(home), NamedTextColor.WHITE),
                null, width, p -> openHomeDetailDialog(p, home));
        }
        return dialogButton(Component.text("New Home", NamedTextColor.GRAY),
            "Set a home at your current location", width, p -> {
                if (setHomeInSlot(p, slotIndex)) {
                    p.sendMessage(color("&7Home " + HOME_PRIMARY + "home" + (slotIndex + 1) + "&7 set."));
                } else {
                    p.sendMessage(color("&cFailed to set that home."));
                }
                openHomesDialog(p);
            });
    }

    /** Command entry point: forgets any remembered expansion, then opens the homes UI. */
    private void openHomesDialogFresh(Player player) {
        homesDialogExpanded.remove(player.getUniqueId());
        openHomesDialog(player);
    }

    /**
     * Main homes screen: EVERY slot the player has access to (unset = "New Home").
     * "Show More" only reveals the LOCKED slots above the player's tier limit,
     * so it's hidden for players who already have all 27 (pizza++/staff).
     */
    private void openHomesDialog(Player player) {
        if (!useDialogUi(player)) {
            setTargetToSelf(player);
            openHomesMenu(player, player);
            return;
        }
        // If the player had expanded the view, keep returning to that view until they leave the GUI.
        Integer expanded = homesDialogExpanded.get(player.getUniqueId());
        if (expanded != null && expanded > 0) {
            openAllHomesDialog(player, expanded);
            return;
        }
        File file = getSetHomeDataFile(player);
        FileConfiguration cfg = (file != null && file.exists()) ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        int limit = allowedHomes(player);
        String[] slots = buildHomeSlots(homes, limit);

        List<ActionButton> buttons = new ArrayList<>();
        boolean grid = slots.length > 4; // 3-slot default tier keeps the compact single-column look
        int btnWidth = grid ? 150 : 200;
        for (int i = 0; i < slots.length; i++) {
            buttons.add(homeSlotButton(cfg, slots[i], i, btnWidth));
        }
        if (slots.length < MAX_HOME_SLOTS) {
            // Default tier expands in two steps (9 then 27); pizza+ goes straight to 27.
            int next = limit < 9 ? 9 : MAX_HOME_SLOTS;
            buttons.add(dialogButton(Component.text("Show More", DIALOG_BRAND),
                "View locked home slots", btnWidth, p -> openAllHomesDialog(p, next)));
        }

        Dialog dialog = buildDialog(Component.text("Homes", DIALOG_BRAND),
            List.of(DialogBody.plainMessage(Component.text(homes.size() + "/" + limit + " homes used", NamedTextColor.GRAY))),
            List.of(),
            DialogType.multiAction(buttons)
                .columns(grid ? 3 : 1)
                .exitAction(dialogButton(Component.text("Close"), null, btnWidth, null))
                .build());
        player.showDialog(dialog);
    }

    /** Slot above the player's tier limit: red "Locked" with an upsell tooltip. */
    private ActionButton lockedSlotButton(int slotIndex, int width, int shownCount) {
        String tierName = slotIndex < 9 ? "Pizza+" : "Pizza++";
        return dialogButton(Component.text("Locked", NamedTextColor.RED),
            "Buy " + tierName + " for more home slots", width, p -> {
                p.sendMessage(color("&cThis home slot requires &e" + tierName + "&c."));
                openAllHomesDialog(p, shownCount);
            });
    }

    /**
     * "Show More": tiered grid. First click shows 9 slots (the Pizza+ amount),
     * a second "Show More" expands to the full 27 (Pizza++). Slots above the
     * player's limit render as red "Locked" buttons.
     */
    private void openAllHomesDialog(Player player, int shownCount) {
        homesDialogExpanded.put(player.getUniqueId(), shownCount);
        File file = getSetHomeDataFile(player);
        FileConfiguration cfg = (file != null && file.exists()) ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        int limit = allowedHomes(player);
        int size = Math.max(shownCount, Math.max(limit, homes.size()));
        if (shownCount < MAX_HOME_SLOTS) {
            size = Math.min(size, Math.max(shownCount, homes.size())); // 9-view stays compact unless bonus homes overflow it
        }
        String[] slots = buildHomeSlots(homes, size);

        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null && i >= limit) {
                buttons.add(lockedSlotButton(i, 150, shownCount));
            } else {
                buttons.add(homeSlotButton(cfg, slots[i], i, 150));
            }
        }
        if (slots.length < MAX_HOME_SLOTS) {
            buttons.add(dialogButton(Component.text("Show More", DIALOG_BRAND),
                "View all " + MAX_HOME_SLOTS + " home slots", 150, p -> openAllHomesDialog(p, MAX_HOME_SLOTS)));
        }
        Dialog dialog = buildDialog(Component.text("All Homes", DIALOG_BRAND),
            List.of(DialogBody.plainMessage(Component.text(homes.size() + "/" + limit + " homes used", NamedTextColor.GRAY))),
            List.of(),
            DialogType.multiAction(buttons)
                .columns(3)
                .exitAction(dialogButton(Component.text("Back"), null, 150, this::openHomesDialogFresh))
                .build());
        player.showDialog(dialog);
    }

    /** Per-home detail: Teleport / Change Icon / Rename / Delete / Back. */
    private void openHomeDetailDialog(Player player, String home) {
        File file = getSetHomeDataFile(player);
        FileConfiguration cfg = (file != null && file.exists()) ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        if (!cfg.getStringList("homes").contains(home)) {
            openHomesDialog(player);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogButton(Component.text("Teleport", NamedTextColor.GREEN),
            null, 200, p -> { p.closeDialog(); handleDirectHomeTeleport(p, home); }));
        buttons.add(dialogButton(Component.text("Change Icon", NamedTextColor.WHITE),
            "Pick a new icon for this home", 200, p -> openIconPickerDialog(p, home)));
        buttons.add(dialogButton(Component.text("Rename", NamedTextColor.WHITE),
            "Give this home a new name", 200, p -> openRenameDialog(p, home)));
        buttons.add(dialogButton(Component.text("Delete", NamedTextColor.RED),
            "Delete this home", 200, p -> openDeleteConfirmDialog(p, home)));
        Dialog dialog = buildDialog(Component.text(homeIcon(cfg, home) + " " + homeDisplayName(home), DIALOG_BRAND),
            List.of(),
            List.of(),
            DialogType.multiAction(buttons)
                .columns(1)
                .exitAction(dialogButton(Component.text("Back"), null, 200, this::openHomesDialog))
                .build());
        player.showDialog(dialog);
    }

    private static final List<String> HOME_ICONS = List.of(
        "🛏", "⛏", "⚔", "★", "♥", "☀", "☾", "⚓", "☠", "✿", "♦", "⚑");

    private void openIconPickerDialog(Player player, String home) {
        List<ActionButton> buttons = new ArrayList<>();
        for (String icon : HOME_ICONS) {
            buttons.add(dialogButton(Component.text(icon, NamedTextColor.WHITE), null, 45, p -> {
                saveHomeIcon(p, home, icon);
                openHomeDetailDialog(p, home);
            }));
        }
        Dialog dialog = buildDialog(Component.text("Change Icon", DIALOG_BRAND),
            List.of(DialogBody.plainMessage(Component.text("Pick an icon for " + homeDisplayName(home) + ".", NamedTextColor.GRAY))),
            List.of(),
            DialogType.multiAction(buttons)
                .columns(4)
                .exitAction(dialogButton(Component.text("Back"), null, 200, p -> openHomeDetailDialog(p, home)))
                .build());
        player.showDialog(dialog);
    }

    private void saveHomeIcon(Player player, String home, String icon) {
        File file = getSetHomeDataFile(player);
        if (file == null || !file.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.getStringList("homes").contains(home)) {
            return;
        }
        cfg.set(home + ".icon", icon);
        try {
            cfg.save(file);
        } catch (Exception ex) {
            getLogger().warning("Failed to save home icon for " + player.getName() + ": " + ex.getMessage());
        }
    }

    /** Rename screen: "New Name" input prefilled with the current name + Save/Cancel. */
    private void openRenameDialog(Player player, String home) {
        openRenameDialog(player, home, null, home);
    }

    /** Rename screen with an optional inline error line and the player's previous input preserved. */
    private void openRenameDialog(Player player, String home, String error, String typed) {
        List<DialogBody> body = error == null
            ? List.of()
            : List.of(DialogBody.plainMessage(Component.text(error, NamedTextColor.RED)));
        List<DialogInput> inputs = List.of(
            DialogInput.text("name", Component.text("New Name", NamedTextColor.GRAY))
                .width(200)
                .maxLength(20)
                .initial(typed == null || typed.isBlank() ? home : typed)
                .build());
        ActionButton save = ActionButton.builder(Component.text("Save", NamedTextColor.GREEN))
            .width(110)
            .action(DialogAction.customClick((view, audience) -> {
                if (audience instanceof Player p) {
                    String raw = view.getText("name");
                    Bukkit.getScheduler().runTask(this, () -> handleHomeRename(p, home, raw));
                }
            }, ClickCallback.Options.builder().build()))
            .build();
        ActionButton cancel = dialogButton(Component.text("Cancel"), null, 110, p -> openHomeDetailDialog(p, home));
        Dialog dialog = buildDialog(Component.text("Rename " + homeDisplayName(home), DIALOG_BRAND),
            body, inputs, DialogType.confirmation(save, cancel));
        player.showDialog(dialog);
    }

    /** Renames the yaml key in place, keeping the home's list position (= slot). */
    private void handleHomeRename(Player player, String home, String rawName) {
        String name = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
        if (name.equals(home)) {
            openHomeDetailDialog(player, home);
            return;
        }
        if (!HOME_NAME_PATTERN.matcher(name).matches()) {
            openRenameDialog(player, home, "Names are 1-20 characters: letters, numbers, - or _", name);
            return;
        }
        File file = getSetHomeDataFile(player);
        if (file == null || !file.exists()) {
            openHomesDialog(player);
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        int idx = homes.indexOf(home);
        if (idx < 0) {
            openHomesDialog(player);
            return;
        }
        if (homes.contains(name)) {
            openRenameDialog(player, home, "You already have a home named " + name, name);
            return;
        }
        ConfigurationSection section = cfg.getConfigurationSection(home);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                cfg.set(name + "." + key, section.get(key));
            }
        }
        cfg.set(home, null);
        homes.set(idx, name);
        cfg.set("homes", homes);
        try {
            cfg.save(file);
            // No chat confirmation on rename (the GUI updates to show the new name).
        } catch (Exception ex) {
            getLogger().warning("Failed to rename home for " + player.getName() + ": " + ex.getMessage());
            player.sendMessage(color("&cFailed to rename that home."));
            openHomeDetailDialog(player, home);
            return;
        }
        openHomeDetailDialog(player, name);
    }

    /** Delete confirmation; on delete the UI returns to the main Homes page. */
    private void openDeleteConfirmDialog(Player player, String home) {
        ActionButton yes = dialogButton(Component.text("Delete", NamedTextColor.RED), null, 110, p -> {
            handleDelHomeCommand(p, home);
            openHomesDialog(p);
        });
        ActionButton no = dialogButton(Component.text("Cancel"), null, 110, p -> openHomeDetailDialog(p, home));
        Dialog dialog = buildDialog(Component.text("Delete " + homeDisplayName(home) + "?", NamedTextColor.RED),
            List.of(DialogBody.plainMessage(Component.text("This cannot be undone.", NamedTextColor.GRAY))),
            List.of(), DialogType.confirmation(yes, no));
        player.showDialog(dialog);
    }

    private int allowedHomes(Player player) {
        if (player.hasPermission(PERM_PLUGIN_ADMIN) || player.hasPermission(PERM_HOMES_27)) {
            return MAX_HOME_SLOTS; // staff + pizza++
        }
        if (player.hasPermission(PERM_HOMES_9)) {
            return 9; // pizza+
        }
        if (player.hasPermission("sethome.maxhomes.5")) {
            return 5; // legacy node — don't regress players below what they already had
        }
        return HOMES_DEFAULT_LIMIT;
    }

    private boolean canViewOtherHomes(Player player) {
        return player.hasPermission(PERM_GTP) || player.hasPermission(PERM_HOME_ADMIN);
    }

    private void shiftTarget(Player viewer, int delta) {
        List<OfflinePlayer> targets = getKnownPlayersWithHomes();
        if (targets.isEmpty()) {
            setTargetToSelf(viewer);
            openHomesMenu(viewer, viewer);
            return;
        }

        int current = adminTargetIndex.getOrDefault(viewer.getUniqueId(), indexOfTarget(targets, viewer.getUniqueId()));
        if (current < 0 || current >= targets.size()) {
            current = indexOfTarget(targets, viewer.getUniqueId());
            if (current < 0) {
                current = 0;
            }
        }

        int next = (current + delta) % targets.size();
        if (next < 0) {
            next += targets.size();
        }

        adminTargetIndex.put(viewer.getUniqueId(), next);
        openHomesMenu(viewer, targets.get(next));
    }

    private void setTargetToSelf(Player viewer) {
        List<OfflinePlayer> targets = getKnownPlayersWithHomes();
        int idx = indexOfTarget(targets, viewer.getUniqueId());
        if (idx < 0) {
            idx = 0;
        }
        adminTargetIndex.put(viewer.getUniqueId(), idx);
    }

    private OfflinePlayer getCurrentTarget(Player viewer) {
        if (!canViewOtherHomes(viewer)) {
            return viewer;
        }
        List<OfflinePlayer> targets = getKnownPlayersWithHomes();
        if (targets.isEmpty()) {
            return viewer;
        }

        int idx = adminTargetIndex.getOrDefault(viewer.getUniqueId(), indexOfTarget(targets, viewer.getUniqueId()));
        if (idx < 0 || idx >= targets.size()) {
            idx = indexOfTarget(targets, viewer.getUniqueId());
            if (idx < 0) {
                idx = 0;
            }
            adminTargetIndex.put(viewer.getUniqueId(), idx);
        }

        return targets.get(idx);
    }

    private OfflinePlayer normalizeTarget(Player viewer, OfflinePlayer preferredTarget) {
        if (!canViewOtherHomes(viewer)) {
            return viewer;
        }
        List<OfflinePlayer> targets = getKnownPlayersWithHomes();
        if (targets.isEmpty()) {
            return viewer;
        }

        UUID preferredUuid = preferredTarget == null ? viewer.getUniqueId() : preferredTarget.getUniqueId();
        int idx = indexOfTarget(targets, preferredUuid);
        if (idx < 0) {
            idx = indexOfTarget(targets, viewer.getUniqueId());
        }
        if (idx < 0) {
            idx = 0;
        }
        adminTargetIndex.put(viewer.getUniqueId(), idx);
        return targets.get(idx);
    }

    private List<OfflinePlayer> getKnownPlayersWithHomes() {
        List<OfflinePlayer> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!isLikelyRealPlayerName(online.getName())) {
                continue;
            }
            File f = getSetHomeDataFile(online);
            if (f != null && f.exists()) {
                out.add(online);
            }
        }
        out.sort(Comparator.comparing(PizzaAdminTools::safeName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private boolean isLikelyRealPlayerName(String name) {
        if (name == null) {
            return false;
        }
        return PLAYER_NAME_PATTERN.matcher(name).matches();
    }

    private int indexOfTarget(List<OfflinePlayer> targets, UUID uuid) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getUniqueId().equals(uuid)) {
                return i;
            }
        }
        return -1;
    }

    private List<String> getHomes(OfflinePlayer player) {
        File file = getSetHomeDataFile(player);
        if (file == null || !file.exists()) {
            return new ArrayList<>();
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        return new ArrayList<>(cfg.getStringList("homes"));
    }

    private File getSetHomeDataDir() {
        File pluginsDir = getDataFolder().getParentFile();
        if (pluginsDir == null) {
            return new File("plugins/SetHome/data");
        }
        File setHomeData = new File(pluginsDir, "SetHome/data");
        if (!setHomeData.exists()) {
            setHomeData = new File(pluginsDir, "sethome/data");
        }
        return setHomeData;
    }

    private void initTransferDestinations() {
        transferDestinationsFile = new File(getDataFolder(), "transfer-destinations.yml");
        if (!transferDestinationsFile.getParentFile().exists() && !transferDestinationsFile.getParentFile().mkdirs()) {
            getLogger().warning("Failed to create data folder for transfer destinations.");
        }
        if (!transferDestinationsFile.exists()) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.set("destinations.lobby.host", "127.0.0.1");
            defaults.set("destinations.lobby.port", 25566);
            defaults.set("destinations.main.host", "127.0.0.1");
            defaults.set("destinations.main.port", 25569);
            try {
                defaults.save(transferDestinationsFile);
            } catch (IOException ex) {
                getLogger().warning("Failed to write transfer-destinations.yml: " + ex.getMessage());
            }
        }
        transferDestinationsConfig = YamlConfiguration.loadConfiguration(transferDestinationsFile);
    }

    private void initMaintenanceTransferState() {
        maintenanceTransferStateFile = new File(getDataFolder(), "maintenance-transfer.yml");
        if (!maintenanceTransferStateFile.exists()) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.set("active", false);
            defaults.set("destination", "maintenance");
            defaults.set("transferred", new ArrayList<String>());
            defaults.set("start-message", List.of(
                "&cMaintenance is active.",
                "&7You are being moved to the maintenance server.",
                "&7When maintenance ends, reconnect here."
            ));
            defaults.set("kick-message", List.of(
                "&cServer maintenance is active.",
                "&7Please join the maintenance server: &e%destination%",
                "&7You cannot join this server until maintenance is complete."
            ));
            try {
                defaults.save(maintenanceTransferStateFile);
            } catch (IOException ex) {
                getLogger().warning("Failed to write maintenance-transfer.yml: " + ex.getMessage());
            }
        }
        maintenanceTransferStateConfig = YamlConfiguration.loadConfiguration(maintenanceTransferStateFile);
    }

    private List<String> getTransferDestinationNames() {
        if (transferDestinationsConfig == null) {
            return Collections.emptyList();
        }
        if (transferDestinationsConfig.getConfigurationSection("destinations") == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>(transferDestinationsConfig.getConfigurationSection("destinations").getKeys(false));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private String getMaintenanceTransferDestinationName() {
        if (maintenanceTransferStateConfig == null) {
            return "maintenance";
        }
        return maintenanceTransferStateConfig.getString("destination", "maintenance");
    }

    private boolean isMaintenanceTransferActive() {
        return maintenanceTransferStateConfig != null && maintenanceTransferStateConfig.getBoolean("active", false);
    }

    private void setMaintenanceTransferActive(boolean active) {
        if (maintenanceTransferStateConfig == null) {
            return;
        }
        maintenanceTransferStateConfig.set("active", active);
    }

    private Set<String> getMaintenanceTransferred() {
        if (maintenanceTransferStateConfig == null) {
            return new HashSet<>();
        }
        return new HashSet<>(maintenanceTransferStateConfig.getStringList("transferred"));
    }

    private void saveMaintenanceTransferred(Set<String> transferred) {
        if (maintenanceTransferStateConfig == null) {
            return;
        }
        maintenanceTransferStateConfig.set("transferred", new ArrayList<>(transferred));
    }

    private void saveMaintenanceTransferState() {
        if (maintenanceTransferStateConfig == null || maintenanceTransferStateFile == null) {
            return;
        }
        try {
            maintenanceTransferStateConfig.save(maintenanceTransferStateFile);
        } catch (IOException ex) {
            getLogger().warning("Failed to save maintenance-transfer.yml: " + ex.getMessage());
        }
    }

    private boolean shouldStayDuringMaintenance(Player player) {
        return player.hasPermission(PERM_MAINTENANCE_STAY)
            || player.hasPermission("maintenance.bypass")
            || player.isOp();
    }

    private List<String> getMaintenanceStartMessages(String host, int port) {
        if (maintenanceTransferStateConfig == null) {
            return List.of("&cMaintenance is active.", "&7You are being moved to " + host + ":" + port + ".");
        }
        List<String> configured = maintenanceTransferStateConfig.getStringList("start-message");
        if (configured.isEmpty()) {
            configured = List.of("&cMaintenance is active.", "&7You are being moved to &e" + host + ":" + port + "&7.");
        }
        String destination = host + ":" + port;
        List<String> out = new ArrayList<>(configured.size());
        for (String line : configured) {
            out.add(line.replace("%destination%", destination));
        }
        return out;
    }

    private String buildMaintenanceKickMessage() {
        if (maintenanceTransferStateConfig == null) {
            return color("&cServer maintenance is active.");
        }
        TransferDestination destination = parseTransferDestination(getMaintenanceTransferDestinationName(), null);
        String destinationText = destination == null ? getMaintenanceTransferDestinationName() : destination.host + ":" + destination.port;
        List<String> lines = maintenanceTransferStateConfig.getStringList("kick-message");
        if (lines.isEmpty()) {
            lines = List.of("&cServer maintenance is active.", "&7Join: &e%destination%");
        }
        List<String> rendered = new ArrayList<>(lines.size());
        for (String line : lines) {
            rendered.add(color(line.replace("%destination%", destinationText)));
        }
        return String.join("\n", rendered);
    }

    private TransferDestination parseTransferDestination(String primary, String optionalPort) {
        if (primary == null || primary.isBlank()) {
            return null;
        }

        if (optionalPort != null && !optionalPort.isBlank()) {
            if (!isLikelyPort(optionalPort)) {
                return null;
            }
            return new TransferDestination(primary, Integer.parseInt(optionalPort));
        }

        int colon = primary.lastIndexOf(':');
        if (colon > 0 && colon < primary.length() - 1) {
            String host = primary.substring(0, colon);
            String port = primary.substring(colon + 1);
            if (!isLikelyPort(port)) {
                return null;
            }
            return new TransferDestination(host, Integer.parseInt(port));
        }

        if (transferDestinationsConfig == null) {
            return null;
        }
        String key = "destinations." + primary.toLowerCase(Locale.ROOT) + ".";
        String host = transferDestinationsConfig.getString(key + "host");
        int port = transferDestinationsConfig.getInt(key + "port", -1);
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            return null;
        }
        return new TransferDestination(host, port);
    }

    private static boolean isLikelyPort(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private File getSetHomeDataFile(OfflinePlayer target) {
        if (target == null || target.getUniqueId() == null) {
            return null;
        }
        return new File(getSetHomeDataDir(), target.getUniqueId() + ".yml");
    }

    private String[] cachedDbCreds;

    private String[] loadDbCreds() {
        if (cachedDbCreds != null) return cachedDbCreds;
        File cfg = new File(getDataFolder().getParentFile(), "PizzaNetworkCore/config.yml");
        FileConfiguration c = YamlConfiguration.loadConfiguration(cfg);
        String host = c.getString("database.host", "127.0.0.1");
        int port = c.getInt("database.port", 3306);
        String name = c.getString("database.name", "pizzasmp");
        String user = c.getString("database.user", "pizzasmp");
        String pass = c.getString("database.password", "");
        cachedDbCreds = new String[]{host + ":" + port, name, user, pass};
        return cachedDbCreds;
    }

    private String resolveHomeArg(String arg, List<String> homes, FileConfiguration cfg) {
        try {
            int idx = Integer.parseInt(arg);
            // Fixed-slot: "/home N" refers to the canonical slot "homeN".
            String canonical = "home" + idx;
            if (idx >= 1 && idx <= MAX_HOME_SLOTS
                    && (homes.contains(canonical) || cfg.contains(canonical + ".x"))) {
                return canonical;
            }
            return null;
        } catch (NumberFormatException ignored) {
            // fall through
        }

        if (homes.contains(arg)) {
            return arg;
        }

        if (cfg.contains(arg + ".x") && cfg.contains(arg + ".y") && cfg.contains(arg + ".z")) {
            return arg;
        }

        return null;
    }

    private Location readHomeLocation(OfflinePlayer target, String home) {
        File dataFile = getSetHomeDataFile(target);
        if (dataFile == null || !dataFile.exists()) {
            return null;
        }
        return readHomeLocation(YamlConfiguration.loadConfiguration(dataFile), home);
    }

    private Location readHomeLocation(FileConfiguration cfg, String home) {
        String worldName = cfg.getString(home + ".world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        if (!cfg.contains(home + ".x") || !cfg.contains(home + ".y") || !cfg.contains(home + ".z")) {
            return null;
        }

        double x = cfg.getDouble(home + ".x");
        double y = cfg.getDouble(home + ".y");
        double z = cfg.getDouble(home + ".z");
        float yaw = (float) cfg.getDouble(home + ".yaw", 0.0);
        float pitch = (float) cfg.getDouble(home + ".pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private boolean isHomesMenu(String title) {
        String strippedTitle = ChatColor.stripColor(title);
        String strippedExpected = ChatColor.stripColor(color(HOME_MENU_TITLE));
        return strippedTitle != null && strippedExpected != null && strippedTitle.equalsIgnoreCase(strippedExpected);
    }

    private boolean isHomeDeleteMenu(String title) {
        String strippedTitle = ChatColor.stripColor(title);
        String strippedExpected = ChatColor.stripColor(color(HOME_DELETE_TITLE));
        return strippedTitle != null && strippedExpected != null && strippedTitle.equalsIgnoreCase(strippedExpected);
    }

    private boolean isSusMenu(String title) {
        String strippedTitle = ChatColor.stripColor(title);
        String strippedExpected = ChatColor.stripColor(color(SUS_MENU_TITLE));
        return strippedTitle != null && strippedExpected != null && strippedTitle.startsWith(strippedExpected);
    }

    private static int indexOfSlot(int[] slots, int targetSlot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == targetSlot) {
                return i;
            }
        }
        return -1;
    }

    private static ItemStack item(Material material, String displayName, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(displayName));
            if (lore != null && !lore.isEmpty()) {
                List<String> coloredLore = new ArrayList<>(lore.size());
                for (String line : lore) {
                    coloredLore.add(color(line));
                }
                meta.setLore(coloredLore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static ItemStack playerHead(OfflinePlayer player, String displayName, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = stack.getItemMeta();
        if (!(rawMeta instanceof SkullMeta meta)) {
            return item(Material.PLAYER_HEAD, displayName, lore);
        }
        meta.setDisplayName(color(displayName));
        if (player != null) {
            meta.setOwningPlayer(player);
        }
        if (lore != null && !lore.isEmpty()) {
            List<String> coloredLore = new ArrayList<>(lore.size());
            for (String line : lore) {
                coloredLore.add(color(line));
            }
            meta.setLore(coloredLore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void openSusMenu(Player viewer, int requestedPage) {
        List<SusEntry> entries = loadRecentSusEntries();
        int maxPage = Math.max(0, (entries.size() - 1) / SUS_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        susMenuPages.put(viewer.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, 54, color(SUS_MENU_TITLE + " &7(Page " + (page + 1) + "/" + (maxPage + 1) + ")"));
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inv.getSize(); slot++) {
            inv.setItem(slot, filler);
        }

        int start = page * SUS_PAGE_SIZE;
        for (int slot = 0; slot < SUS_PAGE_SIZE; slot++) {
            int index = start + slot;
            if (index >= entries.size()) {
                break;
            }
            inv.setItem(slot, buildSusItem(entries.get(index)));
        }

        if (entries.isEmpty()) {
            inv.setItem(22, item(
                Material.LIME_DYE,
                "&aNo Recent Grim Flags",
                List.of("&7No players have been flagged in the last 30 minutes.")
            ));
        }

        inv.setItem(45, page > 0
            ? item(Material.ARROW, "&ePrevious Page", List.of("&7Go back one page."))
            : item(Material.GRAY_STAINED_GLASS_PANE, "&8Previous Page", List.of()));
        inv.setItem(49, item(
            Material.NETHER_STAR,
            "&bRefresh",
            List.of("&7Reload Grim suspects from the last 30 minutes.")
        ));
        inv.setItem(53, page < maxPage
            ? item(Material.ARROW, "&eNext Page", List.of("&7Go forward one page."))
            : item(Material.BARRIER, "&cClose", List.of("&7Close this menu."))
        );

        viewer.openInventory(inv);
    }

    private ItemStack buildSusItem(SusEntry entry) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(entry.uuid);
        Player online = Bukkit.getPlayer(entry.uuid);
        List<String> lore = new ArrayList<>();
        lore.add("&7Last Flag: &f" + formatAge(entry.latestAt) + " ago");
        lore.add("&7Flags: &f" + entry.flagCount);
        lore.add("&7Latest Check: &f" + entry.latestCheck);
        lore.add("&7Highest VL: &f" + entry.highestVl);
        lore.add("&7Status: " + (online != null ? "&aOnline" : "&cOffline"));
        if (entry.latestVerbose != null && !entry.latestVerbose.isBlank()) {
            lore.add("&7Detail:");
            for (String line : wrapText(entry.latestVerbose, 30)) {
                lore.add("&f" + line);
            }
        }
        lore.add("&7UUID: &f" + entry.uuid);
        lore.add(online != null ? "&eClick to teleport." : "&cPlayer is offline.");
        return playerHead(
            offline,
            (online != null ? "&a" : "&c") + entry.playerName,
            lore
        );
    }

    private void handleSusClick(Player viewer, int rawSlot) {
        int currentPage = susMenuPages.getOrDefault(viewer.getUniqueId(), 0);
        List<SusEntry> entries = loadRecentSusEntries();
        int maxPage = Math.max(0, (entries.size() - 1) / SUS_PAGE_SIZE);

        if (rawSlot < SUS_PAGE_SIZE) {
            int index = (currentPage * SUS_PAGE_SIZE) + rawSlot;
            if (index < 0 || index >= entries.size()) {
                return;
            }
            SusEntry entry = entries.get(index);
            Player target = Bukkit.getPlayer(entry.uuid);
            if (target == null || !target.isOnline()) {
                sendOfflinePlayerMessage(viewer, entry.playerName);
                openSusMenu(viewer, currentPage);
                return;
            }
            viewer.closeInventory();
            viewer.teleport(target, PlayerTeleportEvent.TeleportCause.COMMAND);
            viewer.sendMessage(color("&aTeleported to &e" + target.getName() + "&a."));
            return;
        }

        if (rawSlot == 45 && currentPage > 0) {
            openSusMenu(viewer, currentPage - 1);
            return;
        }
        if (rawSlot == 49) {
            openSusMenu(viewer, currentPage);
            return;
        }
        if (rawSlot == 53) {
            if (currentPage < maxPage) {
                openSusMenu(viewer, currentPage + 1);
            } else {
                viewer.closeInventory();
            }
        }
    }

    private void sendConsoleSusPage(CommandSender sender, int requestedPage) {
        List<SusEntry> entries = loadRecentSusEntries();
        if (entries.isEmpty()) {
            sender.sendMessage(color("&7No Grim suspects in the last 30 minutes."));
            return;
        }

        int perPage = 10;
        int maxPage = Math.max(0, (entries.size() - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        int start = page * perPage;
        int end = Math.min(entries.size(), start + perPage);

        sender.sendMessage(color("&bServer Suspects &7(page " + (page + 1) + "/" + (maxPage + 1) + ")"));
        for (int i = start; i < end; i++) {
            SusEntry entry = entries.get(i);
            sender.sendMessage(color(
                "&f" + entry.playerName
                    + " &7| &f" + entry.latestCheck
                    + " &7| &fVL " + entry.highestVl
                    + " &7| &f" + entry.flagCount + " flags"
                    + " &7| &f" + formatAge(entry.latestAt) + " ago"
            ));
        }
    }

    private List<SusEntry> loadRecentSusEntries() {
        File dbFile = findGrimViolationsDb();
        if (dbFile == null || !dbFile.exists()) {
            return Collections.emptyList();
        }

        long cutoff = System.currentTimeMillis() - SUS_LOOKBACK_MILLIS;
        Map<UUID, SusEntry> grouped = new HashMap<>();

        String sql = "SELECT hex(v.uuid) AS uuid_hex, c.check_name_string, v.verbose, v.vl, v.created_at "
            + "FROM grim_history_violations v "
            + "JOIN grim_history_check_names c ON c.id = v.check_name_id "
            + "JOIN grim_history_servers s ON s.id = v.server_id "
            + "WHERE s.server_name = ? AND v.created_at >= ? "
            + "ORDER BY v.created_at DESC";

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "ExampleSMP");
            statement.setLong(2, cutoff);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uuid = uuidFromHex(resultSet.getString("uuid_hex"));
                    if (uuid == null) {
                        continue;
                    }

                    String checkName = resultSet.getString("check_name_string");
                    String verbose = resultSet.getString("verbose");
                    int vl = resultSet.getInt("vl");
                    long createdAt = resultSet.getLong("created_at");

                    SusEntry existing = grouped.get(uuid);
                    if (existing == null) {
                        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                        existing = new SusEntry(uuid, safeName(offline), createdAt, checkName, verbose, vl, 0);
                        grouped.put(uuid, existing);
                    }

                    existing.flagCount++;
                    existing.highestVl = Math.max(existing.highestVl, vl);
                    if (createdAt >= existing.latestAt) {
                        existing.latestAt = createdAt;
                        existing.latestCheck = checkName == null ? "Unknown" : checkName;
                        existing.latestVerbose = verbose == null ? "" : verbose;
                    }
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to load Grim suspects: " + ex.getMessage());
            return Collections.emptyList();
        }

        List<SusEntry> out = new ArrayList<>(grouped.values());
        out.sort(Comparator
            .comparingLong((SusEntry entry) -> entry.latestAt).reversed()
            .thenComparingInt(entry -> -entry.flagCount));
        return out;
    }

    private File findGrimViolationsDb() {
        File primary = new File(getServer().getWorldContainer(), "plugins/GrimAC/violations.sqlite");
        if (primary.exists()) {
            return primary;
        }
        File legacy = new File(getServer().getWorldContainer(), "plugins/AC/GrimAC/violations.sqlite");
        if (legacy.exists()) {
            return legacy;
        }
        return null;
    }

    private static UUID uuidFromHex(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replace("-", "").trim();
        if (normalized.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(
                normalized.substring(0, 8) + "-"
                    + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-"
                    + normalized.substring(16, 20) + "-"
                    + normalized.substring(20, 32)
            );
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String formatAge(long timestamp) {
        long deltaSeconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (deltaSeconds < 60L) {
            return deltaSeconds + "s";
        }
        long minutes = deltaSeconds / 60L;
        if (minutes < 60L) {
            return minutes + "m";
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + "h";
        }
        return (hours / 24L) + "d";
    }

    private static List<String> wrapText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String token : text.split("\\s+")) {
            if (current.length() == 0) {
                current.append(token);
                continue;
            }
            if (current.length() + 1 + token.length() > maxLength) {
                out.add(current.toString());
                current.setLength(0);
                current.append(token);
                continue;
            }
            current.append(' ').append(token);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private void openHomeDeleteConfirmMenu(Player viewer, int slotIndex) {
        pendingDeleteSlot.put(viewer.getUniqueId(), slotIndex);
        Inventory inv = Bukkit.createInventory(null, 27, color(HOME_DELETE_TITLE));
        // No glass-pane background.
        Location loc = readHomeLocation(viewer, "home" + (slotIndex + 1));
        List<String> previewLore = new ArrayList<>();
        previewLore.add("&7You are about to delete &fHome " + (slotIndex + 1) + "&7.");
        if (loc != null && loc.getWorld() != null) {
            previewLore.add("&8" + loc.getWorld().getName() + " " + (int) loc.getX() + ", " + (int) loc.getY() + ", " + (int) loc.getZ());
        }
        previewLore.add("&cThis cannot be undone (a backup is kept).");
        inv.setItem(13, item(Material.BLUE_BED, "&cDelete Home " + (slotIndex + 1), previewLore));
        inv.setItem(11, item(Material.RED_STAINED_GLASS_PANE, "&cCancel", List.of("&fClick To Cancel")));
        inv.setItem(15, item(Material.LIME_STAINED_GLASS_PANE, "&aConfirm", List.of("&fClick To Delete Home " + (slotIndex + 1))));
        viewer.openInventory(inv);
    }

    private void handleDeleteConfirmClick(Player viewer, int rawSlot) {
        if (rawSlot == 11) {
            pendingDeleteHome.remove(viewer.getUniqueId());
            pendingDeleteSlot.remove(viewer.getUniqueId());
            openHomesMenu(viewer, getCurrentTarget(viewer));
            return;
        }
        if (rawSlot != 15) {
            return;
        }
        // Name-keyed delete (grid path): resolves the actual home by name (handles custom names).
        String homeName = pendingDeleteHome.remove(viewer.getUniqueId());
        if (homeName != null) {
            handleDelHomeCommand(viewer, homeName);
            openHomesMenu(viewer, viewer);
            return;
        }
        // Legacy slot-index path (kept for safety).
        Integer slotIndex = pendingDeleteSlot.remove(viewer.getUniqueId());
        if (slotIndex == null) {
            openHomesMenu(viewer, getCurrentTarget(viewer));
            return;
        }
        if (removeHomeAtSlot(viewer, slotIndex)) {
            viewer.sendActionBar(color("&7Deleted Home " + (slotIndex + 1) + "&7."));
        }
        openHomesMenu(viewer, viewer);
    }

    /**
     * /sethome [name] — named-homes backend. No name picks the lowest free canonical
     * slot (home1..homeN). Re-setting an existing name moves that home (no limit check);
     * creating a new one is gated by the player's tier limit (3 / 9 / 27).
     */
    private void handleSetHomeCommand(Player player, String rawName) {
        File file = getSetHomeDataFile(player);
        if (file == null) {
            player.sendMessage(color("&cHomes are unavailable right now."));
            return;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            player.sendMessage(color("&cHomes are unavailable right now."));
            getLogger().warning("Could not create SetHome data dir: " + dir);
            return;
        }
        FileConfiguration cfg = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));

        String name;
        if (rawName == null || rawName.isEmpty()) {
            name = null;
            for (int i = 1; i <= MAX_HOME_SLOTS; i++) {
                String candidate = "home" + i;
                if (!homes.contains(candidate)) {
                    name = candidate;
                    break;
                }
            }
            if (name == null) {
                player.sendMessage(color("&cAll home slots are in use. Delete one with &e/delhome&c."));
                return;
            }
        } else {
            name = rawName.toLowerCase(Locale.ROOT);
            if (!HOME_NAME_PATTERN.matcher(name).matches()) {
                player.sendMessage(color("&cHome names must be 1-20 characters: letters, numbers, &e-&c or &e_&c."));
                return;
            }
        }

        boolean isNew = !homes.contains(name);
        int limit = allowedHomes(player);
        if (isNew && homes.size() >= limit) {
            player.sendMessage(color("&cHome limit reached (&e" + homes.size() + "&7/&e" + limit + "&c). Delete a home or upgrade to Pizza+ / Pizza++ for more."));
            return;
        }
        if (isNew) {
            homes.add(name);
            cfg.set("homes", homes);
        }

        Location loc = player.getLocation();
        cfg.set(name + ".world", loc.getWorld() == null ? "world" : loc.getWorld().getName());
        cfg.set(name + ".x", loc.getX());
        cfg.set(name + ".y", loc.getY());
        cfg.set(name + ".z", loc.getZ());
        cfg.set(name + ".yaw", loc.getYaw());
        cfg.set(name + ".pitch", loc.getPitch());
        try {
            cfg.save(file);
        } catch (Exception ex) {
            getLogger().warning("Failed to save home for " + player.getName() + ": " + ex.getMessage());
            player.sendMessage(color("&cFailed to save your home. Staff have been notified."));
            return;
        }
        if (isNew) {
            player.sendMessage(color("&7Home " + HOME_PRIMARY + name + "&7 set. &8(" + homes.size() + "/" + limit + " homes)"));
        } else {
            player.sendMessage(color("&7Home " + HOME_PRIMARY + name + "&7 moved here."));
        }
    }

    /** /delhome <name|number> — name-based delete with the standard pre-delete backup. */
    private void handleDelHomeCommand(Player player, String arg) {
        File file = getSetHomeDataFile(player);
        if (file == null || !file.exists()) {
            player.sendMessage(color("&cNo homes found."));
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        if (homes.isEmpty()) {
            player.sendMessage(color("&cNo homes found."));
            return;
        }
        String name = resolveHomeArg(arg.toLowerCase(Locale.ROOT), homes, cfg);
        if (name == null) {
            player.sendMessage(color("&cHome not found. See &e/homes&c."));
            return;
        }
        backupHome(player, name, cfg);
        homes.remove(name);
        cfg.set(name, null);
        cfg.set("homes", homes);
        try {
            cfg.save(file);
            getLogger().info("[Homes] " + player.getName() + " deleted " + name + " via /delhome (backed up).");
        } catch (Exception ex) {
            getLogger().warning("Failed to delete home for " + player.getName() + ": " + ex.getMessage());
            player.sendMessage(color("&cFailed to delete that home."));
            return;
        }
        // "Deleted home {number}" for default slots (homeN), "Deleted {name}" for custom-named homes.
        if (name.matches("home\\d+")) {
            player.sendMessage(color("&7Deleted home " + HOME_PRIMARY + name.substring(4) + "&7."));
        } else {
            player.sendMessage(color("&7Deleted " + HOME_PRIMARY + name + "&7."));
        }
    }

    private boolean setHomeInSlot(Player player, int slotIndex) {
        File file = getSetHomeDataFile(player);
        if (file == null) {
            return false;
        }
        FileConfiguration cfg = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        // Fixed-slot: this slot is ALWAYS "home"+(slotIndex+1). Add to the list only if absent.
        String homeName = "home" + (slotIndex + 1);
        if (!homes.contains(homeName)) {
            homes.add(homeName);
            cfg.set("homes", homes);
        }

        Location loc = player.getLocation();
        cfg.set(homeName + ".world", loc.getWorld() == null ? "world" : loc.getWorld().getName());
        cfg.set(homeName + ".x", loc.getX());
        cfg.set(homeName + ".y", loc.getY());
        cfg.set(homeName + ".z", loc.getZ());
        cfg.set(homeName + ".yaw", loc.getYaw());
        cfg.set(homeName + ".pitch", loc.getPitch());
        try {
            cfg.save(file);
            return true;
        } catch (Exception ex) {
            getLogger().warning("Failed to save home for " + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean removeHomeAtSlot(Player player, int slotIndex) {
        File file = getSetHomeDataFile(player);
        if (file == null || !file.exists()) {
            return false;
        }
        // Fixed-slot, name-based deletion: delete EXACTLY "home"+(slotIndex+1) — never by list position.
        String homeName = "home" + (slotIndex + 1);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        List<String> homes = new ArrayList<>(cfg.getStringList("homes"));
        if (!homes.contains(homeName) && !cfg.contains(homeName + ".world")) {
            return false;
        }
        // Back up the home before removing it (recoverable if a delete was a mistake).
        backupHome(player, homeName, cfg);
        homes.remove(homeName);
        cfg.set(homeName, null);
        cfg.set("homes", homes);
        try {
            cfg.save(file);
            getLogger().info("[Homes] " + player.getName() + " deleted " + homeName + " (backed up).");
            return true;
        } catch (Exception ex) {
            getLogger().warning("Failed to delete home for " + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    /** Appends a timestamped snapshot of a home's location to a per-player backup file. */
    private void backupHome(OfflinePlayer player, String homeName, FileConfiguration source) {
        try {
            File dir = new File(getDataFolder(), "home-backups");
            if (!dir.exists() && !dir.mkdirs()) {
                getLogger().warning("Could not create home-backups dir.");
                return;
            }
            File backupFile = new File(dir, player.getUniqueId() + ".yml");
            FileConfiguration backup = backupFile.exists() ? YamlConfiguration.loadConfiguration(backupFile) : new YamlConfiguration();
            String key = "deleted." + System.currentTimeMillis();
            backup.set(key + ".home-name", homeName);
            backup.set(key + ".world", source.get(homeName + ".world"));
            backup.set(key + ".x", source.get(homeName + ".x"));
            backup.set(key + ".y", source.get(homeName + ".y"));
            backup.set(key + ".z", source.get(homeName + ".z"));
            backup.set(key + ".yaw", source.get(homeName + ".yaw"));
            backup.set(key + ".pitch", source.get(homeName + ".pitch"));
            backup.save(backupFile);
        } catch (Exception ex) {
            getLogger().warning("Failed to back up home " + homeName + " for " + player.getUniqueId() + ": " + ex.getMessage());
        }
    }

    private void tagCombat(Player player) {
        combatTaggedUntil.put(player.getUniqueId(), System.currentTimeMillis() + COMBAT_TAG_MILLIS);
    }

    private boolean isCombatTagged(Player player) {
        Long until = combatTaggedUntil.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            combatTaggedUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private long remainingCombatSeconds(Player player) {
        Long until = combatTaggedUntil.get(player.getUniqueId());
        if (until == null) {
            return 0;
        }
        long remainingMs = until - System.currentTimeMillis();
        if (remainingMs <= 0) {
            combatTaggedUntil.remove(player.getUniqueId());
            return 0;
        }
        return Math.max(1, (remainingMs + 999) / 1000);
    }

    private void startCountdownTeleport(Player player, Location destination, String successActionBarMessage) {
        startCountdownCommand(player, () -> {
            player.teleportAsync(destination).thenAccept(ok -> {
                if (ok) {
                    // Kill momentum + accrued fall so teleporting mid-fall deals no fall damage.
                    player.setFallDistance(0.0f);
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    noFallDamageUntil.put(player.getUniqueId(), System.currentTimeMillis() + 4000L);
                    player.sendActionBar(color(successActionBarMessage));
                    // Ender-pearl arrival sound, consistent with RTP/TPA.
                    try { player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f); } catch (Throwable ignored) {}
                }
            });
        }, successActionBarMessage);
    }

    /** Cancel fall damage briefly after a teleport (covers momentum the client carried in). */
    @EventHandler(ignoreCancelled = true)
    public void onTeleportFallDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Long until = noFallDamageUntil.get(player.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            event.setCancelled(true);
            player.setFallDistance(0.0f);
        }
    }

    private void startCountdownCommand(Player player, Runnable action, String successActionBarMessage) {
        // Instant teleport — no warmup, no cooldown. Combat is the only gate.
        cancelPendingTeleport(player);
        if (!player.isOnline()) {
            return;
        }
        if (!player.hasPermission(PERM_COMBAT_BYPASS) && isCombatTagged(player)) {
            player.sendMessage(color("&cYou are in combat. Wait &e" + remainingCombatSeconds(player) + "s &cbefore teleporting."));
            return;
        }
        action.run();
    }

    private void cancelPendingTeleport(Player player) {
        UUID uuid = player.getUniqueId();
        Integer taskId = pendingTeleportTasks.remove(uuid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        pendingTeleportOrigins.remove(uuid);
    }

    private boolean hasPlayerMoved(Location from, Location to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.getWorld() == null || to.getWorld() == null) {
            return true;
        }
        if (!from.getWorld().getUID().equals(to.getWorld().getUID())) {
            return true;
        }
        return from.distanceSquared(to) > 0.0001D;
    }

    private boolean isFrozen(Player player) {
        return frozenPlayers.contains(player.getUniqueId());
    }

    private void notifyFrozen(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long nextAllowed = frozenNoticeCooldown.getOrDefault(uuid, 0L);
        if (nextAllowed > now) {
            return;
        }
        frozenNoticeCooldown.put(uuid, now + 1_500L);
        if (maintenanceFrozen.contains(uuid)) {
            player.sendActionBar(color("&aYour region is under maintenance"));
        } else {
            player.sendActionBar(color("&cYou are frozen."));
        }
    }

    private List<String> completePlayerNames(String prefix, boolean frozenOnly) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (frozenOnly && !frozenPlayers.contains(online.getUniqueId())) {
                continue;
            }
            String name = online.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(name);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private List<String> completeOnlineNames(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            String name = online.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(name);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private List<String> completeKnownHomeTargets(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (OfflinePlayer p : getKnownPlayersWithHomes()) {
            String name = p.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(name);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private List<String> completeTargetHomeSlots(String targetArg, String prefix) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetArg);
        File targetFile = getSetHomeDataFile(target);
        if (targetFile == null || !targetFile.exists()) {
            return Collections.emptyList();
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(targetFile);
        List<String> homes = cfg.getStringList("homes");
        List<String> out = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (int i = 0; i < homes.size(); i++) {
            String idx = String.valueOf(i + 1);
            if (idx.startsWith(lower)) {
                out.add(idx);
            }
        }
        for (String home : homes) {
            if (home.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(home);
            }
        }
        return out;
    }

    private List<String> completeTransferCommand(CommandSender sender, String[] args) {
        boolean canTransferOthers = hasAnyPermission(sender, PERM_TRANSFER_OTHERS_CMD);
        List<String> destinationNames = getTransferDestinationNames();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String destination : destinationNames) {
                if (destination.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(destination);
                }
            }
            if (canTransferOthers) {
                out.addAll(completeOnlineNames(args[0]));
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return dedupeCaseInsensitive(out);
        }

        if (args.length == 2) {
            if (canTransferOthers && Bukkit.getPlayerExact(args[0]) != null) {
                return filterByPrefix(destinationNames, args[1]);
            }
            if (isLikelyPort(args[1])) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<>(filterByPrefix(destinationNames, args[1]));
            out.addAll(filterByPrefix(List.of("25569", "25570", "25571"), args[1]));
            return dedupeCaseInsensitive(out);
        }

        if (args.length == 3) {
            if (canTransferOthers && Bukkit.getPlayerExact(args[0]) != null) {
                return filterByPrefix(List.of("25569", "25570", "25571"), args[2]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> dedupeCaseInsensitive(List<String> values) {
        if (values.isEmpty()) {
            return values;
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String key = value.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            out.add(value);
        }
        return out;
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    private static TabRequest parseTabRequest(String rawBuffer) {
        if (rawBuffer == null || rawBuffer.isBlank()) {
            return null;
        }
        String line = rawBuffer.startsWith("/") ? rawBuffer.substring(1) : rawBuffer;
        if (line.isBlank()) {
            return null;
        }

        boolean endsWithSpace = line.endsWith(" ");
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String[] split = trimmed.split("\\s+");
        if (split.length == 0) {
            return null;
        }

        String root = split[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0 && colon + 1 < root.length()) {
            root = root.substring(colon + 1);
        }

        List<String> args = new ArrayList<>();
        for (int i = 1; i < split.length; i++) {
            args.add(split[i]);
        }

        int argPosition;
        String prefix;
        if (endsWithSpace) {
            argPosition = args.size();
            prefix = "";
        } else if (args.isEmpty()) {
            argPosition = -1;
            prefix = "";
        } else {
            argPosition = args.size() - 1;
            prefix = args.get(args.size() - 1).toLowerCase(Locale.ROOT);
        }

        return new TabRequest(root, args, argPosition, prefix);
    }

    private static List<String> filterByPrefix(List<String> values, String prefix) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (lower.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(value);
            }
        }
        return out;
    }

    private static boolean hasAnyPermission(CommandSender sender, String... permissions) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        for (String permission : permissions) {
            if (player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveRtpWorldName(String worldArg) {
        if (worldArg == null || worldArg.isBlank()) {
            return null;
        }
        String normalized = worldArg.toLowerCase(Locale.ROOT);
        String aliased = RTP_WORLD_ALIASES.get(normalized);
        if (aliased != null) {
            return aliased;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().equalsIgnoreCase(worldArg)) {
                return world.getName();
            }
        }
        return null;
    }

    private static Listener findRtpListener() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(RTP_PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        for (RegisteredListener registered : HandlerList.getRegisteredListeners(plugin)) {
            Listener listener = registered.getListener();
            if (RTP_LISTENER_CLASS.equals(listener.getClass().getName())) {
                return listener;
            }
        }
        return null;
    }

    private CommandMap resolveCommandMap() {
        try {
            Method method = getServer().getClass().getMethod("getCommandMap");
            Object result = method.invoke(getServer());
            if (result instanceof CommandMap resolved) {
                return resolved;
            }
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Failed to resolve command map: " + ex.getMessage());
        }
        return null;
    }

    // Roots that are always valid even if the legacy command map misses them (Paper/Brigadier
    // registers some commands outside SimpleCommandMap).
    private static final Set<String> ALWAYS_VALID_ROOTS = Set.of(
        "plugins", "pl", "help", "?", "ver", "version", "about", "icanhasbukkit",
        "me", "msg", "tell", "w", "whisper", "r", "reply", "trigger", "teammsg", "tm"
    );

    private boolean isRegisteredCommand(String root) {
        if (root == null || root.isBlank()) {
            return false;
        }
        if (ALWAYS_VALID_ROOTS.contains(root.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return commandMap != null && commandMap.getCommand(root) != null;
    }

    // ---- Staff mode (/sfmode) ----

    /** True if the player has staff access AND is not hiding it via /sfmode. */
    private boolean isEffectivelyStaff(Player player) {
        return player.hasPermission(PERM_PLUGIN_ADMIN) && !staffMode.contains(player.getUniqueId());
    }

    private void loadStaffMode() {
        staffModeFile = new File(getDataFolder(), "staffmode.yml");
        if (!staffModeFile.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(staffModeFile);
        for (String s : cfg.getStringList("staff-mode")) {
            try { staffMode.add(UUID.fromString(s)); } catch (Exception ignored) {}
        }
    }

    private void saveStaffMode() {
        if (staffModeFile == null) {
            staffModeFile = new File(getDataFolder(), "staffmode.yml");
        }
        FileConfiguration cfg = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID u : staffMode) ids.add(u.toString());
        cfg.set("staff-mode", ids);
        try {
            getDataFolder().mkdirs();
            cfg.save(staffModeFile);
        } catch (Exception ex) {
            getLogger().warning("Failed saving staffmode.yml: " + ex.getMessage());
        }
    }

    /** /gmcbypass <player> — CONSOLE ONLY. Grants one creative-mode change that bypasses the ban. */
    private boolean handleGmcBypassCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player p) {
            // Console-only: players get the unknown-command treatment.
            p.sendActionBar(color("&cThis command does not exist"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(color("&cUsage: /gmcbypass <player>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(color("&cPlayer not found or offline: &e" + args[0]));
            return true;
        }
        creativeBypass.add(target.getUniqueId());
        target.setGameMode(GameMode.CREATIVE);
        sender.sendMessage(color("&aSet &e" + target.getName() + " &ato creative (rule bypassed)."));
        return true;
    }

    /** /sfmode — toggle staff mode (hides staff commands + blocks their use). Staff only. */
    private boolean handleSfModeCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cOnly players can use /sfmode."));
            return true;
        }
        // Real perm (ignores staff-mode) so a hidden staffer can still toggle back off.
        if (!player.hasPermission(PERM_PLUGIN_ADMIN)) {
            player.sendActionBar(color("&cThis command does not exist"));
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (staffMode.contains(uuid)) {
            staffMode.remove(uuid);
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                "§aStaff mode is now OFF. Your staff functions are back."));
        } else {
            staffMode.add(uuid);
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                "§eStaff mode is now ON. All staff functions are hidden and you can no longer use any."));
        }
        saveStaffMode();
        // Re-send the command tree now so the hide/show applies immediately (no relog needed).
        try { player.updateCommands(); } catch (Throwable ignored) {}
        return true;
    }

    private Player resolveOnlinePlayer(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(input);
        if (exact != null) {
            return exact;
        }
        return Bukkit.getPlayer(input);
    }

    private void sendUnknownCommandMessage(CommandSender sender) {
        if (sender instanceof Player player) {
            player.sendActionBar(color("&cThis command does not exist"));
            return;
        }
        sender.sendMessage(color("&cThis command does not exist"));
    }

    private void sendOfflinePlayerMessage(CommandSender sender, String input) {
        if (sender instanceof Player player) {
            player.sendActionBar(color("&cThe user is not online"));
            return;
        }
        sender.sendMessage(color("&cThe user is not online"));
    }

    private static String[] parseCommandParts(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        String message = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] split = message.trim().split("\\s+");
        if (split.length == 0) {
            return new String[0];
        }

        String root = split[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0 && colon + 1 < root.length()) {
            root = root.substring(colon + 1);
        }
        split[0] = root;
        return split;
    }

    private static boolean isTeleportHomeCommand(String[] parts) {
        if (parts.length <= 1) {
            return false;
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        return !NON_TELEPORT_HOME_SUBCOMMANDS.contains(sub);
    }

    private static String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    private static String sanitizeBanMessage(String message) {
        String[] lines = message.replace("\r", "").split("\n");
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String trimmed = ChatColor.stripColor(line == null ? "" : line).trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("appeal your punishment")) {
                continue;
            }
            if (trimmed.startsWith("website:")) {
                continue;
            }
            if (trimmed.startsWith("discord:")) {
                continue;
            }
            kept.add(line);
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).trim().isEmpty()) {
            kept.remove(kept.size() - 1);
        }
        return String.join("\n", kept);
    }

    private static String normalizeBanKickMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String stripped = ChatColor.stripColor(message).replace("\r", "");
        String lower = stripped.toLowerCase(Locale.ROOT);

        if (lower.contains("you are banned from pizzasmp") && lower.contains("ban id:") && lower.contains("duration:")) {
            return message;
        }

        String reason = "No reason stated.";
        String duration = "Permanent";
        String banId = null;
        String date = null;

        int reasonLine = lower.indexOf("\nreason\n");
        if (reasonLine >= 0) {
            int start = reasonLine + "\nreason\n".length();
            String parsed = stripped.substring(start).trim();
            if (!parsed.isEmpty()) {
                reason = parsed;
            }
        }

        int durationLine = lower.indexOf("duration:");
        if (durationLine >= 0) {
            int end = stripped.indexOf('\n', durationLine);
            String parsed = (end >= 0 ? stripped.substring(durationLine + "duration:".length(), end)
                : stripped.substring(durationLine + "duration:".length())).trim();
            if (!parsed.isEmpty()) {
                duration = parsed;
            }
        }

        int banIdLine = lower.indexOf("ban id:");
        if (banIdLine >= 0) {
            int end = stripped.indexOf('\n', banIdLine);
            String parsed = (end >= 0 ? stripped.substring(banIdLine + "ban id:".length(), end)
                : stripped.substring(banIdLine + "ban id:".length())).trim();
            if (!parsed.isEmpty()) {
                banId = parsed;
            }
        }

        int dateLine = lower.indexOf("date:");
        if (dateLine >= 0) {
            int end = stripped.indexOf('\n', dateLine);
            String parsed = (end >= 0 ? stripped.substring(dateLine + "date:".length(), end)
                : stripped.substring(dateLine + "date:".length())).trim();
            if (!parsed.isEmpty()) {
                date = parsed;
            }
        }

        String prefix = "You are banned from this server for ";
        int wrapper = lower.indexOf(prefix);
        if (wrapper >= 0) {
            int reasonStart = lower.indexOf(". reason:", wrapper);
            if (reasonStart > wrapper) {
                String parsedDuration = stripped.substring(wrapper + prefix.length(), reasonStart).trim();
                if (!parsedDuration.isEmpty()) {
                    duration = parsedDuration;
                }
                String wrappedReason = stripped.substring(reasonStart + ". reason:".length()).trim();
                int nestedHeader = wrappedReason.toLowerCase(Locale.ROOT).indexOf("you have been banned from the pizzasmp");
                if (nestedHeader >= 0) {
                    wrappedReason = wrappedReason.substring(nestedHeader);
                }
                int nestedReasonLine = wrappedReason.toLowerCase(Locale.ROOT).indexOf("\nreason\n");
                if (nestedReasonLine >= 0) {
                    String parsed = wrappedReason.substring(nestedReasonLine + "\nreason\n".length()).trim();
                    if (!parsed.isEmpty()) {
                        reason = parsed;
                    }
                } else if (!wrappedReason.isEmpty()
                    && !wrappedReason.toLowerCase(Locale.ROOT).startsWith("you have been banned from the pizzasmp")) {
                    reason = wrappedReason;
                }
            }
        }

        StringBuilder rebuilt = new StringBuilder()
            .append(ChatColor.RED)
            .append("You are banned from this server. If you believe this was a mistake please make a ticket in the Example SMP Discord")
            .append('\n')
            .append(ChatColor.YELLOW)
            .append(DISCORD_INVITE)
            .append("\n\n");
        if (date != null) {
            rebuilt.append(ChatColor.GRAY).append("Date: ").append(ChatColor.WHITE).append(date).append('\n');
        }
        rebuilt.append(ChatColor.GRAY).append("Duration: ").append(ChatColor.WHITE).append(duration).append('\n');
        if (banId != null) {
            rebuilt.append(ChatColor.GRAY).append("Ban ID: ").append(ChatColor.WHITE).append(banId).append('\n');
        }
        rebuilt.append(ChatColor.GRAY).append("Reason: ").append(ChatColor.WHITE).append(reason).append('\n');
        rebuilt.append(ChatColor.GRAY).append("You may be able to appeal this ban on ").append(ChatColor.WHITE).append(DISCORD_INVITE).append(ChatColor.GRAY).append('.');
        return rebuilt.toString();
    }

    @EventHandler
    public void onNvPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        if (nvEnabled.contains(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> {
                Player p = event.getPlayer();
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            });
        }
    }

    @EventHandler
    public void onNvItemConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        if (nvEnabled.contains(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player p = event.getPlayer();
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }, 2L);
        }
    }

    @EventHandler
    public void onNvResurrect(org.bukkit.event.entity.EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player p && nvEnabled.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }, 2L);
        }
    }

    @EventHandler
    public void onNvJoin(PlayerJoinEvent event) {
        if (nvEnabled.contains(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player p = event.getPlayer();
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }, 10L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoinBrand(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            try {
                Method getHandle = player.getClass().getMethod("getHandle");
                Object serverPlayer = getHandle.invoke(player);

                Class<?> brandPayloadClass = Class.forName(
                    "net.minecraft.network.protocol.common.custom.BrandPayload");
                Object brandPayload = brandPayloadClass
                    .getConstructor(String.class)
                    .newInstance(BRAND_NAME);

                Class<?> customPacketPayloadClass = Class.forName(
                    "net.minecraft.network.protocol.common.custom.CustomPacketPayload");
                Class<?> packetClass = Class.forName(
                    "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
                Object packet = packetClass
                    .getConstructor(customPacketPayloadClass)
                    .newInstance(brandPayload);

                Field connectionField = serverPlayer.getClass().getField("connection");
                Object connection = connectionField.get(serverPlayer);

                Class<?> packetBaseClass = Class.forName("net.minecraft.network.protocol.Packet");
                Method send = connection.getClass().getMethod("send", packetBaseClass);
                send.invoke(connection, packet);
            } catch (Exception e) {
                getLogger().warning("Failed to send brand packet: " + e.getMessage());
            }
        }, 20L);
    }

    private static String safeName(OfflinePlayer p) {
        return p.getName() == null ? p.getUniqueId().toString() : p.getName();
    }

    private static final class TabRequest {
        private final String root;
        private final List<String> args;
        private final int argPosition;
        private final String prefix;

        private TabRequest(String root, List<String> args, int argPosition, String prefix) {
            this.root = root;
            this.args = args;
            this.argPosition = argPosition;
            this.prefix = prefix;
        }
    }

    private static final class SusEntry {
        private final UUID uuid;
        private final String playerName;
        private long latestAt;
        private String latestCheck;
        private String latestVerbose;
        private int highestVl;
        private int flagCount;

        private SusEntry(UUID uuid, String playerName, long latestAt, String latestCheck, String latestVerbose, int highestVl, int flagCount) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.latestAt = latestAt;
            this.latestCheck = latestCheck == null ? "Unknown" : latestCheck;
            this.latestVerbose = latestVerbose == null ? "" : latestVerbose;
            this.highestVl = highestVl;
            this.flagCount = flagCount;
        }
    }

    private static final class TransferDestination {
        private final String host;
        private final int port;

        private TransferDestination(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
