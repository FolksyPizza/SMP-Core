package me.pizzasmp.punishdrop;

/*
 * PunishDropPlugin is part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Released under the MIT License (see LICENSE). Provided AS IS, without warranty.
 */

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
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

public final class PunishDropPlugin extends JavaPlugin implements Listener {
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([ydhms])", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("^#\\d+$");
    private static final Pattern LEGACY_ID_PATTERN = Pattern.compile("^PS-[A-Z0-9]{8}$");
    private static final Pattern IPV4_PATTERN = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final long DUPLICATE_WINDOW_MS = 2500L;
    private static final long SHORT_LIBERTY_FALLBACK_MS = 60_000L;
    private static final String ID_PREFIX = "#";
    private static final String DISCORD_INVITE = "discord.gg/example";
    private static final ZoneId BAN_DATE_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("MM/dd/yyyy").withZone(BAN_DATE_ZONE);
    private static final int MENU_GUI_SIZE = 45;
    private static final int LIST_GUI_SIZE = 54;
    private static final int LIST_PAGE_SIZE = 45;

    private File locationsFile;
    private FileConfiguration locationsConfig;
    private File pendingActionsFile;
    private FileConfiguration pendingActionsConfig;
    private File recordsFile;
    private FileConfiguration recordsConfig;
    private File auditFile;
    private File offensesFile;
    private FileConfiguration offensesConfig;
    private final Set<String> flightOwnerWhitelist = new HashSet<>();
    private final Set<String> activeLocks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> recentActionTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BukkitTask> pendingReleaseTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PunishmentRecord> records = new ConcurrentHashMap<>();
    private final Map<String, PunishmentPreset> presetsByKey = new HashMap<>();
    private final List<String> presetSuggestions = new ArrayList<>();
    private final ConcurrentHashMap<String, PendingBulkClear> pendingBulkClearConfirms = new ConcurrentHashMap<>();
    private final SecureRandom idRandom = new SecureRandom();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadFlightWhitelist();
        loadPunishmentPresets();
        initLocations();
        initPendingActions();
        initRecords();
        schedulePendingActions();

