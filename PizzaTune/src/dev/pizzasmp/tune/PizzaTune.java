package dev.pizzasmp.tune;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PizzaTune - runtime-mutable view distance and chunk send/load rates.
 * Feedback to players is via actionbar (hotbar message), not chat.
 */
@SuppressWarnings("deprecation")
public final class PizzaTune extends JavaPlugin {

    @Override
    public void onEnable() {
        getCommand("viewdistance").setExecutor(this);
        getCommand("chunkspeeds").setExecutor(this);
        getLogger().info("PizzaTune ready. /viewdistance and /chunkspeeds are live-tunable.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "viewdistance": return handleViewDistance(sender, args);
            case "chunkspeeds":  return handleChunkSpeeds(sender, args);
            default: return false;
        }
    }

    // ---------- /viewdistance ----------

    private boolean handleViewDistance(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "Current view-distance per world:");
            for (World w : Bukkit.getWorlds()) {
                sender.sendMessage(ChatColor.GRAY + "  " + w.getName()
                        + ChatColor.WHITE + " = " + w.getViewDistance()
                        + ChatColor.DARK_GRAY + "  (sim=" + w.getSimulationDistance() + ")");
            }
            sender.sendMessage(ChatColor.DARK_GRAY + "Usage: /viewdistance <2-32> | /viewdistance <world> <N>");
            return true;
        }

        int n;
        World targetWorld = null;
        if (args.length == 1) {
            Integer p = parseInt(args[0]);
            if (p == null) { feedback(sender, ChatColor.RED, "view-distance: N must be a number 2-32"); return true; }
            n = p;
        } else {
            targetWorld = Bukkit.getWorld(args[0]);
            if (targetWorld == null) { feedback(sender, ChatColor.RED, "unknown world: " + args[0]); return true; }
            Integer p = parseInt(args[1]);
            if (p == null) { feedback(sender, ChatColor.RED, "view-distance: N must be a number 2-32"); return true; }
            n = p;
        }
        if (n < 2 || n > 32) { feedback(sender, ChatColor.RED, "view-distance: range 2-32"); return true; }

        if (targetWorld == null) {
            for (World w : Bukkit.getWorlds()) w.setViewDistance(n);
            for (Player p : Bukkit.getOnlinePlayers()) p.setViewDistance(n);
            broadcastActionbar(ChatColor.GREEN, "view-distance \u2192 " + n);
            feedback(sender, ChatColor.GREEN, "view-distance \u2192 " + n + " (all worlds, all players)");
            getLogger().info("[PizzaTune] " + sender.getName() + " view-distance -> " + n + " (all worlds)");
        } else {
            targetWorld.setViewDistance(n);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().equals(targetWorld)) p.setViewDistance(n);
            }
            broadcastActionbar(ChatColor.GREEN, "view-distance \u2192 " + n + " (" + targetWorld.getName() + ")");
            feedback(sender, ChatColor.GREEN, "view-distance \u2192 " + n + " (" + targetWorld.getName() + ")");
            getLogger().info("[PizzaTune] " + sender.getName() + " view-distance -> " + n + " (" + targetWorld.getName() + ")");
        }
        return true;
    }

    // ---------- /chunkspeeds ----------

    private boolean handleChunkSpeeds(CommandSender sender, String[] args) {
        if (args.length == 0) { showChunkSpeeds(sender); return true; }

        if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            boolean ok = applyChunkSpeed(sender, "send", -1.0)
                      & applyChunkSpeed(sender, "load", -1.0)
                      & applyChunkSpeed(sender, "generate", -1.0);
            if (ok) broadcastActionbar(ChatColor.GREEN, "chunkspeeds \u2192 unlimited");
            return true;
        }

        if (args.length == 1) {
            Double d = parseDouble(args[0]);
            if (d == null) { feedback(sender, ChatColor.RED, "chunkspeeds: N must be a number (-1 = unlimited)"); return true; }
            boolean ok = applyChunkSpeed(sender, "send", d)
                      & applyChunkSpeed(sender, "load", d)
                      & applyChunkSpeed(sender, "generate", d);
            if (ok) broadcastActionbar(ChatColor.GREEN, "chunkspeeds send/load/generate \u2192 " + fmt(d));
            return true;
        }

        if (args.length == 2) {
            String key = args[0].toLowerCase(Locale.ROOT);
            if (!List.of("send", "load", "generate").contains(key)) {
                feedback(sender, ChatColor.RED, "key must be send | load | generate");
                return true;
            }
            Double d = parseDouble(args[1]);
            if (d == null) { feedback(sender, ChatColor.RED, "chunkspeeds: N must be a number (-1 = unlimited)"); return true; }
            if (applyChunkSpeed(sender, key, d)) broadcastActionbar(ChatColor.GREEN, "chunkspeeds " + key + " \u2192 " + fmt(d));
            return true;
        }

        feedback(sender, ChatColor.GRAY, "Usage: /chunkspeeds <N> | /chunkspeeds <send|load|generate> <N> | /chunkspeeds reset");
        return true;
    }

    private void showChunkSpeeds(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "Current chunk rates (chunks/sec, -1 = unlimited):");
        for (String key : List.of("send", "load", "generate")) {
            Double v = readChunkSpeed(key);
            sender.sendMessage(ChatColor.GRAY + "  player-max-chunk-" + key + "-rate "
                    + ChatColor.WHITE + "= " + (v == null ? "?" : fmt(v)));
        }
        Integer cc = readPlayerMaxConcurrentLoads();
        sender.sendMessage(ChatColor.GRAY + "  player-max-concurrent-chunk-loads "
                + ChatColor.WHITE + "= " + (cc == null ? "?" : cc));
        sender.sendMessage(ChatColor.DARK_GRAY + "Usage: /chunkspeeds <N> | /chunkspeeds <key> <N> | /chunkspeeds reset");
    }

    // ---------- reflection plumbing ----------

    private static final String CFG_CLASS = "io.papermc.paper.configuration.GlobalConfiguration";

    private Object getGlobalConfig() {
        try { return Class.forName(CFG_CLASS).getMethod("get").invoke(null); }
        catch (ReflectiveOperationException e) { return null; }
    }

    private Object getChunkLoadingBasic() {
        Object gc = getGlobalConfig();
        if (gc == null) return null;
        try { return findFieldIgnoreCase(gc.getClass(), "chunkLoadingBasic").get(gc); }
        catch (ReflectiveOperationException e) { return null; }
    }

    private Object getChunkLoadingAdvanced() {
        Object gc = getGlobalConfig();
        if (gc == null) return null;
        try { return findFieldIgnoreCase(gc.getClass(), "chunkLoadingAdvanced").get(gc); }
        catch (ReflectiveOperationException e) { return null; }
    }

    private boolean applyChunkSpeed(CommandSender sender, String key, double value) {
        Object basic = getChunkLoadingBasic();
        if (basic == null) {
            feedback(sender, ChatColor.RED, "no Paper GlobalConfiguration (non-Paper server?)");
            return false;
        }
        String fieldName = "playerMaxChunk" + capitalize(key) + "Rate";
        try {
            Field f = findFieldIgnoreCase(basic.getClass(), fieldName);
            f.setAccessible(true);
            f.setDouble(basic, value);
            feedback(sender, ChatColor.GREEN, "player-max-chunk-" + key + "-rate \u2192 " + fmt(value));
            return true;
        } catch (ReflectiveOperationException e) {
            feedback(sender, ChatColor.RED, "field " + fieldName + " not found: " + e.getMessage());
            return false;
        }
    }

    private Double readChunkSpeed(String key) {
        Object basic = getChunkLoadingBasic();
        if (basic == null) return null;
        String fieldName = "playerMaxChunk" + capitalize(key) + "Rate";
        try { Field f = findFieldIgnoreCase(basic.getClass(), fieldName); f.setAccessible(true); return f.getDouble(basic); }
        catch (ReflectiveOperationException e) { return null; }
    }

    private Integer readPlayerMaxConcurrentLoads() {
        Object adv = getChunkLoadingAdvanced();
        if (adv == null) return null;
        try { Field f = findFieldIgnoreCase(adv.getClass(), "playerMaxConcurrentChunkLoads"); f.setAccessible(true); return f.getInt(adv); }
        catch (ReflectiveOperationException e) { return null; }
    }

    private Field findFieldIgnoreCase(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equalsIgnoreCase(name)) { f.setAccessible(true); return f; }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchFieldException(name + " (searched " + cls.getName() + " + supers)");
    }

    // ---------- feedback: actionbar for players, chat for console ----------

    private void feedback(CommandSender sender, ChatColor color, String text) {
        if (sender instanceof Player p) {
            // Paper legacy helper: Player.sendActionBar(String) sends to the action bar (hotbar line)
            p.sendActionBar(color + text);
        } else {
            sender.sendMessage(color + text);
        }
    }

    private void broadcastActionbar(ChatColor color, String text) {
        String line = color + text;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("pizzasmp.tune.viewdistance") || p.hasPermission("pizzasmp.tune.chunkspeeds")) {
                p.sendActionBar(line);
            }
        }
    }

    // ---------- helpers ----------

    private static Integer parseInt(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; } }
    private static Double parseDouble(String s) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; } }
    private static String capitalize(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static String fmt(double d) { return d == (long) d ? String.valueOf((long) d) : String.valueOf(d); }
}
