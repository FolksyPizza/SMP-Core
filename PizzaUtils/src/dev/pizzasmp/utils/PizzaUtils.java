package dev.pizzasmp.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PizzaUtils extends JavaPlugin {

    private static final String PERM_PING = "pizzasmp.ping";
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9A-Fa-f]{6})");

    @Override
    public void onEnable() {
        getCommand("ping").setExecutor(this);
        getLogger().info("PizzaUtils enabled. /ping is available.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ping")) return false;
        return handlePing(sender);
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
