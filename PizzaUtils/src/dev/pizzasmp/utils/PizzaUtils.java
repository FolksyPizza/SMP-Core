package dev.pizzasmp.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class PizzaUtils extends JavaPlugin implements Listener {

    private static final String PERM_NV = "pizzasmp.nv";
    private static final String PERM_NV_OTHERS = "pizzasmp.nv.others";
    private static final String PERM_PING = "pizzasmp.ping";
    private static final String PERM_VD = "pizzasmp.admin.viewdistance";
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9A-Fa-f]{6})");

    private final Set<UUID> nvEnabled = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        loadNvState();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (UUID id : nvEnabled) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline() && !p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
                }
            }
        }, 40L, 40L);
        getLogger().info("PizzaUtils enabled.");
    }

    @Override
    public void onDisable() {
        saveNvState();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "nv":
            case "nightvision":
                return handleNv(sender, args);
            case "ping":
                return handlePing(sender);
            case "viewdistance":
                return handleViewDistance(sender, args);
            default:
                return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ((name.equals("nv") || name.equals("nightvision")) && args.length == 1 && sender.hasPermission(PERM_NV_OTHERS)) {
            List<String> names = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(p.getName());
            }
            return names;
        }
        if (name.equals("viewdistance") && args.length == 1) {
            return List.of("2", "4", "6", "8", "10", "12", "14", "16");
        }
        return List.of();
    }

    private boolean handleNv(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        if (!player.hasPermission(PERM_NV)) {
            player.sendMessage("§cYou do not have permission to use /nv.");
            return true;
        }
        Player target = player;
        if (args.length > 0) {
            if (!player.hasPermission(PERM_NV_OTHERS)) {
                player.sendMessage("§cYou do not have permission to toggle night vision on others.");
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage("§cPlayer not found: §e" + args[0]);
                return true;
            }
        }
        if (nvEnabled.contains(target.getUniqueId())) {
            nvEnabled.remove(target.getUniqueId());
            target.removePotionEffect(PotionEffectType.NIGHT_VISION);
            target.sendActionBar(net.kyori.adventure.text.Component.text("§7night vision disabled"));
            if (target != player) player.sendActionBar(net.kyori.adventure.text.Component.text("§7night vision disabled for " + target.getName()));
        } else {
            nvEnabled.add(target.getUniqueId());
            target.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            target.sendActionBar(net.kyori.adventure.text.Component.text("§7night vision enabled"));
            if (target != player) player.sendActionBar(net.kyori.adventure.text.Component.text("§7night vision enabled for " + target.getName()));
        }
        saveNvState();
        return true;
    }

    private boolean handlePing(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        if (!player.hasPermission(PERM_PING)) {
            player.sendMessage("§cYou do not have permission.");
            return true;
        }
        int ping = player.getPing();
        player.sendActionBar(legacyColorize("§fYour ping is &#00BFFF" + ping + "§fms"));
        return true;
    }

    private boolean handleViewDistance(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission(PERM_VD)) {
            player.sendMessage("§cYou do not have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /viewdistance <distance>");
            return true;
        }
        try {
            int distance = Integer.parseInt(args[0]);
            if (distance < 2 || distance > 32) {
                sender.sendMessage("§cView distance must be between 2 and 32.");
                return true;
            }
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    Bukkit.getScheduler().runTask(this, () -> {
                        for (org.bukkit.World world : Bukkit.getWorlds()) {
                            world.setViewDistance(distance);
                        }
                        sender.sendMessage("§aView distance set to " + distance + ".");
                    });
                } catch (Exception ex) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§cFailed: " + ex.getMessage()));
                }
            });
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid distance: " + args[0]);
        }
        return true;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (nvEnabled.contains(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> {
                Player p = event.getPlayer();
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            });
        }
    }

    @EventHandler
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (nvEnabled.contains(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player p = event.getPlayer();
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }, 2L);
        }
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player p && nvEnabled.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }, 2L);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (nvEnabled.contains(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player p = event.getPlayer();
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
            }, 10L);
        }
    }

    private File nvStateFile() {
        return new File(getDataFolder(), "nv-players.yml");
    }

    private void saveNvState() {
        try {
            File f = nvStateFile();
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            FileConfiguration cfg = new YamlConfiguration();
            List<String> ids = new ArrayList<>();
            for (UUID id : nvEnabled) ids.add(id.toString());
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
                try { nvEnabled.add(UUID.fromString(s)); } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            getLogger().warning("Failed loading NV state: " + ex.getMessage());
        }
    }

    private net.kyori.adventure.text.Component legacyColorize(String text) {
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
            .legacySection().deserialize(sb.toString());
    }
}