        registerCommand("punish");
        registerCommand("ban");
        registerCommand("permban");
        registerCommand("unban");
        registerCommand("unpunish");
        registerCommand("forgive");
        registerCommand("idunban");
        registerCommand("ipban");
        registerCommand("ippermban");
        registerCommand("kick");
        registerCommand("mute");
        registerCommand("unmute");
        registerCommand("history");
        registerCommand("bancheck");
        registerCommand("searchid");
        registerCommand("bans");
        registerCommand("listbans");
        registerCommand("listmutes");
        registerCommand("clearbans");
        registerCommand("clearmutes");
        registerCommand("moderation");
        registerCommand("offend");
        registerCommand("offenses");
        registerCommand("unoffend");
        initOffenses();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(this::enforceFlightRules));
        Bukkit.getScheduler().runTaskTimer(this, () -> Bukkit.getOnlinePlayers().forEach(this::enforceFlightRules), 20L, 20L);

        getLogger().info("PunishDrop enabled.");
    }

    @Override
    public void onDisable() {
        pendingReleaseTasks.values().forEach(task -> {
            if (task != null) {
                task.cancel();
            }
        });
        pendingReleaseTasks.clear();
        savePendingActions();
        saveRecords();
        saveOffenses();
    }

    private void registerCommand(String name) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command missing in plugin.yml: " + name);
            return;
        }
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    private void initLocations() {
        locationsFile = new File(getDataFolder(), "ban-locations.yml");
        if (!locationsFile.getParentFile().exists()) {
            locationsFile.getParentFile().mkdirs();
        }
        locationsConfig = YamlConfiguration.loadConfiguration(locationsFile);
    }

    private void initPendingActions() {
        pendingActionsFile = new File(getDataFolder(), "pending-unbans.yml");
        pendingActionsConfig = YamlConfiguration.loadConfiguration(pendingActionsFile);
    }

    private void initRecords() {
        recordsFile = new File(getDataFolder(), "ban-records.yml");
        recordsConfig = YamlConfiguration.loadConfiguration(recordsFile);
        auditFile = new File(getDataFolder(), "ban-audit.log");
        records.clear();

        ConfigurationSection section = recordsConfig.getConfigurationSection("records");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection recordSection = section.getConfigurationSection(id);
            if (recordSection == null) {
                continue;
            }
            PunishmentRecord record = PunishmentRecord.fromConfig(id, recordSection);
            if (record != null) {
                records.put(record.id, record);
            }
        }
    }

    private void loadPunishmentPresets() {
        presetsByKey.clear();
        presetSuggestions.clear();

        ConfigurationSection section = getConfig().getConfigurationSection("punishment-categories");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection presetSection = section.getConfigurationSection(key);
            if (presetSection == null) {
                continue;
            }
            PunishmentPreset preset = PunishmentPreset.fromConfig(key, presetSection);
            if (preset == null) {
                continue;
            }
            presetSuggestions.add(preset.key);
            presetsByKey.put(preset.key, preset);
            for (String alias : preset.aliases) {
                presetsByKey.put(alias, preset);
            }
        }
        Collections.sort(presetSuggestions);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.isBlank()) {
            return;
        }

        String[] split = raw.substring(1).trim().split("\\s+");
        if (split.length > 0 && isFlyCommand(split[0]) && !canUseSurvivalFlight(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Flight is only allowed for the owner or in creative mode.");
            enforceFlightRules(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        UUID uuid = event.getPlayer().getUniqueId();
        Location location = consumeStoredLocation(uuid);

        Bukkit.getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            if (!player.isOnline()) {
                return;
            }
            if (location != null && location.getWorld() != null) {
                player.teleport(location);
            }
            enforceFlightRules(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Bukkit.getScheduler().runTask(this, () -> enforceFlightRules(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (canUseSurvivalFlight(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        enforceFlightRules(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Vanilla drop behavior: items drop at the death spot. The ONLY recovery
        // path is the player walking back to retrieve them before they despawn —
        // there is intentionally no /reclaim or other command-based recovery.
        event.setKeepInventory(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onModerationGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ModerationMenuHolder) {
            event.setCancelled(true);
            handleMenuClick(player, event.getRawSlot());
            return;
        }
        if (holder instanceof PunishmentListHolder listHolder) {
            event.setCancelled(true);
            handleListClick(player, event.getRawSlot(), listHolder);
            return;
        }
        if (holder instanceof PunishmentDetailHolder detailHolder) {
            event.setCancelled(true);
            handleDetailClick(player, event.getRawSlot(), detailHolder);
            return;
        }
        if (holder instanceof BulkClearHolder clearHolder) {
            event.setCancelled(true);
            handleBulkClearClick(player, event.getRawSlot(), clearHolder);
            return;
        }
        if (holder instanceof OnlinePlayersHolder onlineHolder) {
            event.setCancelled(true);
            handleOnlinePlayersClick(player, event.getRawSlot(), onlineHolder);
            return;
        }
        if (holder instanceof PlayerActionsHolder actionsHolder) {
            event.setCancelled(true);
            handlePlayerActionsClick(player, event.getRawSlot(), actionsHolder);
            return;
        }
        if (holder instanceof OffensesHolder offensesHolder) {
            event.setCancelled(true);
            handleOffensesClick(player, event.getRawSlot(), offensesHolder);
            return;
        }
        if (holder instanceof OffenseDetailHolder offenseDetailHolder) {
            event.setCancelled(true);
            handleOffenseDetailClick(player, event.getRawSlot(), offenseDetailHolder);
            return;
        }
        if (holder instanceof ChatStrikesHolder chatStrikesHolder) {
            event.setCancelled(true);
            handleChatStrikesClick(player, event.getRawSlot(), chatStrikesHolder);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "punish":
                return handleGenericPunishCommand(sender, args);
            case "ban":
                return handleExplicitPunishmentCommand(sender, args, PunishmentType.BAN, false, false);
            case "permban":
                return handleExplicitPunishmentCommand(sender, args, PunishmentType.BAN, true, false);
            case "ipban":
                return handleExplicitPunishmentCommand(sender, args, PunishmentType.IP_BAN, false, false);
            case "ippermban":
                return handleExplicitPunishmentCommand(sender, args, PunishmentType.IP_BAN, true, false);
            case "mute":
                return handleExplicitPunishmentCommand(sender, args, PunishmentType.MUTE, false, false);
            case "kick":
                return handleExplicitPunishmentCommand(sender, args, PunishmentType.KICK, false, false);
            case "unban":
            case "unpunish":
            case "forgive":
            case "idunban":
                return handleReleaseCommand(sender, args, true);
            case "unmute":
                return handleReleaseCommand(sender, args, false);
            case "history":
                return handleHistoryCommand(sender, args);
            case "bancheck":
                return handleBancheckCommand(sender, args);
            case "searchid":
                return handleSearchIdCommand(sender, args);
            case "bans":
            case "moderation":
                return handleModerationMenuCommand(sender);
            case "listbans":
                return handleListCommand(sender, args, ViewMode.ACTIVE_BANS);
            case "listmutes":
                return handleListCommand(sender, args, ViewMode.ACTIVE_MUTES);
            case "clearbans":
                return handleBulkClearCommand(sender, args, ViewMode.ACTIVE_BANS);
            case "clearmutes":
                return handleBulkClearCommand(sender, args, ViewMode.ACTIVE_MUTES);
            case "offend":
            case "offense":
                return handleOffendCommand(sender, args);
            case "offenses":
            case "strikes":
                return handleOffensesCommand(sender, args);
            case "unoffend":
                return handleUnoffendCommand(sender, args);
            default:
                return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "punish":
            case "ban":
            case "permban":
            case "mute":
            case "kick":
                return completePunishmentCommand(name, args);
            case "ipban":
            case "ippermban":
                return completeIpBanCommand(args);
            case "unban":
            case "unpunish":
            case "forgive":
            case "idunban":
                if (args.length == 1) {
                    return filterByPrefix(getReleaseSuggestions(true), args[0]);
                }
                return List.of();
            case "unmute":
                if (args.length == 1) {
                    return filterByPrefix(getReleaseSuggestions(false), args[0]);
                }
                return List.of();
            case "history":
            case "bancheck":
                if (args.length == 1) {
                    return filterByPrefix(getHistorySuggestions(), args[0]);
                }
                if (args.length == 2) {
                    return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[1]);
                }
                return List.of();
            case "searchid":
                if (args.length == 1) {
                    return filterByPrefix(getRecordIdSuggestions(), args[0]);
                }
                return List.of();
            case "listbans":
            case "listmutes":
                if (args.length == 1) {
                    return filterByPrefix(List.of("1", "2", "3", "4", "5"), args[0]);
                }
                return List.of();
            case "clearbans":
            case "clearmutes":
                if (args.length == 1) {
                    return filterByPrefix(List.of("confirm"), args[0]);
                }
                return List.of();
            case "offend":
            case "offense":
                if (args.length == 1) return completeKnownPlayerTargets(args[0]);
                return filterByPrefix(CHEAT_REASON_PRESETS, args[args.length - 1]);
            case "offenses":
            case "strikes":
                if (args.length == 1) return completeKnownPlayerTargets(args[0]);
                return List.of();
            case "unoffend":
                if (args.length == 1) return completeKnownPlayerTargets(args[0]);
                if (args.length == 2) return filterByPrefix(List.of("1", "2", "3", "all"), args[1]);
                return List.of();
            default:
                return List.of();
        }
    }

    private boolean handleGenericPunishCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !hasPunishPermission(player)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to punish players.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /punish <player> <category|duration|reason...> [duration]");
            return true;
        }

        ResolvedTarget target = resolvePlayerTarget(args[0], false);
        if (!target.found()) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
            return true;
        }

        ParsedPunishment parsed = parsePunishArgs(PunishmentType.BAN, Arrays.copyOfRange(args, 1, args.length), false, true);
        if (parsed == null) {
            sender.sendMessage(ChatColor.RED + "Usage: /punish <player> <category|duration|reason...> [duration]");
            return true;
        }

        // Only drop the player's inventory if they're combat-tagged at the moment
        // of the ban (PizzaNetworkCore exposes this via the "pizza_combat" tag).
        // Otherwise the player keeps their inventory across the ban.
        boolean dropInventory = parsed.type == PunishmentType.BAN && isInPizzaCombat(target.onlinePlayer);
        return executePunishmentCommand(sender, target, parsed, dropInventory, "punish");
    }

    private boolean handleExplicitPunishmentCommand(
        CommandSender sender,
        String[] args,
        PunishmentType forcedType,
        boolean forcePermanent,
        boolean fromGui
    ) {
        if (sender instanceof Player player && !hasPermissionForType(player, forcedType)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use " + commandLabelForType(forcedType) + ".");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: " + usageForType(forcedType, forcePermanent));
            return true;
        }

        ResolvedTarget target = forcedType == PunishmentType.IP_BAN
            ? resolveIpTarget(args[0])
            : resolvePlayerTarget(args[0], forcedType == PunishmentType.KICK);
        if (!target.found()) {
            sender.sendMessage(ChatColor.RED + "Unable to resolve target: " + args[0] + ".");
            return true;
        }

        ParsedPunishment parsed = parsePunishArgs(forcedType, Arrays.copyOfRange(args, 1, args.length), forcePermanent, false);
        if (parsed == null) {
            sender.sendMessage(ChatColor.RED + "Usage: " + usageForType(forcedType, forcePermanent));
            return true;
        }

        return executePunishmentCommand(sender, target, parsed, false, commandLabelForType(forcedType));
    }

    private boolean executePunishmentCommand(
        CommandSender sender,
        ResolvedTarget target,
        ParsedPunishment parsed,
        boolean dropInventoryOnBan,
        String sourceCommand
    ) {
        String lockKey = parsed.type.name() + ":" + target.lockKey();
        if (!activeLocks.add(lockKey)) {
            return true;
        }

        try {
            long now = System.currentTimeMillis();
            Long previous = recentActionTimes.get(lockKey);
            if (previous != null && now - previous < DUPLICATE_WINDOW_MS) {
                return true;
            }
            recentActionTimes.put(lockKey, now);

            PunishmentRecord record = new PunishmentRecord(
                generateId(),
                parsed.type,
                target.targetKind,
                target.targetName,
                target.targetUuid == null ? null : target.targetUuid.toString(),
                target.targetAddress,
                parsed.reason,
                sender.getName(),
                now,
                parsed.durationInput,
                parsed.expiry == null ? 0L : parsed.expiry.getTime(),
                parsed.type.isActiveByDefault(),
                0L,
                null,
                sourceCommand
            );

            boolean applied = switch (parsed.type) {
                case BAN -> applyPlayerBan(record, target.onlinePlayer, dropInventoryOnBan);
                case IP_BAN -> applyIpBan(record, target.onlinePlayer);
                case MUTE -> applyMute(record);
                case KICK -> applyKick(record, target.onlinePlayer);
            };
            if (!applied) {
                sender.sendMessage(ChatColor.RED + "Failed to apply " + parsed.type.displayName + " to " + target.displayTarget() + ".");
                return true;
            }

            if (parsed.type == PunishmentType.BAN && dropInventoryOnBan && target.onlinePlayer != null) {
                Location dropLocation = target.onlinePlayer.getLocation().clone();
                storeLocation(target.onlinePlayer.getUniqueId(), dropLocation);
            }

            if (record.active || record.type == PunishmentType.KICK) {
                storeRecord(record);
                appendAuditLine("APPLY", record, sender.getName());
            }

            if (record.active && record.expiresAt > 0L) {
                scheduleRelease(record);
            } else {
                removePendingAction(record.id);
            }

            if (record.type == PunishmentType.KICK) {
                record.active = false;
                record.clearedAt = System.currentTimeMillis();
                record.clearedBy = sender.getName();
                persistRecord(record);
                saveRecords();
            }

            sender.sendMessage(ChatColor.GREEN + record.type.displayName + " applied to " + target.displayTarget() + ". ID: " + record.id + ".");
            return true;
        } finally {
            activeLocks.remove(lockKey);
        }
    }

    private boolean handleReleaseCommand(CommandSender sender, String[] args, boolean banLike) {
        if (sender instanceof Player player && !hasReleasePermission(player, banLike)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to " + (banLike ? "unban" : "unmute") + " players.");
            return true;
        }
        if (args.length < 1 || args[0].isBlank()) {
            sender.sendMessage(ChatColor.RED + "Usage: " + (banLike ? "/unban <player|ip|id>" : "/unmute <player|id>"));
            return true;
        }

        String lookup = args[0].trim();
        PunishmentRecord activeRecord = findActiveRecordByLookup(lookup, banLike
            ? Set.of(PunishmentType.BAN, PunishmentType.IP_BAN)
            : Set.of(PunishmentType.MUTE));

        if (activeRecord == null && looksLikeId(lookup)) {
            sender.sendMessage(ChatColor.RED + "No active record found for " + lookup + ".");
            return true;
        }

        boolean changed = false;
        if (activeRecord != null) {
            changed = releaseRecord(activeRecord, sender.getName(), true);
            if (changed) {
                sender.sendMessage(ChatColor.GREEN + activeRecord.type.clearVerb + " " + activeRecord.displayTarget() + ". ID: " + activeRecord.id + ".");
            } else {
                sender.sendMessage(ChatColor.RED + "Failed to clear record " + activeRecord.id + ".");
            }
            return true;
        }

        if (banLike) {
            changed = performGenericUnban(lookup, resolveOfflineUuid(lookup), findKnownAddress(lookup));
            sender.sendMessage(changed
                ? ChatColor.GREEN + "Processed unban request for " + lookup + "."
                : ChatColor.RED + "No active local record found. Native unban request sent for " + lookup + ".");
            return true;
        }

        changed = performGenericUnmute(lookup, resolveOfflineUuid(lookup));
        sender.sendMessage(changed
            ? ChatColor.GREEN + "Processed unmute request for " + lookup + "."
            : ChatColor.RED + "No active local record found. Native unmute request sent for " + lookup + ".");
        return true;
    }

    private boolean handleHistoryCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !hasHistoryPermission(player)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to view punishment history.");
            return true;
        }
        if (args.length < 1 || args[0].isBlank()) {
            if (sender instanceof Player player) {
                openListGui(player, ViewMode.HISTORY, 0);
            } else {
                sender.sendMessage(ChatColor.RED + "Usage: /history <player|ip|id> [page]");
            }
            return true;
        }

        String lookup = args[0].trim();
        int page = parsePageArg(args.length > 1 ? args[1] : null);
        if (page < 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /history <player|ip|id> [page]");
            return true;
        }

        if (looksLikeId(lookup)) {
            PunishmentRecord record = findBestRecordByLookup(lookup);
            if (record == null) {
                sender.sendMessage(ChatColor.RED + "No record found for " + lookup + ".");
                return true;
            }
            sendRecordDetails(sender, record);
            return true;
        }

        List<PunishmentRecord> matches = findRecordsForLookup(lookup);
        if (matches.isEmpty()) {
            boolean dispatched = dispatchNativeHistory(lookup);
            if (!dispatched) {
                sender.sendMessage(ChatColor.RED + "No local history found for " + lookup + ".");
            }
            return true;
        }
        sendHistoryPage(sender, matches, lookup, page);
        return true;
    }

    private boolean handleBancheckCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return handleListCommand(sender, new String[0], ViewMode.ACTIVE_BANS);
        }
        return handleHistoryCommand(sender, args);
    }

    private boolean handleSearchIdCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !hasHistoryPermission(player)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to search punishment IDs.");
            return true;
        }
        if (args.length < 1 || args[0].isBlank()) {
            sender.sendMessage(ChatColor.RED + "Usage: /searchid <id>");
            return true;
        }

        PunishmentRecord record = findBestRecordByLookup(args[0].trim());
        if (record == null) {
            sender.sendMessage(ChatColor.RED + "No record found for " + args[0].trim() + ".");
            return true;
        }
        sendRecordDetails(sender, record);
        return true;
    }

    private boolean handleModerationMenuCommand(CommandSender sender) {
        if (sender instanceof Player player) {
            if (!hasMenuPermission(player)) {
                player.sendMessage(ChatColor.RED + "You do not have permission to open the moderation GUI.");
                return true;
            }
            openModerationMenu(player);
            return true;
        }
        return handleListCommand(sender, new String[0], ViewMode.ACTIVE_BANS);
    }

    private boolean handleListCommand(CommandSender sender, String[] args, ViewMode viewMode) {
        if (sender instanceof Player player && !hasMenuPermission(player)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to view this list.");
            return true;
        }

        int page = parsePageArg(args.length > 0 ? args[0] : null);
        if (page < 0) {
            sender.sendMessage(ChatColor.RED + "Usage: " + (viewMode == ViewMode.ACTIVE_BANS ? "/listbans [page]" : "/listmutes [page]"));
            return true;
        }

        if (sender instanceof Player player) {
            openListGui(player, viewMode, page);
            return true;
        }

        sendConsoleListPage(sender, viewMode, page);
        return true;
    }

    private boolean handleBulkClearCommand(CommandSender sender, String[] args, ViewMode mode) {
        if (sender instanceof Player player && !hasReleasePermission(player, mode == ViewMode.ACTIVE_BANS)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to clear these punishments.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
            return runPendingBulkClear(sender, mode);
        }

        if (sender instanceof Player player) {
            openBulkClearConfirm(player, mode);
            return true;
        }

        pendingBulkClearConfirms.put(confirmKey(sender), new PendingBulkClear(mode, System.currentTimeMillis() + getConfirmWindowMillis()));
        sender.sendMessage(ChatColor.YELLOW + "Run " + commandForBulkMode(mode) + " confirm within "
            + (getConfirmWindowMillis() / 1000L) + "s to clear all active " + mode.displayName.toLowerCase(Locale.ROOT) + ".");
        return true;
    }

    private boolean runPendingBulkClear(CommandSender sender, ViewMode requestedMode) {
        PendingBulkClear pending = pendingBulkClearConfirms.get(confirmKey(sender));
        if (pending == null || pending.mode != requestedMode || pending.expiresAt < System.currentTimeMillis()) {
            pendingBulkClearConfirms.remove(confirmKey(sender));
            sender.sendMessage(ChatColor.RED + "No pending confirmation was found for " + commandForBulkMode(requestedMode) + ".");
            return true;
        }
        pendingBulkClearConfirms.remove(confirmKey(sender));
        int cleared = clearActiveRecords(requestedMode, sender.getName());
        sender.sendMessage(ChatColor.GREEN + "Cleared " + cleared + " active " + requestedMode.displayName.toLowerCase(Locale.ROOT) + ".");
        return true;
    }

    private boolean applyPlayerBan(PunishmentRecord record, Player onlineTarget, boolean dropInventoryOnBan) {
        if (dropInventoryOnBan && onlineTarget != null) {
            Location dropLocation = onlineTarget.getLocation().clone();
            dropAndClearInventory(onlineTarget, dropLocation);
            try {
                onlineTarget.saveData();
            } catch (Exception ex) {
                getLogger().warning("Failed to save player data for " + record.targetName + ": " + ex.getMessage());
            }
            flushServerSaves();
        }

        boolean applied = false;
        try {
            Bukkit.getBanList(BanList.Type.NAME).addBan(
                record.targetName,
                buildBanScreenMessage(record),
                record.getExpiryDate(),
                record.issuedBy
            );
            applied = true;
        } catch (Exception ex) {
            getLogger().warning("Immediate name-ban failed for " + record.targetName + ": " + ex.getMessage());
        }

        if (Bukkit.getPluginManager().isPluginEnabled("LibertyBans")) {
            if (record.getExpiryDate() != null && isShortTemporaryBan(record.getExpiryDate())) {
                if (onlineTarget != null && onlineTarget.isOnline()) {
                    onlineTarget.kickPlayer(buildBanScreenMessage(record));
                }
                return applied;
            }

            String libertyDuration = normalizeLibertyDuration(record.getExpiryDate());
            String command = record.expiresAt <= 0L
                ? "ban " + record.targetName + " " + record.reason
                : "ban " + record.targetName + " " + libertyDuration + " " + record.reason;
            applied = dispatchNativeCommand(command) || applied;
        }

        if (onlineTarget != null && onlineTarget.isOnline()) {
            onlineTarget.kickPlayer(buildBanScreenMessage(record));
        }
        return applied;
    }

    private boolean applyIpBan(PunishmentRecord record, Player onlineTarget) {
        if (record.targetAddress == null || record.targetAddress.isBlank()) {
            return false;
        }

        boolean applied = false;
        try {
            Bukkit.getBanList(BanList.Type.IP).addBan(
                record.targetAddress,
                buildBanScreenMessage(record),
                record.getExpiryDate(),
                record.issuedBy
            );
            applied = true;
        } catch (Exception ex) {
            getLogger().warning("Immediate IP ban failed for " + record.targetAddress + ": " + ex.getMessage());
        }

        if (Bukkit.getPluginManager().isPluginEnabled("LibertyBans")) {
            if (record.getExpiryDate() != null && isShortTemporaryBan(record.getExpiryDate())) {
                if (onlineTarget != null && onlineTarget.isOnline()) {
                    onlineTarget.kickPlayer(buildBanScreenMessage(record));
                }
                return applied;
            }

            String libertyDuration = normalizeLibertyDuration(record.getExpiryDate());
            String command = record.expiresAt <= 0L
                ? "ipban " + record.targetAddress + " " + record.reason
                : "ipban " + record.targetAddress + " " + libertyDuration + " " + record.reason;
            applied = dispatchNativeCommand(command) || applied;
        }

        if (onlineTarget != null && onlineTarget.isOnline()) {
            onlineTarget.kickPlayer(buildBanScreenMessage(record));
        }
        return applied;
    }

    private boolean applyMute(PunishmentRecord record) {
        if (record.targetName == null || record.targetName.isBlank()) {
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("LibertyBans")) {
            getLogger().warning("Mute requires LibertyBans to be enabled.");
            return false;
        }
        String libertyDuration = normalizeLibertyDuration(record.getExpiryDate());
        String command = record.expiresAt <= 0L
            ? "mute " + record.targetName + " " + record.reason
            : "mute " + record.targetName + " " + libertyDuration + " " + record.reason;
        return dispatchNativeCommand(command);
    }

    private boolean applyKick(PunishmentRecord record, Player onlineTarget) {
        if (onlineTarget == null || !onlineTarget.isOnline()) {
            return false;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("LibertyBans")) {
            if (dispatchNativeCommand("kick " + record.targetName + " " + record.reason)) {
                return true;
            }
        }
        onlineTarget.kickPlayer(ChatColor.RED + record.reason);
        return true;
    }

    private boolean releaseRecord(PunishmentRecord record, String actor, boolean notifyAndPersist) {
        boolean changed = switch (record.type) {
            case BAN -> performGenericUnban(record.targetName, record.getTargetUuid(), record.targetAddress);
            case IP_BAN -> performGenericIpUnban(record.targetAddress);
            case MUTE -> performGenericUnmute(record.targetName, record.getTargetUuid());
            case KICK -> false;
        };

        if (notifyAndPersist && changed) {
            removePendingAction(record.id);
            record.active = false;
            record.clearedAt = System.currentTimeMillis();
            record.clearedBy = actor;
            persistRecord(record);
            saveRecords();
            appendAuditLine("CLEAR", record, actor);
        }
        return changed;
    }

    private boolean performGenericUnban(String targetName, UUID targetUuid, String address) {
        boolean changed = false;
        if (targetName != null && !targetName.isBlank()) {
            try {
                if (Bukkit.getBanList(BanList.Type.NAME).isBanned(targetName)) {
                    Bukkit.getBanList(BanList.Type.NAME).pardon(targetName);
                    changed = true;
                }
            } catch (Exception ignored) {
                // no-op
            }
            try {
                for (BanEntry<String> entry : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
                    if (entry.getTarget() != null && entry.getTarget().equalsIgnoreCase(targetName)) {
                        Bukkit.getBanList(BanList.Type.NAME).pardon(entry.getTarget());
                        changed = true;
                    }
                }
            } catch (Exception ignored) {
                // no-op
            }
            changed = dispatchNativeCommand("unban " + targetName) || changed;
        }
        if (targetUuid != null) {
            changed = dispatchNativeCommand("unban " + targetUuid) || changed;
        }
        if (address != null && !address.isBlank()) {
            changed = performGenericIpUnban(address) || changed;
        }
        return changed;
    }

    private boolean performGenericIpUnban(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        boolean changed = false;
        try {
            if (Bukkit.getBanList(BanList.Type.IP).isBanned(address)) {
                Bukkit.getBanList(BanList.Type.IP).pardon(address);
                changed = true;
            }
        } catch (Exception ignored) {
            // no-op
        }
        changed = dispatchNativeCommand("unbanip " + address) || changed;
        return changed;
    }

    private boolean performGenericUnmute(String targetName, UUID targetUuid) {
        boolean changed = false;
        if (targetName != null && !targetName.isBlank()) {
            changed = dispatchNativeCommand("unmute " + targetName) || changed;
        }
        if (targetUuid != null) {
            changed = dispatchNativeCommand("unmute " + targetUuid) || changed;
        }
        return changed;
    }

    private void schedulePendingActions() {
        for (String key : pendingActionsConfig.getKeys(false)) {
            long expiresAt = pendingActionsConfig.getLong(key + ".expires-at", 0L);
            if (expiresAt <= 0L) {
                pendingActionsConfig.set(key, null);
                continue;
            }
            schedulePendingAction(
                key,
                pendingActionsConfig.getLong(key + ".expires-at"),
                pendingActionsConfig.getString(key + ".record-id")
            );
        }
        savePendingActions();
    }

    private void scheduleRelease(PunishmentRecord record) {
        pendingActionsConfig.set(record.id + ".record-id", record.id);
        pendingActionsConfig.set(record.id + ".expires-at", record.expiresAt);
        savePendingActions();
        schedulePendingAction(record.id, record.expiresAt, record.id);
    }

    private void schedulePendingAction(String key, long expiresAt, String recordId) {
        BukkitTask existing = pendingReleaseTasks.remove(key);
        if (existing != null) {
            existing.cancel();
        }
        long delayTicks = Math.max(1L, (expiresAt - System.currentTimeMillis() + 49L) / 50L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> runPendingAction(key, recordId), delayTicks);
        pendingReleaseTasks.put(key, task);
    }

    private void runPendingAction(String key, String recordId) {
        PunishmentRecord record = records.get(normalizeId(recordId));
        if (record == null || !record.active) {
            removePendingAction(key);
            return;
        }
        if (record.expiresAt > System.currentTimeMillis() + 1000L) {
            schedulePendingAction(key, record.expiresAt, record.id);
            return;
        }
        try {
            if (releaseRecord(record, "SYSTEM", true)) {
                getLogger().info("Scheduled " + record.type.clearVerb.toLowerCase(Locale.ROOT) + " executed for " + record.displayTarget() + ".");
            }
        } finally {
            removePendingAction(key);
        }
    }

    private void removePendingAction(String key) {
        BukkitTask task = pendingReleaseTasks.remove(key);
        if (task != null) {
            task.cancel();
        }
        pendingActionsConfig.set(key, null);
        savePendingActions();
    }

    private void storeRecord(PunishmentRecord record) {
        records.put(record.id, record);
        persistRecord(record);
        saveRecords();
    }

    private void persistRecord(PunishmentRecord record) {
        String base = "records." + record.id;
        recordsConfig.set(base + ".type", record.type.name());
        recordsConfig.set(base + ".target-kind", record.targetKind.name());
        recordsConfig.set(base + ".target-name", record.targetName);
        recordsConfig.set(base + ".target-uuid", record.targetUuid);
        recordsConfig.set(base + ".target-address", record.targetAddress);
        recordsConfig.set(base + ".reason", record.reason);
        recordsConfig.set(base + ".issued-by", record.issuedBy);
        recordsConfig.set(base + ".issued-at", record.issuedAt);
        recordsConfig.set(base + ".duration-input", record.durationInput);
        recordsConfig.set(base + ".expires-at", record.expiresAt);
        recordsConfig.set(base + ".active", record.active);
        recordsConfig.set(base + ".cleared-at", record.clearedAt);
        recordsConfig.set(base + ".cleared-by", record.clearedBy);
        recordsConfig.set(base + ".source-command", record.sourceCommand);
    }

    private void appendAuditLine(String action, PunishmentRecord record, String actor) {
        if (auditFile == null) {
            return;
        }
        String line = Instant.now()
            + " action=" + action
            + " id=" + record.id
            + " type=" + record.type.name()
            + " target=" + record.displayTarget()
            + " actor=" + actor
            + " active=" + record.active
            + " reason=" + record.reason
            + System.lineSeparator();
        try {
            if (!auditFile.getParentFile().exists()) {
                auditFile.getParentFile().mkdirs();
            }
            Files.writeString(
                auditFile.toPath(),
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ex) {
            getLogger().warning("Failed to append audit line: " + ex.getMessage());
        }
    }

    // ===================== Dialog UI (1.21.6+) — /moderation as Paper dialogs =====================
    private static final TextColor DIALOG_BRAND = TextColor.color(0x00BFFF);
    private static final int DLG_PAGE = 12;

    private boolean isBedrockPlayer(Player p) {
        return p.getUniqueId().toString().startsWith("00000000-0000-0000-");
    }

    /** Dialogs need protocol 771+ (1.21.6). Bedrock + older Via clients get the inventory GUI. */
    private boolean useDialogUi(Player p) {
        if (isBedrockPlayer(p)) {
            return false;
        }
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            int protocol = (Integer) api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, p.getUniqueId());
            return protocol >= 771;
        } catch (Throwable t) {
            return true; // no ViaVersion (native client)
        }
    }

    private DialogAction dialogClick(java.util.function.Consumer<Player> handler) {
        return DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player p) {
                Bukkit.getScheduler().runTask(this, () -> handler.accept(p));
            }
        }, ClickCallback.Options.builder().build());
    }

    private ActionButton dialogButton(Component label, String tooltip, int width, java.util.function.Consumer<Player> click) {
        ActionButton.Builder b = ActionButton.builder(label).width(width);
        if (tooltip != null) {
            b.tooltip(Component.text(tooltip, NamedTextColor.GRAY));
        }
        if (click != null) {
            b.action(dialogClick(click));
        }
        return b.build();
    }

    private Dialog buildDialog(Component title, List<DialogBody> body, DialogType type) {
        return Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .inputs(List.of())
                .build())
            .type(type));
    }

    private DialogBody line(String text, NamedTextColor color) {
        return DialogBody.plainMessage(Component.text(text, color));
    }

    /** Ensures a multiAction dialog always has at least one button (Paper requires a non-empty list). */
    private void ensureButtons(List<ActionButton> buttons) {
        if (buttons.isEmpty()) {
            buttons.add(dialogButton(Component.text("(none)", NamedTextColor.DARK_GRAY), null, 150, null));
        }
    }

    private void openModerationDialog(Player player) {
        int activeBans = 0;
        int activeMutes = 0;
        for (PunishmentRecord r : records.values()) {
            if (r.isActiveBanLike()) activeBans++;
            else if (r.active && r.type == PunishmentType.MUTE) activeMutes++;
        }
        int offenders = 0;
        if (offensesConfig != null) {
            ConfigurationSection counts = offensesConfig.getConfigurationSection("counts");
            if (counts != null) offenders = counts.getKeys(false).size();
        }
        int chatStrikes = readChatStrikes().size();
        double tps = 20.0;
        try { tps = Bukkit.getTPS()[0]; } catch (Throwable ignored) {}
        List<DialogBody> body = List.of(
            line("Online: " + Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers()
                + "    TPS: " + String.format(Locale.ROOT, "%.1f", Math.min(20.0, tps)), NamedTextColor.GRAY),
            line("Active bans: " + activeBans + "    Active mutes: " + activeMutes, NamedTextColor.GRAY),
            line("Total records: " + records.size(), NamedTextColor.GRAY),
            line("Offense strikes: " + offenders + "    Chat strikes: " + chatStrikes, NamedTextColor.GRAY));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogButton(Component.text("Active Bans", NamedTextColor.RED), null, 150, p -> openListDialog(p, ViewMode.ACTIVE_BANS, 0)));
        buttons.add(dialogButton(Component.text("Active Mutes", NamedTextColor.YELLOW), null, 150, p -> openListDialog(p, ViewMode.ACTIVE_MUTES, 0)));
        buttons.add(dialogButton(Component.text("Recent History", NamedTextColor.AQUA), null, 150, p -> openListDialog(p, ViewMode.HISTORY, 0)));
        buttons.add(dialogButton(Component.text("Online Players", NamedTextColor.GREEN), null, 150, p -> openOnlinePlayersDialog(p)));
        buttons.add(dialogButton(Component.text("Offense Strikes", NamedTextColor.RED), null, 150, p -> openOffensesDialog(p)));
        buttons.add(dialogButton(Component.text("Chat Strikes", NamedTextColor.YELLOW), null, 150, p -> openChatStrikesDialog(p)));
        buttons.add(dialogButton(Component.text("Anticheat Flags", DIALOG_BRAND), null, 150, p -> { p.closeDialog(); p.performCommand("sus"); }));
        buttons.add(dialogButton(Component.text("Clear All Bans", NamedTextColor.RED), null, 150, p -> openBulkClearDialog(p, ViewMode.ACTIVE_BANS)));
        buttons.add(dialogButton(Component.text("Clear All Mutes", NamedTextColor.YELLOW), null, 150, p -> openBulkClearDialog(p, ViewMode.ACTIVE_MUTES)));
        player.showDialog(buildDialog(Component.text("ExampleSMP Moderation", DIALOG_BRAND), body,
            DialogType.multiAction(buttons).columns(3).exitAction(dialogButton(Component.text("Close"), null, 150, null)).build()));
    }

    private void openOnlinePlayersDialog(Player viewer) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        List<ActionButton> buttons = new ArrayList<>();
        for (Player target : online) {
            UUID id = target.getUniqueId();
            String nm = target.getName();
            int strikes = currentStrikes(id.toString());
            buttons.add(dialogButton(Component.text(nm, NamedTextColor.GREEN),
                strikes > 0 ? (strikes + " offense strikes") : "Quick actions", 150,
                p -> openPlayerActionsDialog(p, id, nm)));
        }
        ensureButtons(buttons);
        List<DialogBody> body = online.isEmpty() ? List.of(line("Nobody online.", NamedTextColor.GRAY)) : List.of();
        viewer.showDialog(buildDialog(Component.text("Online Players", DIALOG_BRAND), body,
            DialogType.multiAction(buttons).columns(3).exitAction(dialogButton(Component.text("Back"), null, 150, p -> openModerationDialog(p))).build()));
    }

    private void openPlayerActionsDialog(Player viewer, UUID targetUuid, String targetName) {
        Player target = Bukkit.getPlayer(targetUuid);
        boolean online = target != null && target.isOnline();
        int strikes = currentStrikes(targetUuid.toString());
        List<DialogBody> body = List.of(
            line(online ? "Online" : "Offline", online ? NamedTextColor.GREEN : NamedTextColor.GRAY),
            line("Offense strikes: " + strikes, strikes > 0 ? NamedTextColor.RED : NamedTextColor.GRAY));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogButton(Component.text("Teleport", NamedTextColor.AQUA), null, 150, p -> {
            Player t = Bukkit.getPlayer(targetUuid);
            if (t == null || !t.isOnline()) { p.sendMessage(ChatColor.RED + targetName + " is no longer online."); return; }
            p.closeDialog(); p.teleport(t.getLocation()); p.sendMessage(ChatColor.GREEN + "Teleported to " + targetName + ".");
        }));
        buttons.add(dialogButton(Component.text("Freeze", NamedTextColor.BLUE), null, 150, p -> { p.closeDialog(); p.performCommand("freeze " + targetName); }));
        buttons.add(dialogButton(Component.text("Unfreeze", NamedTextColor.DARK_AQUA), null, 150, p -> { p.closeDialog(); p.performCommand("unfreeze " + targetName); }));
        buttons.add(dialogButton(Component.text("Kick", NamedTextColor.GOLD), null, 150, p -> { p.closeDialog(); handleExplicitPunishmentCommand(p, new String[] {targetName, "Kicked", "via", "moderation", "hub"}, PunishmentType.KICK, false, false); }));
        buttons.add(dialogButton(Component.text("Mute 30m", NamedTextColor.YELLOW), null, 150, p -> { p.closeDialog(); handleExplicitPunishmentCommand(p, new String[] {targetName, "30m", "Muted", "via", "moderation", "hub"}, PunishmentType.MUTE, false, false); }));
        buttons.add(dialogButton(Component.text("Offend (+1)", NamedTextColor.RED), null, 150, p -> { p.closeDialog(); handleOffendCommand(p, new String[] {targetName}); }));
        buttons.add(dialogButton(Component.text("Clear Chat Strikes", NamedTextColor.YELLOW), null, 150, p -> { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "clearwarnings " + targetName); p.sendMessage(ChatColor.GREEN + "Cleared chat strikes for " + targetName + "."); }));
        buttons.add(dialogButton(Component.text("History", NamedTextColor.AQUA), null, 150, p -> { p.closeDialog(); handleHistoryCommand(p, new String[] {targetName}); }));
        viewer.showDialog(buildDialog(Component.text("Actions: " + targetName, DIALOG_BRAND), body,
            DialogType.multiAction(buttons).columns(2).exitAction(dialogButton(Component.text("Back"), null, 150, p -> openOnlinePlayersDialog(p))).build()));
    }

    private void openOffensesDialog(Player viewer) {
        List<String[]> rows = new ArrayList<>();
        if (offensesConfig != null) {
            ConfigurationSection counts = offensesConfig.getConfigurationSection("counts");
            if (counts != null) {
                for (String key : counts.getKeys(false)) {
                    int n = counts.getInt(key, 0);
                    if (n <= 0) continue;
                    rows.add(new String[] {key, offensesConfig.getString("names." + key, key), String.valueOf(n)});
                }
            }
        }
        rows.sort((a, b) -> Integer.parseInt(b[2]) - Integer.parseInt(a[2]));
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] row : rows) {
            String key = row[0];
            String display = row[1];
            int strikes = Integer.parseInt(row[2]);
            buttons.add(dialogButton(Component.text(display + " (" + strikes + "/3)", strikes >= 3 ? NamedTextColor.DARK_RED : NamedTextColor.RED),
                "Manage strikes", 250, p -> openOffenseDetailDialog(p, key, display)));
        }
        ensureButtons(buttons);
        List<DialogBody> body = rows.isEmpty() ? List.of(line("No offense strikes recorded.", NamedTextColor.GRAY)) : List.of();
        viewer.showDialog(buildDialog(Component.text("Offense Strikes", DIALOG_BRAND), body,
            DialogType.multiAction(buttons).columns(1).exitAction(dialogButton(Component.text("Back"), null, 150, p -> openModerationDialog(p))).build()));
    }

    private void openOffenseDetailDialog(Player viewer, String key, String name) {
        int strikes = currentStrikes(key);
        List<DialogBody> body = new ArrayList<>();
        body.add(line("Strikes: " + strikes + "/3", NamedTextColor.RED));
        List<String> hist = offensesConfig == null ? List.of() : offensesConfig.getStringList("history." + key);
        int from = Math.max(0, hist.size() - 5);
        for (int i = from; i < hist.size(); i++) {
            String[] parts = hist.get(i).split("\\|", 4);
            String by = parts.length > 1 ? parts[1] : "?";
            String dur = parts.length > 2 ? parts[2] : "?";
            String why = parts.length > 3 ? parts[3] : "";
            long ms = 0L; try { ms = Long.parseLong(parts[0]); } catch (Exception ignored) {}
            String ago = ms > 0 ? formatAgo(System.currentTimeMillis() - ms) : "?";
            body.add(line("- " + dur + " by " + by + " (" + ago + " ago)" + (why.isBlank() ? "" : ": " + trimReason(why, 24)), NamedTextColor.GRAY));
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(dialogButton(Component.text("Offend (+1)", NamedTextColor.RED), null, 250, p -> { p.closeDialog(); handleOffendCommand(p, new String[] {name}); }));
        buttons.add(dialogButton(Component.text("Remove 1 Strike", NamedTextColor.YELLOW), null, 250, p -> { handleUnoffendCommand(p, new String[] {name, "1"}); openOffenseDetailDialog(p, key, name); }));
        buttons.add(dialogButton(Component.text("Clear All Strikes", NamedTextColor.GREEN), null, 250, p -> { handleUnoffendCommand(p, new String[] {name, "all"}); openOffensesDialog(p); }));
        buttons.add(dialogButton(Component.text("History", NamedTextColor.AQUA), null, 250, p -> { p.closeDialog(); handleHistoryCommand(p, new String[] {name}); }));
        viewer.showDialog(buildDialog(Component.text("Offenses: " + name, DIALOG_BRAND), body,
            DialogType.multiAction(buttons).columns(1).exitAction(dialogButton(Component.text("Back"), null, 250, p -> openOffensesDialog(p))).build()));
    }

    private void openChatStrikesDialog(Player viewer) {
        List<ChatStrikeEntry> entries = readChatStrikes();
        List<ActionButton> buttons = new ArrayList<>();
        for (ChatStrikeEntry entry : entries) {
            String nm = entry.name;
            buttons.add(dialogButton(Component.text(nm + " (" + entry.count + "/5)", NamedTextColor.YELLOW), "Click to clear strikes", 250, p -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "clearwarnings " + nm);
                p.sendMessage(ChatColor.GREEN + "Cleared chat strikes for " + nm + ".");
                Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) openChatStrikesDialog(p); }, 2L);
            }));
        }
        ensureButtons(buttons);
        List<DialogBody> body = entries.isEmpty() ? List.of(line("No active chat strikes.", NamedTextColor.GRAY)) : List.of();
        viewer.showDialog(buildDialog(Component.text("Chat Strikes", DIALOG_BRAND), body,
            DialogType.multiAction(buttons).columns(1).exitAction(dialogButton(Component.text("Back"), null, 150, p -> openModerationDialog(p))).build()));
    }

    private void openListDialog(Player player, ViewMode mode, int requestedPage) {
        List<PunishmentRecord> list = recordsForView(mode);
        int maxPage = Math.max(0, (list.size() - 1) / DLG_PAGE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        int start = page * DLG_PAGE;
        int end = Math.min(list.size(), start + DLG_PAGE);
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = start; i < end; i++) {
            PunishmentRecord rec = list.get(i);
            buttons.add(dialogButton(Component.text(rec.id + "  " + rec.displayTarget(), NamedTextColor.WHITE),
                rec.type.displayName + " — " + formatDuration(rec), 300, p -> openRecordDetailDialog(p, rec.id, mode, page)));
        }
        buttons.add(dialogButton(Component.text("Bans", NamedTextColor.RED), null, 100, p -> openListDialog(p, ViewMode.ACTIVE_BANS, 0)));
        buttons.add(dialogButton(Component.text("Mutes", NamedTextColor.YELLOW), null, 100, p -> openListDialog(p, ViewMode.ACTIVE_MUTES, 0)));
        buttons.add(dialogButton(Component.text("History", NamedTextColor.AQUA), null, 100, p -> openListDialog(p, ViewMode.HISTORY, 0)));
        if (page > 0) buttons.add(dialogButton(Component.text("◀ Prev", DIALOG_BRAND), null, 150, p -> openListDialog(p, mode, page - 1)));
        if (page < maxPage) buttons.add(dialogButton(Component.text("Next ▶", DIALOG_BRAND), null, 150, p -> openListDialog(p, mode, page + 1)));
        List<DialogBody> body = list.isEmpty()
            ? List.of(line("No records.", NamedTextColor.GRAY))
            : List.of(line("Page " + (page + 1) + " / " + (maxPage + 1), NamedTextColor.GRAY));
        player.showDialog(buildDialog(Component.text(ChatColor.stripColor(mode.title), DIALOG_BRAND), body,
            DialogType.multiAction(buttons).columns(1).exitAction(dialogButton(Component.text("Menu"), null, 150, p -> openModerationDialog(p))).build()));
    }

    private void openRecordDetailDialog(Player player, String recordId, ViewMode returnMode, int returnPage) {
        PunishmentRecord record = records.get(recordId);
        if (record == null) { openListDialog(player, returnMode, returnPage); return; }
        List<DialogBody> body = new ArrayList<>();
        body.add(line("Type: " + record.type.displayName, NamedTextColor.GRAY));
        body.add(line("Target: " + record.displayTarget(), NamedTextColor.GRAY));
        body.add(line("Status: " + (record.active ? "Active" : "Inactive"), NamedTextColor.GRAY));
        body.add(line("Date: " + formatDate(record.issuedAt) + " by " + record.issuedBy, NamedTextColor.GRAY));
        body.add(line("Duration: " + formatDuration(record), NamedTextColor.GRAY));
        if (record.targetAddress != null && !record.targetAddress.isBlank()) body.add(line("Address: " + record.targetAddress, NamedTextColor.GRAY));
        body.add(line("Reason: " + record.reason, NamedTextColor.WHITE));
        if (!record.active && record.clearedAt > 0L) {
            body.add(line("Cleared: " + formatDate(record.clearedAt) + " by " + (record.clearedBy == null ? "Unknown" : record.clearedBy), NamedTextColor.GRAY));
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (record.type.canBeCleared() && record.active) {
            buttons.add(dialogButton(Component.text("Confirm " + record.type.clearVerb, NamedTextColor.GREEN), null, 300, p -> {
                PunishmentRecord r = records.get(recordId);
                if (r != null && r.active && r.type.canBeCleared()) {
                    if (releaseRecord(r, p.getName(), true)) p.sendMessage(ChatColor.GREEN + r.type.clearVerb + " " + r.displayTarget() + ". ID: " + r.id + ".");
                    else p.sendMessage(ChatColor.RED + "Failed to clear " + r.id + ".");
                }
                openListDialog(p, returnMode, returnPage);
            }));
        }
        ensureButtons(buttons);
        player.showDialog(buildDialog(Component.text(record.type.displayName + " " + record.id, DIALOG_BRAND), body,
            DialogType.multiAction(buttons).columns(1).exitAction(dialogButton(Component.text("Back"), null, 300, p -> openListDialog(p, returnMode, returnPage))).build()));
    }

    private void openBulkClearDialog(Player player, ViewMode mode) {
        int count = recordsForView(mode).size();
        String verb = mode == ViewMode.ACTIVE_BANS ? "unban" : "unmute";
        List<DialogBody> body = List.of(
            line("This will " + verb + " all " + count + " active " + mode.displayName.toLowerCase(Locale.ROOT) + ".", NamedTextColor.GRAY),
            line("This updates PunishDrop records.", NamedTextColor.YELLOW));
        ActionButton yes = dialogButton(Component.text("Confirm", NamedTextColor.GREEN), null, 150, p -> {
            int cleared = clearActiveRecords(mode, p.getName());
            p.sendMessage(ChatColor.GREEN + "Cleared " + cleared + " active " + mode.displayName.toLowerCase(Locale.ROOT) + ".");
            openModerationDialog(p);
        });
        ActionButton no = dialogButton(Component.text("Cancel", NamedTextColor.RED), null, 150, p -> openModerationDialog(p));
        player.showDialog(buildDialog(Component.text("Confirm " + mode.displayName, DIALOG_BRAND), body, DialogType.confirmation(yes, no)));
    }

    private void openModerationMenu(Player player) {
        if (useDialogUi(player)) {
            openModerationDialog(player);
            return;
        }
        ModerationMenuHolder holder = new ModerationMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, MENU_GUI_SIZE, ChatColor.DARK_RED + "ExampleSMP Moderation");
        holder.inventory = inventory;

        fillInventory(inventory, Material.BLACK_STAINED_GLASS_PANE, ChatColor.BLACK.toString());
        inventory.setItem(10, createItem(
            Material.IRON_BARS,
            ChatColor.RED + "Active Bans",
            List.of(ChatColor.GRAY + "View and manage active bans and IP bans.")
        ));
        inventory.setItem(11, createItem(
            Material.BOOK,
            ChatColor.GOLD + "Category Presets",
            buildPresetLore()
        ));
        inventory.setItem(12, createItem(
            Material.PAPER,
            ChatColor.YELLOW + "Active Mutes",
            List.of(ChatColor.GRAY + "View and manage active mutes.")
        ));
        inventory.setItem(14, createItem(
            Material.WRITABLE_BOOK,
            ChatColor.AQUA + "Recent History",
            List.of(ChatColor.GRAY + "View the most recent local punishment records.")
        ));
        inventory.setItem(15, createItem(
            Material.LAVA_BUCKET,
            ChatColor.RED + "Clear All Bans",
            List.of(ChatColor.GRAY + "Bulk-unban all active local bans.", ChatColor.YELLOW + "Requires confirmation.")
        ));
        inventory.setItem(16, createItem(
            Material.WATER_BUCKET,
            ChatColor.YELLOW + "Clear All Mutes",
            List.of(ChatColor.GRAY + "Bulk-unmute all active local mutes.", ChatColor.YELLOW + "Requires confirmation.")
        ));
        inventory.setItem(4, buildStatsItem());
        inventory.setItem(19, createItem(
            Material.PLAYER_HEAD,
            ChatColor.GREEN + "Online Players",
            List.of(
                ChatColor.GRAY + "Quick actions on online players:",
                ChatColor.GRAY + "teleport, freeze, kick, mute,",
                ChatColor.GRAY + "offend, history."
            )
        ));
        inventory.setItem(21, createItem(
            Material.NETHERITE_SWORD,
            ChatColor.RED + "Offense Strikes",
            List.of(
                ChatColor.GRAY + "Browse /offend cheating strikes.",
                ChatColor.GRAY + "Manage strikes per player."
            )
        ));
        inventory.setItem(23, createItem(
            Material.BELL,
            ChatColor.YELLOW + "Chat Strikes",
            List.of(
                ChatColor.GRAY + "ChatGuard automod warnings.",
                ChatColor.GRAY + "Click a player to clear their strikes."
            )
        ));
        inventory.setItem(25, createItem(
            Material.SPYGLASS,
            ChatColor.AQUA + "Anticheat Flags",
            List.of(
                ChatColor.GRAY + "Open the GrimAC suspicious",
                ChatColor.GRAY + "players panel (/sus)."
            )
        ));
        inventory.setItem(31, createItem(
            Material.NAME_TAG,
            ChatColor.GOLD + "Lookup Commands",
            List.of(
                ChatColor.GRAY + "/punish <player> <category>",
                ChatColor.GRAY + "/ban <player> <duration> <reason>",
                ChatColor.GRAY + "/mute <player> <duration> <reason>",
                ChatColor.GRAY + "/history <player|ip|id>",
                ChatColor.GRAY + "/searchid <id>",
                ChatColor.GRAY + "/offend <player> [reason]"
            )
        ));
        inventory.setItem(40, createItem(Material.BARRIER, ChatColor.RED + "Close", List.of(ChatColor.GRAY + "Close the moderation menu.")));

        player.openInventory(inventory);
    }

    private ItemStack buildStatsItem() {
        int activeBans = 0;
        int activeMutes = 0;
        for (PunishmentRecord record : records.values()) {
            if (record.isActiveBanLike()) {
                activeBans++;
            } else if (record.active && record.type == PunishmentType.MUTE) {
                activeMutes++;
            }
        }
        int offenders = 0;
        if (offensesConfig != null) {
            ConfigurationSection counts = offensesConfig.getConfigurationSection("counts");
            if (counts != null) {
                offenders = counts.getKeys(false).size();
            }
        }
        int chatStrikes = readChatStrikes().size();
        double tps = 20.0;
        try {
            tps = Bukkit.getTPS()[0];
        } catch (Throwable ignored) {
            // Bukkit.getTPS is Paper-only; fall back silently.
        }

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Online: " + ChatColor.WHITE + Bukkit.getOnlinePlayers().size()
            + ChatColor.GRAY + " / " + Bukkit.getMaxPlayers());
        lore.add(ChatColor.GRAY + "TPS: " + (tps >= 19.0 ? ChatColor.GREEN : tps >= 15.0 ? ChatColor.YELLOW : ChatColor.RED)
            + String.format(Locale.ROOT, "%.1f", Math.min(20.0, tps)));
        lore.add("");
        lore.add(ChatColor.GRAY + "Active bans: " + ChatColor.RED + activeBans);
        lore.add(ChatColor.GRAY + "Active mutes: " + ChatColor.YELLOW + activeMutes);
        lore.add(ChatColor.GRAY + "Total records: " + ChatColor.WHITE + records.size());
        lore.add(ChatColor.GRAY + "Players with offense strikes: " + ChatColor.RED + offenders);
        lore.add(ChatColor.GRAY + "Players with chat strikes: " + ChatColor.YELLOW + chatStrikes);
        return createItem(Material.BEACON, ChatColor.GOLD + "Server Moderation Stats", lore);
    }

    private void handleMenuClick(Player player, int rawSlot) {
        switch (rawSlot) {
            case 10:
                openListGui(player, ViewMode.ACTIVE_BANS, 0);
                break;
            case 12:
                openListGui(player, ViewMode.ACTIVE_MUTES, 0);
                break;
            case 14:
                openListGui(player, ViewMode.HISTORY, 0);
                break;
            case 15:
                openBulkClearConfirm(player, ViewMode.ACTIVE_BANS);
                break;
            case 16:
                openBulkClearConfirm(player, ViewMode.ACTIVE_MUTES);
                break;
            case 19:
                openOnlinePlayersGui(player);
                break;
            case 21:
                openOffensesGui(player);
                break;
            case 23:
                openChatStrikesGui(player);
                break;
            case 25:
                player.closeInventory();
                player.performCommand("sus");
                break;
            case 40:
                player.closeInventory();
                break;
            default:
                break;
        }
    }

    // ===== Online players quick-actions =====

    private void openOnlinePlayersGui(Player viewer) {
        if (useDialogUi(viewer)) { openOnlinePlayersDialog(viewer); return; }
        OnlinePlayersHolder holder = new OnlinePlayersHolder();
        Inventory inventory = Bukkit.createInventory(holder, LIST_GUI_SIZE, ChatColor.DARK_RED + "Online Players");
        holder.inventory = inventory;
        fillInventory(inventory, Material.BLACK_STAINED_GLASS_PANE, ChatColor.BLACK.toString());

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        int slot = 0;
        for (Player target : online) {
            if (slot >= LIST_PAGE_SIZE) {
                break;
            }
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta rawMeta = item.getItemMeta();
            if (rawMeta instanceof SkullMeta meta) {
                meta.setOwningPlayer(target);
                meta.setDisplayName(ChatColor.GREEN + target.getName());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Ping: " + ChatColor.WHITE + target.getPing() + "ms");
                lore.add(ChatColor.GRAY + "World: " + ChatColor.WHITE + target.getWorld().getName());
                lore.add(ChatColor.GRAY + "Gamemode: " + ChatColor.WHITE + target.getGameMode().name().toLowerCase(Locale.ROOT));
                int strikes = currentStrikes(target.getUniqueId().toString());
                if (strikes > 0) {
                    lore.add(ChatColor.GRAY + "Offense strikes: " + ChatColor.RED + strikes);
                }
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click for quick actions");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
            holder.slotTargets.put(slot, target.getUniqueId());
            slot++;
        }
        if (online.isEmpty()) {
            inventory.setItem(22, createItem(Material.GRAY_DYE, ChatColor.GRAY + "Nobody online", List.of()));
        }

        inventory.setItem(45, createItem(Material.NETHER_STAR, ChatColor.GOLD + "Menu", List.of(ChatColor.GRAY + "Return to the moderation dashboard.")));
        inventory.setItem(49, createItem(Material.SUNFLOWER, ChatColor.YELLOW + "Refresh", List.of(ChatColor.GRAY + "Reload the online player list.")));
        inventory.setItem(53, createItem(Material.BARRIER, ChatColor.RED + "Close", List.of()));
        viewer.openInventory(inventory);
    }

    private void handleOnlinePlayersClick(Player viewer, int rawSlot, OnlinePlayersHolder holder) {
        if (rawSlot == 45) {
            openModerationMenu(viewer);
            return;
        }
        if (rawSlot == 49) {
            openOnlinePlayersGui(viewer);
            return;
        }
        if (rawSlot == 53) {
            viewer.closeInventory();
            return;
        }
        UUID targetUuid = holder.slotTargets.get(rawSlot);
        if (targetUuid == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            viewer.sendMessage(ChatColor.RED + "That player is no longer online.");
            openOnlinePlayersGui(viewer);
            return;
        }
        openPlayerActionsGui(viewer, target.getUniqueId(), target.getName());
    }

    private void openPlayerActionsGui(Player viewer, UUID targetUuid, String targetName) {
        if (useDialogUi(viewer)) { openPlayerActionsDialog(viewer, targetUuid, targetName); return; }
        PlayerActionsHolder holder = new PlayerActionsHolder(targetUuid, targetName);
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.DARK_RED + "Actions: " + targetName);
        holder.inventory = inventory;
        fillInventory(inventory, Material.GRAY_STAINED_GLASS_PANE, ChatColor.BLACK.toString());

        Player target = Bukkit.getPlayer(targetUuid);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = head.getItemMeta();
        if (rawMeta instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));
            meta.setDisplayName(ChatColor.GREEN + targetName);
            List<String> lore = new ArrayList<>();
            if (target != null && target.isOnline()) {
                lore.add(ChatColor.GRAY + "Ping: " + ChatColor.WHITE + target.getPing() + "ms");
                lore.add(ChatColor.GRAY + "World: " + ChatColor.WHITE + target.getWorld().getName());
                lore.add(ChatColor.GRAY + "Gamemode: " + ChatColor.WHITE + target.getGameMode().name().toLowerCase(Locale.ROOT));
            } else {
                lore.add(ChatColor.RED + "Offline");
            }
            int strikes = currentStrikes(targetUuid.toString());
            lore.add(ChatColor.GRAY + "Offense strikes: " + (strikes > 0 ? ChatColor.RED : ChatColor.WHITE) + strikes);
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        inventory.setItem(4, head);

        inventory.setItem(10, createItem(Material.ENDER_PEARL, ChatColor.AQUA + "Teleport", List.of(ChatColor.GRAY + "Teleport to " + targetName + ".")));
        inventory.setItem(11, createItem(Material.ICE, ChatColor.BLUE + "Freeze", List.of(ChatColor.GRAY + "Freeze " + targetName + " in place.")));
        inventory.setItem(12, createItem(Material.BLUE_ICE, ChatColor.DARK_AQUA + "Unfreeze", List.of(ChatColor.GRAY + "Release " + targetName + ".")));
        inventory.setItem(13, createItem(Material.FEATHER, ChatColor.GOLD + "Kick", List.of(ChatColor.GRAY + "Kick " + targetName + " from the server.")));
        inventory.setItem(14, createItem(Material.PAPER, ChatColor.YELLOW + "Mute 30m", List.of(ChatColor.GRAY + "Mute " + targetName + " for 30 minutes.")));
        inventory.setItem(15, createItem(Material.RED_DYE, ChatColor.RED + "Offend (+1 strike)", List.of(
            ChatColor.GRAY + "Add a cheating strike and apply",
            ChatColor.GRAY + "the escalating ban (7d/30d/365d).")));
        inventory.setItem(16, createItem(Material.WRITABLE_BOOK, ChatColor.YELLOW + "Clear Chat Strikes", List.of(ChatColor.GRAY + "Reset ChatGuard automod warnings.")));
        inventory.setItem(21, createItem(Material.BOOK, ChatColor.AQUA + "History", List.of(ChatColor.GRAY + "Show punishment history in chat.")));
        inventory.setItem(18, createItem(Material.ARROW, ChatColor.YELLOW + "Back", List.of(ChatColor.GRAY + "Back to online players.")));
        inventory.setItem(26, createItem(Material.BARRIER, ChatColor.RED + "Close", List.of()));

        viewer.openInventory(inventory);
    }

    private void handlePlayerActionsClick(Player viewer, int rawSlot, PlayerActionsHolder holder) {
        String name = holder.targetName;
        Player target = Bukkit.getPlayer(holder.targetUuid);
        switch (rawSlot) {
            case 10:
                if (target == null || !target.isOnline()) {
                    viewer.sendMessage(ChatColor.RED + name + " is no longer online.");
                    return;
                }
                viewer.closeInventory();
                viewer.teleport(target.getLocation());
                viewer.sendMessage(ChatColor.GREEN + "Teleported to " + name + ".");
                break;
            case 11:
                viewer.closeInventory();
                viewer.performCommand("freeze " + name);
                break;
            case 12:
                viewer.closeInventory();
                viewer.performCommand("unfreeze " + name);
                break;
            case 13:
                viewer.closeInventory();
                handleExplicitPunishmentCommand(viewer, new String[] {name, "Kicked", "via", "moderation", "hub"}, PunishmentType.KICK, false, false);
                break;
            case 14:
                viewer.closeInventory();
                handleExplicitPunishmentCommand(viewer, new String[] {name, "30m", "Muted", "via", "moderation", "hub"}, PunishmentType.MUTE, false, false);
                break;
            case 15:
                viewer.closeInventory();
                handleOffendCommand(viewer, new String[] {name});
                break;
            case 16:
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "clearwarnings " + name);
                viewer.sendMessage(ChatColor.GREEN + "Cleared chat strikes for " + name + ".");
                break;
            case 21:
                viewer.closeInventory();
                handleHistoryCommand(viewer, new String[] {name});
                break;
            case 18:
                openOnlinePlayersGui(viewer);
                break;
            case 26:
                viewer.closeInventory();
                break;
            default:
                break;
        }
    }

    // ===== Offense strikes panel =====

    private void openOffensesGui(Player viewer) {
        if (useDialogUi(viewer)) { openOffensesDialog(viewer); return; }
        OffensesHolder holder = new OffensesHolder();
        Inventory inventory = Bukkit.createInventory(holder, LIST_GUI_SIZE, ChatColor.DARK_RED + "Offense Strikes");
        holder.inventory = inventory;
        fillInventory(inventory, Material.BLACK_STAINED_GLASS_PANE, ChatColor.BLACK.toString());

        List<String[]> rows = new ArrayList<>();
        if (offensesConfig != null) {
            ConfigurationSection counts = offensesConfig.getConfigurationSection("counts");
            if (counts != null) {
                for (String key : counts.getKeys(false)) {
                    int n = counts.getInt(key, 0);
                    if (n <= 0) {
                        continue;
                    }
                    String display = offensesConfig.getString("names." + key, key);
                    rows.add(new String[] {key, display, String.valueOf(n)});
                }
            }
        }
        rows.sort((a, b) -> Integer.parseInt(b[2]) - Integer.parseInt(a[2]));

        int slot = 0;
        for (String[] row : rows) {
            if (slot >= LIST_PAGE_SIZE) {
                break;
            }
            String key = row[0];
            String display = row[1];
            int strikes = Integer.parseInt(row[2]);
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta rawMeta = item.getItemMeta();
            if (rawMeta instanceof SkullMeta meta) {
                try {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(key)));
                } catch (IllegalArgumentException ignored) {
                    // name-based key, no skin
                }
                meta.setDisplayName((strikes >= 3 ? ChatColor.DARK_RED : ChatColor.RED) + display);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Strikes: " + ChatColor.RED + strikes + ChatColor.GRAY + "/3");
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click to manage");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
            holder.slotKeys.put(slot, key);
            holder.slotNames.put(slot, display);
            slot++;
        }
        if (rows.isEmpty()) {
            inventory.setItem(22, createItem(Material.GRAY_DYE, ChatColor.GRAY + "No offense strikes recorded", List.of()));
        }

        inventory.setItem(45, createItem(Material.NETHER_STAR, ChatColor.GOLD + "Menu", List.of(ChatColor.GRAY + "Return to the moderation dashboard.")));
        inventory.setItem(53, createItem(Material.BARRIER, ChatColor.RED + "Close", List.of()));
        viewer.openInventory(inventory);
    }

    private void handleOffensesClick(Player viewer, int rawSlot, OffensesHolder holder) {
        if (rawSlot == 45) {
            openModerationMenu(viewer);
            return;
        }
        if (rawSlot == 53) {
            viewer.closeInventory();
            return;
        }
        String key = holder.slotKeys.get(rawSlot);
        if (key != null) {
            openOffenseDetailGui(viewer, key, holder.slotNames.get(rawSlot));
        }
    }

    private void openOffenseDetailGui(Player viewer, String key, String name) {
        if (useDialogUi(viewer)) { openOffenseDetailDialog(viewer, key, name); return; }
        OffenseDetailHolder holder = new OffenseDetailHolder(key, name);
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.DARK_RED + "Offenses: " + name);
        holder.inventory = inventory;
        fillInventory(inventory, Material.GRAY_STAINED_GLASS_PANE, ChatColor.BLACK.toString());

        int strikes = currentStrikes(key);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Strikes: " + ChatColor.RED + strikes + ChatColor.GRAY + "/3");
        List<String> hist = offensesConfig == null ? List.of() : offensesConfig.getStringList("history." + key);
        if (!hist.isEmpty()) {
            lore.add("");
            lore.add(ChatColor.GRAY + "Recent history:");
            int from = Math.max(0, hist.size() - 5);
            for (int i = from; i < hist.size(); i++) {
                String[] parts = hist.get(i).split("\\|", 4);
                String by = parts.length > 1 ? parts[1] : "?";
                String dur = parts.length > 2 ? parts[2] : "?";
                String why = parts.length > 3 ? parts[3] : "";
                long ms = 0L;
                try {
                    ms = Long.parseLong(parts[0]);
                } catch (Exception ignored) {
                }
                String ago = ms > 0 ? formatAgo(System.currentTimeMillis() - ms) : "?";
                lore.add(ChatColor.DARK_GRAY + "- " + ChatColor.WHITE + dur + ChatColor.GRAY + " by " + by
                    + " (" + ago + " ago)" + (why.isBlank() ? "" : ": " + trimReason(why, 24)));
            }
        }
        inventory.setItem(13, createItem(Material.BOOK, ChatColor.GOLD + name, lore));

        inventory.setItem(10, createItem(Material.RED_DYE, ChatColor.RED + "Offend (+1 strike)", List.of(
            ChatColor.GRAY + "Add a strike and apply the",
            ChatColor.GRAY + "escalating ban (7d/30d/365d).")));
        inventory.setItem(12, createItem(Material.YELLOW_DYE, ChatColor.YELLOW + "Remove 1 Strike", List.of(ChatColor.GRAY + "Revoke one strike.")));
        inventory.setItem(14, createItem(Material.LIME_DYE, ChatColor.GREEN + "Clear All Strikes", List.of(ChatColor.GRAY + "Revoke all strikes.")));
        inventory.setItem(16, createItem(Material.BOOK, ChatColor.AQUA + "History", List.of(ChatColor.GRAY + "Show punishment history in chat.")));
        inventory.setItem(18, createItem(Material.ARROW, ChatColor.YELLOW + "Back", List.of(ChatColor.GRAY + "Back to offense strikes.")));
        inventory.setItem(26, createItem(Material.BARRIER, ChatColor.RED + "Close", List.of()));

        viewer.openInventory(inventory);
    }

    private void handleOffenseDetailClick(Player viewer, int rawSlot, OffenseDetailHolder holder) {
        switch (rawSlot) {
            case 10:
                viewer.closeInventory();
                handleOffendCommand(viewer, new String[] {holder.name});
                break;
            case 12:
                handleUnoffendCommand(viewer, new String[] {holder.name, "1"});
                openOffenseDetailGui(viewer, holder.key, holder.name);
                break;
            case 14:
                handleUnoffendCommand(viewer, new String[] {holder.name, "all"});
                openOffensesGui(viewer);
                break;
            case 16:
                viewer.closeInventory();
                handleHistoryCommand(viewer, new String[] {holder.name});
                break;
            case 18:
                openOffensesGui(viewer);
                break;
            case 26:
                viewer.closeInventory();
                break;
            default:
                break;
        }
    }

    // ===== ChatGuard strikes panel =====

    /** Reads ChatGuard's persisted strike state (strikes.yml in the PizzaChatGuard data folder). */
    private List<ChatStrikeEntry> readChatStrikes() {
        List<ChatStrikeEntry> out = new ArrayList<>();
        File strikesFile = new File(getDataFolder().getParentFile(), "PizzaChatGuard/strikes.yml");
        if (!strikesFile.exists()) {
            return out;
        }
        try {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(strikesFile);
            ConfigurationSection counts = cfg.getConfigurationSection("counts");
            if (counts == null) {
                return out;
            }
            long now = System.currentTimeMillis();
            for (String key : counts.getKeys(false)) {
                int n = counts.getInt(key, 0);
                if (n <= 0) {
                    continue;
                }
                long expiry = cfg.getLong("expiry." + key, 0L);
                if (expiry > 0L && now > expiry) {
                    continue;
                }
                String name = cfg.getString("names." + key, key);
                out.add(new ChatStrikeEntry(key, name, n, expiry));
            }
        } catch (Exception ex) {
            getLogger().warning("Failed reading ChatGuard strikes.yml: " + ex.getMessage());
        }
        out.sort((a, b) -> Integer.compare(b.count, a.count));
        return out;
    }

    private void openChatStrikesGui(Player viewer) {
        if (useDialogUi(viewer)) { openChatStrikesDialog(viewer); return; }
        ChatStrikesHolder holder = new ChatStrikesHolder();
        Inventory inventory = Bukkit.createInventory(holder, LIST_GUI_SIZE, ChatColor.DARK_RED + "Chat Strikes");
        holder.inventory = inventory;
        fillInventory(inventory, Material.BLACK_STAINED_GLASS_PANE, ChatColor.BLACK.toString());

        List<ChatStrikeEntry> entries = readChatStrikes();
        int slot = 0;
        for (ChatStrikeEntry entry : entries) {
            if (slot >= LIST_PAGE_SIZE) {
                break;
            }
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta rawMeta = item.getItemMeta();
            if (rawMeta instanceof SkullMeta meta) {
                try {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(entry.uuid)));
                } catch (IllegalArgumentException ignored) {
                }
                meta.setDisplayName(ChatColor.YELLOW + entry.name);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Automod strikes: " + ChatColor.RED + entry.count + ChatColor.GRAY + "/5");
                if (entry.expiry > 0L) {
                    long remaining = entry.expiry - System.currentTimeMillis();
                    if (remaining > 0L) {
                        lore.add(ChatColor.GRAY + "Decays in: " + ChatColor.WHITE + formatAgo(remaining));
                    }
                }
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click to clear strikes");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
            holder.slotNames.put(slot, entry.name);
            slot++;
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, createItem(Material.GRAY_DYE, ChatColor.GRAY + "No active chat strikes", List.of()));
        }

        inventory.setItem(45, createItem(Material.NETHER_STAR, ChatColor.GOLD + "Menu", List.of(ChatColor.GRAY + "Return to the moderation dashboard.")));
        inventory.setItem(49, createItem(Material.SUNFLOWER, ChatColor.YELLOW + "Refresh", List.of(ChatColor.GRAY + "Reload chat strikes.")));
        inventory.setItem(53, createItem(Material.BARRIER, ChatColor.RED + "Close", List.of()));
        viewer.openInventory(inventory);
    }

    private void handleChatStrikesClick(Player viewer, int rawSlot, ChatStrikesHolder holder) {
        if (rawSlot == 45) {
            openModerationMenu(viewer);
            return;
        }
        if (rawSlot == 49) {
            openChatStrikesGui(viewer);
            return;
        }
        if (rawSlot == 53) {
            viewer.closeInventory();
            return;
        }
        String name = holder.slotNames.get(rawSlot);
        if (name == null) {
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "clearwarnings " + name);
        viewer.sendMessage(ChatColor.GREEN + "Cleared chat strikes for " + name + ".");
        // Re-open after a tick so ChatGuard has persisted the cleared state.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (viewer.isOnline()) {
                openChatStrikesGui(viewer);
            }
        }, 2L);
    }

    private void openListGui(Player player, ViewMode mode, int requestedPage) {
        if (useDialogUi(player)) { openListDialog(player, mode, requestedPage); return; }
        List<PunishmentRecord> list = recordsForView(mode);
        int maxPage = Math.max(0, (list.size() - 1) / LIST_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));

        PunishmentListHolder holder = new PunishmentListHolder(mode, page);
        Inventory inventory = Bukkit.createInventory(holder, LIST_GUI_SIZE, mode.title);
        holder.inventory = inventory;
        fillInventory(inventory, Material.BLACK_STAINED_GLASS_PANE, ChatColor.BLACK.toString());

        int start = page * LIST_PAGE_SIZE;
        for (int slot = 0; slot < LIST_PAGE_SIZE; slot++) {
            int index = start + slot;
            if (index >= list.size()) {
                inventory.setItem(slot, null);
                continue;
            }
            inventory.setItem(slot, buildRecordItem(list.get(index), mode));
        }

        if (page > 0) {
            inventory.setItem(45, createItem(Material.ARROW, ChatColor.YELLOW + "Previous Page", List.of(ChatColor.GRAY + "Go back one page.")));
        } else {
            inventory.setItem(45, createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "Previous Page", List.of()));
        }
        inventory.setItem(46, createItem(Material.NETHER_STAR, ChatColor.GOLD + "Menu", List.of(ChatColor.GRAY + "Return to the moderation dashboard.")));
        inventory.setItem(47, createItem(Material.IRON_BARS, ChatColor.RED + "Bans", List.of(ChatColor.GRAY + "View active bans.")));
        inventory.setItem(48, createItem(Material.PAPER, ChatColor.YELLOW + "Mutes", List.of(ChatColor.GRAY + "View active mutes.")));
        inventory.setItem(49, createItem(Material.WRITABLE_BOOK, ChatColor.AQUA + "History", List.of(ChatColor.GRAY + "View recent punishment history.")));
        inventory.setItem(50, createItem(Material.BOOK, ChatColor.GOLD + "Search", List.of(ChatColor.GRAY + "Use /searchid <id>", ChatColor.GRAY + "Use /history <player|ip|id>")));
        inventory.setItem(51, createItem(Material.LAVA_BUCKET, ChatColor.RED + "Clear Bans", List.of(ChatColor.GRAY + "Bulk clear active bans.")));
        inventory.setItem(52, createItem(Material.WATER_BUCKET, ChatColor.YELLOW + "Clear Mutes", List.of(ChatColor.GRAY + "Bulk clear active mutes.")));
        if (page < maxPage) {
            inventory.setItem(53, createItem(Material.ARROW, ChatColor.YELLOW + "Next Page", List.of(ChatColor.GRAY + "Go forward one page.")));
        } else {
            inventory.setItem(53, createItem(Material.BARRIER, ChatColor.RED + "Close", List.of(ChatColor.GRAY + "Close this list.")));
        }

        if (list.isEmpty()) {
            inventory.setItem(22, createItem(Material.GRAY_DYE, ChatColor.GRAY + "No records", List.of(ChatColor.GRAY + "Nothing to show in this view.")));
        }

        player.openInventory(inventory);
    }

    private void handleListClick(Player player, int rawSlot, PunishmentListHolder holder) {
        if (rawSlot < 0 || rawSlot >= LIST_GUI_SIZE) {
            return;
        }
        if (rawSlot < LIST_PAGE_SIZE) {
            PunishmentRecord record = recordForViewSlot(holder.mode, holder.page, rawSlot);
            if (record != null) {
                openRecordDetail(player, record, holder.mode, holder.page);
            }
            return;
        }

        switch (rawSlot) {
            case 45:
                openListGui(player, holder.mode, holder.page - 1);
                break;
            case 46:
                openModerationMenu(player);
                break;
            case 47:
                openListGui(player, ViewMode.ACTIVE_BANS, 0);
                break;
            case 48:
                openListGui(player, ViewMode.ACTIVE_MUTES, 0);
                break;
            case 49:
                openListGui(player, ViewMode.HISTORY, 0);
                break;
            case 51:
                openBulkClearConfirm(player, ViewMode.ACTIVE_BANS);
                break;
            case 52:
                openBulkClearConfirm(player, ViewMode.ACTIVE_MUTES);
                break;
            case 53:
                if (holder.page < Math.max(0, (recordsForView(holder.mode).size() - 1) / LIST_PAGE_SIZE)) {
                    openListGui(player, holder.mode, holder.page + 1);
                } else {
                    player.closeInventory();
                }
                break;
            default:
                break;
        }
    }

    private void openRecordDetail(Player player, PunishmentRecord record, ViewMode returnMode, int returnPage) {
        if (useDialogUi(player)) { openRecordDetailDialog(player, record.id, returnMode, returnPage); return; }
        PunishmentDetailHolder holder = new PunishmentDetailHolder(record.id, returnMode, returnPage);
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.DARK_RED + record.type.displayName + " " + record.id);
        holder.inventory = inventory;
        fillInventory(inventory, Material.GRAY_STAINED_GLASS_PANE, ChatColor.BLACK.toString());

        inventory.setItem(13, buildDetailCenterItem(record));
        inventory.setItem(11, createItem(
            record.type.canBeCleared() && record.active ? Material.LIME_WOOL : Material.GRAY_WOOL,
            record.type.canBeCleared() && record.active
                ? ChatColor.GREEN + "Confirm " + record.type.clearVerb
                : ChatColor.DARK_GRAY + "No Clear Action",
            record.type.canBeCleared() && record.active
                ? List.of(ChatColor.GRAY + record.type.clearVerb + " " + record.displayTarget() + ".", ChatColor.GRAY + "ID: " + record.id)
                : List.of(ChatColor.GRAY + "This record is already inactive.")
        ));
        inventory.setItem(15, createItem(Material.RED_WOOL, ChatColor.RED + "Back", List.of(ChatColor.GRAY + "Return to the previous view.")));
        inventory.setItem(22, createItem(Material.BARRIER, ChatColor.RED + "Close", List.of(ChatColor.GRAY + "Close this screen.")));

        player.openInventory(inventory);
    }

    private void handleDetailClick(Player player, int rawSlot, PunishmentDetailHolder holder) {
        PunishmentRecord record = records.get(holder.recordId);
        if (record == null) {
            openListGui(player, holder.returnMode, holder.returnPage);
            return;
        }

        if (rawSlot == 11 && record.active && record.type.canBeCleared()) {
            if (releaseRecord(record, player.getName(), true)) {
                player.sendMessage(ChatColor.GREEN + record.type.clearVerb + " " + record.displayTarget() + ". ID: " + record.id + ".");
            } else {
                player.sendMessage(ChatColor.RED + "Failed to clear " + record.id + ".");
            }
            openListGui(player, holder.returnMode, holder.returnPage);
            return;
        }

        if (rawSlot == 15) {
            openListGui(player, holder.returnMode, holder.returnPage);
            return;
        }
        if (rawSlot == 22) {
            player.closeInventory();
        }
    }

    private void openBulkClearConfirm(Player player, ViewMode mode) {
        if (useDialogUi(player)) { openBulkClearDialog(player, mode); return; }
        BulkClearHolder holder = new BulkClearHolder(mode);
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.DARK_RED + "Confirm " + mode.displayName);
        holder.inventory = inventory;
        fillInventory(inventory, Material.GRAY_STAINED_GLASS_PANE, ChatColor.BLACK.toString());

        int count = recordsForView(mode).size();
        inventory.setItem(13, createItem(
            mode == ViewMode.ACTIVE_BANS ? Material.LAVA_BUCKET : Material.WATER_BUCKET,
            ChatColor.GOLD + "Clear " + count + " active " + mode.displayName.toLowerCase(Locale.ROOT),
            List.of(
                ChatColor.GRAY + "This will " + (mode == ViewMode.ACTIVE_BANS ? "unban" : "unmute") + " all locally tracked active "
                    + mode.displayName.toLowerCase(Locale.ROOT) + ".",
                ChatColor.YELLOW + "This action updates PunishDrop records."
            )
        ));
        inventory.setItem(11, createItem(Material.LIME_WOOL, ChatColor.GREEN + "Confirm", List.of(ChatColor.GRAY + "Run the bulk clear.")));
        inventory.setItem(15, createItem(Material.RED_WOOL, ChatColor.RED + "Cancel", List.of(ChatColor.GRAY + "Return to the previous menu.")));

        player.openInventory(inventory);
    }

    private void handleBulkClearClick(Player player, int rawSlot, BulkClearHolder holder) {
        if (rawSlot == 11) {
            int cleared = clearActiveRecords(holder.mode, player.getName());
            player.sendMessage(ChatColor.GREEN + "Cleared " + cleared + " active " + holder.mode.displayName.toLowerCase(Locale.ROOT) + ".");
            openModerationMenu(player);
            return;
        }
        if (rawSlot == 15) {
            openModerationMenu(player);
            return;
        }
    }

    private int clearActiveRecords(ViewMode mode, String actor) {
        int cleared = 0;
        for (PunishmentRecord record : recordsForView(mode)) {
            if (record.active && releaseRecord(record, actor, true)) {
                cleared++;
            }
        }
        return cleared;
    }

    private ItemStack buildRecordItem(PunishmentRecord record, ViewMode mode) {
        if (record.targetKind == TargetKind.PLAYER && record.type != PunishmentType.IP_BAN) {
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta rawMeta = item.getItemMeta();
            if (rawMeta instanceof SkullMeta meta) {
                UUID uuid = record.getTargetUuid();
                if (uuid != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
                }
                meta.setDisplayName(colorByType(record.type) + record.displayTarget());
                meta.setLore(recordLore(record, mode));
                item.setItemMeta(meta);
                return item;
            }
        }

        Material material = switch (record.type) {
            case BAN -> Material.IRON_BARS;
            case IP_BAN -> Material.REDSTONE_BLOCK;
            case MUTE -> Material.PAPER;
            case KICK -> Material.FEATHER;
        };
        return createItem(material, colorByType(record.type) + record.displayTarget(), recordLore(record, mode));
    }

    private ItemStack buildDetailCenterItem(PunishmentRecord record) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Type: " + ChatColor.WHITE + record.type.displayName);
        lore.add(ChatColor.GRAY + "Target: " + ChatColor.WHITE + record.displayTarget());
        lore.add(ChatColor.GRAY + "Status: " + ChatColor.WHITE + (record.active ? "Active" : "Inactive"));
        lore.add(ChatColor.GRAY + "Date: " + ChatColor.WHITE + formatDate(record.issuedAt));
        lore.add(ChatColor.GRAY + "By: " + ChatColor.WHITE + record.issuedBy);
        lore.add(ChatColor.GRAY + "Duration: " + ChatColor.WHITE + formatDuration(record));
        if (record.targetAddress != null && !record.targetAddress.isBlank()) {
            lore.add(ChatColor.GRAY + "Address: " + ChatColor.WHITE + record.targetAddress);
        }
        lore.add(ChatColor.GRAY + "Reason:");
        for (String line : wrapText(record.reason, 32)) {
            lore.add(ChatColor.WHITE + line);
        }
        if (!record.active && record.clearedAt > 0L) {
            lore.add("");
            lore.add(ChatColor.GRAY + "Cleared: " + ChatColor.WHITE + formatDate(record.clearedAt));
            lore.add(ChatColor.GRAY + "Cleared by: " + ChatColor.WHITE + (record.clearedBy == null ? "Unknown" : record.clearedBy));
        }
        return createItem(Material.BOOK, ChatColor.GOLD + record.id, lore);
    }

    private List<String> recordLore(PunishmentRecord record, ViewMode mode) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "ID: " + ChatColor.WHITE + record.id);
        lore.add(ChatColor.GRAY + "Type: " + ChatColor.WHITE + record.type.displayName);
        lore.add(ChatColor.GRAY + "Date: " + ChatColor.WHITE + formatDate(record.issuedAt));
        lore.add(ChatColor.GRAY + "Duration: " + ChatColor.WHITE + formatDuration(record));
        lore.add(ChatColor.GRAY + "Reason:");
        for (String line : wrapText(record.reason, 28)) {
            lore.add(ChatColor.WHITE + line);
        }
        if (mode != ViewMode.HISTORY && record.type.canBeCleared()) {
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to manage");
        } else if (mode == ViewMode.HISTORY) {
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click for details");
        }
        return lore;
    }

    private List<PunishmentRecord> recordsForView(ViewMode mode) {
        return switch (mode) {
            case ACTIVE_BANS -> records.values().stream()
                .filter(PunishmentRecord::isActiveBanLike)
                .sorted(Comparator.comparingLong((PunishmentRecord record) -> record.issuedAt).reversed())
                .toList();
            case ACTIVE_MUTES -> records.values().stream()
                .filter(record -> record.active && record.type == PunishmentType.MUTE)
                .sorted(Comparator.comparingLong((PunishmentRecord record) -> record.issuedAt).reversed())
                .toList();
            case HISTORY -> records.values().stream()
                .sorted(Comparator.comparingLong((PunishmentRecord record) -> record.issuedAt).reversed())
                .toList();
        };
    }

    private PunishmentRecord recordForViewSlot(ViewMode mode, int page, int slot) {
        int index = (page * LIST_PAGE_SIZE) + slot;
        List<PunishmentRecord> list = recordsForView(mode);
        if (index < 0 || index >= list.size()) {
            return null;
        }
        return list.get(index);
    }

    private void sendConsoleListPage(CommandSender sender, ViewMode mode, int requestedPage) {
        List<PunishmentRecord> list = recordsForView(mode);
        if (list.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "There are no " + mode.displayName.toLowerCase(Locale.ROOT) + " to display.");
            return;
        }

        int perPage = 10;
        int maxPage = Math.max(0, (list.size() - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        int start = page * perPage;
        int end = Math.min(list.size(), start + perPage);

        sender.sendMessage(ChatColor.GOLD + mode.displayName + " page " + (page + 1) + "/" + (maxPage + 1));
        for (int index = start; index < end; index++) {
            PunishmentRecord record = list.get(index);
            sender.sendMessage(
                ChatColor.YELLOW + record.id
                    + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + record.type.displayName
                    + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + record.displayTarget()
                    + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + trimReason(record.reason, 48)
            );
        }
    }

    private void sendHistoryPage(CommandSender sender, List<PunishmentRecord> matches, String lookup, int requestedPage) {
        int perPage = 10;
        int maxPage = Math.max(0, (matches.size() - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        int start = page * perPage;
        int end = Math.min(matches.size(), start + perPage);

        sender.sendMessage(ChatColor.GOLD + "History for " + lookup + " page " + (page + 1) + "/" + (maxPage + 1));
        for (int index = start; index < end; index++) {
            PunishmentRecord record = matches.get(index);
            sender.sendMessage(
                ChatColor.YELLOW + record.id
                    + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + record.type.displayName
                    + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + (record.active ? "Active" : "Inactive")
                    + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + formatDate(record.issuedAt)
                    + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + trimReason(record.reason, 40)
            );
        }
    }

    private void sendRecordDetails(CommandSender sender, PunishmentRecord record) {
        sender.sendMessage(ChatColor.GOLD + "Punishment Record " + record.id);
        sender.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + record.type.displayName);
        sender.sendMessage(ChatColor.GRAY + "Target: " + ChatColor.WHITE + record.displayTarget());
        sender.sendMessage(ChatColor.GRAY + "Status: " + ChatColor.WHITE + (record.active ? "Active" : "Inactive"));
        sender.sendMessage(ChatColor.GRAY + "Date: " + ChatColor.WHITE + formatDate(record.issuedAt));
        sender.sendMessage(ChatColor.GRAY + "By: " + ChatColor.WHITE + record.issuedBy);
        sender.sendMessage(ChatColor.GRAY + "Duration: " + ChatColor.WHITE + formatDuration(record));
        if (record.targetAddress != null && !record.targetAddress.isBlank()) {
            sender.sendMessage(ChatColor.GRAY + "Address: " + ChatColor.WHITE + record.targetAddress);
        }
        sender.sendMessage(ChatColor.GRAY + "Reason: " + ChatColor.WHITE + record.reason);
        if (!record.active && record.clearedAt > 0L) {
            sender.sendMessage(ChatColor.GRAY + "Cleared: " + ChatColor.WHITE + formatDate(record.clearedAt));
            sender.sendMessage(ChatColor.GRAY + "Cleared by: " + ChatColor.WHITE + (record.clearedBy == null ? "Unknown" : record.clearedBy));
        }
    }

    private boolean dispatchNativeHistory(String lookup) {
        return dispatchNativeCommand("history " + lookup);
    }

    private boolean dispatchNativeCommand(String command) {
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "libertybans:" + command)
                || Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception ex) {
            getLogger().warning("Failed to dispatch command '" + command + "': " + ex.getMessage());
            return false;
        }
    }

    private ParsedPunishment parsePunishArgs(
        PunishmentType forcedType,
        String[] payloadArgs,
        boolean forcePermanent,
        boolean genericPunish
    ) {
        if (payloadArgs.length == 0) {
            return null;
        }

        List<String> payload = new ArrayList<>(Arrays.asList(payloadArgs));
        PunishmentPreset preset = presetByLookup(payload.get(0));
        if (genericPunish && preset != null) {
            PunishmentType resolvedType = preset.actionType;
            String duration = resolvedType.usesDuration() ? (forcePermanent ? "perm" : preset.duration) : "";
            Date expiry = resolvedType.usesDuration() && !isPermanentDuration(duration) ? parseExpiry(duration) : null;
            String reason = preset.reason;
            if (payload.size() > 1) {
                reason = reason + " | Note: " + normalizeSingleLine(String.join(" ", payload.subList(1, payload.size())));
            }
            return new ParsedPunishment(resolvedType, duration, expiry, reason);
        }

        if (preset != null) {
            String duration = forcedType.usesDuration() ? (forcePermanent ? "perm" : preset.duration) : "";
            Date expiry = forcedType.usesDuration() && !isPermanentDuration(duration) ? parseExpiry(duration) : null;
            String reason = preset.reason;
            if (payload.size() > 1) {
                reason = reason + " | Note: " + normalizeSingleLine(String.join(" ", payload.subList(1, payload.size())));
            }
            return new ParsedPunishment(forcedType, duration, expiry, reason);
        }

        if (!forcedType.usesDuration()) {
            String reason = normalizeSingleLine(String.join(" ", payload));
            return reason.isBlank() ? null : new ParsedPunishment(forcedType, "", null, reason);
        }

        if (forcePermanent) {
            String reason = normalizeSingleLine(String.join(" ", payload));
            return reason.isBlank() ? null : new ParsedPunishment(forcedType, "perm", null, reason);
        }

        String duration = null;
        String reason = null;
        if (looksLikeDuration(payload.get(0))) {
            duration = payload.get(0);
            reason = payload.size() > 1 ? normalizeSingleLine(String.join(" ", payload.subList(1, payload.size()))) : "";
        } else if (payload.size() > 1 && looksLikeDuration(payload.get(payload.size() - 1))) {
            duration = payload.get(payload.size() - 1);
            reason = normalizeSingleLine(String.join(" ", payload.subList(0, payload.size() - 1)));
        }
        if (duration == null || reason == null || reason.isBlank()) {
            return null;
        }
        if (!isPermanentDuration(duration) && parseExpiry(duration) == null) {
            return null;
        }
        Date expiry = isPermanentDuration(duration) ? null : parseExpiry(duration);
        return new ParsedPunishment(forcedType, duration, expiry, reason);
    }

    private ResolvedTarget resolvePlayerTarget(String input, boolean requireOnline) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return new ResolvedTarget(
                TargetKind.PLAYER,
                online.getName(),
                online.getUniqueId(),
                resolveAddress(online),
                online
            );
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(input);
        if (requireOnline || !offline.hasPlayedBefore()) {
            return ResolvedTarget.notFound();
        }
        return new ResolvedTarget(
            TargetKind.PLAYER,
            offline.getName() == null ? input : offline.getName(),
            offline.getUniqueId(),
            findKnownAddress(offline.getName() == null ? input : offline.getName()),
            null
        );
    }

    private ResolvedTarget resolveIpTarget(String input) {
        if (isValidIp(input)) {
            return new ResolvedTarget(TargetKind.ADDRESS, input, null, input, null);
        }
        ResolvedTarget playerTarget = resolvePlayerTarget(input, false);
        if (!playerTarget.found()) {
            return ResolvedTarget.notFound();
        }
        if (playerTarget.targetAddress == null || playerTarget.targetAddress.isBlank()) {
            return ResolvedTarget.notFound();
        }
        return new ResolvedTarget(
            TargetKind.ADDRESS,
            playerTarget.targetName,
            playerTarget.targetUuid,
            playerTarget.targetAddress,
            playerTarget.onlinePlayer
        );
    }

    private String resolveAddress(Player player) {
        if (player == null || player.getAddress() == null || player.getAddress().getAddress() == null) {
            return null;
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    private String findKnownAddress(String lookup) {
        if (lookup == null || lookup.isBlank()) {
            return null;
        }
        String normalized = normalizeLookup(lookup);
        return records.values().stream()
            .filter(record -> record.targetAddress != null && !record.targetAddress.isBlank())
            .filter(record -> normalizeLookup(record.targetName).equals(normalized)
                || normalizeLookup(record.targetAddress).equals(normalized))
            .max(Comparator.comparingLong(record -> record.issuedAt))
            .map(record -> record.targetAddress)
            .orElse(null);
    }

    private boolean isFlyCommand(String token) {
        String command = token.toLowerCase(Locale.ROOT);
        return command.equals("fly") || command.equals("efly") || command.equals("essentials:fly");
    }

    private void loadFlightWhitelist() {
        List<String> configuredOwners = getConfig().getStringList("flight-owner-whitelist");
        if (configuredOwners.isEmpty()) {
            configuredOwners = List.of("FolksyPizza");
            getConfig().set("flight-owner-whitelist", configuredOwners);
            saveConfig();
        }

        flightOwnerWhitelist.clear();
        for (String owner : configuredOwners) {
            if (owner == null || owner.isBlank()) {
                continue;
            }
            flightOwnerWhitelist.add(owner.trim().toLowerCase(Locale.ROOT));
        }
    }

    private boolean canUseSurvivalFlight(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return true;
        }
        return flightOwnerWhitelist.contains(player.getName().toLowerCase(Locale.ROOT));
    }

    private void enforceFlightRules(Player player) {
        if (canUseSurvivalFlight(player)) {
            return;
        }
        if (player.isFlying()) {
            player.setFlying(false);
        }
        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
        }
    }

    private void dropAndClearInventory(Player player, Location location) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);
        clearAllSlots(inventory);
        forceClearViaCommand(player);
        player.setItemOnCursor(null);
        player.updateInventory();
    }

    private void clearAllSlots(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }
        try {
            ItemStack[] extra = inventory.getExtraContents();
            if (extra != null && extra.length > 0) {
                inventory.setExtraContents(new ItemStack[extra.length]);
            }
        } catch (Exception ignored) {
            // not available on all APIs
        }
    }

    private void forceClearViaCommand(Player player) {
        String name = player.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:clear " + name);
        } catch (Exception ignored) {
            // no-op
        }
    }

    private void dropItems(World world, Location location, ItemStack[] items) {
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            world.dropItemNaturally(location, item.clone());
        }
    }

    private void flushServerSaves() {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        } catch (Exception ex) {
            getLogger().warning("Failed to flush world saves: " + ex.getMessage());
        }
    }

    private boolean hasPunishPermission(Player player) {
        return player.hasPermission("mycommand.punish")
            || player.hasPermission("pizzasmp.punish")
            || player.hasPermission("pizzasmp.ban")
            || player.hasPermission("pizzasmp.mute")
            || player.hasPermission("pizzasmp.kick");
    }

    private boolean hasPermissionForType(Player player, PunishmentType type) {
        return switch (type) {
            case BAN -> hasPunishPermission(player) || player.hasPermission("pizzasmp.ban");
            case IP_BAN -> player.hasPermission("pizzasmp.ipban") || player.hasPermission("pizzasmp.ban");
            case MUTE -> player.hasPermission("pizzasmp.mute") || player.hasPermission("pizzasmp.punish");
            case KICK -> player.hasPermission("pizzasmp.kick") || player.hasPermission("pizzasmp.punish");
        };
    }

    private boolean hasReleasePermission(Player player, boolean banLike) {
        if (banLike) {
            return player.hasPermission("pizzasmp.unban")
                || player.hasPermission("pizzasmp.unpunish")
                || player.hasPermission("pizzasmp.forgive")
                || player.hasPermission("mycommand.unpunish")
                || player.hasPermission("mycommand.forgive");
        }
        return player.hasPermission("pizzasmp.unmute")
            || player.hasPermission("pizzasmp.unpunish");
    }

    private boolean hasHistoryPermission(Player player) {
        return player.hasPermission("pizzasmp.bancheck")
            || player.hasPermission("mycommand.bancheck")
            || player.hasPermission("pizzasmp.history")
            || player.hasPermission("pizzasmp.bans")
            || player.hasPermission("pizzasmp.listbans")
            || player.hasPermission("pizzasmp.listmutes");
    }

    private boolean hasMenuPermission(Player player) {
        return hasHistoryPermission(player);
    }

    private String usageForType(PunishmentType type, boolean permanent) {
        return switch (type) {
            case BAN -> permanent
                ? "/permban <player> <category|reason...>"
                : "/ban <player> <category|duration|reason...> [duration]";
            case IP_BAN -> permanent
                ? "/ippermban <player|ip> <category|reason...>"
                : "/ipban <player|ip> <category|duration|reason...> [duration]";
            case MUTE -> "/mute <player> <category|duration|reason...> [duration]";
            case KICK -> "/kick <player> <category|reason...>";
        };
    }

    private String commandLabelForType(PunishmentType type) {
        return switch (type) {
            case BAN -> "/ban";
            case IP_BAN -> "/ipban";
            case MUTE -> "/mute";
            case KICK -> "/kick";
        };
    }

    private String buildBanScreenMessage(PunishmentRecord record) {
        return ChatColor.RED + "You are banned from ExampleSMP. If you believe this was a mistake please make a ticket in the Example SMP Discord"
            + "\n"
            + ChatColor.YELLOW + DISCORD_INVITE
            + "\n\n"
            + ChatColor.GRAY + "Date: " + ChatColor.WHITE + formatDate(record.issuedAt)
            + "\n"
            + ChatColor.GRAY + "Duration: " + ChatColor.WHITE + formatDuration(record)
            + "\n"
            + ChatColor.GRAY + "Ban ID: " + ChatColor.WHITE + record.id
            + "\n"
            + ChatColor.GRAY + "Reason: " + ChatColor.WHITE + record.reason
            + "\n"
            + ChatColor.GRAY + "You may be able to appeal this ban on " + ChatColor.WHITE + DISCORD_INVITE + ChatColor.GRAY + ".";
    }

    private String formatDate(long timestamp) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    private String formatDuration(PunishmentRecord record) {
        if (!record.type.usesDuration()) {
            return "Instant";
        }
        if (record.expiresAt <= 0L) {
            return "Permanent";
        }
        return record.durationInput + " (until " + formatDate(record.expiresAt) + ")";
    }

    private boolean looksLikeDuration(String value) {
        return value != null && !value.isBlank() && (isPermanentDuration(value) || parseExpiry(value) != null);
    }

    private boolean isPermanentDuration(String duration) {
        if (duration == null) {
            return false;
        }
        String lowered = duration.toLowerCase(Locale.ROOT);
        return lowered.equals("perm")
            || lowered.equals("permanent")
            || lowered.equals("forever")
            || lowered.equals("permanently");
    }

    private Date parseExpiry(String duration) {
        if (duration == null || duration.isBlank() || isPermanentDuration(duration)) {
            return null;
        }

        long totalSeconds = 0L;
        Matcher matcher = DURATION_PATTERN.matcher(duration);
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                return null;
            }
            consumed = matcher.end();

            long value;
            try {
                value = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
            if (value <= 0L) {
                return null;
            }

            char unit = Character.toLowerCase(matcher.group(2).charAt(0));
            switch (unit) {
                case 'y':
                    totalSeconds += value * 365L * 24L * 3600L;
                    break;
                case 'd':
                    totalSeconds += value * 24L * 3600L;
                    break;
                case 'h':
                    totalSeconds += value * 3600L;
                    break;
                case 'm':
                    totalSeconds += value * 60L;
                    break;
                case 's':
                    totalSeconds += value;
                    break;
                default:
                    return null;
            }
        }

        if (consumed != duration.length() || totalSeconds <= 0L) {
            return null;
        }
        return new Date(System.currentTimeMillis() + (totalSeconds * 1000L));
    }

    private String normalizeLibertyDuration(Date expiry) {
        if (expiry == null) {
            return null;
        }
        long msRemaining = expiry.getTime() - System.currentTimeMillis();
        long minutes = Math.max(1L, (msRemaining + 59_999L) / 60_000L);
        return minutes + "m";
    }

    private boolean isShortTemporaryBan(Date expiry) {
        long remaining = expiry.getTime() - System.currentTimeMillis();
        return remaining > 0L && remaining < SHORT_LIBERTY_FALLBACK_MS;
    }

    private boolean isValidIp(String value) {
        if (value == null || !IPV4_PATTERN.matcher(value).matches()) {
            return false;
        }
        String[] parts = value.split("\\.");
        for (String part : parts) {
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (number < 0 || number > 255) {
                return false;
            }
        }
        return true;
    }

    private PunishmentPreset presetByLookup(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return presetsByKey.get(token.trim().toLowerCase(Locale.ROOT));
    }

    // True if the player is currently combat-tagged via PizzaNetworkCore.
    // PizzaNetworkCore mirrors its in-memory combat tag onto the "pizza_combat"
    // scoreboard tag so cross-plugin checks like this one stay cheap and accurate.
    private boolean isInPizzaCombat(Player p) {
        return p != null && p.isOnline() && p.getScoreboardTags().contains("pizza_combat");
    }

    // ============================================================================
    // /offend cheating-strike system
    // ============================================================================
    // Each /offend increments the player's strike count and applies an escalating
    // ban: 1st = 7d, 2nd = 30d, 3rd+ = 365d. Counts persist in offenses.yml.
    // /offenses lists counts; /unoffend revokes strikes. Drops are gated by the
    // pizza_combat scoreboard tag (same rule as /punish).

    private void initOffenses() {
        offensesFile = new File(getDataFolder(), "offenses.yml");
        if (offensesFile.getParentFile() != null && !offensesFile.getParentFile().exists()) {
            offensesFile.getParentFile().mkdirs();
        }
        if (!offensesFile.exists()) {
            try { offensesFile.createNewFile(); } catch (Exception ignored) {}
        }
        offensesConfig = YamlConfiguration.loadConfiguration(offensesFile);
    }

    private void saveOffenses() {
        if (offensesConfig == null || offensesFile == null) return;
        try { offensesConfig.save(offensesFile); } catch (Exception ex) {
            getLogger().warning("Failed saving offenses.yml: " + ex.getMessage());
        }
    }

    private String offenseKey(ResolvedTarget target) {
        if (target.targetUuid != null) return target.targetUuid.toString();
        return "name:" + (target.targetName == null ? "unknown" : target.targetName.toLowerCase(Locale.ROOT));
    }

    private int currentStrikes(String key) {
        return offensesConfig == null ? 0 : Math.max(0, offensesConfig.getInt("counts." + key, 0));
    }

    private void setStrikes(String key, int n, String nameDisplay) {
        if (offensesConfig == null) return;
        if (n <= 0) {
            offensesConfig.set("counts." + key, null);
        } else {
            offensesConfig.set("counts." + key, n);
            if (nameDisplay != null) offensesConfig.set("names." + key, nameDisplay);
        }
    }

    private void appendOffenseHistory(String key, String entry) {
        if (offensesConfig == null) return;
        List<String> hist = new ArrayList<>(offensesConfig.getStringList("history." + key));
        hist.add(entry);
        // Cap history at 20 entries per player so the file doesn't grow unbounded.
        while (hist.size() > 20) hist.remove(0);
        offensesConfig.set("history." + key, hist);
    }

    private boolean handleOffendCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.hasPermission("pizzasmp.admin.offend")) {
            p.sendMessage(ChatColor.RED + "You do not have permission to issue offenses.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /offend <player> [reason...]");
            return true;
        }
        ResolvedTarget target = resolvePlayerTarget(args[0], false);
        if (!target.found()) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
            return true;
        }
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim() : "Cheating";
        if (reason.isBlank()) reason = "Cheating";
        String key = offenseKey(target);
        int newCount = Math.min(99, currentStrikes(key) + 1);
        setStrikes(key, newCount, target.targetName);
        String duration = newCount <= 1 ? "7d" : newCount == 2 ? "30d" : "365d";
        appendOffenseHistory(key,
            System.currentTimeMillis() + "|" + sender.getName() + "|" + duration + "|" + reason);
        saveOffenses();

        // Reuse the existing /punish pipeline so combat-tag inventory gating,
        // history, Ban-ID issuance, and screen text all work consistently.
        String prefixed = "[Offense " + newCount + "/3] " + reason;
        String[] reasonTokens = prefixed.split(" ");
        String[] banArgs = new String[2 + reasonTokens.length];
        banArgs[0] = args[0];
        banArgs[1] = duration;
        System.arraycopy(reasonTokens, 0, banArgs, 2, reasonTokens.length);
        sender.sendMessage(ChatColor.RED + "Offense " + newCount + "/3 logged for " + target.targetName
            + " — applying " + duration + " ban.");
        return handleGenericPunishCommand(sender, banArgs);
    }

    private boolean handleOffensesCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.hasPermission("pizzasmp.staff.offenses")) {
            p.sendMessage(ChatColor.RED + "You do not have permission to view offenses.");
            return true;
        }
        if (offensesConfig == null) {
            sender.sendMessage(ChatColor.YELLOW + "No offenses recorded yet.");
            return true;
        }
        if (args.length == 0) {
            org.bukkit.configuration.ConfigurationSection counts = offensesConfig.getConfigurationSection("counts");
            if (counts == null || counts.getKeys(false).isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No offenses recorded yet.");
                return true;
            }
            List<String[]> rows = new ArrayList<>();
            for (String k : counts.getKeys(false)) {
                int n = counts.getInt(k, 0);
                String display = offensesConfig.getString("names." + k, k);
                rows.add(new String[]{ display, String.valueOf(n) });
            }
            rows.sort((a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
            sender.sendMessage(ChatColor.GOLD + "Top offenders (" + rows.size() + ")");
            int shown = 0;
            for (String[] r : rows) {
                sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + r[0]
                    + ChatColor.GRAY + ": " + ChatColor.RED + r[1] + ChatColor.GRAY + " strike(s)");
                if (++shown >= 15) break;
            }
            return true;
        }
        ResolvedTarget target = resolvePlayerTarget(args[0], false);
        if (!target.found()) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
            return true;
        }
        String key = offenseKey(target);
        int strikes = currentStrikes(key);
        sender.sendMessage(ChatColor.GOLD + target.targetName + ChatColor.GRAY + " has "
            + ChatColor.RED + strikes + ChatColor.GRAY + " offense strike(s).");
        List<String> hist = offensesConfig.getStringList("history." + key);
        if (hist.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "  (no history)");
        } else {
            int from = Math.max(0, hist.size() - 5);
            for (int i = from; i < hist.size(); i++) {
                String[] parts = hist.get(i).split("\\|", 4);
                String when = parts.length > 0 ? parts[0] : "?";
                String by   = parts.length > 1 ? parts[1] : "?";
                String dur  = parts.length > 2 ? parts[2] : "?";
                String why  = parts.length > 3 ? parts[3] : "";
                long ms = 0L;
                try { ms = Long.parseLong(when); } catch (Exception ignored) {}
                String ago = ms > 0 ? formatAgo(System.currentTimeMillis() - ms) : "?";
                sender.sendMessage(ChatColor.GRAY + "  - " + ChatColor.WHITE + dur
                    + ChatColor.GRAY + " by " + ChatColor.AQUA + by
                    + ChatColor.GRAY + " (" + ago + " ago): " + ChatColor.YELLOW + why);
            }
        }
        return true;
    }

    private boolean handleUnoffendCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.hasPermission("pizzasmp.admin.offend")) {
            p.sendMessage(ChatColor.RED + "You do not have permission to revoke offenses.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /unoffend <player> [count|all]");
            return true;
        }
        ResolvedTarget target = resolvePlayerTarget(args[0], false);
        if (!target.found()) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
            return true;
        }
        String key = offenseKey(target);
        int strikes = currentStrikes(key);
        if (strikes <= 0) {
            sender.sendMessage(ChatColor.YELLOW + target.targetName + " has no strikes to revoke.");
            return true;
        }
        int remove;
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            remove = strikes;
        } else if (args.length >= 2) {
            try { remove = Math.max(1, Integer.parseInt(args[1])); }
            catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Usage: /unoffend <player> [count|all]");
                return true;
            }
        } else {
            remove = 1;
        }
        int next = Math.max(0, strikes - remove);
        setStrikes(key, next, target.targetName);
        appendOffenseHistory(key,
            System.currentTimeMillis() + "|" + sender.getName() + "|revoke|-" + (strikes - next));
        saveOffenses();
        sender.sendMessage(ChatColor.GREEN + "Revoked " + (strikes - next) + " strike(s) from "
            + target.targetName + " (" + next + " remaining).");
        return true;
    }

    private static String formatAgo(long ms) {
        if (ms < 60_000L) return (ms / 1000L) + "s";
        if (ms < 3_600_000L) return (ms / 60_000L) + "m";
        if (ms < 86_400_000L) return (ms / 3_600_000L) + "h";
        return (ms / 86_400_000L) + "d";
    }

    private static final List<String> DURATION_PRESETS = List.of(
        "30m", "1h", "6h", "12h", "1d", "3d", "7d", "14d", "30d", "60d", "90d", "180d", "365d", "perm"
    );
    private static final List<String> CHEAT_REASON_PRESETS = List.of(
        "killaura", "reach", "xray", "fly", "speed", "scaffold", "elytra", "boatfly",
        "autoclicker", "macro", "antikb", "phase", "noslow", "ban evasion", "alt abuse",
        "exploiting", "duping", "advertising", "spam", "harassment", "racism", "doxxing"
    );

    private List<String> completePunishmentCommand(String commandName, String[] args) {
        if (args.length == 1) {
            return completeKnownPlayerTargets(args[0]);
        }
        if (args.length == 2) {
            List<String> suggestions = new ArrayList<>(presetSuggestions);
            suggestions.addAll(CHEAT_REASON_PRESETS);
            if (!commandName.equals("kick")) {
                suggestions.addAll(DURATION_PRESETS);
            }
            return filterByPrefix(suggestions, args[1]);
        }
        if (args.length >= 3 && !"kick".equals(commandName)) {
            String firstPayload = args[1];
            String currentArg = args[args.length - 1];
            boolean firstIsDuration = looksLikeDuration(firstPayload);
            boolean firstIsPreset = presetByLookup(firstPayload) != null;
            // If the prior token already specified a duration or a preset reason,
            // every following token is free-form reason text.
            if (firstIsDuration || firstIsPreset) {
                List<String> rest = new ArrayList<>(CHEAT_REASON_PRESETS);
                rest.add("<reason...>");
                return filterByPrefix(rest, currentArg);
            }
            // Otherwise the user is typing free-form reason; offer durations on arg[2].
            if (args.length == 3) {
                return filterByPrefix(DURATION_PRESETS, currentArg);
            }
            return filterByPrefix(List.of("<reason...>"), currentArg);
        }
        return List.of();
    }

    private List<String> completeIpBanCommand(String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(completeKnownPlayerTargets(args[0]));
            suggestions.addAll(getKnownAddressSuggestions());
            return filterByPrefix(suggestions, args[0]);
        }
        if (args.length == 2) {
            List<String> suggestions = new ArrayList<>(presetSuggestions);
            suggestions.addAll(List.of("7d", "14d", "30d", "perm"));
            return filterByPrefix(suggestions, args[1]);
        }
        if (args.length == 3) {
            return filterByPrefix(List.of("7d", "14d", "30d", "perm", "<reason...>"), args[2]);
        }
        return List.of();
    }

    private List<String> completeKnownPlayerTargets(String prefix) {
        Set<String> names = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        for (PunishmentRecord record : records.values()) {
            if (record.targetName != null && !record.targetName.isBlank()) {
                names.add(record.targetName);
            }
        }
        List<String> out = new ArrayList<>(names);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return filterByPrefix(out, prefix);
    }

    private List<String> getReleaseSuggestions(boolean banLike) {
        Set<String> out = new HashSet<>();
        for (PunishmentRecord record : records.values()) {
            if (!record.active) {
                continue;
            }
            if (banLike && record.isActiveBanLike()) {
                out.add(record.id);
                out.add(record.displayTarget());
            }
            if (!banLike && record.type == PunishmentType.MUTE) {
                out.add(record.id);
                out.add(record.displayTarget());
            }
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private List<String> getHistorySuggestions() {
        Set<String> out = new HashSet<>();
        for (PunishmentRecord record : records.values()) {
            out.add(record.id);
            if (record.targetName != null && !record.targetName.isBlank()) {
                out.add(record.targetName);
            }
            if (record.targetAddress != null && !record.targetAddress.isBlank()) {
                out.add(record.targetAddress);
            }
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private List<String> getRecordIdSuggestions() {
        List<String> out = new ArrayList<>(records.keySet());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private List<String> getKnownAddressSuggestions() {
        Set<String> addresses = new HashSet<>();
        for (PunishmentRecord record : records.values()) {
            if (record.targetAddress != null && !record.targetAddress.isBlank()) {
                addresses.add(record.targetAddress);
            }
        }
        List<String> out = new ArrayList<>(addresses);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private PunishmentRecord findActiveRecordByLookup(String lookup, Set<PunishmentType> allowedTypes) {
        String normalizedLookup = normalizeLookup(lookup);
        PunishmentRecord byId = records.get(normalizeId(lookup));
        if (byId != null && byId.active && allowedTypes.contains(byId.type)) {
            return byId;
        }
        return records.values().stream()
            .filter(record -> record.active)
            .filter(record -> allowedTypes.contains(record.type))
            .filter(record -> record.matchesLookup(normalizedLookup))
            .max(Comparator.comparingLong(record -> record.issuedAt))
            .orElse(null);
    }

    private PunishmentRecord findBestRecordByLookup(String lookup) {
        PunishmentRecord byId = records.get(normalizeId(lookup));
        if (byId != null) {
            return byId;
        }
        String normalized = normalizeLookup(lookup);
        return records.values().stream()
            .filter(record -> record.matchesLookup(normalized))
            .max(Comparator.comparingLong(record -> record.issuedAt))
            .orElse(null);
    }

    private List<PunishmentRecord> findRecordsForLookup(String lookup) {
        String normalized = normalizeLookup(lookup);
        return records.values().stream()
            .filter(record -> record.matchesLookup(normalized))
            .sorted(Comparator.comparingLong((PunishmentRecord record) -> record.issuedAt).reversed())
            .toList();
    }

    private String generateId() {
        for (int attempt = 0; attempt < 512; attempt++) {
            String candidate = ID_PREFIX + nextRandomNumericId();
            if (!records.containsKey(candidate)) {
                return candidate;
            }
        }
        return ID_PREFIX + Math.abs(System.currentTimeMillis()) + Math.abs(System.nanoTime() % 10_000L);
    }

    private long nextRandomNumericId() {
        long raw = idRandom.nextLong();
        long positive = raw == Long.MIN_VALUE ? 0L : Math.abs(raw);
        return 10_000L + (positive % 90_000L);
    }

    private boolean looksLikeId(String value) {
        String normalized = normalizeId(value);
        return NUMERIC_ID_PATTERN.matcher(normalized).matches() || LEGACY_ID_PATTERN.matcher(normalized).matches();
    }

    private String normalizeId(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        if (LEGACY_ID_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        String numeric = trimmed.startsWith(ID_PREFIX) ? trimmed.substring(ID_PREFIX.length()) : trimmed;
        if (!numeric.isBlank() && numeric.chars().allMatch(Character::isDigit)) {
            return ID_PREFIX + stripLeadingZeros(numeric);
        }
        return trimmed;
    }

    private long parseNumericId(String value) {
        String normalized = normalizeId(value);
        if (!NUMERIC_ID_PATTERN.matcher(normalized).matches()) {
            return -1L;
        }
        try {
            return Long.parseLong(normalized.substring(1));
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    private String normalizeLookup(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private UUID resolveOfflineUuid(String targetName) {
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            return offline == null ? null : offline.getUniqueId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeSingleLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private int parsePageArg(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw) - 1);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private long getConfirmWindowMillis() {
        return Math.max(5L, getConfig().getLong("confirm-window-seconds", 30L)) * 1000L;
    }

    private String confirmKey(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId().toString();
        }
        return "console:" + sender.getName().toLowerCase(Locale.ROOT);
    }

    private String commandForBulkMode(ViewMode mode) {
        return mode == ViewMode.ACTIVE_BANS ? "/clearbans" : "/clearmutes";
    }

    private void storeLocation(UUID uuid, Location location) {
        locationsConfig.set(uuid.toString(), location);
        saveLocations();
    }

    private Location consumeStoredLocation(UUID uuid) {
        String key = uuid.toString();
        Location location = locationsConfig.getLocation(key);
        if (location == null) {
            return null;
        }
        locationsConfig.set(key, null);
        saveLocations();
        return location;
    }

    private void saveLocations() {
        try {
            locationsConfig.save(locationsFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save ban locations: " + e.getMessage());
        }
    }

    private void savePendingActions() {
        try {
            pendingActionsConfig.save(pendingActionsFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save pending actions: " + e.getMessage());
        }
    }

    private void saveRecords() {
        try {
            recordsConfig.save(recordsFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save punishment records: " + e.getMessage());
        }
    }

    private void fillInventory(Inventory inventory, Material material, String name) {
        ItemStack filler = createItem(material, name, List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> buildPresetLore() {
        List<String> lore = new ArrayList<>();
        int shown = 0;
        for (String key : presetSuggestions) {
            PunishmentPreset preset = presetsByKey.get(key);
            if (preset == null || !preset.key.equals(key)) {
                continue;
            }
            lore.add(ChatColor.YELLOW + preset.key + ChatColor.GRAY + " -> " + preset.actionType.displayName + " " + preset.duration);
            shown++;
            if (shown >= 10) {
                break;
            }
        }
        lore.add("");
        lore.add(ChatColor.GRAY + "Use /punish <player> <category>");
        lore.add(ChatColor.GRAY + "Use /ban or /mute for explicit action overrides.");
        return lore;
    }

    private String colorByType(PunishmentType type) {
        return switch (type) {
            case BAN, IP_BAN -> ChatColor.RED.toString();
            case MUTE -> ChatColor.YELLOW.toString();
            case KICK -> ChatColor.GOLD.toString();
        };
    }

    private List<String> filterByPrefix(List<String> values, String prefix) {
        String lowered = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (lowered.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                out.add(value);
            }
        }
        return out;
    }

    private List<String> wrapText(String text, int maxLength) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }

        StringBuilder current = new StringBuilder();
        for (String part : text.split("\\s+")) {
            if (current.length() == 0) {
                current.append(part);
                continue;
            }
            if (current.length() + 1 + part.length() > maxLength) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(part);
                continue;
            }
            current.append(' ').append(part);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private String trimReason(String reason, int maxLength) {
        if (reason == null) {
            return "";
        }
        if (reason.length() <= maxLength) {
            return reason;
        }
        return reason.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private enum PunishmentType {
        BAN("Ban", "Unban", true, true),
        IP_BAN("IP Ban", "Unban", true, true),
        MUTE("Mute", "Unmute", true, true),
        KICK("Kick", "Close", false, false);

        private final String displayName;
        private final String clearVerb;
        private final boolean usesDuration;
        private final boolean activeByDefault;

        PunishmentType(String displayName, String clearVerb, boolean usesDuration, boolean activeByDefault) {
            this.displayName = displayName;
            this.clearVerb = clearVerb;
            this.usesDuration = usesDuration;
            this.activeByDefault = activeByDefault;
        }

        private boolean usesDuration() {
            return usesDuration;
        }

        private boolean isActiveByDefault() {
            return activeByDefault;
        }

        private boolean canBeCleared() {
            return this == BAN || this == IP_BAN || this == MUTE;
        }
    }

    private enum TargetKind {
        PLAYER,
        ADDRESS
    }

    private enum ViewMode {
        ACTIVE_BANS("Active Bans", ChatColor.DARK_RED + "ExampleSMP Bans"),
        ACTIVE_MUTES("Active Mutes", ChatColor.DARK_RED + "ExampleSMP Mutes"),
        HISTORY("History", ChatColor.DARK_RED + "ExampleSMP History");

        private final String displayName;
        private final String title;

        ViewMode(String displayName, String title) {
            this.displayName = displayName;
            this.title = title;
        }
    }

    private static final class ParsedPunishment {
        private final PunishmentType type;
        private final String durationInput;
        private final Date expiry;
        private final String reason;

        private ParsedPunishment(PunishmentType type, String durationInput, Date expiry, String reason) {
            this.type = type;
            this.durationInput = durationInput == null ? "" : durationInput;
            this.expiry = expiry;
            this.reason = reason;
        }
    }

    private static final class ResolvedTarget {
        private static final ResolvedTarget NOT_FOUND = new ResolvedTarget(null, null, null, null, null);

        private final TargetKind targetKind;
        private final String targetName;
        private final UUID targetUuid;
        private final String targetAddress;
        private final Player onlinePlayer;

        private ResolvedTarget(TargetKind targetKind, String targetName, UUID targetUuid, String targetAddress, Player onlinePlayer) {
            this.targetKind = targetKind;
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.targetAddress = targetAddress;
            this.onlinePlayer = onlinePlayer;
        }

        private static ResolvedTarget notFound() {
            return NOT_FOUND;
        }

        private boolean found() {
            return targetKind != null;
        }

        private String displayTarget() {
            if (targetKind == TargetKind.ADDRESS) {
                return targetName != null && targetAddress != null && !targetName.equals(targetAddress)
                    ? targetName + " (" + targetAddress + ")"
                    : targetAddress;
            }
            return targetName;
        }

        private String lockKey() {
            if (targetKind == TargetKind.ADDRESS) {
                return targetAddress == null ? "" : targetAddress.toLowerCase(Locale.ROOT);
            }
            return targetName == null ? "" : targetName.toLowerCase(Locale.ROOT);
        }
    }

    private static final class PendingBulkClear {
        private final ViewMode mode;
        private final long expiresAt;

        private PendingBulkClear(ViewMode mode, long expiresAt) {
            this.mode = mode;
            this.expiresAt = expiresAt;
        }
    }

    private static final class PunishmentPreset {
        private final String key;
        private final PunishmentType actionType;
        private final String duration;
        private final String reason;
        private final List<String> aliases;

        private PunishmentPreset(String key, PunishmentType actionType, String duration, String reason, List<String> aliases) {
            this.key = key;
            this.actionType = actionType;
            this.duration = duration;
            this.reason = reason;
            this.aliases = aliases;
        }

        private static PunishmentPreset fromConfig(String key, ConfigurationSection section) {
            String actionRaw = section.getString("action", "BAN").toUpperCase(Locale.ROOT);
            PunishmentType actionType;
            try {
                actionType = PunishmentType.valueOf(actionRaw);
            } catch (IllegalArgumentException ex) {
                actionType = PunishmentType.BAN;
            }
            String duration = section.getString("duration", actionType == PunishmentType.KICK ? "" : "30d");
            String reason = section.getString("reason", "ExampleSMP Rule Violation");
            List<String> aliases = new ArrayList<>();
            for (String alias : section.getStringList("aliases")) {
                if (alias == null || alias.isBlank()) {
                    continue;
                }
                aliases.add(alias.trim().toLowerCase(Locale.ROOT));
            }
            return new PunishmentPreset(
                key.trim().toLowerCase(Locale.ROOT),
                actionType,
                duration,
                reason,
                aliases
            );
        }
    }

    private static final class PunishmentRecord {
        private final String id;
        private final PunishmentType type;
        private final TargetKind targetKind;
        private final String targetName;
        private final String targetUuid;
        private final String targetAddress;
        private final String reason;
        private final String issuedBy;
        private final long issuedAt;
        private final String durationInput;
        private final long expiresAt;
        private boolean active;
        private long clearedAt;
        private String clearedBy;
        private final String sourceCommand;

        private PunishmentRecord(
            String id,
            PunishmentType type,
            TargetKind targetKind,
            String targetName,
            String targetUuid,
            String targetAddress,
            String reason,
            String issuedBy,
            long issuedAt,
            String durationInput,
            long expiresAt,
            boolean active,
            long clearedAt,
            String clearedBy,
            String sourceCommand
        ) {
            this.id = id;
            this.type = type;
            this.targetKind = targetKind;
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.targetAddress = targetAddress;
            this.reason = reason;
            this.issuedBy = issuedBy;
            this.issuedAt = issuedAt;
            this.durationInput = durationInput == null ? "" : durationInput;
            this.expiresAt = expiresAt;
            this.active = active;
            this.clearedAt = clearedAt;
            this.clearedBy = clearedBy;
            this.sourceCommand = sourceCommand;
        }

        private static PunishmentRecord fromConfig(String id, ConfigurationSection section) {
            String targetName = section.getString("target-name");
            String targetAddress = section.getString("target-address");
            if ((targetName == null || targetName.isBlank()) && (targetAddress == null || targetAddress.isBlank())) {
                return null;
            }

            PunishmentType type;
            try {
                type = PunishmentType.valueOf(section.getString("type", "BAN").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                type = PunishmentType.BAN;
            }

            TargetKind targetKind;
            try {
                targetKind = TargetKind.valueOf(section.getString("target-kind", "PLAYER").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                targetKind = TargetKind.PLAYER;
            }

            return new PunishmentRecord(
                id.toUpperCase(Locale.ROOT),
                type,
                targetKind,
                targetName,
                section.getString("target-uuid"),
                targetAddress,
                section.getString("reason", "No reason stated."),
                section.getString("issued-by", "Unknown"),
                section.getLong("issued-at", 0L),
                section.getString("duration-input", type.usesDuration() ? "perm" : ""),
                section.getLong("expires-at", 0L),
                section.getBoolean("active", type.isActiveByDefault()),
                section.getLong("cleared-at", 0L),
                section.getString("cleared-by"),
                section.getString("source-command", type.name().toLowerCase(Locale.ROOT))
            );
        }

        private Date getExpiryDate() {
            return expiresAt <= 0L ? null : new Date(expiresAt);
        }

        private UUID getTargetUuid() {
            if (targetUuid == null || targetUuid.isBlank()) {
                return null;
            }
            try {
                return UUID.fromString(targetUuid);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private String displayTarget() {
            if (targetKind == TargetKind.ADDRESS) {
                if (targetName != null && targetAddress != null && !targetName.equalsIgnoreCase(targetAddress)) {
                    return targetName + " (" + targetAddress + ")";
                }
                return targetAddress;
            }
            return targetName;
        }

        private boolean matchesLookup(String normalizedLookup) {
            if (normalizedLookup.isBlank()) {
                return false;
            }
            if (normalizeLookup(id).equals(normalizedLookup)) {
                return true;
            }
            if (targetName != null && normalizeLookup(targetName).equals(normalizedLookup)) {
                return true;
            }
            if (targetAddress != null && normalizeLookup(targetAddress).equals(normalizedLookup)) {
                return true;
            }
            if (targetUuid != null && normalizeLookup(targetUuid).equals(normalizedLookup)) {
                return true;
            }
            return false;
        }

        private boolean isActiveBanLike() {
            return active && (type == PunishmentType.BAN || type == PunishmentType.IP_BAN);
        }

        private static String normalizeLookup(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static final class ModerationMenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class PunishmentListHolder implements InventoryHolder {
        private final ViewMode mode;
        private final int page;
        private Inventory inventory;

        private PunishmentListHolder(ViewMode mode, int page) {
            this.mode = mode;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class PunishmentDetailHolder implements InventoryHolder {
        private final String recordId;
        private final ViewMode returnMode;
        private final int returnPage;
        private Inventory inventory;

        private PunishmentDetailHolder(String recordId, ViewMode returnMode, int returnPage) {
            this.recordId = recordId;
            this.returnMode = returnMode;
            this.returnPage = returnPage;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class BulkClearHolder implements InventoryHolder {
        private final ViewMode mode;
        private Inventory inventory;

        private BulkClearHolder(ViewMode mode) {
            this.mode = mode;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class OnlinePlayersHolder implements InventoryHolder {
        private final Map<Integer, UUID> slotTargets = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class PlayerActionsHolder implements InventoryHolder {
        private final UUID targetUuid;
        private final String targetName;
        private Inventory inventory;

        private PlayerActionsHolder(UUID targetUuid, String targetName) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class OffensesHolder implements InventoryHolder {
        private final Map<Integer, String> slotKeys = new HashMap<>();
        private final Map<Integer, String> slotNames = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class OffenseDetailHolder implements InventoryHolder {
        private final String key;
        private final String name;
        private Inventory inventory;

        private OffenseDetailHolder(String key, String name) {
            this.key = key;
            this.name = name;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ChatStrikesHolder implements InventoryHolder {
        private final Map<Integer, String> slotNames = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ChatStrikeEntry {
        private final String uuid;
        private final String name;
        private final int count;
        private final long expiry;

        private ChatStrikeEntry(String uuid, String name, int count, long expiry) {
            this.uuid = uuid;
            this.name = name;
            this.count = count;
            this.expiry = expiry;
        }
    }
}
