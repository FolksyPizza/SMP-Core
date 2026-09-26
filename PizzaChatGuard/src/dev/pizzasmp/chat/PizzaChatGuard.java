package dev.pizzasmp.chat;

/*
 * PizzaChatGuard — part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Licensed under the MIT License (see LICENSE). No feature is gated or paid.
 */

import java.lang.ref.WeakReference;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.pizzasmp.common.scheduler.PlatformScheduler;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PizzaChatGuard — false-positive-resistant chat filter.
 *
 * Design goals:
 *  - Whole-WORD matching only (word boundaries). "ass" never matches inside "class"/"grass"/"pass".
 *  - A safe-word whitelist of common words that contain banned fragments, checked first.
 *  - Leet-speak normalization (4→a, 3→e, 1→i/l, 0→o, $→s, @→a, etc.) so "f4ggot" is caught,
 *    but ONLY for the slur list, and still under word-boundary rules.
 *  - Spam controls: duplicate-message and rate limiting (configurable).
 *  - Caps filter: only flags long messages that are mostly uppercase (won't catch "OK"/"GG").
 *  - Escalation: warn -> 5m mute -> 30m mute -> 24h mute, tracked per player.
 *  - Staff bypass via permission.
 */
public final class PizzaChatGuard extends JavaPlugin implements Listener {

    private static final long ASYNC_PERMISSION_SNAPSHOT_MAX_AGE_NANOS = 2_000_000_000L;
    private static final long STRIKE_PERSISTENCE_RETRY_DELAY_MILLIS = 5_000L;
    private static final long MUTE_DISPATCH_RETRY_DELAY_MILLIS = 5_000L;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String PERM_BYPASS = "pizzasmp.chatguard.bypass";
    private static final String PERM_CLEAR = "pizzasmp.chatguard.clearwarnings";
    private static final String PERM_LINKS = "pizzasmp.chatguard.links";   // may post links (bypass ADVERT only)
    private static final String PERM_MANAGE = "pizzasmp.chatguard.manage"; // manage whitelist + owner-guard toggle
    // Staff groups get link permission automatically. Owner/co-owner/dev are ALSO full-exempt (below).
    private static final String[] STAFF_GROUPS = {
        "group.mod", "group.srmod", "group.admin", "group.sradmin", "group.co-owner", "group.owner", "group.dev", "group.staff"
    };

    private record FilterPolicy(
            boolean enabled,
            long rateLimitMs,
            long duplicateWindowMs,
            int capsMinLength,
            double capsThreshold,
            Set<String> slurWords,
            Set<String> blockedWords,
            Set<String> safeWords,
            List<Pattern> advertPatterns) {

        private FilterPolicy {
            slurWords = Set.copyOf(slurWords);
            blockedWords = Set.copyOf(blockedWords);
            safeWords = Set.copyOf(safeWords);
            advertPatterns = List.copyOf(advertPatterns);
        }

        private static FilterPolicy defaults() {
            return new FilterPolicy(true, 750L, 3000L, 8, 0.7,
                Set.of(), Set.of(), Set.of(), List.of());
        }
    }

    private record ModerationPolicyState(boolean guardOwner, Set<UUID> linkWhitelist,
                                         Map<UUID, String> linkWhitelistNames) {
        private ModerationPolicyState {
            linkWhitelist = Set.copyOf(linkWhitelist);
            linkWhitelistNames = Map.copyOf(linkWhitelistNames);
        }

        private static ModerationPolicyState defaults(boolean guardOwner) {
            return new ModerationPolicyState(guardOwner, Set.of(), Map.of());
        }
    }

    private record StrikeState(int count, long expiresAtMillis, String playerName) { }

    private record PendingMute(UUID playerId, String playerName, String category, String duration) { }

    private record MessageWindow(long timestampMillis, String normalizedText) { }

    private record PlayerIdentity(UUID id, String name) { }

    private static final class PermissionSession {
        private final UUID playerId;
        private final WeakReference<Player> player;

        private PermissionSession(UUID playerId, Player player) {
            this.playerId = playerId;
            this.player = new WeakReference<>(player);
        }

        private UUID playerId() {
            return playerId;
        }

        private boolean belongsTo(Player candidate) {
            return this.player.get() == candidate;
        }

        private Player player() {
            return this.player.get();
        }
    }

    private record PermissionSnapshot(PermissionSession session, long capturedAtNanos,
                                      boolean ownerOrDev, boolean linkPermission) { }

    // One immutable policy is published only after a complete config load.
    private volatile FilterPolicy filterPolicy = FilterPolicy.defaults();

    private final Map<Character, Character> leet = new HashMap<>();

    // Per-player state
    private final Map<UUID, MessageWindow> lastMessages = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionSession> permissionSessions = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionSession> pendingPermissionRefreshes = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionSnapshot> asyncPermissionSnapshots = new ConcurrentHashMap<>();
    private final Object permissionSessionIdentityLock = new Object();
    // AsyncChatEvent can run off-region; cache session identity so filtering never reads live permission state there.
    private final Map<Player, PermissionSession> permissionSessionsByPlayer = new IdentityHashMap<>();
    // One immutable value keeps each player's count, expiry, and persisted name coherent.
    private final Map<UUID, StrikeState> strikeStates = new ConcurrentHashMap<>();
    private final Map<UUID, PendingMute> pendingMutes = new ConcurrentHashMap<>();
    private final Set<UUID> scheduledMuteDispatches = ConcurrentHashMap.newKeySet();
    private final Set<UUID> failedMuteDispatchWarnings = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> muteRetryAfterMillis = new ConcurrentHashMap<>();

    // Players get this many warnings before being muted (unless the violation is SEVERE).
    private static final int STRIKE_LIMIT = 5;
    // Strikes decay after this long of clean behavior.
    private static final long STRIKE_DECAY_MS = 30L * 60L * 1000L; // 30 minutes
    // Staff who should see the short "muted by automod" notice.
    private static final String PERM_NOTIFY = "pizzasmp.staff";

    // Unified storage (yaml default; mysql for cross-instance sync). Namespace "chatguard".
    private volatile dev.pizzasmp.common.SuiteStorage storage;
    private final Object storageLifecycleLock = new Object();
    private volatile boolean storageClosing;
    private final Object strikePersistenceLock = new Object();
    private long strikeRevision;
    private long persistedStrikeRevision;
    private long strikeRetryNotBeforeMillis;
    private boolean strikeWriteScheduled;
    private final Object policyPersistenceLock = new Object();
    private long policyRevision;
    private long persistedPolicyRevision;
    private boolean policyWriteScheduled;
    private volatile boolean permissionSnapshotsClosing;
    private boolean permissionSessionBootstrapDone;
    private PlatformScheduler.TaskHandle permissionSnapshotTask;

    // Exemption policy (persisted in the "policy" storage doc, so it syncs across instances).
    // guardOwner=false (default): owner/co-owner/dev are fully exempt from the filter. Toggle it ON
    // from /admin (or /pcg owner on) to also guard owner/dev. Staff always keep link permission.
    private volatile ModerationPolicyState moderationPolicy = ModerationPolicyState.defaults(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.storage = dev.pizzasmp.common.SuiteStorage.fromConfig(this, "chatguard");
        this.storage.importFromYamlOnce(java.util.List.of("strikes"), false);
        if (!this.storage.isMysql()) {
            getLogger().warning("[storage] running on local files: chat strikes will NOT be shared "
                + "between backends, so a player can shed them by changing server. "
                + "Set storage.backend: mysql to share them.");
        }
        loadPolicy();
        initLeet();
        loadStrikes();
        loadPolicyState();
        Bukkit.getPluginManager().registerEvents(this, this);
        permissionSnapshotTask = PlatformScheduler.globalRepeating(this, () -> {
            retryStrikePersistence();
            retryPendingMutes();
            refreshOnlinePermissionSnapshots();
        }, 1L, 20L);
        FilterPolicy policy = filterPolicy;
        getLogger().info("PizzaChatGuard enabled — " + policy.slurWords().size() + " slurs, "
            + policy.blockedWords().size() + " blocked, " + policy.safeWords().size() + " safe-words, "
            + policy.advertPatterns().size() + " advert patterns, filter=" + policy.enabled());
    }

    @Override
    public void onDisable() {
        permissionSnapshotsClosing = true;
        storageClosing = true;
        if (permissionSnapshotTask != null) {
            permissionSnapshotTask.cancel();
            permissionSnapshotTask = null;
        }
        permissionSessions.clear();
        pendingPermissionRefreshes.clear();
        asyncPermissionSnapshots.clear();
        if (!pendingMutes.isEmpty()) {
            getLogger().severe("One or more automod mutes remain pending; the mute command was not confirmed.");
            getLogger().severe("Strike records will receive a final persistence attempt.");
        }
        pendingMutes.clear();
        scheduledMuteDispatches.clear();
        failedMuteDispatchWarnings.clear();
        muteRetryAfterMillis.clear();
        synchronized (permissionSessionIdentityLock) {
            permissionSessionsByPlayer.clear();
        }
        synchronized (storageLifecycleLock) {
            if (this.storage != null) {
                ModerationPolicyState policy = moderationPolicy;
                long revision;
                synchronized (policyPersistenceLock) {
                    revision = policyRevision;
                }
                boolean policySaved = writePolicyState(policy);
                synchronized (policyPersistenceLock) {
                    if (policySaved) persistedPolicyRevision = Math.max(persistedPolicyRevision, revision);
                    policyWriteScheduled = false;
                }
                writeStrikes();
                this.storage.close();
            }
        }
    }

    /**
     * Persist live strike state to strikes.yml so the PunishDrop moderation hub
     * can display chat strikes, and so strikes survive restarts.
     */
    private void persistStrikes() {
        if (storage == null || storageClosing) return;
        synchronized (strikePersistenceLock) {
            strikeRevision++;
        }
        scheduleStrikePersistence();
    }

    private void scheduleStrikePersistence() {
        synchronized (strikePersistenceLock) {
            if (storage == null || storageClosing || strikeWriteScheduled
                    || persistedStrikeRevision >= strikeRevision
                    || System.currentTimeMillis() < strikeRetryNotBeforeMillis) return;
            strikeWriteScheduled = true;
        }
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.asyncNow(this, this::writePendingStrikes);
            if (!task.wasAccepted()) {
                synchronized (strikePersistenceLock) {
                    strikeWriteScheduled = false;
                    strikeRetryNotBeforeMillis = System.currentTimeMillis() + STRIKE_PERSISTENCE_RETRY_DELAY_MILLIS;
                }
                getLogger().warning("Could not schedule strike persistence; records remain queued for retry.");
            }
        } catch (RuntimeException ex) {
            synchronized (strikePersistenceLock) {
                strikeWriteScheduled = false;
                strikeRetryNotBeforeMillis = System.currentTimeMillis() + STRIKE_PERSISTENCE_RETRY_DELAY_MILLIS;
            }
            getLogger().warning("Could not schedule strike persistence (cause=" + ex.getClass().getSimpleName() + ").");
        }
    }

    private void retryStrikePersistence() {
        scheduleStrikePersistence();
    }

    private void writePendingStrikes() {
        long revision = -1L;
        boolean saved = false;
        try {
            synchronized (storageLifecycleLock) {
                if (storage != null && !storageClosing) {
                    synchronized (strikePersistenceLock) {
                        revision = strikeRevision;
                    }
                    saved = writeStrikes();
                }
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Failed saving strikes (cause=" + ex.getClass().getSimpleName() + ").");
        } finally {
            boolean retryImmediately;
            synchronized (strikePersistenceLock) {
                if (saved && revision >= 0L) {
                    persistedStrikeRevision = Math.max(persistedStrikeRevision, revision);
                    strikeRetryNotBeforeMillis = 0L;
                } else if (!storageClosing) {
                    strikeRetryNotBeforeMillis = System.currentTimeMillis() + STRIKE_PERSISTENCE_RETRY_DELAY_MILLIS;
                }
                strikeWriteScheduled = false;
                retryImmediately = saved && !storageClosing && persistedStrikeRevision < strikeRevision;
            }
            if (retryImmediately) scheduleStrikePersistence();
        }
    }

    /** Caller holds storageLifecycleLock so shutdown cannot close storage during this write. */
    private boolean writeStrikes() {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, StrikeState> e : Map.copyOf(strikeStates).entrySet()) {
                StrikeState state = e.getValue();
                if (state.count() <= 0) continue;
                if (state.expiresAtMillis() > 0L && now > state.expiresAtMillis()) continue; // stale — don't persist
                String key = e.getKey().toString();
                cfg.set("counts." + key, state.count());
                if (state.expiresAtMillis() > 0L) cfg.set("expiry." + key, state.expiresAtMillis());
                if (state.playerName() != null) cfg.set("names." + key, state.playerName());
            }
            // yaml -> strikes.yml file; mysql -> suite_docs row (shared across instances).
            boolean saved = this.storage.saveDocResult("strikes", cfg);
            if (!saved) getLogger().warning("ChatGuard strike save failed; records remain queued for retry.");
            return saved;
        } catch (Exception ex) {
            getLogger().warning("Failed saving strikes (cause=" + ex.getClass().getSimpleName() + ").");
            return false;
        }
    }

    private void loadStrikes() {
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg = this.storage.loadDoc("strikes");
            org.bukkit.configuration.ConfigurationSection counts = cfg.getConfigurationSection("counts");
            if (counts == null) return;
            long now = System.currentTimeMillis();
            for (String key : counts.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    long exp = cfg.getLong("expiry." + key, 0L);
                    if (exp > 0L && now > exp) continue; // expired while offline
                    int n = counts.getInt(key, 0);
                    if (n <= 0) continue;
                    String name = cfg.getString("names." + key);
                    strikeStates.put(id, new StrikeState(n, exp, name));
                } catch (IllegalArgumentException ignored) {}
            }
            if (!strikeStates.isEmpty()) {
                getLogger().info("[ChatGuard] restored " + strikeStates.size() + " strike record(s).");
            }
        } catch (Exception ex) {
            getLogger().warning("Failed loading strikes (cause=" + ex.getClass().getSimpleName() + ").");
        }
    }

    private void initLeet() {
        leet.put('4', 'a'); leet.put('@', 'a'); leet.put('3', 'e');
        leet.put('1', 'i'); leet.put('!', 'i'); leet.put('0', 'o');
        leet.put('$', 's'); leet.put('5', 's'); leet.put('7', 't');
        leet.put('8', 'b'); leet.put('9', 'g');
    }

    private void loadPolicy() {
        reloadConfig();
        filterPolicy = parseFilterPolicy(getConfig());
    }

    private FilterPolicy parseFilterPolicy(FileConfiguration config) {
        boolean enabled = config.getBoolean("enabled", true);
        long rateLimitMs = config.getLong("rate-limit-ms", 750L);
        long duplicateWindowMs = config.getLong("duplicate-window-ms", 3000L);
        int capsMinLength = config.getInt("caps-min-length", 8);
        double capsThreshold = config.getDouble("caps-threshold", 0.7);

        Set<String> slurWords = new HashSet<>();
        for (String s : config.getStringList("slur-words")) {
            if (!s.isBlank()) slurWords.add(normalizeLeet(s.toLowerCase(Locale.ROOT)));
        }
        Set<String> blockedWords = new HashSet<>();
        for (String s : config.getStringList("blocked-words")) {
            if (!s.isBlank()) blockedWords.add(s.toLowerCase(Locale.ROOT));
        }
        Set<String> safeWords = new HashSet<>();
        for (String s : config.getStringList("safe-words")) {
            if (!s.isBlank()) safeWords.add(s.toLowerCase(Locale.ROOT));
        }
        List<Pattern> advertPatterns = new ArrayList<>();
        if (config.getBoolean("block-advertising", true)) {
            // IPv4 with optional :port, and domains with common TLDs (avoids matching "1.21" version refs by requiring 4 octets or a TLD)
            advertPatterns.add(Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b"));
            advertPatterns.add(Pattern.compile("\\b[a-z0-9-]+\\.(?:com|net|org|gg|io|co|me|xyz|fun|club|online|store|us|tk)\\b", Pattern.CASE_INSENSITIVE));
        }
        return new FilterPolicy(enabled, rateLimitMs, duplicateWindowMs,
            capsMinLength, capsThreshold, slurWords, blockedWords, safeWords, advertPatterns);
    }

    private String normalizeLeet(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            sb.append(leet.getOrDefault(c, c));
        }
        return sb.toString();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent e) {
        FilterPolicy policy = filterPolicy;
        Player p = e.getPlayer();
        if (!policy.enabled() || e.isCancelled()) return;
        PermissionSession session = permissionSessionFor(p);
        // Join normally seeds this identity cache. The fallback reads only the UUID if event delivery
        // races join/bootstrap; permission checks remain snapshot-only here.
        // AsyncChatEvent can miss the join/bootstrap cache during startup and quit races. UUID is
        // the player's immutable identity; this fallback reads no mutable world or entity state.
        UUID playerId = session != null ? session.playerId() : p.getUniqueId();
        PermissionSnapshot permissions = currentPermissionSnapshot(playerId);
        // Unknown or expired exemption snapshots fail closed for every filter violation, including spam/caps.
        // Ordinary chat still passes; staff may see a false positive until their next permission refresh.
        // Owner/co-owner/dev are fully exempt unless the owner-guard is toggled on.
        if (isFullyExempt(permissions)) return;
        String message = PLAIN.serialize(e.message());
        if (message == null || message.isBlank()) return;

        Violation v = inspect(playerId, message, policy, hasAsyncLinkBypass(playerId, permissions));
        // Staff and whitelisted players may post links: an ADVERT-only violation is allowed for them.
        if (v == Violation.NONE) {
            return;
        }
        e.setCancelled(true);
        handleViolation(p, playerId, v, message, permissions);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        PermissionSession session = registerPermissionSession(id, player);
        schedulePermissionSnapshot(player, id, session);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        PermissionSession session;
        synchronized (permissionSessionIdentityLock) {
            session = permissionSessionsByPlayer.remove(e.getPlayer());
        }
        if (session == null) return;
        UUID id = session.playerId();
        if (!permissionSessions.remove(id, session)) return;
        pendingPermissionRefreshes.remove(id, session);
        PermissionSnapshot snapshot = asyncPermissionSnapshots.get(id);
        if (snapshot != null && snapshot.session() == session) {
            asyncPermissionSnapshots.remove(id, snapshot);
        }
    }

    private void refreshOnlinePermissionSnapshots() {
        if (permissionSnapshotsClosing) return;
        if (!permissionSessionBootstrapDone) {
            permissionSessionBootstrapDone = true;
            // Capture player references globally; read player fields only after routing to the owning scheduler.
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlatformScheduler.entityNow(this, player, () -> {
                    if (permissionSnapshotsClosing || !player.isOnline()) return;
                    UUID id = player.getUniqueId();
                    PermissionSession session = registerPermissionSession(id, player);
                    schedulePermissionSnapshot(player, id, session);
                }, () -> { });
            }
        }
        for (Map.Entry<UUID, PermissionSession> entry : permissionSessions.entrySet()) {
            UUID id = entry.getKey();
            PermissionSession session = entry.getValue();
            Player player = session.player();
            if (player == null) {
                if (permissionSessions.remove(id, session)) {
                    pendingPermissionRefreshes.remove(id, session);
                }
                PermissionSnapshot snapshot = asyncPermissionSnapshots.get(id);
                if (snapshot != null && snapshot.session() == session) asyncPermissionSnapshots.remove(id, snapshot);
                synchronized (permissionSessionIdentityLock) {
                    permissionSessionsByPlayer.entrySet().removeIf(identityEntry -> identityEntry.getValue() == session);
                }
                continue;
            }
            schedulePermissionSnapshot(player, id, session);
        }
    }

    private PermissionSession registerPermissionSession(UUID id, Player player) {
        PermissionSession session = permissionSessions.compute(id, (ignored, current) ->
            current != null && current.belongsTo(player) ? current : newPermissionSession(id, player, current));
        synchronized (permissionSessionIdentityLock) {
            if (permissionSessions.get(id) == session) {
                permissionSessionsByPlayer.entrySet().removeIf(entry ->
                    entry.getValue().playerId().equals(id) && entry.getKey() != player);
                permissionSessionsByPlayer.put(player, session);
            }
        }
        return session;
    }

    private PermissionSession newPermissionSession(UUID id, Player player, PermissionSession previous) {
        if (previous != null) asyncPermissionSnapshots.remove(id);
        return new PermissionSession(id, player);
    }

    private PermissionSession permissionSessionFor(Player player) {
        synchronized (permissionSessionIdentityLock) {
            return permissionSessionsByPlayer.get(player);
        }
    }

    private void schedulePermissionSnapshot(Player player, UUID id, PermissionSession session) {
        if (permissionSnapshotsClosing || permissionSessions.get(id) != session
                || pendingPermissionRefreshes.putIfAbsent(id, session) != null) return;
        Runnable retired = () -> {
            pendingPermissionRefreshes.remove(id, session);
            PermissionSnapshot snapshot = asyncPermissionSnapshots.get(id);
            if (snapshot != null && snapshot.session() == session) {
                asyncPermissionSnapshots.remove(id, snapshot);
            }
        };
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.entityNow(this, player, () -> {
                try {
                    if (permissionSnapshotsClosing || permissionSessions.get(id) != session || !player.isOnline()) return;
                    boolean ownerOrDev = isOwnerOrDev(player);
                    boolean linkPermission = player.hasPermission(PERM_LINKS);
                    if (!linkPermission) {
                        for (String group : STAFF_GROUPS) {
                            if (player.hasPermission(group)) {
                                linkPermission = true;
                                break;
                            }
                        }
                    }
                    asyncPermissionSnapshots.put(id, new PermissionSnapshot(session,
                        System.nanoTime(), ownerOrDev, linkPermission));
                } finally {
                    pendingPermissionRefreshes.remove(id, session);
                }
            }, retired);
            if (!task.wasAccepted()) retired.run();
        } catch (RuntimeException ex) {
            retired.run();
        }
    }

    private PermissionSnapshot currentPermissionSnapshot(UUID id) {
        PermissionSnapshot snapshot = asyncPermissionSnapshots.get(id);
        if (snapshot == null || permissionSessions.get(id) != snapshot.session()
                || System.nanoTime() - snapshot.capturedAtNanos() > ASYNC_PERMISSION_SNAPSHOT_MAX_AGE_NANOS) {
            return null;
        }
        return snapshot;
    }

    private PermissionSnapshot currentPermissionSnapshot(UUID id, PermissionSession expectedSession) {
        PermissionSnapshot snapshot = currentPermissionSnapshot(id);
        return snapshot != null && snapshot.session() == expectedSession ? snapshot : null;
    }

    private boolean isFullyExempt(PermissionSnapshot snapshot) {
        return snapshot != null && snapshot.ownerOrDev() && !moderationPolicy.guardOwner();
    }

    private boolean hasAsyncLinkBypass(UUID id, PermissionSnapshot snapshot) {
        ModerationPolicyState policy = moderationPolicy;
        return policy.linkWhitelist().contains(id) || (snapshot != null
            && (snapshot.linkPermission() || (snapshot.ownerOrDev() && !policy.guardOwner())));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPrivateMessage(PlayerCommandPreprocessEvent e) {
        FilterPolicy policy = filterPolicy;
        Player p = e.getPlayer();
        if (!policy.enabled()) return;
        if (isFullyExempt(p)) return;
        String msg = e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        // Only inspect the message body of private-message commands.
        String[] pmCmds = {"/msg ", "/tell ", "/w ", "/whisper ", "/dm ", "/pm ", "/r ", "/reply "};
        String body = null;
        for (String cmd : pmCmds) {
            if (lower.startsWith(cmd)) {
                String rest = msg.substring(cmd.length()).trim();
                // For msg/tell/w/whisper/dm/pm strip the target name (first token)
                if (!cmd.equals("/r ") && !cmd.equals("/reply ")) {
                    int sp = rest.indexOf(' ');
                    body = sp >= 0 ? rest.substring(sp + 1) : "";
                } else {
                    body = rest;
                }
                break;
            }
        }
        if (body == null || body.isBlank()) return;
        UUID playerId = p.getUniqueId();
        Violation v = inspectContent(body, policy);
        if (v == Violation.ADVERT && hasLinkBypass(p)) v = Violation.NONE;
        if (v != Violation.NONE) {
            e.setCancelled(true);
            handleViolation(p, playerId, v, body, currentPermissionSnapshot(playerId));
        }
    }

    /** When staff unmute a player, clear their automod strikes so they start fresh. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStaffUnmute(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim();
        String lower = msg.toLowerCase(Locale.ROOT);
        String targetName = null;
        if (lower.startsWith("/unmute ")) {
            targetName = msg.substring("/unmute ".length()).trim();
        } else if (lower.startsWith("/mute remove ")) {
            targetName = msg.substring("/mute remove ".length()).trim();
        }
        if (targetName == null || targetName.isBlank()) return;
        int sp = targetName.indexOf(' ');
        if (sp >= 0) targetName = targetName.substring(0, sp);
        String requestedName = targetName;
        String issuerName = e.getPlayer().getName();
        resolvePlayerIdentity(requestedName, target -> {
            strikeStates.remove(target.id());
            persistStrikes();
            getLogger().info("[ChatGuard] strikes cleared for " + requestedName + " (unmuted by " + issuerName + ").");
        });
    }

    // ---- exemption policy --------------------------------------------------
    private boolean isOwnerOrDev(Player p) {
        return p.hasPermission("group.owner") || p.hasPermission("group.co-owner") || p.hasPermission("group.dev");
    }

    /** Fully exempt from the whole filter: owner/co-owner/dev, unless the owner-guard is toggled on. */
    private boolean isFullyExempt(Player p) {
        return isOwnerOrDev(p) && !moderationPolicy.guardOwner();
    }

    /** May post links (bypasses the ADVERT/URL filter only): staff, whitelisted players, and the full-exempt. */
    private boolean hasLinkBypass(Player p) {
        if (isFullyExempt(p)) return true;
        if (p.hasPermission(PERM_LINKS)) return true;
        if (moderationPolicy.linkWhitelist().contains(p.getUniqueId())) return true;
        for (String g : STAFF_GROUPS) if (p.hasPermission(g)) return true;
        return false;
    }

    private ModerationPolicyState updateModerationPolicy(UnaryOperator<ModerationPolicyState> update) {
        boolean schedule;
        ModerationPolicyState updated;
        synchronized (policyPersistenceLock) {
            if (storageClosing) return moderationPolicy;
            updated = update.apply(moderationPolicy);
            moderationPolicy = updated;
            policyRevision++;
            schedule = schedulePolicyWriteLocked();
        }
        if (schedule) schedulePolicyWrite();
        return updated;
    }

    private boolean schedulePolicyWriteLocked() {
        if (storage == null || storageClosing || policyWriteScheduled
                || persistedPolicyRevision >= policyRevision) return false;
        policyWriteScheduled = true;
        return true;
    }

    private void schedulePolicyWrite() {
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.asyncNow(this, this::writePendingPolicy);
            if (!task.wasAccepted()) {
                synchronized (policyPersistenceLock) {
                    policyWriteScheduled = false;
                }
                getLogger().warning("Could not schedule ChatGuard policy persistence (scheduler rejected task).");
            }
        } catch (RuntimeException ex) {
            synchronized (policyPersistenceLock) {
                policyWriteScheduled = false;
            }
            getLogger().warning("Could not schedule ChatGuard policy persistence (cause="
                + ex.getClass().getSimpleName() + ").");
        }
    }

    private void writePendingPolicy() {
        boolean flushSucceeded = false;
        try {
            synchronized (storageLifecycleLock) {
                if (!storageClosing && storage != null) {
                    flushSucceeded = flushPendingPolicyWritesLocked();
                }
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Failed saving ChatGuard policy (cause=" + ex.getClass().getSimpleName() + ").");
        }

        boolean reschedule = false;
        synchronized (policyPersistenceLock) {
            policyWriteScheduled = false;
            if (flushSucceeded) reschedule = schedulePolicyWriteLocked();
        }
        if (reschedule) schedulePolicyWrite();
    }

    /** Caller holds storageLifecycleLock. */
    private boolean flushPendingPolicyWritesLocked() {
        while (true) {
            ModerationPolicyState state;
            long revision;
            synchronized (policyPersistenceLock) {
                if (persistedPolicyRevision >= policyRevision) return true;
                state = moderationPolicy;
                revision = policyRevision;
            }
            if (!writePolicyState(state)) return false;
            synchronized (policyPersistenceLock) {
                persistedPolicyRevision = Math.max(persistedPolicyRevision, revision);
                if (persistedPolicyRevision >= policyRevision) return true;
            }
        }
    }

    private boolean writePolicyState(ModerationPolicyState state) {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("guard-owner", state.guardOwner());
            List<String> whitelist = new ArrayList<>();
            for (UUID id : state.linkWhitelist()) whitelist.add(id.toString());
            cfg.set("link-whitelist", whitelist);
            for (Map.Entry<UUID, String> entry : state.linkWhitelistNames().entrySet()) {
                cfg.set("names." + entry.getKey(), entry.getValue());
            }
            boolean saved = storage.saveDocResult("policy", cfg);
            if (!saved) getLogger().warning("ChatGuard policy save failed; persisted revision was not advanced.");
            return saved;
        } catch (Exception ex) {
            getLogger().warning("Failed saving ChatGuard policy (cause=" + ex.getClass().getSimpleName() + ").");
            return false;
        }
    }

    private ModerationPolicyState parsePolicyState(YamlConfiguration config, boolean fallbackGuardOwner) {
        Set<UUID> whitelist = new HashSet<>();
        Map<UUID, String> namesById = new HashMap<>();
        for (String entry : config.getStringList("link-whitelist")) {
            try { whitelist.add(UUID.fromString(entry)); } catch (IllegalArgumentException ignored) { }
        }
        org.bukkit.configuration.ConfigurationSection names = config.getConfigurationSection("names");
        if (names != null) for (String key : names.getKeys(false)) {
            try {
                String playerName = names.getString(key);
                if (playerName != null) namesById.put(UUID.fromString(key), playerName);
            } catch (IllegalArgumentException ignored) { }
        }
        return new ModerationPolicyState(config.getBoolean("guard-owner", fallbackGuardOwner), whitelist, namesById);
    }

    private void loadPolicyState() {
        boolean configuredGuardOwner = getConfig().getBoolean("guard-owner", false);
        ModerationPolicyState fallback = ModerationPolicyState.defaults(configuredGuardOwner);
        synchronized (policyPersistenceLock) {
            if (policyRevision == 0L) moderationPolicy = fallback;
        }
        try {
            dev.pizzasmp.common.SuiteStorage.DocumentLoadResult result = storage.loadDocResult("policy");
            if (result.status() == dev.pizzasmp.common.SuiteStorage.DocumentLoadStatus.FAILED) {
                getLogger().warning("Failed loading ChatGuard policy document; retaining the current policy.");
                return;
            }
            ModerationPolicyState loaded = result.status() == dev.pizzasmp.common.SuiteStorage.DocumentLoadStatus.MISSING
                ? fallback : parsePolicyState(result.document(), configuredGuardOwner);
            synchronized (policyPersistenceLock) {
                moderationPolicy = loaded;
                policyRevision++;
                persistedPolicyRevision = policyRevision;
            }
            getLogger().info("[ChatGuard] policy: guard-owner=" + loaded.guardOwner()
                + ", link-whitelist=" + loaded.linkWhitelist().size());
        } catch (Exception ex) {
            getLogger().warning("Failed loading ChatGuard policy (cause=" + ex.getClass().getSimpleName() + ").");
        }
    }

    private void reloadPolicyAsync(org.bukkit.command.CommandSender sender) {
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.asyncNow(this, () -> {
                try {
                    File configFile = new File(getDataFolder(), "config.yml");
                    YamlConfiguration config = new YamlConfiguration();
                    config.load(configFile);
                    FilterPolicy loadedFilterPolicy = parseFilterPolicy(config);

                    ModerationPolicyState loadedModerationPolicy;
                    long revisionBeforeLoad;
                    long persistedRevisionBeforeLoad;
                    synchronized (storageLifecycleLock) {
                        if (storageClosing || storage == null) return;
                        while (true) {
                            if (!flushPendingPolicyWritesLocked()) {
                                throw new IllegalStateException("pending moderation policy could not be saved");
                            }
                            synchronized (policyPersistenceLock) {
                                revisionBeforeLoad = policyRevision;
                                persistedRevisionBeforeLoad = persistedPolicyRevision;
                            }
                            if (revisionBeforeLoad == persistedRevisionBeforeLoad) break;
                        }
                        dev.pizzasmp.common.SuiteStorage.DocumentLoadResult result = storage.loadDocResult("policy");
                        if (result.status() == dev.pizzasmp.common.SuiteStorage.DocumentLoadStatus.FAILED) {
                            throw new IllegalStateException("policy document read failed");
                        }
                        loadedModerationPolicy = result.status()
                            == dev.pizzasmp.common.SuiteStorage.DocumentLoadStatus.MISSING
                            ? ModerationPolicyState.defaults(config.getBoolean("guard-owner", false))
                            : parsePolicyState(result.document(), config.getBoolean("guard-owner", false));
                    }
                    synchronized (policyPersistenceLock) {
                        if (storageClosing) return;
                        filterPolicy = loadedFilterPolicy;
                        if (policyRevision == revisionBeforeLoad
                                && persistedPolicyRevision == persistedRevisionBeforeLoad) {
                            moderationPolicy = loadedModerationPolicy;
                            policyRevision++;
                            persistedPolicyRevision = policyRevision;
                        }
                    }
                    sendCommandMessage(sender, "§aPizzaChatGuard policy reloaded.");
                } catch (Exception ex) {
                    getLogger().warning("Failed reloading ChatGuard policy (cause=" + ex.getClass().getSimpleName() + ").");
                    sendCommandMessage(sender, "§cPizzaChatGuard policy could not be reloaded.");
                }
            });
            if (!task.wasAccepted()) {
                getLogger().warning("Could not schedule ChatGuard policy reload (scheduler rejected task).");
                sender.sendMessage("§cPizzaChatGuard policy could not be reloaded.");
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Could not schedule ChatGuard policy reload (cause=" + ex.getClass().getSimpleName() + ").");
            sender.sendMessage("§cPizzaChatGuard policy could not be reloaded.");
        }
    }

    private void sendCommandMessage(org.bukkit.command.CommandSender sender, String message) {
        if (sender instanceof Player player) {
            PlatformScheduler.entityNow(this, player, () -> {
                if (player.isOnline()) player.sendMessage(message);
            }, () -> { });
        } else {
            PlatformScheduler.globalNow(this, () -> sender.sendMessage(message));
        }
    }

    private enum Violation { NONE, SLUR, BLOCKED, ADVERT, SPAM_RATE, SPAM_DUPLICATE, CAPS }

    private Violation inspect(UUID id, String message, FilterPolicy policy, boolean linkBypass) {
        long now = System.currentTimeMillis();
        String normalized = message.toLowerCase(Locale.ROOT).trim();
        java.util.concurrent.atomic.AtomicReference<Violation> result =
            new java.util.concurrent.atomic.AtomicReference<>(Violation.NONE);
        this.lastMessages.compute(id, (ignored, previous) -> {
            Violation violation = Violation.NONE;
            if (previous != null && now - previous.timestampMillis() < policy.rateLimitMs()) {
                violation = Violation.SPAM_RATE;
            } else if (previous != null && now - previous.timestampMillis() < policy.duplicateWindowMs()
                    && isSameOrSimilar(previous.normalizedText(), normalized)) {
                violation = Violation.SPAM_DUPLICATE;
            } else if (isCapsViolation(message, policy)) {
                violation = Violation.CAPS;
            } else {
                violation = inspectContent(message, policy);
                if (violation == Violation.ADVERT && linkBypass) violation = Violation.NONE;
            }
            result.set(violation);
            return violation == Violation.NONE ? new MessageWindow(now, normalized) : previous;
        });
        return result.get();
    }

    private Violation inspectContent(String message, FilterPolicy policy) {
        // Tokenize into words (letters/digits/leet symbols). Punctuation separates words.
        String[] rawTokens = message.toLowerCase(Locale.ROOT).split("[^a-z0-9@!$]+");
        // Robustness: rejoin runs of single-character tokens so "n i g g e r" / "f.u.c.k" are caught.
        StringBuilder spaced = new StringBuilder();
        for (String t : rawTokens) {
            if (t.length() == 1) spaced.append(t);
            else spaced.append(' ');
        }
        String joinedSingles = normalizeLeet(spaced.toString().trim());
        if (joinedSingles.length() >= 3 && !policy.safeWords().contains(joinedSingles)) {
            if (policy.slurWords().contains(joinedSingles)) return Violation.SLUR;
            if (policy.blockedWords().contains(joinedSingles)) return Violation.BLOCKED;
        }
        for (String token : rawTokens) {
            if (token.isBlank()) continue;
            // Whitelist: legitimate words are never flagged.
            if (policy.safeWords().contains(token)) continue;
            String norm = normalizeLeet(token);
            if (policy.safeWords().contains(norm)) continue;
            // Soft-blocked words: whole-word match (raw OR leet-normalized).
            if (policy.blockedWords().contains(token) || policy.blockedWords().contains(norm)) {
                return Violation.BLOCKED;
            }
            // Slurs: normalize leet, then whole-word match.
            if (policy.slurWords().contains(norm)) {
                return Violation.SLUR;
            }
            // Also catch slurs with repeated padding chars removed (e.g. "niiigger" -> "niger"? no —
            // collapse 3+ repeats to 1 to catch stretched spelling, but require min length 4 to avoid
            // collapsing short safe words).
            if (norm.length() >= 4) {
                String collapsed = norm.replaceAll("(.)\\1{2,}", "$1");
                if (!collapsed.equals(norm) && policy.slurWords().contains(collapsed)
                        && !policy.safeWords().contains(collapsed)) {
                    return Violation.SLUR;
                }
            }
        }
        // Advertising / IP / URL
        for (Pattern pat : policy.advertPatterns()) {
            Matcher m = pat.matcher(message);
            if (m.find()) {
                return Violation.ADVERT;
            }
        }
        return Violation.NONE;
    }

    // True when two (already lowercased/trimmed) messages are identical, or close enough to
    // count as spam: alphanumeric-only forms within a small Levenshtein distance. Fuzzy
    // matching is skipped for very short messages ("ok", "gg") to avoid false positives —
    // exact repeats of those are still caught by the equality check.
    private boolean isSameOrSimilar(String a, String b) {
        if (a.equals(b)) return true;
        String na = a.replaceAll("[^a-z0-9]", "");
        String nb = b.replaceAll("[^a-z0-9]", "");
        if (na.equals(nb)) return true; // same text, punctuation/spacing shuffled
        if (na.length() < 5 || nb.length() < 5) return false;
        int maxLen = Math.max(na.length(), nb.length());
        if (Math.abs(na.length() - nb.length()) > maxLen * 0.2) return false; // cheap pre-filter
        int dist = levenshtein(na, nb);
        return (double) dist / maxLen <= 0.2; // >=80% similar
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    private boolean isCapsViolation(String message, FilterPolicy policy) {
        String letters = message.replaceAll("[^a-zA-Z]", "");
        if (letters.length() < policy.capsMinLength()) return false;
        int upper = 0;
        for (char c : letters.toCharArray()) {
            if (Character.isUpperCase(c)) upper++;
        }
        return (double) upper / letters.length() >= policy.capsThreshold();
    }

    private void handleViolation(Player p, UUID id, Violation v, String message,
                                 PermissionSnapshot eventPermissions) {
        PermissionSession eventSession = eventPermissions == null ? null : eventPermissions.session();
        AtomicBoolean resolved = new AtomicBoolean();
        Runnable applyOfflineIfCurrent = () -> {
            PermissionSnapshot current = eventSession == null ? null : currentPermissionSnapshot(id, eventSession);
            if (current != null && !hasViolationBypass(id, v, current)) {
                applyViolation(null, id, v, message);
            }
        };
        Runnable retired = () -> {
            if (resolved.compareAndSet(false, true)) applyOfflineIfCurrent.run();
        };
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.entityNow(this, p, () -> {
                if (!resolved.compareAndSet(false, true)) return;
                if (p.isOnline()) {
                    if (isFullyExempt(p) || (v == Violation.ADVERT && hasLinkBypass(p))) return;
                    applyViolation(p, id, v, message);
                } else {
                    applyOfflineIfCurrent.run();
                }
            }, retired);
            if (!task.wasAccepted()) retired.run();
        } catch (RuntimeException ex) {
            retired.run();
        }
    }

    private boolean hasViolationBypass(UUID id, Violation violation, PermissionSnapshot permissions) {
        return isFullyExempt(permissions)
            || (violation == Violation.ADVERT && hasAsyncLinkBypass(id, permissions));
    }

    private void applyViolation(Player p, UUID id, Violation v, String message) {
        // Spam and caps are soft — warn only, never escalate to mutes.
        if (v == Violation.SPAM_RATE) {
            if (p != null && p.isOnline()) p.sendMessage("§cPlease wait 1 second before your next message");
            return;
        }
        if (v == Violation.SPAM_DUPLICATE) {
            if (p != null && p.isOnline()) p.sendMessage("§cPlease do not repeat the same (or similar) message.");
            return;
        }
        if (v == Violation.CAPS) {
            if (p != null && p.isOnline()) {
                p.sendActionBar(net.kyori.adventure.text.Component.text("§cPlease don't use excessive caps."));
            }
            return;
        }
        // Hard violations (slur/blocked/advert) — escalate with a 5-strike system.
        // SEVERE violations (hate speech / slurs) are "really bad" — instant mute, no warnings.
        boolean severe = (v == Violation.SLUR);
        String playerName = p != null && p.isOnline() ? p.getName() : null;
        StrikeState strike = strikeStates.compute(id, (ignored, previous) -> {
            long now = System.currentTimeMillis();
            // Expiry and increment share the same per-player atomic update.
            boolean expired = previous != null && previous.expiresAtMillis() > 0L
                && now > previous.expiresAtMillis();
            int previousCount = previous == null || expired ? 0 : previous.count();
            int count = previousCount == Integer.MAX_VALUE ? Integer.MAX_VALUE : previousCount + 1;
            boolean shouldMute = severe || count > STRIKE_LIMIT;
            String duration = severe ? "3d" : escalateMute(count);
            long expiry = now + (shouldMute ? durationToMillis(duration) : STRIKE_DECAY_MS);
            String name = playerName != null ? playerName : previous == null ? null : previous.playerName();
            return new StrikeState(count, expiry, name);
        });
        int count = strike.count();

        // Category-specific mute reason so the punishment says WHY (e.g. advertising).
        String category;
        switch (v) {
            case SLUR   -> category = "Hate speech / slurs";
            case ADVERT -> category = "Advertising";
            default      -> category = "Inappropriate language";
        }
        boolean shouldMute = severe || count > STRIKE_LIMIT;
        // ChatGuard mutes are capped at 3 days max. Severe (slurs) = the full 3d, others escalate.
        String duration = severe ? "3d" : escalateMute(count);

        final String fCategory = category;
        final String fDur = duration;
        final boolean fMute = shouldMute;
        final int fCount = count;
        if (fMute) {
            completeMute(id, playerName, fCategory, fDur);
        } else {
            if (p != null && p.isOnline()) {
                p.sendActionBar(net.kyori.adventure.text.Component.text("§cMessage blocked: " + fCategory));
                int remaining = (STRIKE_LIMIT + 1) - fCount;
                p.sendMessage("§c⚠ Warning (" + fCount + "/" + STRIKE_LIMIT + "): §f" + fCategory
                    + "§c. " + remaining + " warning" + (remaining == 1 ? "" : "s") + " left before a mute.");
            }
            persistStrikes();
        }
        if (playerName != null) {
            getLogger().info("[ChatGuard] " + playerName + " (" + v + ", strike #" + count
                + ", mute-requested=" + shouldMute + "): " + message);
        }
    }

    private void completeMute(UUID id, String preferredName, String category, String duration) {
        PendingMute pending = new PendingMute(id, preferredName, category, duration);
        pending = pendingMutes.merge(id, pending, this::strongerPendingMute);
        // Persist the strike even if dispatch is temporarily unavailable. The pending action remains
        // queued in memory and is retried while the plugin is enabled.
        persistStrikes();
        schedulePendingMute(pending);
    }

    private PendingMute strongerPendingMute(PendingMute current, PendingMute next) {
        if (current.category().equals("Hate speech / slurs")) return current;
        if (next.category().equals("Hate speech / slurs")) return next;
        return durationToMillis(current.duration()) >= durationToMillis(next.duration()) ? current : next;
    }

    private void retryPendingMutes() {
        for (PendingMute pending : pendingMutes.values()) {
            schedulePendingMute(pending);
        }
    }

    private void schedulePendingMute(PendingMute pending) {
        long now = System.currentTimeMillis();
        Long retryAfter = muteRetryAfterMillis.get(pending.playerId());
        if (retryAfter != null && now < retryAfter) return;
        if (retryAfter != null) muteRetryAfterMillis.remove(pending.playerId(), retryAfter);
        if (storageClosing || pendingMutes.get(pending.playerId()) != pending
                || !scheduledMuteDispatches.add(pending.playerId())) return;
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.globalNow(this, () -> dispatchPendingMute(pending));
            if (!task.wasAccepted()) {
                scheduledMuteDispatches.remove(pending.playerId());
                muteRetryAfterMillis.put(pending.playerId(), System.currentTimeMillis() + MUTE_DISPATCH_RETRY_DELAY_MILLIS);
                if (failedMuteDispatchWarnings.add(pending.playerId())) {
                    getLogger().warning("Could not schedule an automod mute; the action remains queued for retry.");
                }
            }
        } catch (RuntimeException ex) {
            scheduledMuteDispatches.remove(pending.playerId());
            muteRetryAfterMillis.put(pending.playerId(), System.currentTimeMillis() + MUTE_DISPATCH_RETRY_DELAY_MILLIS);
            if (failedMuteDispatchWarnings.add(pending.playerId())) {
                getLogger().warning("Could not schedule an automod mute (cause="
                    + ex.getClass().getSimpleName() + "); the action remains queued for retry.");
            }
        }
    }

    private void dispatchPendingMute(PendingMute pending) {
        UUID id = pending.playerId();
        try {
            if (storageClosing || pendingMutes.get(id) != pending) return;
            StrikeState strike = strikeStates.get(id);
            String playerName = pending.playerName() != null ? pending.playerName()
                : strike == null ? null : strike.playerName();
            if (playerName == null || playerName.isBlank()) {
                playerName = Bukkit.getOfflinePlayer(id).getName();
            }
            if (playerName == null || playerName.isBlank()) {
                if (failedMuteDispatchWarnings.add(id)) {
                    getLogger().warning("Could not resolve a name for a pending automod mute; the action remains queued.");
                }
                muteRetryAfterMillis.put(id, System.currentTimeMillis() + MUTE_DISPATCH_RETRY_DELAY_MILLIS);
                return;
            }

            boolean dispatched;
            try {
                dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "mute " + playerName + " " + pending.duration() + " " + pending.category());
            } catch (RuntimeException ex) {
                if (failedMuteDispatchWarnings.add(id)) {
                    getLogger().warning("Automod mute dispatch failed (cause="
                        + ex.getClass().getSimpleName() + "); the action remains queued for retry.");
                }
                muteRetryAfterMillis.put(id, System.currentTimeMillis() + MUTE_DISPATCH_RETRY_DELAY_MILLIS);
                return;
            }
            if (!dispatched) {
                if (failedMuteDispatchWarnings.add(id)) {
                    getLogger().warning("The automod mute command was not dispatched; the action remains queued for retry.");
                }
                muteRetryAfterMillis.put(id, System.currentTimeMillis() + MUTE_DISPATCH_RETRY_DELAY_MILLIS);
                return;
            }

            if (!pendingMutes.remove(id, pending)) return;
            muteRetryAfterMillis.remove(id);
            failedMuteDispatchWarnings.remove(id);
            notifyStaff("§c" + playerName + " §7muted by automod §8("
                + pending.category().toLowerCase(Locale.ROOT) + ")");
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                PlatformScheduler.entityNow(this, player, () -> {
                    if (!player.isOnline()) return;
                    player.sendActionBar(net.kyori.adventure.text.Component.text("§cMessage blocked: " + pending.category()));
                    player.sendMessage("§cYou have been muted by automod: §f" + pending.category()
                        + " §7(" + pending.duration() + ")");
                }, () -> { });
            }
            persistStrikes();
        } finally {
            scheduledMuteDispatches.remove(id);
        }
    }

    /** Sends a short notice to online staff (no offending text, no verbose flag). */
    private void notifyStaff(String message) {
        net.kyori.adventure.text.Component comp = net.kyori.adventure.text.Component.text(message);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            PlatformScheduler.entityNow(this, staff, () -> {
                if (!staff.isOnline()) return;
                if (staff.isOp() || staff.hasPermission(PERM_NOTIFY)
                        || staff.hasPermission("pizzasmp.staff.notify")
                        || staff.hasPermission("minecraft.command.mute")) {
                    staff.sendMessage(comp);
                }
            }, () -> { });
        }
    }

    /** Resolve a name without reading an online target's identity from another region. */
    private void resolvePlayerIdentity(String requestedName, Consumer<PlayerIdentity> callback) {
        PlatformScheduler.globalNow(this, () -> {
            Player online = Bukkit.getPlayerExact(requestedName);
            if (online == null) {
                this.resolveOfflineIdentity(requestedName, callback);
                return;
            }

            AtomicBoolean completed = new AtomicBoolean();
            Runnable retired = () -> {
                if (completed.compareAndSet(false, true)) {
                    this.resolveOfflineIdentity(requestedName, callback);
                }
            };
            try {
                var task = online.getScheduler().run(this, scheduled -> {
                    if (!completed.compareAndSet(false, true)) return;
                    PlayerIdentity identity = new PlayerIdentity(online.getUniqueId(), online.getName());
                    PlatformScheduler.globalNow(this, () -> callback.accept(identity));
                }, retired);
                if (task == null) retired.run();
            } catch (RuntimeException ex) {
                retired.run();
            }
        });
    }

    /** Caller is routed to the global scheduler before consulting the offline profile cache. */
    private void resolveOfflineIdentity(String requestedName, Consumer<PlayerIdentity> callback) {
        PlatformScheduler.globalNow(this, () -> {
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(requestedName);
            String name = offline.getName();
            callback.accept(new PlayerIdentity(offline.getUniqueId(),
                name == null || name.isBlank() ? requestedName : name));
        });
    }

    private long durationToMillis(String dur) {
        try {
            if (dur == null || dur.length() < 2) return STRIKE_DECAY_MS;
            char unit = dur.charAt(dur.length() - 1);
            long n = Long.parseLong(dur.substring(0, dur.length() - 1));
            return switch (unit) {
                case 'm' -> n * 60_000L;
                case 'h' -> n * 3_600_000L;
                case 'd' -> n * 86_400_000L;
                default  -> n * 1000L;
            };
        } catch (NumberFormatException ex) {
            return STRIKE_DECAY_MS;
        }
    }

    private String escalateMute(int count) {
        // count is the strike number that tripped the mute (always > STRIKE_LIMIT here).
        int over = count - STRIKE_LIMIT;
        if (over <= 1) return "30m";
        if (over == 2) return "2h";
        if (over == 3) return "12h";
        return "24h";
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                             String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("clearwarnings") || name.equals("clearwarn") || name.equals("clearwarns")) {
            if (!sender.hasPermission(PERM_CLEAR)) { sender.sendMessage("§cNo permission."); return true; }
            if (args.length < 1) { sender.sendMessage("§cUsage: /clearwarnings <player>"); return true; }
            String requestedName = args[0];
            resolvePlayerIdentity(requestedName, target -> {
                strikeStates.remove(target.id());
                persistStrikes();
                sendCommandMessage(sender, "§aCleared chat warnings for §f" + requestedName);
            });
            return true;
        }
        if (name.equals("pizzachatguard") || name.equals("pcg")) {
            if (args.length >= 1) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("reload") && sender.hasPermission(PERM_CLEAR)) {
                    reloadPolicyAsync(sender);
                    return true;
                }
                if (sub.equals("owner")) {
                    if (!sender.hasPermission(PERM_MANAGE)) { sender.sendMessage("§cNo permission."); return true; }
                    boolean hasRequestedState = args.length >= 2;
                    boolean requestedState = hasRequestedState
                        && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true"));
                    ModerationPolicyState updated = updateModerationPolicy(current ->
                        new ModerationPolicyState(hasRequestedState ? requestedState : !current.guardOwner(),
                            current.linkWhitelist(), current.linkWhitelistNames()));
                    sender.sendMessage("§7Owner/Dev chat guard is now " + (updated.guardOwner()
                        ? "§aON §7(owner/dev are filtered)" : "§cOFF §7(owner/dev exempt)"));
                    return true;
                }
                if (sub.equals("whitelist") || sub.equals("wl")) {
                    if (!sender.hasPermission(PERM_MANAGE)) { sender.sendMessage("§cNo permission."); return true; }
                    String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
                    if ((action.equals("add") || action.equals("remove")) && args.length >= 3) {
                        String requestedName = args[2];
                        boolean adding = action.equals("add");
                        resolvePlayerIdentity(requestedName, target -> {
                            updateModerationPolicy(current -> {
                                Set<UUID> whitelist = new HashSet<>(current.linkWhitelist());
                                Map<UUID, String> namesById = new HashMap<>(current.linkWhitelistNames());
                                if (adding) {
                                    whitelist.add(target.id());
                                    namesById.put(target.id(), target.name());
                                } else {
                                    whitelist.remove(target.id());
                                    namesById.remove(target.id());
                                }
                                return new ModerationPolicyState(current.guardOwner(), whitelist, namesById);
                            });
                            if (adding) {
                                sendCommandMessage(sender, "§aAdded §f" + target.name() + " §ato the ChatGuard link whitelist.");
                            } else {
                                sendCommandMessage(sender, "§7Removed §f" + target.name() + " §7from the ChatGuard link whitelist.");
                            }
                        });
                        return true;
                    }
                    ModerationPolicyState current = moderationPolicy;
                    sender.sendMessage("§eChatGuard link whitelist §7(" + current.linkWhitelist().size() + "):");
                    for (UUID u : current.linkWhitelist()) {
                        sender.sendMessage("§7- §f" + current.linkWhitelistNames().getOrDefault(u, u.toString()));
                    }
                    sender.sendMessage("§7Usage: /pcg whitelist add|remove <player>");
                    return true;
                }
            }
            sender.sendMessage("§ePizzaChatGuard §7— false-positive-resistant chat filter");
            sender.sendMessage("§7/clearwarnings <player> §8- clear a player's warnings");
            sender.sendMessage("§7/pcg reload §8- reload config");
            sender.sendMessage("§7/pcg owner on|off §8- also guard owner/dev");
            sender.sendMessage("§7/pcg whitelist add|remove|list <player> §8- link whitelist");
            return true;
        }
        return false;
    }
}
