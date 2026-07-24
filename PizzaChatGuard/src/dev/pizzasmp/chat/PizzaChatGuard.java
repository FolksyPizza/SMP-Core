package dev.pizzasmp.chat;

/*
 * PizzaChatGuard is part of the SMP-Core plugin suite.
 * Copyright (c) 2025-2026 William W. (FolksyPizza).
 * Released under the MIT License (see LICENSE). Provided AS IS, without warranty.
 */

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
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

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String PERM_BYPASS = "pizzasmp.chatguard.bypass";
    private static final String PERM_CLEAR = "pizzasmp.chatguard.clearwarnings";
    private static final String PERM_LINKS = "pizzasmp.chatguard.links";   // may post links (bypass ADVERT only)
    private static final String PERM_MANAGE = "pizzasmp.chatguard.manage"; // manage whitelist + owner-guard toggle
    // Staff groups get link permission automatically. Owner/co-owner/dev are ALSO full-exempt (below).
    private static final String[] STAFF_GROUPS = {
        "group.mod", "group.srmod", "group.admin", "group.sradmin", "group.co-owner", "group.owner", "group.dev", "group.staff"
    };

    // Slurs / hard bans — matched as whole words after leet normalization. Kept minimal &
    // specific to avoid catching innocent words. (Stored normalized/lowercase.)
    private final Set<String> slurWords = new HashSet<>();
    // Soft-banned words (advertising, etc.) — whole-word, no leet normalization.
    private final Set<String> blockedWords = new HashSet<>();
    // Safe words that CONTAIN a banned fragment but are legitimate — never flagged.
    private final Set<String> safeWords = new HashSet<>();
    // Advertising / IP & URL patterns.
    private final List<Pattern> advertPatterns = new ArrayList<>();

    private final Map<Character, Character> leet = new HashMap<>();

    // Per-player state
    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessageText = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violationCount = new ConcurrentHashMap<>();
    // When the current strike tally for a player expires (after a mute ends or after good behavior).
    private final Map<UUID, Long> strikeExpiry = new ConcurrentHashMap<>();
    // Last-known name per striked player (for the moderation hub display).
    private final Map<UUID, String> strikeNames = new ConcurrentHashMap<>();

    // Players get this many warnings before being muted (unless the violation is SEVERE).
    private static final int STRIKE_LIMIT = 5;
    // Strikes decay after this long of clean behavior.
    private static final long STRIKE_DECAY_MS = 30L * 60L * 1000L; // 30 minutes
    // Staff who should see the short "muted by automod" notice.
    private static final String PERM_NOTIFY = "pizzasmp.staff";

    // Config
    private boolean filterEnabled = true;
    private long rateLimitMs = 750L;          // min ms between messages
    private long duplicateWindowMs = 3000L;   // block identical message within this window
    private int capsMinLength = 8;            // only caps-check messages this long+
    private double capsThreshold = 0.7;       // >70% uppercase letters = caps violation

    // Unified storage (yaml default; mysql for cross-instance sync). Namespace "chatguard".
    private dev.pizzasmp.common.SuiteStorage storage;

    // Exemption policy (persisted in the "policy" storage doc, so it syncs across instances).
    // guardOwner=false (default): owner/co-owner/dev are fully exempt from the filter. Toggle it ON
    // from /admin (or /pcg owner on) to also guard owner/dev. Staff always keep link permission.
    private boolean guardOwner = false;
    // Players opted-in to link permission (bypass the ADVERT/URL filter only). Managed via /admin.
    private final Set<UUID> linkWhitelist = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> linkWhitelistNames = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.storage = dev.pizzasmp.common.SuiteStorage.fromConfig(this, "chatguard");
        this.storage.importFromYamlOnce(java.util.List.of("strikes"), false);
        loadPolicy();
        initLeet();
        loadStrikes();
        loadPolicyState();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("PizzaChatGuard enabled — " + slurWords.size() + " slurs, "
            + blockedWords.size() + " blocked, " + safeWords.size() + " safe-words, "
            + advertPatterns.size() + " advert patterns, filter=" + filterEnabled);
    }

    @Override
    public void onDisable() {
        persistStrikes();
        if (this.storage != null) this.storage.close();
    }

    /**
     * Persist live strike state to strikes.yml so the PunishDrop moderation hub
     * can display chat strikes, and so strikes survive restarts.
     */
    private synchronized void persistStrikes() {
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Integer> e : violationCount.entrySet()) {
                Long exp = strikeExpiry.get(e.getKey());
                if (e.getValue() == null || e.getValue() <= 0) continue;
                if (exp != null && now > exp) continue; // stale — don't persist
                String key = e.getKey().toString();
                cfg.set("counts." + key, e.getValue());
                if (exp != null) cfg.set("expiry." + key, exp);
                String name = strikeNames.get(e.getKey());
                if (name != null) cfg.set("names." + key, name);
            }
            // yaml -> strikes.yml file; mysql -> suite_docs row (shared across instances).
            this.storage.saveDoc("strikes", cfg);
        } catch (Exception ex) {
            getLogger().warning("Failed saving strikes: " + ex.getMessage());
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
                    violationCount.put(id, n);
                    if (exp > 0L) strikeExpiry.put(id, exp);
                    String name = cfg.getString("names." + key);
                    if (name != null) strikeNames.put(id, name);
                } catch (IllegalArgumentException ignored) {}
            }
            if (!violationCount.isEmpty()) {
                getLogger().info("[ChatGuard] restored " + violationCount.size() + " strike record(s).");
            }
        } catch (Exception ex) {
            getLogger().warning("Failed loading strikes.yml: " + ex.getMessage());
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
        filterEnabled = getConfig().getBoolean("enabled", true);
        rateLimitMs = getConfig().getLong("rate-limit-ms", 750L);
        duplicateWindowMs = getConfig().getLong("duplicate-window-ms", 3000L);
        capsMinLength = getConfig().getInt("caps-min-length", 8);
        capsThreshold = getConfig().getDouble("caps-threshold", 0.7);

        slurWords.clear();
        for (String s : getConfig().getStringList("slur-words")) {
            if (!s.isBlank()) slurWords.add(normalizeLeet(s.toLowerCase(Locale.ROOT)));
        }
        blockedWords.clear();
        for (String s : getConfig().getStringList("blocked-words")) {
            if (!s.isBlank()) blockedWords.add(s.toLowerCase(Locale.ROOT));
        }
        safeWords.clear();
        for (String s : getConfig().getStringList("safe-words")) {
            if (!s.isBlank()) safeWords.add(s.toLowerCase(Locale.ROOT));
        }
        advertPatterns.clear();
        if (getConfig().getBoolean("block-advertising", true)) {
            // IPv4 with optional :port, and domains with common TLDs (avoids matching "1.21" version refs by requiring 4 octets or a TLD)
            advertPatterns.add(Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b"));
            advertPatterns.add(Pattern.compile("\\b[a-z0-9-]+\\.(?:com|net|org|gg|io|co|me|xyz|fun|club|online|store|us|tk)\\b", Pattern.CASE_INSENSITIVE));
        }
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
        Player p = e.getPlayer();
        if (!filterEnabled || e.isCancelled()) return;
        // Owner/co-owner/dev are fully exempt unless the owner-guard is toggled on.
        if (isFullyExempt(p)) return;
        String message = PLAIN.serialize(e.message());
        if (message == null || message.isBlank()) return;

        Violation v = inspect(p, message);
        // Staff and whitelisted players may post links: an ADVERT-only violation is allowed for them.
        if (v == Violation.ADVERT && hasLinkBypass(p)) v = Violation.NONE;
        if (v == Violation.NONE) {
            lastMessageTime.put(p.getUniqueId(), System.currentTimeMillis());
            lastMessageText.put(p.getUniqueId(), message.toLowerCase(Locale.ROOT).trim());
            return;
        }
        e.setCancelled(true);
        handleViolation(p, v, message);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPrivateMessage(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!filterEnabled) return;
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
        Violation v = inspectContent(body);
        if (v == Violation.ADVERT && hasLinkBypass(p)) v = Violation.NONE;
        if (v != Violation.NONE) {
            e.setCancelled(true);
            handleViolation(p, v, body);
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
        Player online = Bukkit.getPlayerExact(targetName);
        UUID id = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();
        violationCount.remove(id);
        strikeExpiry.remove(id);
        strikeNames.remove(id);
        persistStrikes();
        getLogger().info("[ChatGuard] strikes cleared for " + targetName + " (unmuted by " + e.getPlayer().getName() + ").");
    }

    // ---- exemption policy --------------------------------------------------
    private boolean isOwnerOrDev(Player p) {
        return p.hasPermission("group.owner") || p.hasPermission("group.co-owner") || p.hasPermission("group.dev");
    }

    /** Fully exempt from the whole filter: owner/co-owner/dev, unless the owner-guard is toggled on. */
    private boolean isFullyExempt(Player p) {
        return isOwnerOrDev(p) && !guardOwner;
    }

    /** May post links (bypasses the ADVERT/URL filter only): staff, whitelisted players, and the full-exempt. */
    private boolean hasLinkBypass(Player p) {
        if (isFullyExempt(p)) return true;
        if (p.hasPermission(PERM_LINKS)) return true;
        if (linkWhitelist.contains(p.getUniqueId())) return true;
        for (String g : STAFF_GROUPS) if (p.hasPermission(g)) return true;
        return false;
    }

    private synchronized void persistPolicy() {
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
            cfg.set("guard-owner", guardOwner);
            java.util.List<String> wl = new java.util.ArrayList<>();
            for (UUID u : linkWhitelist) wl.add(u.toString());
            cfg.set("link-whitelist", wl);
            for (Map.Entry<UUID, String> e : linkWhitelistNames.entrySet()) cfg.set("names." + e.getKey(), e.getValue());
            this.storage.saveDoc("policy", cfg);
        } catch (Exception ex) {
            getLogger().warning("Failed saving ChatGuard policy: " + ex.getMessage());
        }
    }

    private void loadPolicyState() {
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg = this.storage.loadDoc("policy");
            guardOwner = cfg.getBoolean("guard-owner", getConfig().getBoolean("guard-owner", false));
            linkWhitelist.clear();
            linkWhitelistNames.clear();
            for (String s : cfg.getStringList("link-whitelist")) {
                try { linkWhitelist.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
            }
            org.bukkit.configuration.ConfigurationSection names = cfg.getConfigurationSection("names");
            if (names != null) for (String k : names.getKeys(false)) {
                try { linkWhitelistNames.put(UUID.fromString(k), names.getString(k)); } catch (IllegalArgumentException ignored) {}
            }
            getLogger().info("[ChatGuard] policy: guard-owner=" + guardOwner + ", link-whitelist=" + linkWhitelist.size());
        } catch (Exception ex) {
            getLogger().warning("Failed loading ChatGuard policy: " + ex.getMessage());
        }
    }

    private enum Violation { NONE, SLUR, BLOCKED, ADVERT, SPAM_RATE, SPAM_DUPLICATE, CAPS }

    private Violation inspect(Player p, String message) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        String normalized = message.toLowerCase(Locale.ROOT).trim();

        // Spam: rate limit
        Long last = lastMessageTime.get(id);
        if (last != null && now - last < rateLimitMs) {
            return Violation.SPAM_RATE;
        }
        // Spam: duplicate or near-duplicate ("similar") within the window
        String lastText = lastMessageText.get(id);
        if (lastText != null && last != null && now - last < duplicateWindowMs
                && isSameOrSimilar(lastText, normalized)) {
            return Violation.SPAM_DUPLICATE;
        }
        // Caps
        if (isCapsViolation(message)) {
            return Violation.CAPS;
        }
        return inspectContent(message);
    }

    private Violation inspectContent(String message) {
        // Tokenize into words (letters/digits/leet symbols). Punctuation separates words.
        String[] rawTokens = message.toLowerCase(Locale.ROOT).split("[^a-z0-9@!$]+");
        // Robustness: rejoin runs of single-character tokens so "n i g g e r" / "f.u.c.k" are caught.
        StringBuilder spaced = new StringBuilder();
        for (String t : rawTokens) {
            if (t.length() == 1) spaced.append(t);
            else spaced.append(' ');
        }
        String joinedSingles = normalizeLeet(spaced.toString().trim());
        if (joinedSingles.length() >= 3 && !safeWords.contains(joinedSingles)) {
            if (slurWords.contains(joinedSingles)) return Violation.SLUR;
            if (blockedWords.contains(joinedSingles)) return Violation.BLOCKED;
        }
        for (String token : rawTokens) {
            if (token.isBlank()) continue;
            // Whitelist: legitimate words are never flagged.
            if (safeWords.contains(token)) continue;
            String norm = normalizeLeet(token);
            if (safeWords.contains(norm)) continue;
            // Soft-blocked words: whole-word match (raw OR leet-normalized).
            if (blockedWords.contains(token) || blockedWords.contains(norm)) {
                return Violation.BLOCKED;
            }
            // Slurs: normalize leet, then whole-word match.
            if (slurWords.contains(norm)) {
                return Violation.SLUR;
            }
            // Also catch slurs with repeated padding chars removed (e.g. "niiigger" -> "niger"? no —
            // collapse 3+ repeats to 1 to catch stretched spelling, but require min length 4 to avoid
            // collapsing short safe words).
            if (norm.length() >= 4) {
                String collapsed = norm.replaceAll("(.)\\1{2,}", "$1");
                if (!collapsed.equals(norm) && slurWords.contains(collapsed) && !safeWords.contains(collapsed)) {
                    return Violation.SLUR;
                }
            }
        }
        // Advertising / IP / URL
        for (Pattern pat : advertPatterns) {
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

    private boolean isCapsViolation(String message) {
        String letters = message.replaceAll("[^a-zA-Z]", "");
        if (letters.length() < capsMinLength) return false;
        int upper = 0;
        for (char c : letters.toCharArray()) {
            if (Character.isUpperCase(c)) upper++;
        }
        return (double) upper / letters.length() >= capsThreshold;
    }

    private void handleViolation(Player p, Violation v, String message) {
        // Spam and caps are soft — warn only, never escalate to mutes.
        if (v == Violation.SPAM_RATE) {
            Bukkit.getScheduler().runTask(this, () ->
                p.sendMessage("§cPlease wait 1 second before your next message"));
            return;
        }
        if (v == Violation.SPAM_DUPLICATE) {
            Bukkit.getScheduler().runTask(this, () ->
                p.sendMessage("§cPlease do not repeat the same (or similar) message."));
            return;
        }
        if (v == Violation.CAPS) {
            Bukkit.getScheduler().runTask(this, () ->
                p.sendActionBar(net.kyori.adventure.text.Component.text("§cPlease don't use excessive caps.")));
            return;
        }
        // Hard violations (slur/blocked/advert) — escalate with a 5-strike system.
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        // Expire stale strikes: once a previous mute has ended or the player has behaved, reset.
        Long exp = strikeExpiry.get(id);
        if (exp != null && now > exp) {
            violationCount.remove(id);
        }

        // SEVERE violations (hate speech / slurs) are "really bad" — instant mute, no warnings.
        boolean severe = (v == Violation.SLUR);
        int count = violationCount.merge(id, 1, Integer::sum);

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
        strikeNames.put(id, p.getName());
        Bukkit.getScheduler().runTask(this, () -> {
            p.sendActionBar(net.kyori.adventure.text.Component.text("§cMessage blocked: " + fCategory));
            if (fMute) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "mute " + p.getName() + " " + fDur + " " + fCategory);
                p.sendMessage("§cYou have been muted by automod: §f" + fCategory + " §7(" + fDur + ")");
                // Staff see ONLY a short notice, not the offending message/full flag.
                notifyStaff("§c" + p.getName() + " §7muted by automod §8(" + fCategory.toLowerCase(Locale.ROOT) + ")");
                // Strikes reset once the mute period elapses (or staff unmute clears them sooner).
                strikeExpiry.put(p.getUniqueId(), System.currentTimeMillis() + durationToMillis(fDur));
            } else {
                int remaining = (STRIKE_LIMIT + 1) - fCount;
                p.sendMessage("§c⚠ Warning (" + fCount + "/" + STRIKE_LIMIT + "): §f" + fCategory
                    + "§c. " + remaining + " warning" + (remaining == 1 ? "" : "s") + " left before a mute.");
                strikeExpiry.put(p.getUniqueId(), System.currentTimeMillis() + STRIKE_DECAY_MS);
            }
            persistStrikes();
        });
        getLogger().info("[ChatGuard] " + p.getName() + " (" + v + ", strike #" + count + ", muted=" + shouldMute + "): " + message);
    }

    /** Sends a short notice to online staff (no offending text, no verbose flag). */
    private void notifyStaff(String message) {
        net.kyori.adventure.text.Component comp = net.kyori.adventure.text.Component.text(message);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.isOp() || staff.hasPermission(PERM_NOTIFY)
                    || staff.hasPermission("pizzasmp.staff.notify")
                    || staff.hasPermission("minecraft.command.mute")) {
                staff.sendMessage(comp);
            }
        }
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
            Player target = Bukkit.getPlayerExact(args[0]);
            UUID id = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[0]).getUniqueId();
            violationCount.remove(id);
            strikeExpiry.remove(id);
            strikeNames.remove(id);
            persistStrikes();
            sender.sendMessage("§aCleared chat warnings for §f" + args[0]);
            return true;
        }
        if (name.equals("pizzachatguard") || name.equals("pcg")) {
            if (args.length >= 1) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("reload") && sender.hasPermission(PERM_CLEAR)) {
                    loadPolicy();
                    loadPolicyState();
                    sender.sendMessage("§aPizzaChatGuard policy reloaded.");
                    return true;
                }
                if (sub.equals("owner")) {
                    if (!sender.hasPermission(PERM_MANAGE)) { sender.sendMessage("§cNo permission."); return true; }
                    if (args.length >= 2) guardOwner = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                    else guardOwner = !guardOwner;
                    persistPolicy();
                    sender.sendMessage("§7Owner/Dev chat guard is now " + (guardOwner ? "§aON §7(owner/dev are filtered)" : "§cOFF §7(owner/dev exempt)"));
                    return true;
                }
                if (sub.equals("whitelist") || sub.equals("wl")) {
                    if (!sender.hasPermission(PERM_MANAGE)) { sender.sendMessage("§cNo permission."); return true; }
                    String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
                    if ((action.equals("add") || action.equals("remove")) && args.length >= 3) {
                        Player online = Bukkit.getPlayerExact(args[2]);
                        UUID id; String pname;
                        if (online != null) { id = online.getUniqueId(); pname = online.getName(); }
                        else { org.bukkit.OfflinePlayer o = Bukkit.getOfflinePlayer(args[2]); id = o.getUniqueId(); pname = o.getName() != null ? o.getName() : args[2]; }
                        if (action.equals("add")) {
                            linkWhitelist.add(id); linkWhitelistNames.put(id, pname);
                            sender.sendMessage("§aAdded §f" + pname + " §ato the ChatGuard link whitelist.");
                        } else {
                            linkWhitelist.remove(id); linkWhitelistNames.remove(id);
                            sender.sendMessage("§7Removed §f" + pname + " §7from the ChatGuard link whitelist.");
                        }
                        persistPolicy();
                        return true;
                    }
                    sender.sendMessage("§eChatGuard link whitelist §7(" + linkWhitelist.size() + "):");
                    for (UUID u : linkWhitelist) sender.sendMessage("§7- §f" + linkWhitelistNames.getOrDefault(u, u.toString()));
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
